package eu.kanade.tachiyomi.data.tts

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import mihon.data.ocr.OcrScreenshotBuffer
import mihon.domain.ocr.model.OcrRegion
import mihon.domain.ocr.model.OcrTextOrientation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import mihon.data.ocr.OcrHistoryStore
import mihon.data.ocr.OcrTextCleaner
import mihon.data.ocr.OcrRegionRules
import mihon.data.ocr.RuMorph
import mihon.data.ocr.MangaTranslatorService
import mihon.data.ocr.CyrillicTranslitFixer
import mihon.domain.ocr.interactor.ScanPageOcr
import mihon.domain.ocr.model.OcrBoundingBox
import mihon.domain.ocr.model.OcrImage
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.model.normalizeOcrTextForDisplay
import mihon.domain.ocr.service.OcrPreferences
import tachiyomi.core.common.util.system.isNetworkAvailable
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.coroutines.cancellation.CancellationException

/**
 * Движок авточтения: скан кадра → фильтр по языку → (перевод) → озвучка
 * реплика-за-репликой с подсветкой текущей (линейка как в AlReader) →
 * сигнал «страница дочитана» для автолистания.
 *
 * Ключевые правила:
 * • Читается ТОЛЬКО текст выбранного языка (ru/en/ja/…): остальной текст
 *   на кадре игнорируется. UI-оверлеи приложения в кадр не попадают —
 *   захватывается контент, а не плавающие кнопки.
 * • История сканов: каждая прочитанная реплика запоминается (нормализованный
 *   хэш) — при повторном попадании в кадр (скролл туда-сюда, миллисекундные
 *   пересечения при листании) она не читается второй раз.
 * • Автолистание БЛОКИРУЕТСЯ, пока все реплики текущего кадра не озвучены:
 *   колбэк onPageFinished зовётся строго после последней реплики.
 */
