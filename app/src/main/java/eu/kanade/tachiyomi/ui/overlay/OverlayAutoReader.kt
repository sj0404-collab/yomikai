package eu.kanade.tachiyomi.ui.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import eu.kanade.tachiyomi.data.tts.TtsSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mihon.data.ocr.OcrPlugins
import mihon.domain.ocr.model.OcrImage
import mihon.domain.ocr.repository.OcrRepository
import mihon.domain.ocr.service.OcrPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.coroutines.coroutineContext

/**
 * Чтение рамки поверх чужого приложения.
 *
 * Цикл: снять кадр области → распознать → озвучить → дождаться конца
 * речи → отправить свайп → повторить.
 *
 * Скролл наш собственный, а не приложения: приложение может вообще не
 * иметь прокрутки или листать по-своему, поэтому продвижение выполняется
 * жестом через [OverlayGestureService].
 */
class OverlayAutoReader(
    private val context: Context,
    private val capture: OverlayCaptureEngine,
) {
    private val prefs: OcrPreferences by lazy { Injekt.get() }

    /** Сколько ждать конца речи перед прокруткой, мс. */
    private val pauseAfterSpeechMs: Long get() = prefs.overlayReadPause().get().coerceIn(0, 10_000).toLong()

    /** Столько ждём после свайпа, пока приложение перерисует кадр. */
    private val settleAfterScrollMs: Long get() = prefs.overlayScrollSettle().get().coerceIn(200, 5_000).toLong()

    private val scrollDistance: Float
        get() = prefs.overlayScrollStep().get().coerceIn(10, 90) / 100f

    /**
     * Движок оверлея — тот, что выбран для домена «приложения».
     *
     * Вызывается только когда переключатель «свой движок» включён: тогда
     * распознавание идёт именно им, а не движком читалки.
     */
    private fun engineModel(): mihon.domain.ocr.model.OcrModel = prefs.appOcrEngine().get()

    /** Что на самом деле распознаёт: свой движок оверлея или движок читалки. */
    private fun currentModel(): mihon.domain.ocr.model.OcrModel =
        if (prefs.overlayOwnEngine().get()) engineModel() else prefs.ocrModel().get()

    fun engineTitle(): String = OcrPlugins.byModel(currentModel()).title

    /**
     * Один кадр области, распознанный в текст.
     *
     * @return текст или null, если кадр не удалось снять/прочитать
     */
    suspend fun readOnce(region: Rect): String? = withContext(Dispatchers.IO) {
        val bmp = runCatching { capture.capture(region) }.getOrNull() ?: return@withContext null
        try {
            val pixels = IntArray(bmp.width * bmp.height)
            bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val image = OcrImage(bmp.width, bmp.height, pixels)
            // Движок передаётся явно: иначе распознавание всё равно ушло бы
            // в движок читалки, и переключатель «свой движок» ничего бы не
            // менял, кроме подписи на кнопке.
            Injekt.get<OcrRepository>()
                .recognizeText(image, if (prefs.overlayOwnEngine().get()) engineModel() else null)
                .trim()
                .takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "overlay read failed", e)
            null
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    /**
     * Запускает цикл чтения.
     *
     * @param onPage сообщает, что распознан новый экран (для панели оверлея)
     * @param onNote сообщает, что делает цикл (для панели оверлея)
     * @return задача цикла — её отмена останавливает чтение
     */
    fun start(
        scope: CoroutineScope,
        region: Rect,
        onPage: (String) -> Unit = {},
        onNote: (String) -> Unit = {},
    ): Job = scope.launch {
        var lastText: String? = null
        var repeats = 0
        onNote("Чтение начато · ${engineTitle()}")

        // Служба доступности может подключиться позже, чем нажали «Читать»:
        // пока она выключена, листать нечем, и раньше цикл молча завершался
        // после первой реплики. Теперь ждём и продолжаем.
        if (!awaitGestureService(onNote)) return@launch

        while (isActive) {
            val text = readOnce(region)
            if (text == null) {
                onNote("Не удалось прочитать кадр")
                // Кадр может не успеть прогрузиться; ждать и пробовать
                // снова вхолостую только жжёт батарею.
                delay(RETRY_DELAY_MS)
                continue
            }

            // Один и тот же текст после прокрутки означает, что приложение
            // не пролистнулось: озвучивать его второй раз бессмысленно, и
            // цикл ушёл бы в бесконечный повтор одного и того же.
            if (text == lastText) {
                repeats++
                if (repeats >= MAX_REPEATS) {
                    onNote("Приложение не пролистывается — листаю иначе")
                    // Последняя попытка: свайп длиннее и из другой точки.
                    // Некоторые приложения слушают жест только у самого низа
                    // экрана, и свайп по центру рамки они игнорируют.
                    if (!scrollWithFallback(region)) {
                        onNote("Прокрутка не идёт — проверьте Службу доступности")
                        return@launch
                    }
                    repeats = 0
                }
            } else {
                repeats = 0
                lastText = text
                onPage(text)
                speakAndWait(text)
            }

            if (!scrollForward(region)) return@launch
            delay(settleAfterScrollMs)
        }
    }

    /**
     * Ждёт включения Службы доступности, пока её нет.
     *
     * Раньше цикл на первой же неудаче просто завершался, и читатель видел
     * одну озвученную реплику без прокрутки и без объяснения. Теперь при
     * выключенной службе чтение честно ждёт и говорит, что именно надо
     * включить, но ждёт не бесконечно: иначе кнопка «Стоп» пришлось бы искать
     * вслепую.
     *
     * @return false, если службу так и не включили либо чтение отменили
     */
    private suspend fun awaitGestureService(onNote: (String) -> Unit): Boolean {
        var waited = 0L
        // currentCoroutineContext(), а не isActive: здесь нет CoroutineScope,
        // и isActive без получателя просто не разрешается.
        while (currentCoroutineContext().isActive &&
            !OverlayGestureService.isEnabled() &&
            waited < GESTURE_WAIT_MAX_MS
        ) {
            if (waited == 0L) onNote("Включите Службу доступности — жду, чтобы листать")
            delay(GESTURE_WAIT_MS)
            waited += GESTURE_WAIT_MS
        }
        if (!OverlayGestureService.isEnabled()) {
            onNote("Без Службы доступности листать нечем — чтение остановлено")
            return false
        }
        return currentCoroutineContext().isActive
    }

    /**
     * Прокрутка вперёд с проверкой результата.
     *
     * Прежний код останавливал чтение на первом же отказе жеста, и читатель
     * слышал одну реплику — после неё не было ни прокрутки, ни объяснения.
     * Теперь отказ не завершает чтение: жест повторяется, а если и он не
     * прошёл, причина называется прямо.
     */
    private suspend fun scrollForward(region: Rect): Boolean {
        repeat(SCROLL_ATTEMPTS) {
            if (OverlayGestureService.swipeAndAwait(verticalSwipe(region, scrollDistance))) return true
            delay(SCROLL_RETRY_MS)
        }
        // Жест уходит, но экран не меняется: значит точка не слушается.
        return scrollWithFallback(region)
    }

    /** Свайп у самого низа рамки и длиннее обычного — последний довод. */
    private suspend fun scrollWithFallback(region: Rect): Boolean {
        val dy = (region.height() * FALLBACK_STEP).toInt()
        if (dy < 1) return false
        val x = region.left + (region.width() * FALLBACK_X).toInt()
        return OverlayGestureService.swipeAndAwait(
            fromX = x,
            fromY = region.bottom - (region.height() * 0.05f).toInt(),
            toX = x,
            toY = region.bottom - (region.height() * 0.05f).toInt() - dy,
            durationMs = FALLBACK_MS,
        )
    }

    /** Пара координат свайпа по центру области на долю [fraction] её высоты. */
    private fun verticalSwipe(region: Rect, fraction: Float): GestureSwipe {
        val dy = (region.height() * fraction).toInt()
        val cx = region.centerX()
        val cy = region.centerY()
        return GestureSwipe(cx, cy + dy, cx, cy - dy)
    }

    /**
     * Озвучивает и ждёт конца речи, затем паузу.
     *
     * `TtsSpeaker` не отдаёт «прочитано» промисом, но сообщает состояние
     * через колбэк: [onState] приходит false, когда речь закончилась.
     */
    private suspend fun speakAndWait(text: String) {
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        var started = false
        TtsSpeaker.speak(context, text) { speaking ->
            if (speaking) {
                started = true
            } else if (started) {
                done.complete(Unit)
            }
        }
        // Если движок не сообщил о начале (например, голос не найден),
        // ждать завершения бессмысленно — цикл обязан идти дальше.
        if (!started) {
            delay(NO_SPEECH_GRACE_MS)
            return
        }
        withTimeoutOrNull(MAX_SPEECH_MS) { done.await() }
        if (coroutineContext.isActive && pauseAfterSpeechMs > 0) delay(pauseAfterSpeechMs)
    }

    /**
     * Листать назад по одному экрану — кнопка «▲ назад» на панели.
     *
     * Работает и без запущенного цикла чтения: пригодится, чтобы вернуться к
     * предыдущей реплике вручную.
     */
    suspend fun scrollBack(region: Rect): Boolean {
        val dy = (region.height() * scrollDistance).toInt()
        if (dy < 1) return false
        val cx = region.centerX()
        val cy = region.centerY()
        return OverlayGestureService.swipeAndAwait(GestureSwipe(cx, cy - dy, cx, cy + dy))
    }

    companion object {
        private const val TAG = "OverlayAutoReader"
        private const val RETRY_DELAY_MS = 900L

        /** Сколько ждать, если движок вообще не начал говорить. */
        private const val NO_SPEECH_GRACE_MS = 300L

        /** Потолок одной реплики: иначе зависший движок стопорит цикл. */
        private const val MAX_SPEECH_MS = 5 * 60_000L

        /** Сколько одинаковых кадров подряд считаем «не пролисталось». */
        private const val MAX_REPEATS = 2

        /** Сколько раз повторить жест, прежде чем менять геометрию. */
        private const val SCROLL_ATTEMPTS = 2

        /** Пауза между попытками жеста, мс. */
        private const val SCROLL_RETRY_MS = 350L

        /** Как часто проверять, не включили ли Службу доступности, мс. */
        private const val GESTURE_WAIT_MS = 1_500L

        /** Сколько ждать включения Службы, прежде чем остановить чтение, мс. */
        private const val GESTURE_WAIT_MAX_MS = 90_000L

        /** Доля высоты области для запасного свайпа. */
        private const val FALLBACK_STEP = 0.7f

        /** Запасной свайп идёт левее центра: там меньше всего кнопок. */
        private const val FALLBACK_X = 0.35f

        /** Запасной свайп длиннее и медленнее — его замечают даже «вялые» списки. */
        private const val FALLBACK_MS = 480L
    }
}