class AutoReadEngine(
    private val context: Context,
    private val scanPageOcr: ScanPageOcr = Injekt.get(),
    private val prefs: OcrPreferences = Injekt.get(),
) {

    /** Детектор панелей/баллонов (YOLO Seeneva, модель в APK). */
    private val detectPanels: mihon.domain.panel.interactor.DetectPanels by lazy { Injekt.get() }

    /** Selected OCR engine for individual balloon crops (Cyrillic by default). */
    private val bubbleOcr: mihon.domain.ocr.interactor.OcrProcessor by lazy { Injekt.get() }

    /** Строка кадра: текст + нормализованный box (для подсветки/порядка). */
    data class Line(val text: String, val boundingBox: OcrBoundingBox)

    data class SpokenRegion(
        val text: String,
        val translated: String?,
        /** Служебные пометки для показа ({1}{ж}) — TTS их не произносит. */
        val marks: String = "",
        val box: OcrBoundingBox,
        val index: Int,
        val total: Int,
    )

    /** Текущая читаемая реплика — для подсветки-линейки поверх страницы. */
    private val _currentRegion = MutableStateFlow<SpokenRegion?>(null)
    val currentRegion = _currentRegion.asStateFlow()

    /**
     * ВСЕ реплики кадра с их статусом: прочитана / читается / предстоит.
     * Оверлей рисует прочитанные полупрозрачно, текущую — ярко, будущие —
     * пунктирно, так видно и историю, и план чтения.
     */
    data class FrameRegion(
        val box: OcrBoundingBox,
        val index: Int,
        val state: State,
        val text: String = "",
    ) {
        enum class State { DONE, CURRENT, UPCOMING }
    }

    private val _frameRegions = MutableStateFlow<List<FrameRegion>>(emptyList())
    // v1.9.39: озвученные строки «заморожены»: после автолистания они могут
    // остаться вверху кадра, но повторно не читаются (помечаются DONE).
    private val spokenLines = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun lineKey(t: String): String =
        t.lowercase().replace(Regex("[^\\p{L}0-9]+"), " ").trim()

    /**
     * Сколько реплик этого кадра уже озвучено потоковым проходом, пока OCR
     * ещё дочитывает страницу. Сбрасывается на каждом кадре вместе с картой.
     */
    private val streamedRegionCount = java.util.concurrent.atomic.AtomicInteger(0)
    val frameRegions = _frameRegions.asStateFlow()

    /**
     * Слоты голосов ВСЕЙ сессии чтения: пол → последний выданный слот.
     * Не сбрасываются между кадрами (только в [stop]), поэтому персонаж,
     * начавший реплику в одном кадре и продолживший в следующем (перекрытие
     * вебтуна), звучит тем же голосом, а каждый НОВЫЙ персонаж того же пола
     * получает следующий свободный голос.
     */
    private val speakerSlots = mutableMapOf<String, Int>()

    /** Полы реплик прошлого кадра (в порядке чтения) — для определения
     *  продолжения одного персонажа через границу кадров. */
    private var prevFrameGenders: List<String?> = emptyList()

    /**
     * Слот голоса для реплики в режиме «много голосов» / «свой голос
     * персонажу». frameOccurrence — сколько реплик БОЛЬШЕ ТОГО же пола уже
     * прочитано в этом кадре (0 = первый персонаж этого пола в сцене).
     *
     * Правило:
     *  • null-пол (нарратор) → слот 0 (свой голос нарратора);
     *  • продолжение реплики прошлого кадра (текст похож на прошлый кадр,
     *    это обычное 65%-перекрытие вебтуна) → ТОТ ЖЕ слот, что и раньше;
     *  • иначе (новый персонаж или следующая реплика того же пола) →
     *    следующий свободный слот сессии.
     */
    private fun voiceSlotFor(gender: String?, text: String, frameOccurrence: Int): Int {
        val key = gender ?: return 0
        val current = speakerSlots[key] ?: -1
        val continuesPrev = frameOccurrence == 0 &&
            prevFrameGenders.lastOrNull() == gender &&
            prevFrameLines.any { prev ->
                trigramSimilarity(
                    prev.lowercase().filter { it.isLetterOrDigit() },
                    text.lowercase().filter { it.isLetterOrDigit() },
                ) >= 0.45f
            }
        val next = if (continuesPrev) current else current + 1
        speakerSlots[key] = next
        return next
    }

    /**
     * Зона книги внутри вьюпорта (доли 0..1) — если кадр перед OCR был
     * обрезан до неё, оверлей обязан пересчитать box'ы обратно.
     */
    @Volatile
    var highlightZone: android.graphics.RectF? = null

    /** Box из координат обрезанного кадра -> координаты вьюпорта. */
    fun mapToViewport(box: OcrBoundingBox): OcrBoundingBox {
        val z = highlightZone ?: return box
        val zw = z.right - z.left
        val zh = z.bottom - z.top
        return OcrBoundingBox(
            left = z.left + box.left * zw,
            top = z.top + box.top * zh,
            right = z.left + box.right * zw,
            bottom = z.top + box.bottom * zh,
        )
    }

    private val _isReading = MutableStateFlow(false)
    val isReading = _isReading.asStateFlow()

    /**
     * Почему авточтение остановилось из-за озвучки; null — озвучка в порядке.
     *
     * Отчёт читателя, который показывается один раз и сбрасывается в [stop].
     */
    private val _voiceBlock = MutableStateFlow<String?>(null)
    val voiceBlock = _voiceBlock.asStateFlow()

    /**
     * Сколько подряд реплик движок принял и не произнёс.
     *
     * Разовый отказ — это нормально (пустой текст, ремарка без звучания, голос
     * занят карточкой перевода). Серия отказов означает, что озвучки на
     * устройстве нет, и авточтение обязано остановиться.
     */
    private var rejectedStreak = 0

    /** Был ли в последнем кадре новый текст (для темпа автоскролла). */
    @Volatile
    var lastFrameHadText: Boolean = false
        private set

    /** Весь распознанный текст последнего кадра — контекст для AI-чата. */
    @Volatile
    var lastFrameText: String = ""
        private set

    /** Реплики прошлого кадра — передаются ассистенту для дедупликации. */
    private var prevFrameLines: List<String> = emptyList()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Поколение запуска: stop() инвалидирует все колбэки прежних запусков. */
    @Volatile
    private var generation = 0

    /**
     * История прочитанного с НЕЧЁТКИМ сравнением: OCR той же реплики при
     * смещённом кадре даёт слегка другой текст (обрезанные края, дрожание),
     * поэтому точный хэш пропускал дубли. Храним нормализованные строки и
     * сравниваем по включению/похожести 3-граммами (порог 0.6).
     *
     * Вебтун листает кадр на ~35% высоты: хвост прошлой страницы остаётся
     * вверху следующего кадра и OCR может дать его текст с искажением. Порог
     * понижен, чтобы такой хвост не читался во второй раз (жалоба: «сначала
     * середину, потом верх прошлой реплики»), ценой редкого пропуска настоящей
     * реплики-повтора на той же странице.
     */
    private val spokenTexts = ArrayDeque<String>()

    @Synchronized
    private fun isDuplicate(rawText: String): Boolean {
        val norm = rawText.lowercase().filter { it.isLetterOrDigit() }
        // Короткие настоящие слова (я, и, но, не, да…) читаем, а не режем как
        // мусор: к этому месту чистка уже отбросила обрывки, а одиночные слова
        // — осмысленные реплики. Раньше guard `length < 4 -> true` глушил их.
        if (norm.length < 4) return false
        for (old in spokenTexts) {
            if (old.contains(norm) || norm.contains(old)) return true
            if (trigramSimilarity(old, norm) >= 0.6f) return true
        }
        spokenTexts.addLast(norm)
        while (spokenTexts.size > HISTORY_LIMIT) spokenTexts.removeFirst()
        return false
    }

    private fun trigramSimilarity(a: String, b: String): Float {
        if (a.length < 3 || b.length < 3) return if (a == b) 1f else 0f
        val ta = HashSet<String>(a.length)
        for (i in 0..a.length - 3) ta.add(a.substring(i, i + 3))
        var common = 0
        var total = 0
        for (i in 0..b.length - 3) {
            total++
            if (b.substring(i, i + 3) in ta) common++
        }
        return if (total == 0) 0f else common.toFloat() / total
    }

    @Synchronized
    fun clearHistory() = spokenTexts.clear()

    /**
     * Порядок чтения кадра: сначала выученное правило книги, иначе пресет
     * типа контента. Правило книги ([BookLearning.KIND_READING_ORDER]) задаёт
     * агент по сайту книги, поэтому оно важнее общего пресета — иначе
     * «наученные» правила остались бы только текстом в чате.
     */
    private fun bookReadingOrder(rules: List<eu.kanade.tachiyomi.data.ai.LearningRule>): String =
        eu.kanade.tachiyomi.data.ai.BookLearning
            .lastOf(rules, eu.kanade.tachiyomi.data.ai.BookLearning.KIND_READING_ORDER)
            ?.text
            ?.let { eu.kanade.tachiyomi.data.ai.BookLearning.readingOrderOf(it) }
            // Знания, записанные до появления rules, хранят порядок в старом
            // поле — иначе после обновления они молча перестали бы действовать.
            ?: legacyBookReadingOrder()
            ?: OcrRegionRules.readingOrderFor(prefs)

    /** Старое одиночное поле `readingOrder` из файла знаний книги. */
    private fun legacyBookReadingOrder(): String? {
        val mangaId = mihon.data.ocr.ReaderContextBus.current.value?.mangaId ?: return null
        val stored = runCatching {
            eu.kanade.tachiyomi.data.ai.BookKnowledge
                .readingOrderOf(context, mangaId)
        }.getOrNull()
        return stored?.takeIf { it != "auto" && it.isNotBlank() }
    }

    /**
     * Правило «у этой книги читать только облачки». Полностраничный движок
     * обычно отдаёт весь текст кадра одним блоком, и детектор баллонов
     * включается лишь запасным путём; выученное правило книги поднимает его
     * в основной, иначе авточтение озвучивало бы в том числе текст мимо
     * реплик.
     */
    private fun bubblesOnly(rules: List<eu.kanade.tachiyomi.data.ai.LearningRule>): Boolean {
        val rule = eu.kanade.tachiyomi.data.ai.BookLearning
            .lastOf(rules, eu.kanade.tachiyomi.data.ai.BookLearning.KIND_BUBBLE)
            ?: return false
        val text = rule.text.lowercase()
        return BUBBLES_ONLY_MARKERS.any { it in text }
    }

    /** Правила книги текущей книги (пусто, если книга не задана). */
    private fun loadBookRules(): List<eu.kanade.tachiyomi.data.ai.LearningRule> =
        eu.kanade.tachiyomi.data.ai.BookKnowledge.rulesOf(
            context,
            mihon.data.ocr.ReaderContextBus.current.value?.mangaId,
        )

    /**
     * Прочитать кадр. [onPageFinished] вызывается ПОСЛЕ озвучки всех реплик —
     * там вызывающая сторона листает/скроллит дальше. Если нового текста нет
     * (всё уже в истории) — завершится сразу.
     */
    fun readFrame(
        bitmap: Bitmap,
        chapterId: Long,
        pageIndex: Int,
        onPageFinished: () -> Unit,
        onLineSpoken: ((OcrBoundingBox) -> Unit)? = null,
    ) {
        job?.cancel()
        TtsSpeaker.stop()
        val myGen = ++generation
        job = scope.launch {
            _isReading.value = true
            var aiRefine: Job? = null
            try {
                // Озвучивать нечем — не тратим время на распознавание кадра и
                // не листаем страницы: читатель должен увидеть причину сразу.
                if (!voicePathUsable()) {
                    blockAutoread(NO_VOICE_MESSAGE)
                    return@launch
                }
                // v1.9.91: кадр для OCR уменьшаем ДО минимально читаемого разрешения
                // (детектор движка всё равно жмёт всё до ~736px). Так конвертация
                // пикселей и онлайн-вызовы выполняются мгновенно в фоне, а текст
                // остаётся читаемым.
                val scanBitmap = downscaleForScan(bitmap)
                val ocrBitmap = scanBitmap
                val pixels = IntArray(ocrBitmap.width * ocrBitmap.height)
                ocrBitmap.getPixels(pixels, 0, ocrBitmap.width, 0, 0, ocrBitmap.width, ocrBitmap.height)
                val image = OcrImage(ocrBitmap.width, ocrBitmap.height, pixels)
                // Кадр в JPEG для AI-определения пола говорящих (если включено)
                // В ручном режиме пол задан читателем — AI Vision не нужен.
                val genderJpeg: ByteArray? = if (prefs.aiGenderVoices().get() &&
                    !prefs.manualVoiceMode().get()
                ) {
                    runCatching {
                        val out = java.io.ByteArrayOutputStream()
                        val scaled = if (ocrBitmap.width > 1024) {
                            val h = ocrBitmap.height * 1024 / ocrBitmap.width
                            Bitmap.createScaledBitmap(ocrBitmap, 1024, h, true)
                        } else ocrBitmap
                        scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
                        if (scaled !== ocrBitmap && !scaled.isRecycled) scaled.recycle()
                        out.toByteArray()
                    }.getOrNull()
                } else null

                // Жёсткий таймаут OCR кадра: движок (локальный при первом
                // запуске/загрузке модели, онлайн при недоступном GLENS или
                // неотвечающем Google) может «зависнуть» без ответа. Без этого
                // авточтение крутится вечно («бесконечное сканирование»).
                // По таймауту кадр считается пустым: возвращаем пустой регион
                // и идём по обычному конвейеру (пустой кадр → дочитывается и
                // страница листается дальше, а не блокируется навсегда).
                //
                // Локальный движок отдаёт области по одной, и реплики уходят в
                // озвучку ПРЯМО ВО ВРЕМЯ распознавания: раньше читатель ждал
                // последнюю реплику страницы, чтобы услышать первую. Потоковый
                // проход работает только в порядке «сверху вниз» (ltr/vertical):
                // при RTL детектор отдаёт области слева направо, и озвучка на
                // лету читала бы реплики в обратном порядке.
                val streamOrder = bookReadingOrder(loadBookRules())
                val streaming = orderAllowsStreaming(streamOrder)
                val partials = Channel<OcrRegion>(Channel.UNLIMITED)
                val result: mihon.domain.ocr.model.OcrPageResult = try {
                    coroutineScope {
                        val scan = async {
                            try {
                                withTimeout(OCR_FRAME_TIMEOUT_MS) {
                                    scanPageOcr.await(
                                        chapterId = chapterId,
                                        pageIndex = pageIndex,
                                        image = image,
                                        onPartial = { region -> partials.trySend(region) },
                                    )
                                }
                            } catch (e: TimeoutCancellationException) {
                                logcat(LogPriority.WARN) {
                                    "OCR frame timeout (${OCR_FRAME_TIMEOUT_MS}ms) pageIndex=$pageIndex"
                                }
                                OcrHistoryStore.addAutoRead(
                                    false,
                                    "OCR-кадр таймаут",
                                    "${OCR_FRAME_TIMEOUT_MS / 1000}с pageIndex=$pageIndex",
                                )
                                mihon.domain.ocr.model.OcrPageResult(
                                    chapterId = chapterId,
                                    pageIndex = pageIndex,
                                    ocrModel = prefs.ocrModel().get(),
                                    imageWidth = ocrBitmap.width,
                                    imageHeight = ocrBitmap.height,
                                    regions = emptyList(),
                                )
                            } finally {
                                partials.close()
                            }
                        }
                        if (streaming) {
                            for (region in partials) {
                                if (myGen != generation || job?.isActive != true) {
                                    scan.cancel()
                                    break
                                }
                                if (!speakStreamingRegion(region, prefs.autoReadLanguage().get())) {
                                    // Озвучки нет: дочитывать страницу незачем.
                                    scan.cancel()
                                    break
                                }
                            }
                        }
                        scan.await()
                    }
                } catch (e: TimeoutCancellationException) {
                    partials.close()
                    logcat(LogPriority.WARN) {
                        "OCR frame timeout (${OCR_FRAME_TIMEOUT_MS}ms) pageIndex=$pageIndex"
                    }
                    OcrHistoryStore.addAutoRead(
                        false,
                        "OCR-кадр таймаут",
                        "${OCR_FRAME_TIMEOUT_MS / 1000}с pageIndex=$pageIndex",
                    )
                    mihon.domain.ocr.model.OcrPageResult(
                        chapterId = chapterId,
                        pageIndex = pageIndex,
                        ocrModel = prefs.ocrModel().get(),
                        imageWidth = ocrBitmap.width,
                        imageHeight = ocrBitmap.height,
                        regions = emptyList(),
                    )
                }

                val language = prefs.autoReadLanguage().get()
                val translate = prefs.autoReadTranslate().get()
                // Правила книги (порядок чтения, «только облачки») читаем один
                // раз на кадр: файл на книгу один, а нужны они в двух местах.
                val bookRules = loadBookRules()
                // Порядок чтения берём из пресета типа контента (манхва/вебтун →
                // «vertical» сверху вниз), а не из старого `scanReadingOrder`,
                // который по умолчанию «rtl» и ломал подсветку на вебтунах.
                // Выученное агентом правило книги важнее пресета: ради него
                // книга и изучается по сайту.
                val order = bookReadingOrder(bookRules)

                // ===== БАЛЛОНЫ ВМЕСТО «ВСЕЙ СТРАНИЦЫ» (фикс по скриншотам) =====
                // Полностраничные движки возвращают один регион 0,0-1,1.
                // Для авточтения такой результат дополнительно разбирается:
                //  1) YOLO-детектор находит баллоны;
                //  2) каждый баллон распознаётся выбранным OCR (по умолчанию
                //     полностью офлайн Cyrillic PP-OCR);
                //  3) каждый баллон = своя реплика со своей рамкой, номером
                //     {N} и полом говорящего.
                // Если детектор ничего не нашёл — текст страницы хотя бы
                // делится на строки-реплики вместо одного блока.
                var lines: List<Line> = result.regions.map {
                    // v1.9.44: OCR-путаница латиницы/кириллицы (M E Ч, А Т М) и
                    // цифр (5→Б) лечится фиксером похожих символов.
                    Line(normalizeOcrTextForDisplay(CyrillicTranslitFixer.autoFixCyrillic(it.text)).trim(), it.boundingBox)
                }
                val wholePage = result.regions.size == 1 && result.regions.first().isWholePage
                // v1.9.91: полностраничные (AI) движки распознают ВСЮ видимую
                // область ОДНИМ вызовом. Больше не режем кадр на маленькие
                // баблы и не распознаём каждый кроп по отдельности — это
                // замедляло AI-OCR и теряло слова. Строки целой страницы сразу
                // становятся репликами; YOLO-баллоны остаются запасным путём,
                // когда модель вернула пустой результат.
                if (wholePage && !ocrBitmap.isRecycled) {
                    val wholeText = lines.firstOrNull()?.text.orEmpty()
                    val usableWhole = wholeText.count(Char::isLetter) >= 4
                    // Правило книги «читать только облачки» перевешивает
                    // полностраничный текст: выученное правило должно менять
                    // конвейер, а не просто лежать в чате.
                    val bubblesFirst = bubblesOnly(bookRules)
                    lines = if (usableWhole && !bubblesFirst) {
                        splitWholePageToLines(lines.first())
                    } else {
                        val bubbleLines = runCatching { readBubbles(ocrBitmap, chapterId, pageIndex, order) }
                            .onFailure {
                                logcat(LogPriority.WARN, it) { "Bubble detection failed" }
                                OcrHistoryStore.addAutoRead(false, "детектор облачков", it.message ?: it.javaClass.simpleName)
                            }
                            .getOrDefault(emptyList())
                        if (bubbleLines.isNotEmpty()) {
                            bubbleLines
                        } else {
                            // Фолбэк: строки полностраничного текста как реплики
                            lines.firstOrNull()?.let { splitWholePageToLines(it) } ?: emptyList()
                        }
                    }
                }
                // v1.9.92: онлайн-движок вернул ПОДОЗРИТЕЛЬНО мало строк (например,
                // только ватермарку «Читай раньше всех на Remanga», а настоящую
                // крупную реплику модель не увидела) — дочитываем кадр локальным
                // разбором баллонов (YOLO + Cyrillic OCR) и доклеиваем результат.
                // Так реальный текст манхвы не пропадает, даже если AI его пропустил.
                if (result.ocrModel in ONLINE_MODELS &&
                    lines.count { it.text.count(Char::isLetter) >= 3 } < SUPPLEMENT_BUBBLES_MIN &&
                    !ocrBitmap.isRecycled
                ) {
                    val extra = runCatching { readBubbles(ocrBitmap, chapterId, pageIndex, order) }
                        .onFailure {
                            logcat(LogPriority.WARN, it) { "Bubble supplement failed" }
                            OcrHistoryStore.addAutoRead(false, "добор баллонов", it.message ?: it.javaClass.simpleName)
                        }
                        .getOrDefault(emptyList())
                    if (extra.isNotEmpty()) {
                        lines = lines + extra
                    }
                }

                // Скриншот-кадр для вкладки «Скриншоты»: JPEG сохраняем ДО recycle —
                // после него пикселей больше нет, а сама запись пишется ниже по коду.
                val screenshotJpeg: ByteArray? = if (prefs.autoScreenshotEnabled().get() && !ocrBitmap.isRecycled) {
                    runCatching { encodeJpeg(ocrBitmap, JPEG_QUALITY) }.getOrNull()
                } else null

                if (!bitmap.isRecycled) bitmap.recycle()
                if (scanBitmap !== bitmap && !scanBitmap.isRecycled) scanBitmap.recycle()

                // Локальный OCR (Cyrillic PP-OCR) отдаёт регион на КАЖДУЮ
                // строку: реплика из двух строк распадалась на два разных
                // «текста», и перенос слова читался как два разных слова.
                // Строки одного облачка (сильное перекрытие по X, малый зазор)
                // склеиваются в одну реплику. Полностраничные фолбэки не трогаем.
                if (!wholePage) lines = mergeBubbleLines(lines)

                // 1) фильтр мусора OCR (обрывки «eS la 4», «| | > |», «о»)
                //    + фильтр по языку; 2) отсев уже прочитанного
                val fresh = lines
                    .asSequence()
                    .map { it.copy(text = cleanOcrGarbage(it.text, language)) }
                    .filter { isMeaningful(it.text, language) }
                    .filter { !isDuplicate(it.text) }
                    // Рамка обязана лежать внутри страницы и иметь разумный
                    // размер: иначе голубая подсветка вылезает за текст/экран.
                    .filter { b ->
                        val bb = b.boundingBox
                        bb.left >= -0.001f && bb.top >= -0.001f &&
                            bb.right <= 1.001f && bb.bottom <= 1.001f &&
                            (bb.right - bb.left) > 0.02f &&
                            (bb.bottom - bb.top) > 0.01f
                    }
                    .toList()

                // 3) порядок чтения (группировка в строки по близости центров Y,
                // а не по фиксированным 12% полосам — убирает «лесенку»)
                val ordered = orderRegions(fresh, order)

                // ===== СКРИНШОТ: сохраняем распознанные регионы в буфер =====
                // Лёгкая запись (~1-5 KB) — текст + координаты + JPEG-кадр кадра
                // (который снимался ещё до recycle). Показывается на вкладке
                // «Скриншоты» вместе с настоящей картинкой и именем движка OCR.
                if (prefs.autoScreenshotEnabled().get() && ordered.isNotEmpty()) {
                    val engineName = result.ocrModel.name.lowercase()
                    val ocrRegions = ordered.mapIndexed { idx, line ->
                        OcrRegion(
                            order = idx,
                            text = line.text,
                            boundingBox = line.boundingBox,
                            textOrientation = OcrTextOrientation.Horizontal,
                        )
                    }
                    OcrScreenshotBuffer.add(
                        chapterId = chapterId,
                        pageIndex = pageIndex,
                        scrollFraction = 0f,
                        regions = ocrRegions,
                        engineUsed = engineName,
                        imageWidth = result.imageWidth,
                        imageHeight = result.imageHeight,
                        imageJpeg = screenshotJpeg,
                    )
                }

                // 3.5) Пол говорящих. Приоритет:
                //  а) ВСТРОЕННЫЙ локальный AI (LocalSpeakerAi) — морфология
                //     русского текста, работает без сети и без ключей;
                //  б) AI-конвейер (prepareFrame) — ПАРАЛЛЕЛЬНО, без блокировки.
                //
                // БЫСТРОЕ АВТОЧТЕНИЕ (фикс замедления): раньше конвейер
                // «чат → голос» БЛОКИРОВАЛ старт озвучки до 8 секунд на
                // каждом кадре (ждали ответа модели). Теперь чтение стартует
                // МГНОВЕННО с локальной морфологией, а ответ ассистента
                // подхватывается на лету и применяется к ещё НЕ прочитанным
                // репликам (пол, чистка, скип дублей). Дубли прошлых кадров
                // и так режутся локальной нечёткой историей — заглушек нет,
                // просто больше не ждём сеть.
                val preparedRef = java.util.concurrent.atomic.AtomicReference<
                    List<eu.kanade.tachiyomi.data.ai.AiAssistant.PreparedLine>?,
                    >(null)
                if (prefs.aiGenderVoices().get() && ordered.isNotEmpty()) {
                    val newLines = ordered.map { it.text }
                    val prevSnapshot = prevFrameLines
                    val advice = eu.kanade.tachiyomi.data.ai.BookKnowledge.renderAdvice(
                        context = context,
                        mangaId = mihon.data.ocr.ReaderContextBus.current.value?.mangaId,
                    )
                    scope.launch {
                        preparedRef.set(
                            eu.kanade.tachiyomi.data.ai.AiAssistant.prepareFrame(
                                newLines = newLines,
                                prevLines = prevSnapshot,
                                advice = advice,
                            ),
                        )
                    }
                }
                prevFrameLines = ordered.map { it.text }

                val localGenders = LocalSpeakerAi.guessGenders(ordered.map { it.text })
                val genders = java.util.concurrent.atomic.AtomicReferenceArray<String?>(ordered.size)
                for (i in ordered.indices) {
                    genders.set(i, localGenders[i] ?: detectGenderByDictionary(ordered[i].text))
                }
                aiRefine = null
                // Gemini Vision как ещё один фоновый уточнитель — только с ключом
                if (genderJpeg != null && ordered.isNotEmpty() &&
                    prefs.googleApiKey().get().isNotBlank() && localGenders.any { it == null }
                ) {
                    scope.launch {
                        val vision = SpeakerGenderService.detect(genderJpeg, ordered.map { it.text }, prefs)
                        for (i in ordered.indices) {
                            if (genders.get(i) == null) genders.set(i, vision.getOrNull(i))
                        }
                    }
                }

                lastFrameHadText = ordered.isNotEmpty()
                if (ordered.isNotEmpty()) {
                    lastFrameText = ordered.joinToString("\n") { it.text }
                }
                // Полы текущего кадра — для слотов голосов следующего кадра
                // (продолжение персонажа через перекрытие вебтуна).
                prevFrameGenders = (0 until ordered.size).map { genders.get(it) }

                // 3.7) перевод ВСЕЙ страницы одним запросом (раньше был
                // отдельный HTTP-запрос на каждую реплику — на 15 бабблах
                // это 15 последовательных обращений между озвучками).
                val target = prefs.translateTarget().get().ifBlank { "ru" }
                val translations: List<String> = if (translate && language != target) {
                    runCatching { MangaTranslatorService.translateAll(ordered.map { it.text }, target) }
                        .getOrElse { ordered.map { it.text } }
                } else {
                    ordered.map { it.text }
                }

                // Публикуем карту кадра: всё, что будет прочитано
                val frozenL = ordered.filter { lineKey(it.text) in spokenLines }
                val speakable = ordered.filterNot { lineKey(it.text) in spokenLines }
                _frameRegions.value = speakable.mapIndexed { i, r ->
                    FrameRegion(r.boundingBox, i + 1, FrameRegion.State.UPCOMING, r.text)
                } + frozenL.mapIndexed { j, r ->
                    FrameRegion(r.boundingBox, speakable.size + j + 1, FrameRegion.State.DONE, r.text)
                }

                // 4) реплика за репликой: подсветка -> озвучка -> ждём конца
                for ((i, region) in speakable.withIndex()) {
                    if (job?.isActive != true) break

                    // Ответ ассистента подхватывается на лету (если уже
                    // пришёл): скип дублей, чистый текст, пол. Если ещё не
                    // пришёл — читаем немедленно локальным конвейером.
                    val prep = preparedRef.get()?.getOrNull(i)
                    if (prep != null && !prep.speak) continue
                    if (prep?.gender != null && genders.get(i) == null) genders.set(i, prep.gender)

                    // Обновляем статусы: до i — прочитано, i — читается, после — предстоит
                    _frameRegions.value = speakable.mapIndexed { j, r ->
                        FrameRegion(
                            r.boundingBox,
                            j + 1,
                            when {
                                j < i -> FrameRegion.State.DONE
                                j == i -> FrameRegion.State.CURRENT
                                else -> FrameRegion.State.UPCOMING
                            },
                            r.text,
                        )
                    }

                    val translatedText = translations.getOrNull(i)?.takeIf { it.isNotBlank() }
                    val speakTextRaw = if (translate && language != target) {
                        translatedText ?: region.text
                    } else {
                        prep?.text?.takeIf { it.isNotBlank() } ?: translatedText ?: region.text
                    }

                    // Ручной режим важнее автоопределения: читатель выбрал
                    // голос кнопкой в читалке и ждёт именно его.
                    //
                    // Роль-режим (1 / 2 / много голосов):
                    //  • SINGLE — весь текст одним голосом нарратора;
                    //  • DUAL/TRIPLE — пол реплики (муж/жен) определяет голос;
                    //  • MULTI — как DUAL, но с отдельным слотом каждому
                    //    персонажу одного пола (см. slot ниже).
                    val roleMode = VoiceModeResolver.currentMode()
                    val gender = when {
                        prefs.manualVoiceMode().get() ->
                            prefs.manualVoiceGender().get().takeIf { it.isNotBlank() } ?: "female"
                        roleMode == VoiceModeResolver.Mode.SINGLE -> VoiceModeResolver.narratorGender()
                        else -> genders.get(i) // мог дозаполниться AI пока читали предыдущие
                    }

                    // Служебные пометки: номер по порядку чтения и пол.
                    // Они показываются на экране, но НЕ произносятся —
                    // SpeechMarkup.strip() снимает их перед синтезом.
                    val marks = buildString {
                        if (prefs.showSpeechNumbers().get()) append("{").append(i + 1).append("}")
                        when (gender) {
                            "female" -> append("{ж}")
                            "male" -> append("{м}")
                        }
                    }

                    _currentRegion.value = SpokenRegion(
                        text = region.text,
                        translated = speakTextRaw.takeIf { it != region.text },
                        box = region.boundingBox,
                        index = i + 1,
                        total = ordered.size,
                        marks = marks,
                    )

                    // Слот говорящего: каждый персонаж одного пола в сцене
                    // получает свой голос. Слоты сессионные (переживают кадры):
                    // продолжение реплики прошлого кадра звучит тем же голосом,
                    // новый персонаж — следующим свободным. В режиме «много
                    // голосов» и при «свой голос персонажу» всегда включено.
                    val slot = if (
                        roleMode == VoiceModeResolver.Mode.MULTI ||
                        prefs.perSpeakerVoices().get()
                    ) {
                        val occurrence = (0 until i).count { genders.get(it) == gender }
                        voiceSlotFor(gender, region.text, occurrence)
                    } else {
                        0
                    }

                    when (speakAndAwait(SpeechMarkup.strip(speakTextRaw), gender, slot)) {
                        SpeakOutcome.SPOKEN, SpeakOutcome.SILENT -> rejectedStreak = 0
                        SpeakOutcome.NO_ENGINE -> {
                            blockAutoread(NO_VOICE_MESSAGE)
                            return@launch
                        }
                        SpeakOutcome.REJECTED -> {
                            // Разовый отказ — обычное дело. Серия отказов означает,
                            // что озвучки нет: дальше листать нечего.
                            rejectedStreak++
                            if (rejectedStreak >= REJECTED_STOP_AFTER) {
                                blockAutoread(REJECTED_VOICE_MESSAGE)
                                return@launch
                            }
                        }
                    }
                    // Плавный режим вебтуна: реплика дочитана — прокрутить ровно
                    // на её высоту, чтобы следующая была уже внизу вьюпорта.
                    if (onLineSpoken != null) {
                        runCatching { onLineSpoken(region.boundingBox) }
                            .onFailure { logcat(LogPriority.WARN, it) { "onLineSpoken failed" } }
                    }
                    spokenLines.add(lineKey(region.text))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "AutoRead frame failed" }
                OcrHistoryStore.addAutoRead(false, "сбой страницы", e.message ?: e.javaClass.simpleName)
            } finally {
                aiRefine?.cancel()
                _currentRegion.value = null
                _frameRegions.value = emptyList()
                streamedRegionCount.set(0)
                _isReading.value = false
                // Колбэк только для АКТУАЛЬНОГО запуска: после stop() старый
                // цикл не имеет права листать дальше или перезапускать чтение
                if (myGen == generation && job?.isCancelled != true) {
                    onPageFinished()
                }
            }
        }
    }

    /**
     * Мгновенный скриншот: распознать текущий кадр выбранным движком (в т.ч.
     * Glens) и сохранить в буфер «Скриншоты», НЕ озвучивая и не трогая
     * состояние авточтения. Вызывается с плавающей кнопки читалки.
     */
    fun captureInstantScreenshot(
        bitmap: Bitmap,
        chapterId: Long,
        pageIndex: Int,
        scrollFraction: Float,
    ) {
        if (prefs.autoScreenshotEnabled().get()) {
            scope.launch {
                // Скриншот в фоне и в минимально читаемом разрешении.
                val scaled = downscaleForScan(bitmap)
                try {
                    val result = try {
                        withTimeout(OCR_FRAME_TIMEOUT_MS) {
                            scanPageOcr.await(chapterId, pageIndex, scaled.toOcrImage())
                        }
                    } catch (e: TimeoutCancellationException) {
                        logcat(LogPriority.WARN) {
                            "Instant screenshot OCR timeout (${OCR_FRAME_TIMEOUT_MS}ms)"
                        }
                        return@launch
                    }
                    // JPEG-кадр пишем ДО recycle: после него пикселей не будет.
                    val jpeg = runCatching { encodeJpeg(scaled, JPEG_QUALITY) }.getOrNull()
                    val width = scaled.width
                    val height = scaled.height
                    if (result.regions.isEmpty()) return@launch
                    val ordered = orderRegions(
                        result.regions.map {
                            Line(
                                text = it.text,
                                boundingBox = it.boundingBox,
                            )
                        },
                        OcrRegionRules.readingOrderFor(prefs),
                    )
                    OcrScreenshotBuffer.add(
                        chapterId = chapterId,
                        pageIndex = pageIndex,
                        scrollFraction = scrollFraction,
                        regions = ordered.mapIndexed { idx, line ->
                            OcrRegion(
                                order = idx,
                                text = line.text,
                                boundingBox = line.boundingBox,
                                textOrientation = OcrTextOrientation.Horizontal,
                            )
                        },
                        engineUsed = result.ocrModel.name.lowercase(),
                        imageWidth = width,
                        imageHeight = height,
                        imageJpeg = jpeg,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "Instant screenshot OCR failed" }
                } finally {
                    if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
                }
            }
        }
    }

    private fun Bitmap.toOcrImage(): OcrImage {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return OcrImage(width, height, pixels)
    }

    /**
     * Озвучки на устройстве нет: останавливаем авточтение и говорим об этом.
     *
     * Раньше конвейел продолжал работу: распознавал страницу, подсвечивал
     * реплики, листал дальше — и не произносил ни слова. Читатель видел
     * «чтение идёт», а звука не было. Теперь `generation` увеличивается, чтобы
     * колбэк «страница прочитана» не листал дальше, а `onPageFinished` не
     * вызывался вовсе.
     */
    private fun blockAutoread(message: String) {
        logcat(LogPriority.WARN) { "Autoread blocked: $message" }
        _voiceBlock.value = message
        OcrHistoryStore.addAutoRead(false, "озвучка недоступна", message)
        rejectedStreak = 0
        generation++
        TtsSpeaker.stop()
    }

    /**
     * Есть ли смысл начинать чтение: выбранный движок должен уметь озвучить.
     *
     * По умолчанию читаем голосом телефона, поэтому проверяется именно он.
     * Сетевые голоса проверяются сетью, системный — наличием установленного
     * TTS-движка (`PackageManager`, без блокировки). Проверка до
     * распознавания: без неё кадр сначала уходит в OCR, и только потом
     * выясняется, что произносить нечем.
     */
    private fun voicePathUsable(): Boolean {
        val enginePref = runCatching { prefs.voiceEngine().get() }.getOrDefault("")
        val phoneOnly = runCatching { prefs.voicePhoneOnly().get() }.getOrDefault(true)
        val online = isNetworkAvailable(context)
        if (phoneOnly) return TtsSpeaker.systemEngineInstalled(context)
        return when (enginePref) {
            TtsSpeaker.ENGINE_GOOGLE_WEB,
            TtsSpeaker.ENGINE_EDGE_TTS,
            TtsSpeaker.ENGINE_ELEVENLABS,
            -> online
            TtsSpeaker.ENGINE_AUTO -> if (online) {
                true
            } else {
                TtsSpeaker.systemEngineInstalled(context)
            }
            // Сетевой голос без сети и так падает на системный (см. speakAs), а
            // удалённый — тоже. Проверяем именно системный движок.
            else -> online || TtsSpeaker.systemEngineInstalled(context)
        }
    }

    fun stop() {
        spokenLines.clear()
        speakerSlots.clear()
        prevFrameGenders = emptyList()
        rejectedStreak = 0
        _voiceBlock.value = null
        generation++ // инвалидируем все pending-колбэки
        job?.cancel()
        job = null
        TtsSpeaker.stop()
        _currentRegion.value = null
        _frameRegions.value = emptyList()
        streamedRegionCount.set(0)
        _isReading.value = false
    }

    /**
     * Озвучить ОДНУ реплику (бабл), выбранную пользователем (значок 🔊,
     * перетаскивание значка или ручной бабл). В отличие от [readFrame] не
     * трогает историю кадра и не листает: просто произносит переданный текст
     * выбранным движком/полом. Запускается в [scope], чтобы [job] был активен
     * во время озвучки (иначе [speakAndAwait] мгновенно прерывается).
     */
    fun speakSingle(text: String, gender: String? = null, speakerSlot: Int = 0) {
        val clean = SpeechMarkup.strip(text).trim()
        if (clean.isBlank()) return
        if (_isReading.value) TtsSpeaker.stop()
        job?.cancel()
        val myGen = ++generation
        job = scope.launch {
            _isReading.value = true
            try {
                speakAndAwait(clean, gender, speakerSlot)
            } finally {
                if (generation == myGen) _isReading.value = false
            }
        }
    }

    /**
     * Исход одной озвучки. `Boolean`-колбэк TTS не различает «движка нет» и
     * «нечего произносить», и авточтение продолжало листать страницы, не
     * сказав ни слова. Различаем явно.
     */
    private enum class SpeakOutcome {
        /** Реплика произнесена. */
        SPOKEN,

        /** Произносить было нечего: пустой текст, ремарка без звучания. */
        SILENT,

        /** На устройстве нет TTS-движка. */
        NO_ENGINE,

        /** Движок есть, но текст принял и не озвучил. */
        REJECTED,
    }

    /**
     * Озвучивает реплику, распознанную локальным движком, не дожидаясь конца
     * страницы, и помечает её прочитанной.
     *
     * Потоковый проход сознательно «глупый»: без определения пола по картинке,
     * без перевода, без склейки соседних строк в одну реплику. Всё это требует
     * знать всю страницу и не может работать на лету. Зато читатель слышит
     * первую реплику сразу, а основной конвейер позже дочитывает остальные и
     * пропускает уже озвученное через [spokenLines].
     *
     * Номера реплик в потоковом режиме не совпадают с итоговой нумерацией
     * страницы: итоговую расставляет конвейер ниже, когда страница собрана.
     *
     * Возвращает false, когда продолжать незачем: озвучки на устройстве нет.
     * Иначе цикл ждал бы по [TTS_START_GRACE_MS] на каждую реплику страницы.
     */
    private suspend fun speakStreamingRegion(
        region: OcrRegion,
        language: String,
    ): Boolean {
        val text = normalizeOcrTextForDisplay(
            CyrillicTranslitFixer.autoFixCyrillic(region.text),
        ).trim()
        if (text.isBlank() || !matchesLanguage(text, language)) return true
        if (lineKey(text) in spokenLines) return true
        val index = streamedRegionCount.getAndIncrement()
        _frameRegions.value = _frameRegions.value + FrameRegion(
            region.boundingBox,
            index + 1,
            FrameRegion.State.CURRENT,
            text,
        )
        return when (speakAndAwait(text, gender = null)) {
            SpeakOutcome.SPOKEN -> {
                spokenLines += lineKey(text)
                markSpokenInFrame(text)
                rejectedStreak = 0
                true
            }
            SpeakOutcome.SILENT -> {
                rejectedStreak = 0
                true
            }
            SpeakOutcome.NO_ENGINE -> {
                blockAutoread(NO_VOICE_MESSAGE)
                false
            }
            SpeakOutcome.REJECTED -> {
                rejectedStreak++
                if (rejectedStreak >= REJECTED_STOP_AFTER) {
                    blockAutoread(REJECTED_VOICE_MESSAGE)
                    false
                } else {
                    true
                }
            }
        }
    }

    /**
     * Помечает уже озвученную потоковым проходом реплику прочитанной в карте
     * кадра: на неё должна гореть галочка, а не «читается сейчас».
     */
    private fun markSpokenInFrame(text: String) {
        val key = lineKey(text)
        _frameRegions.value = _frameRegions.value.map { region ->
            if (lineKey(region.text) == key) {
                region.copy(state = FrameRegion.State.DONE)
            } else {
                region
            }
        }
    }

    /**
     * Допустим ли потоковый порядок «сверху вниз».
     *
     * Локальный детектор отдаёт области по координате Y, а при RTL чтении
     * реплики одной строки идут справа налево. Озвучка на лету тогда прочла бы
     * реплики задом наперёд, поэтому для RTL остаётся пакетный режим.
     */
    internal fun orderAllowsStreaming(order: String): Boolean = order != "rtl"

    /** Озвучка с ожиданием реального окончания фразы. */
    private suspend fun speakAndAwait(text: String, gender: String? = null, speakerSlot: Int = 0): SpeakOutcome {
        // Оба флага — MutableStateFlow: onState приходит из потока TTS, а читается
        // из этой корутины (диспетчер IO). Обычный var здесь означает гонку — цикл
        // ожидания мог бы не увидеть `started = true` и сочти фразу неозвученной.
        val started = MutableStateFlow(false)
        // Флаг фактического завершения фразы.
        val done = MutableStateFlow(false)
        val t0 = System.currentTimeMillis()
        TtsSpeaker.speakAs(context, text, gender, speakerSlot) { speaking ->
            if (speaking && !started.value) {
                started.value = true
                logcat(LogPriority.DEBUG) { "TTS started (${System.currentTimeMillis() - t0}ms): ${text.take(60)}" }
            }
            if (!speaking && started.value) {
                done.value = true
                logcat(LogPriority.DEBUG) { "TTS done in ${System.currentTimeMillis() - t0}ms" }
                OcrHistoryStore.addAutoRead(true, "озвучено (${System.currentTimeMillis() - t0} мс)", text.take(60))
            }
        }
        val start = System.currentTimeMillis()
        // Фаза 1: ждём, пока движок начнёт говорить. Если за TTS_START_GRACE_MS
        // реплика не стартовала, произносить просто нечего: пустой текст после
        // снятия разметки, ремарка без звучания, незагруженный голос. Ждать
        // полный таймаут нельзя — на странице из одной реплики это и была
        // «остановка»: цикл молчал 8+ секунд, ничего не говоря.
        while (!ttsStartedOrGiveUp(started.value, System.currentTimeMillis() - start)) {
            if (job?.isActive != true) {
                TtsSpeaker.stop()
                return SpeakOutcome.SILENT
            }
            delay(20)
        }
        if (!started.value) {
            // Причина известна: либо движка нет вовсе, либо он отказал в тексте,
            // либо произносить было нечего. Молча пропускать реплику можно только
            // в последнем случае.
            val failure = TtsSpeaker.lastFailure
            val detail = TtsSpeaker.lastFailureDetail
            logcat(LogPriority.WARN) {
                "TTS never started (${failure.name}${if (detail.isBlank()) "" else ": $detail"}): ${text.take(60)}"
            }
            OcrHistoryStore.addAutoRead(false, "TTS не запустился", "${failure.name}: ${text.take(60)}")
            return when (failure) {
                TtsSpeaker.SpeakFailure.NO_ENGINE -> SpeakOutcome.NO_ENGINE
                TtsSpeaker.SpeakFailure.REJECTED -> SpeakOutcome.REJECTED
                TtsSpeaker.SpeakFailure.NONE -> SpeakOutcome.SILENT
            }
        }
        // Фаза 2: реплика пошла — ждём завершения по onState.
        val timeoutMs = ttsTimeoutMs(text.length, prefs.speechRate().get())
        while (!done.value && System.currentTimeMillis() - start < timeoutMs) {
            if (job?.isActive != true) {
                TtsSpeaker.stop()
                return SpeakOutcome.SILENT
            }
            delay(40) // быстрый опрос: между репликами нет лишней паузы
        }
        // Диагностика недоговорённых реплик: TTS мог прерваться без onDone.
        if (!done.value) {
            logcat(LogPriority.WARN) {
                "TTS timeout without onDone: ${text.take(60)} (waited ${System.currentTimeMillis() - start}ms)"
            }
            OcrHistoryStore.addAutoRead(false, "TTS без завершения", text.take(60))
            // onError приходит в том же Boolean-колбэке, что и onDone, поэтому
            // сбой посреди фразы выглядел как «озвучено». Теперь он виден.
            if (TtsSpeaker.lastFailure == TtsSpeaker.SpeakFailure.REJECTED) {
                return SpeakOutcome.REJECTED
            }
        }
        return SpeakOutcome.SPOKEN
    }

    /**
     * Словарный фолбэк пола говорящего: работает, когда морфология
     * LocalSpeakerAi не дала ответа (в реплике нет «я …ла/…л»). Ориентируемся
     * на маркеры окружения персонажа: родственные связи и роли. Возвращаем
     * пол только при явном перевесе — иначе null (нейтральный голос).
     */
    private fun detectGenderByDictionary(text: String): String? {
        val maleMarkers = listOf(
            "брат", "отец", "папа", "дед", "сын", "мужчина", "парень",
            "господин", "старик", "мальчик", "юноша", "принц", "король",
        )
        val femaleMarkers = listOf(
            "сестра", "мать", "мама", "бабушка", "дочь", "женщина", "девушка",
            "госпожа", "старуха", "девочка", "принцесса", "королева",
        )
        val lower = text.lowercase()
        // Подстрочный поиск покрывает падежи: «моей сестры», «к отцу».
        val maleCount = maleMarkers.count { lower.contains(it) }
        val femaleCount = femaleMarkers.count { lower.contains(it) }
        val byMarkers = when {
            maleCount > femaleCount -> "male"
            femaleCount > maleCount -> "female"
            else -> null
        }
        if (byMarkers != null) return byMarkers
        // Морфологический фолбэк: род по окончаниям словоформ (RuMorph).
        val morph = RuMorph.guessGender(text)
        if (morph != null) {
            OcrHistoryStore.addAutoRead(true, "пол говорящего: морфология", "$morph: ${text.take(40)}")
        }
        return morph
    }

    // region Баллоны (YOLO) и чистка OCR-мусора

    /**
     * YOLO-детект баллонов на кадре + пофрагментный OCR. Каждый найденный
     * баллон становится отдельной репликой со своей рамкой. После разовой
     * загрузки моделей весь конвейер работает полностью офлайн.
     */
    private suspend fun readBubbles(
        bitmap: Bitmap,
        chapterId: Long,
        pageIndex: Int,
        order: String,
    ): List<Line> {
        val direction = when (order) {
            "ltr" -> tachiyomi.core.common.util.system.ReadingDirection.LTR
            "vertical" -> tachiyomi.core.common.util.system.ReadingDirection.VERTICAL
            else -> tachiyomi.core.common.util.system.ReadingDirection.RTL
        }
        val det = detectPanels.await(
            cacheKey = "autoread_${chapterId}_$pageIndex",
            image = bitmap,
            originalWidth = bitmap.width,
            originalHeight = bitmap.height,
            direction = direction,
        )
        val bubbles = det.debugBubbles
            .map { it.rect }
            .filter { it.width() > 16 && it.height() > 16 }
            .take(MAX_BUBBLES_PER_FRAME)
        if (bubbles.isEmpty()) return emptyList()

        val out = mutableListOf<Line>()
        for ((index, r) in bubbles.withIndex()) {
            if (job?.isActive != true) break
            // 1) Широкая рамка (ширина > 1.6 высоты) часто обнимает ДВА круглых
            //    облачка, совмещённых вплотную, с разным текстом. Прогоняем по
            //    ней панельный детектор ещё раз и распознаём ВЕСЬ текст,
            //    разбив его по найденным облачкам.
            if (r.width() > 1.6f * r.height()) {
                val subLines = readBubbleSplits(
                    bitmap = bitmap,
                    chapterId = chapterId,
                    pageIndex = pageIndex,
                    rect = r,
                    index = index,
                    direction = direction,
                )
                out += subLines
                continue
            }
            // 2) Обычное облачко: поля 6%, круглая форма маскируется эллипсом,
            //    чтобы углы квадратного кадрирования не тащили текст соседа.
            val padX = (r.width() * 0.06f).toInt()
            val padY = (r.height() * 0.06f).toInt()
            val left = (r.left - padX).coerceAtLeast(0)
            val top = (r.top - padY).coerceAtLeast(0)
            val right = (r.right + padX).coerceAtMost(bitmap.width)
            val bottom = (r.bottom + padY).coerceAtMost(bitmap.height)
            if (right - left < 16 || bottom - top < 16) continue
            var text = ocrCroppedRegion(
                bitmap,
                left,
                top,
                right,
                bottom,
                maskRound = (right - left).toFloat() / (bottom - top) in 0.7f..1.4f,
            )
            var clean = normalizeOcrTextForDisplay(text).trim()
            // 3) Пустой/куцый результат (углы коснулись соседнего облачка или
            //    рамка съехала) — повторяем по ужатому центру рамки.
            if (clean.count { it.isLetter() } < 3 && bottom - top > 32) {
                val dx = ((right - left) * 0.14f).toInt()
                val dy = ((bottom - top) * 0.14f).toInt()
                text = ocrCroppedRegion(
                    bitmap,
                    left + dx,
                    top + dy,
                    right - dx,
                    bottom - dy,
                    maskRound = false,
                )
                clean = normalizeOcrTextForDisplay(text).trim()
            }
            if (clean.isNotBlank()) {
                out += Line(
                    text = clean,
                    boundingBox = OcrBoundingBox(
                        left = left.toFloat() / bitmap.width,
                        top = top.toFloat() / bitmap.height,
                        right = right.toFloat() / bitmap.width,
                        bottom = bottom.toFloat() / bitmap.height,
                    ),
                )
            }
        }
        return out
    }

    /**
     * Два круглых облачка вплотную внутри одной широкой рамки: повторный
     * панельный детектор на кадрированном фрагменте находит их по отдельности,
     * каждый распознаётся со своей репликой. Если детектор не нашёл ничего —
     * распознаём всю широкую рамку целиком.
     */
    private suspend fun readBubbleSplits(
        bitmap: Bitmap,
        chapterId: Long,
        pageIndex: Int,
        rect: android.graphics.Rect,
        index: Int,
        direction: tachiyomi.core.common.util.system.ReadingDirection,
    ): List<Line> {
        val out = mutableListOf<Line>()
        val padX = (rect.width() * 0.04f).toInt()
        val padY = (rect.height() * 0.04f).toInt()
        val left = (rect.left - padX).coerceAtLeast(0)
        val top = (rect.top - padY).coerceAtLeast(0)
        val right = (rect.right + padX).coerceAtMost(bitmap.width)
        val bottom = (rect.bottom + padY).coerceAtMost(bitmap.height)
        if (right - left < 16 || bottom - top < 16) return out
        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        val kids = try {
            detectPanels.await(
                cacheKey = "bubble_split_${chapterId}_${pageIndex}_$index",
                image = crop,
                originalWidth = crop.width,
                originalHeight = crop.height,
                direction = direction,
            ).debugBubbles.map { it.rect }
                .filter {
                    it.width() > 16 && it.height() > 16 &&
                        (it.width() * it.height()) >= 0.13f * crop.width * crop.height
                }
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "bubble split detect failed" }
            emptyList()
        } finally {
            if (!crop.isRecycled) crop.recycle()
        }
        if (kids.size < 2) {
            // Детектор не увидел отдельных облачков — читаем всю рамку как есть.
            val text = ocrCroppedRegion(bitmap, left, top, right, bottom, maskRound = false)
            val clean = normalizeOcrTextForDisplay(text).trim()
            if (clean.isNotBlank()) {
                out += Line(
                    text = clean,
                    boundingBox = normalizeBox(left, top, right, bottom, bitmap),
                )
            }
            return out
        }
        for (kid in kids.take(MAX_BUBBLES_PER_FRAME)) {
            if (job?.isActive != true) break
            val kPadX = (kid.width() * 0.05f).toInt()
            val kPadY = (kid.height() * 0.05f).toInt()
            val kl = (left + kid.left - kPadX).coerceAtLeast(0)
            val kt = (top + kid.top - kPadY).coerceAtLeast(0)
            val kr = (left + kid.right + kPadX).coerceAtMost(bitmap.width)
            val kb = (top + kid.bottom + kPadY).coerceAtMost(bitmap.height)
            if (kr - kl < 16 || kb - kt < 16) continue
            val text = ocrCroppedRegion(
                bitmap,
                kl,
                kt,
                kr,
                kb,
                maskRound = (kr - kl).toFloat() / (kb - kt) in 0.7f..1.4f,
            )
            val clean = normalizeOcrTextForDisplay(text).trim()
            if (clean.isNotBlank()) {
                out += Line(clean, normalizeBox(kl, kt, kr, kb, bitmap))
            }
        }
        return out
    }

    /** Обрезка + (по желанию) круглая маска + OCR одной области. */
    private suspend fun ocrCroppedRegion(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        maskRound: Boolean,
    ): String {
        val w = right - left
        val h = bottom - top
        if (w < 16 || h < 16) return ""
        val crop = Bitmap.createBitmap(bitmap, left, top, w, h)
        return try {
            val ocrBitmap = if (maskRound) maskCircle(crop) else crop
            val pixels = IntArray(ocrBitmap.width * ocrBitmap.height)
            ocrBitmap.getPixels(pixels, 0, ocrBitmap.width, 0, 0, ocrBitmap.width, ocrBitmap.height)
            bubbleOcr.getText(OcrImage(ocrBitmap.width, ocrBitmap.height, pixels))
        } finally {
            if (!crop.isRecycled) crop.recycle()
        }
    }

    /** Нормализация области кадрирования в доли стороны кадра. */
    private fun normalizeBox(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        bitmap: Bitmap,
    ) = OcrBoundingBox(
        left = left.toFloat() / bitmap.width,
        top = top.toFloat() / bitmap.height,
        right = right.toFloat() / bitmap.width,
        bottom = bottom.toFloat() / bitmap.height,
    )

    /** Круглая маска: углы квадратного облачка убираются (пустые), текст
     *  соседнего облачка в углах кадрирования не попадает в распознавание. */
    private fun maskCircle(src: Bitmap): Bitmap {
        val masked = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(masked)
        canvas.drawBitmap(src, 0f, 0f, null)
        val erase = android.graphics.Paint().apply {
            isAntiAlias = true
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        }
        canvas.drawOval(
            android.graphics.RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()),
            erase,
        )
        // Полностью прозрачные углы заливаем белым: для OCR чёрный/прозрачный
        // фон в углах вреднее белого.
        val px = IntArray(masked.width * masked.height)
        masked.getPixels(px, 0, masked.width, 0, 0, masked.width, masked.height)
        var changed = false
        for (i in px.indices) {
            if (px[i] and -0x1000000 == 0) {
                px[i] = android.graphics.Color.WHITE
                changed = true
            }
        }
        if (changed) masked.setPixels(px, 0, masked.width, 0, 0, masked.width, masked.height)
        return masked
    }

    /**
     * Полностраничный результат (один регион 0..1) делится на строки:
     * каждая непустая строка — отдельная реплика. Рамки приблизительные
     * (равномерно по высоте) — хоть какая-то подсветка вместо всей страницы.
     */
    private fun splitWholePageToLines(whole: Line): List<Line> {
        val rows = whole.text.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (rows.size <= 1) return listOf(whole)
        val h = 1f / rows.size
        return rows.mapIndexed { i, t ->
            Line(t, OcrBoundingBox(0.05f, i * h, 0.95f, (i + 1) * h))
        }
    }

    // endregion

    // region Склейка строк одной реплики

    /**
     * Склеивает соседние строки OCR, принадлежащие ОДНОЙ реплике / облачку,
     * в одну реплику.
     *
     * Локальный движок (Cyrillic PP-OCR) отдаёт регион на КАЖДУЮ строку
     * текста: реплика из двух строк распадалась на два разных «текста»,
     * а перенос слова (понеде- / льник) читался как два разных слова.
     */
    private fun mergeBubbleLines(lines: List<Line>): List<Line> {
        if (lines.size <= 1) return lines
        val sorted = lines.sortedBy { it.boundingBox.top }
        val merged = mutableListOf<Line>()
        var current: Line? = null
        for (line in sorted) {
            val cur = current
            if (cur != null && sameBubble(cur, line)) {
                current = Line(
                    text = joinBubbleText(cur.text, line.text),
                    boundingBox = union(cur.boundingBox, line.boundingBox),
                )
            } else {
                current?.let { merged += it }
                current = line
            }
        }
        current?.let { merged += it }
        return merged
    }

    /** Обе строки выровнены (одна колонка/облачко) и стоят вплотную. */
    private fun sameBubble(a: Line, b: Line): Boolean {
        val ab = a.boundingBox
        val bb = b.boundingBox
        val aW = ab.right - ab.left
        val bW = bb.right - bb.left
        if (aW <= 0f || bW <= 0f) return false
        // Перекрытие по горизонтали ≥ 50% ширины меньшей строки — один «столбец».
        val overlap = kotlin.math.min(ab.right, bb.right) - kotlin.math.max(ab.left, bb.left)
        if (overlap <= 0f) return false
        if (overlap / kotlin.math.min(aW, bW) < 0.5f) return false
        // Зазор между строками одного облачка много меньше зазора между
        // облачками (у диалогов и рамок есть пустое поле между ними).
        val aH = ab.bottom - ab.top
        val bH = bb.bottom - bb.top
        if (aH <= 0f || bH <= 0f) return false
        val gap = bb.top - ab.bottom
        if (gap < 0f || gap > kotlin.math.max(aH, bH) * 0.55f) return false
        // Вертикальные японские колонки (узкие и очень высокие) не склеиваем.
        // Порог 4x вместо 2x: у manga-диалогов облачка бывают вытянутыми по высоте.
        if (aH > aW * 4f || bH > bW * 4f) return false
        return true
    }

    /** Перенос слова в конце строки склеивается вплотную, иначе пробел. */
    private fun joinBubbleText(a: String, b: String): String {
        val aT = a.trim()
        val bT = b.trim()
        if (aT.isBlank()) return bT
        if (bT.isBlank()) return aT
        // Перенос слова: дефис / короткое тире / длинное тире в конце строки
        // → склейка без пробела (понеде- / льник → понедельник).
        return when {
            aT.endsWith("-") || aT.endsWith("\u2010") || aT.endsWith("\u2011") ->
                aT.dropLast(1) + bT
            aT.endsWith("\u2013") || aT.endsWith("\u2014") || aT.endsWith("\u2015") ->
                aT.dropLast(1) + bT
            else -> "$aT $bT"
        }
    }

    private fun union(a: OcrBoundingBox, b: OcrBoundingBox) = OcrBoundingBox(
        left = minOf(a.left, b.left),
        top = minOf(a.top, b.top),
        right = maxOf(a.right, b.right),
        bottom = maxOf(a.bottom, b.bottom),
    )

    // endregion Склейка строк одной реплики

    // region Чистка мусора OCR

    companion object {
        private const val HISTORY_LIMIT = 600
        private const val MAX_BUBBLES_PER_FRAME = 40

        /**
         * Максимальная длинная сторона кадра для OCR: минимальное разрешение,
         * при котором текст ещё читается. Детектор движка всё равно масштабирует
         * кадр до ~736px, поэтому уменьшенный заранее кадр ускоряет конвертацию
         * пикселей и онлайн-OCR без потери слов.
         */
        private const val OCR_SCAN_MAX_EDGE = 1600

        /** Качество JPEG скриншотов во вкладку «Скриншоты». */
        private const val JPEG_QUALITY = 78

        /**
         * Онлайн-движки OCR (ответ идёт по сети). Если такой движок вернул
         * очень мало строк — кадр дочитывается локальным разбором баллонов,
         * чтобы AI-пропущенная реплика (напр. крупный текст поверх ватермарки)
         * не терялась. Офлайн-движки (CYRILLIC/MLKIT) и так дают строки с боксами.
         */
        private val ONLINE_MODELS = setOf(
            OcrModel.GLENS,
            OcrModel.ZEN_FREE,
            OcrModel.GOOGLE,
            OcrModel.OPENROUTER,
            OcrModel.OWOCR,
        )

        /**
         * Порог «подозрительно мало»: при меньшем числе осмысленных строк
         * онлайн-результата запускается локальный добор баллонов.
         */
        private const val SUPPLEMENT_BUBBLES_MIN = 3

        /**
         * Формулировки выученного правила «баллоны», при которых авточтение
         * берёт облачки основным путём, а текст полной страницы — запасным.
         * Правило пишет агент (или пользователь) обычными словами, поэтому
         * список короткий и явный: что не распознали — остаётся как было.
         */
        private val BUBBLES_ONLY_MARKERS = listOf(
            "только бабл",
            "только облачк",
            "только реплик",
            "без текста страницы",
            "не читать текст страницы",
            "игнорировать текст страницы",
            "пропускать текст страницы",
        )

        /** Возвращает кадр в минимально читаемом разрешении (сам же, если он уже мал). */
        private fun downscaleForScan(src: Bitmap): Bitmap {
            if (src.isRecycled) return src
            val longEdge = maxOf(src.width, src.height)
            if (longEdge <= OCR_SCAN_MAX_EDGE) return src
            val scale = OCR_SCAN_MAX_EDGE.toFloat() / longEdge
            return Bitmap.createScaledBitmap(
                src,
                (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        }

        /** Сжимает кадр в JPEG-байты (для вкладки «Скриншоты»). */
        private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            return out.toByteArray()
        }

        /**
         * Максимальное время OCR одного кадра в авточтении. Локальный движок
         * при первом запуске/загрузке модели, а также онлайн-движок (GLENS /
         * Google) при недоступном сервисе БЕЗ этого жёсткого лимита могли
         * заморозить авточтение навсегда. По таймауту кадр считается пустым.
         */
        internal const val OCR_FRAME_TIMEOUT_MS = 25_000L

        /**
         * Сколько ждать первого `onStart`, прежде чем признать реплику
         * неозвучиваемой. Раньше ожидание шло полным [ttsTimeoutMs] (минимум 8 с)
         * на КАЖДУЮ реплику, которую движок не смог произнести: пустой текст после
         * снятия разметки, ремарка без звучания, незагруженный голос. На странице
         * из одной реплики это выглядело как «авточтение остановилось»: ждать
         * больше нечего, но цикл молчал 8+ секунд и только потом листал дальше.
         */
        internal const val TTS_START_GRACE_MS = 1_200L

        /**
         * Граница, после которой не запустившуюся реплику ждать бессмысленно.
         * Вынесена отдельно, чтобы [speakAndAwait] не ждал полный таймаут.
         */
        internal fun ttsStartedOrGiveUp(started: Boolean, elapsedMs: Long): Boolean =
            started || elapsedMs >= TTS_START_GRACE_MS

        /**
         * Сколько подряд неозвученных реплик считается «озвучки нет».
         *
         * Разовый отказ ничего не значит: ремарка без звучания, пустой текст
         * после снятия разметки, занятый другим экраном голос. Рерия из трёх —
         * уже не совпадение.
         */
        internal const val REJECTED_STOP_AFTER = 3

        /** Сообщение читателю, когда системного TTS-движка на устройстве нет. */
        internal const val NO_VOICE_MESSAGE =
            "Авточтение остановлено: на устройстве нет TTS-движка. Установите голос " +
                "(Настройки → Озвучка) или включите сетевой голос."

        /** Сообщение читателю, когда движок есть, но текст он не принимает. */
        internal const val REJECTED_VOICE_MESSAGE =
            "Авточтение остановлено: голос не озвучивает текст. Проверьте язык и данные " +
                "голоса в Настройках → Озвучка."

        fun ttsTimeoutMs(textLength: Int, speechRate: Float): Long {
            val rate = speechRate.takeIf { it.isFinite() && it > 0f }?.coerceIn(0.5f, 2f) ?: 1f
            return (textLength.coerceAtLeast(0) * 220L / rate).toLong() + 8_000L
        }

        /** Настоящие одно- и двухбуквенные русские слова (союзы/предлоги/междометия). */
        private val RUSSIAN_SINGLE_WORD = setOf("а", "и", "в", "с", "у", "о", "я", "к")
        private val RUSSIAN_SHORT_WORD = setOf(
            "но", "не", "да", "он", "мы", "вы", "ты", "ни", "от", "до", "на",
            "за", "по", "из", "ко", "то", "ли", "же", "бы", "ну", "ах", "ох",
            "ой", "эх", "уж", "ага", "так", "тот", "это", "се", "об", "при", "про",
        )

        /** Одно- или двухбуквенное настоящее русское слово. */
        fun isShortRussianWord(text: String): Boolean {
            val t = text.trim().lowercase()
            return t in RUSSIAN_SINGLE_WORD || t in RUSSIAN_SHORT_WORD
        }

        /**
         * Упорядочивает реплики кадра в читаемый порядок.
         *
         * Раньше реплики сортировались одним ключом: манга — по правому краю
         * (самая правая рамка первой), комикс — по левому, вебтун — по верху.
         * На реальной странице рамки баллонов РАЗНОЙ ширины и высоты (широкий
         * баллон сверху и узкий справа внизу), и одноключевая сортировка
         * читала узкий баллон в центре панели раньше верхнего широкого — «верха
         * пропускает». [ReadingOrderSorter] делит кадр рекурсивным разрезанием:
         *  • горизонтальный разрез — сверху вниз (первично);
         *  • вертикальный разрез — справа налево (манга) / слева направо;
         *  • когда чистого разреза нет (перекрытия вебтуна) — позиционный
         *    фолбэк, где первичен верх, а не правый край.
         * Так верхние реплики всегда читаются раньше нижних в любом вложении.
         */
        fun orderRegions(lines: List<Line>, order: String): List<Line> {
            if (lines.size <= 1) return lines
            val direction = when (order) {
                "ltr" -> tachiyomi.core.common.util.system.ReadingDirection.LTR
                "vertical" -> tachiyomi.core.common.util.system.ReadingDirection.VERTICAL
                else -> tachiyomi.core.common.util.system.ReadingDirection.RTL
            }
            // Рамки хранятся в долях 0..1, а сортировщик работает с целыми
            // Rect: масштабируем на 100000, геометрия и зазоры сохраняются.
            val scale = 100_000
            fun rectOf(line: Line) = line.boundingBox.let { b ->
                // Поля Rect заполняем через свойства, а не через конструктор
                // (left, top, right, bottom): mockable-android jar в юнит-тестах
                // не пишет значения из этого конструктора, и сортировщик видел
                // бы вырожденные рамки (0,0,0,0).
                android.graphics.Rect().apply {
                    left = (b.left * scale).toInt()
                    top = (b.top * scale).toInt()
                    right = (b.right * scale).toInt()
                    bottom = (b.bottom * scale).toInt()
                }
            }

            // Манга и комикс читаются по горизонтальным полосам (строкам реплик),
            // а направление работает ВНУТРИ полосы. Без этого сортировщик при
            // отсутствии чистого разреза выходил на фолбэк «сначала top», и бабл,
            // стоящий на пару процентов выше слева, читался раньше правого —
            // манга уходила в чтение слева направо.
            if (order != "vertical") {
                val bands = groupIntoBands(lines)
                if (bands.size > 1) {
                    return bands.flatMap { band ->
                        if (band.size == 1) {
                            band
                        } else {
                            tachiyomi.core.common.util.system.ReadingOrderSorter
                                .sort(band.map(::rectOf), direction)
                                .map { band[it] }
                        }
                    }
                }
            }

            val sortedIndices =
                tachiyomi.core.common.util.system.ReadingOrderSorter.sort(lines.map(::rectOf), direction)
            return sortedIndices.map { lines[it] }
        }

        /**
         * Раскладывает реплики по горизонтальным полосам: реплика попадает в
         * текущую полосу, пока не опустилась ниже её низа (с допуском
         * [BAND_TOLERANCE] на неточность координат OCR).
         */
        private fun groupIntoBands(lines: List<Line>): List<List<Line>> {
            val bands = mutableListOf<MutableList<Line>>()
            var bandBottom = 0f
            for (line in lines.sortedBy { it.boundingBox.top }) {
                val box = line.boundingBox
                val current = bands.lastOrNull()
                if (current == null || box.top >= bandBottom - BAND_TOLERANCE) {
                    bands += mutableListOf(line)
                    bandBottom = box.bottom
                } else {
                    current += line
                    bandBottom = maxOf(bandBottom, box.bottom)
                }
            }
            return bands
        }

        private const val BAND_TOLERANCE = 0.02f

        /**
         * Чистка OCR-мусора ВНУТРИ реплики (по скриншотам пользователя:
         * «АХЕ возьмешь — МЕНЯ НА РУЧКИ? eS la 4…», «Я | | > | КАК ПРИНЦЕСС…»,
         * «РУ у 4а (WX i ДЖЕЙН…»). Правила:
         *  • строки из символов-палок/скобок/стрелок выбрасываются целиком;
         *  • строки, где смесь латиницы+цифр не похожа на слова (eS la 4,
         *    WX i, 4a) — выбрасываются при целевом языке ru;
         *  • одиночные буквы-обрывки («о», «Я» без продолжения в 1 строку из
         *    многих) — выбрасываются;
         *  • остальные строки склеиваются пробелом.
         */
        fun cleanOcrGarbage(text: String, language: String): String {
            val rawRows = text.lines()
                .map { OcrTextCleaner.stripPromotionalText(it).trim() }
                .filter { it.isNotBlank() }
            // Короткие целые слова («шум», «гам») OCR-чистка больше не кромсает:
            // одиночное слово из букв проходит без правок и фильтров.
            val singleWord = rawRows.singleOrNull()
            if (singleWord != null && singleWord.length in 3..14 && singleWord.all { it.isLetter() }) {
                return singleWord
            }
            // OCR часто путает буквы с цифрами («4» вместо «а», «1» вместо «л»).
            // Убираем только ЦИФРЫ, слипшиеся с буквой (4а, а4, eS la 4) — это
            // типичная ошибка OCR. Самостоятельные числа («1», «глава 1», «5»)
            // НЕ трогаем: пользователь просил, чтобы цифры озвучивались
            // («он цифры не говорит почему-то»). Чисто числовые строки-мусор
            // («2/89», «7») отсекаются ниже по isMeaningfulRow (нет букв).
            val rows = rawRows.map { row ->
                row.replace(Regex("(?<=[\\p{L}])[0-9]+|[0-9]+(?=[\\p{L}])"), " ")
                    .replace(Regex("\\s+"), " ").trim()
            }.filter { it.isNotBlank() }
            if (rows.isEmpty()) return ""
            val kept = rows.filter { row -> isMeaningfulRow(row, language) }
            // Если ВСЁ забраковано, но исходник был длинный — вернём самую
            // «словесную» строку, чтобы не терять настоящие реплики.
            if (kept.isEmpty()) {
                val best = rows.maxByOrNull { r -> r.count { it.isLetter() } }
                return if (best != null && best.count { it.isLetter() } >= 4) best else ""
            }
            // Склейка строк с учётом переноса слова в конце строки:
            // «понеде-» + «льник» → «понедельник», иначе обычный пробел.
            val joined = StringBuilder()
            for (row in kept) {
                if (joined.isNotEmpty()) {
                    if (joined.lastOrNull() == '-') {
                        joined.deleteCharAt(joined.length - 1)
                    } else {
                        joined.append(' ')
                    }
                }
                joined.append(row)
            }
            var result = joined.toString().replace(Regex("\\s+"), " ").trim()
            // Пост-обработка: убираем «застрявшие» повторы символов, которые OCR
            // генерирует на шумных кадрах: "!!!!" → "!", "????" → "?", "......" → "…"
            result = result.replace(Regex("(.)\\1{2,}")) { m ->
                val ch = m.groupValues[1]
                when (ch) {
                    "!", "?", ".", "…", "~" -> ch
                    else -> m.value
                }
            }
            return result
        }

        /** Похожа ли строка на осмысленный текст (не обрывок/не мусор). */
        private fun isMeaningfulRow(row: String, language: String): Boolean {
            val letters = row.count { it.isLetter() }
            val total = row.length
            // Палки, скобки, стрелки, точки: буквы < 40% строки — мусор
            if (letters == 0) return false
            if (letters.toFloat() / total < 0.4f && total >= 3) return false
            // Одна-две буквы — настоящие русские слова (я, и, в, с, но, не…)
            if (letters <= 2) {
                if (language != "ru") return false
                val compact = row.filter(Char::isLetter).lowercase()
                if (isShortRussianWord(compact)) return true
                return compact.length == 2 &&
                    compact.all { it in '\u0400'..'\u04FF' } &&
                    Regex("^[\\p{L}][\\s—–-]+[\\p{L}][.!?…]*$").matches(row)
            }
            when (language) {
                "ru" -> {
                    val cyr = row.count { it in '\u0400'..'\u04FF' }
                    // Латиница с цифрами (eS la 4, WX i) при русском языке — мусор
                    if (cyr == 0) return false
                    if (cyr.toFloat() / letters < 0.6f) return false
                    // Должно быть хотя бы одно «слово» из 3+ кириллических букв
                    return Regex("[\\u0400-\\u04FF]{3,}").containsMatchIn(row)
                }
                "en" -> {
                    val lat = row.count { it in 'a'..'z' || it in 'A'..'Z' }
                    if (lat.toFloat() / letters < 0.6f) return false
                    return Regex("[A-Za-z]{3,}").containsMatchIn(row)
                }
                else -> return true
            }
        }

        /** Финальная проверка собранной реплики перед чтением. */
        fun isMeaningful(text: String, language: String): Boolean {
            if (text.isBlank()) return false
            if (OcrTextCleaner.isPromotionalText(text)) return false
            if (!matchesLanguage(text, language)) return false
            // Служебная навигация сайта: «— том 1 глава 1 →», «том 1 глава 1»,
            // «← том 3 глава 5 →». Это шапка/футер манга-ридера, а не реплика;
            // раньше авточтение постоянно зачитывало эти подписи и стрелки.
            if (isSiteChromeNoise(text)) return false
            val words = text.split(Regex("\\s+"))
            return words.any { w -> w.count { it.isLetter() } >= 3 } ||
                // Короткие настоящие русские слова (я, и, но, не…) — читаем.
                (language == "ru" && words.any { isShortRussianWord(it.filter(Char::isLetter)) }) ||
                // …или короткая осмысленная («Да!», «Ах!», «Нет?»)
                (text.length in 2..6 && text.count { it.isLetter() } >= 2)
        }

        /**
         * Служебная навигация манга-ридера, которую авточтение не должно
         * зачитывать: подписи «том N глава N», счётчик страниц «N/M» и
         * строки, состоящие только из стрелок/декора (нет букв).
         * Возвращает true, если текст — это навигация, а не реплика.
         */
        fun isSiteChromeNoise(text: String): Boolean {
            val t = text.trim()
            if (t.isBlank()) return false
            val letters = t.count { it.isLetter() }
            if (letters > 0) {
                // «— том 1 глава 1 →», «том 1, глава 2», «← том 3 глава 5 →».
                // Отсекаем только если после удаления подписи остаётся мало
                // настоящих букв (это навигация, а не реплика с упоминанием тома).
                val chapter = Regex("том(а|у|е)?[\\s\\d.,-]*глава[\\s\\d.,-]*", RegexOption.IGNORE_CASE)
                if (chapter.containsMatchIn(t)) {
                    val left = chapter.replace(t, "")
                        .replace(Regex("[→←⇐⇒↔—–-|/\\\\\\d\\s,.\\(\\)«»\\[\\]\\\"']"), "")
                    if (left.count { it.isLetter() } <= 3) return true
                }
                // Оглавление ридера «Том 1», «Т. 12», «Том 2, глава 3» без реплики.
                if (Regex("^том(а|у|е)?\\.?\\s*\\d+", RegexOption.IGNORE_CASE).containsMatchIn(t) &&
                    t.count { it.isDigit() } >= 1 && letters <= 6) return true
                // Счётчик страниц «N / M», «2/89», «стр. 2 из 89».
                if (Regex("^\\s*[\\d.,\\s]+\\s*(/|из|оф)\\s*[\\d.,\\s]+\\s*$", RegexOption.IGNORE_CASE)
                        .containsMatchIn(t)) {
                    return true
                }
            }
            // Строка без букв (стрелки, точки, палки, цифры) — не реплика.
            if (letters == 0) {
                return t.any { it in "→←⇐⇒↔<|/\\·•⭐❤⟩⟨" } || t.count { it.isDigit() } >= 2
            }
            return false
        }

        /**
         * Определение языка текста по алфавиту. Реплика проходит фильтр,
         * если ≥60% её букв принадлежат целевому алфавиту.
         */
        fun matchesLanguage(text: String, language: String): Boolean {
            if (language == "any") return true
            val letters = text.filter { it.isLetter() }
            if (letters.isEmpty()) return false
            val matching = letters.count { ch ->
                when (language) {
                    "ru" -> ch in '\u0400'..'\u04FF'
                    "en" -> ch in 'a'..'z' || ch in 'A'..'Z'
                    "ja" -> ch in '\u3040'..'\u30FF' || ch in '\u4E00'..'\u9FFF' || ch in '\u31F0'..'\u31FF'
                    "ko" -> ch in '\uAC00'..'\uD7AF' || ch in '\u1100'..'\u11FF'
                    "zh" -> ch in '\u4E00'..'\u9FFF'
                    else -> true
                }
            }
            return matching.toFloat() / letters.length >= 0.6f
        }
    }
}
