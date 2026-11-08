package eu.kanade.tachiyomi.ui.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

/**
 * Захват экрана через MediaProjection и вырезание рамки.
 *
 * Перенесено из отдельного приложения overlay-reader в основной Yomikai:
 * режим «рамка поверх окна» должен работать без установки второй программы.
 *
 * VirtualDisplay создаётся один раз и держится до остановки — это
 * единственный надёжный путь на Android 14+, где пересоздание дисплеев
 * одноразовыми проекциями запрещено.
 *
 * ── Что исправлено после жалоб «теряются кадры» и «перезапускается» ─────────
 *
 * 1. **Память.** Раньше на КАЖДЫЙ кадр аллоцировалось два полноэкранных
 *    битмапа (1080×2400 ≈ 10 МБ каждый): буфер строки + копия экрана, и только
 *    потом вырезалась область. На слабом телефоне это давало паузы сборщика
 *    мусора — то самое «кадры теряются», — а иногда OutOfMemory. Теперь
 *    полноэкранный битман ОДИН и переиспользуется между кадрами, а битмап
 *    области создаётся сразу нужного размера.
 * 2. **Разрешение для распознавания.** Область перед OCR уменьшается до
 *    [MAX_OCR_SIDE] по длинной стороне (модель всё равно работает с меньшим
 *    входом): меньше пикселей — быстрее распознавание, меньше нагрузка.
 * 3. **Ожидание кадра.** `acquireLatestImage()` бросает IllegalStateException,
 *    если предыдущие изображения не закрыты, и возвращает null, когда экран
 *    статичен. Оба случая раньше могли зациклить захват на 3 секунды —
 *    теперь ожидание короткое, с честным закрытием и лимитом попыток.
 * 4. **Мёртвая проекция.** Если система остановила MediaProjection (экран
 *    погас, пользователь нажал «Остановить» в шторке), движок больше не
 *    пытается оживить дисплей: он честно отвечает null, а сервис показывает
 *    «нужно заново разрешить захват» вместо бесконечных попыток.
 */
class OverlayCaptureEngine(
    context: Context,
    private val projection: android.media.projection.MediaProjection,
) {
    private companion object {
        const val TAG = "YomikaiOverlayCapture"

        /** Дольше этого ждать свежий кадр нет смысла: экран статичен. */
        const val FRAME_WAIT_MS = 700L

        /** Пауза между попытками получить кадр, мс. */
        const val FRAME_POLL_MS = 40L

        /** Длинная сторона картинки, которая уходит в распознавание. */
        const val MAX_OCR_SIDE = 1600

        /** Не снимать чаще, чем раз в столько миллисекунд: кадры не теряем сами. */
        const val MIN_INTERVAL_MS = 120L
    }

    private val appContext = context.applicationContext

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var sizeW: Int = 0
    private var sizeH: Int = 0
    private var density: Int = 0

    /** Полноэкранный буфер, который переиспользуется между кадрами. */
    private var scratch: Bitmap? = null

    /** Проекция умерла (система остановила захват) — оживлять бессмысленно. */
    @Volatile
    private var projectionDead = false

    private var lastCaptureAt = 0L

    /** Размер захватываемого экрана в px (актуальны после первого capture). */
    var screenWidth: Int = 0
        private set
    var screenHeight: Int = 0
        private set

    /** Жива ли ещё проекция: по этому флагу сервис решает, просить ли захват заново. */
    val isAlive: Boolean get() = !projectionDead

    /**
     * Захватить текущий кадр и вернуть битмап области [rect] (в экранных px).
     *
     * Возвращённый битмап принадлежит вызывающему — его нужно освободить.
     * null означает «кадр не получен»: статичный экран, мёртвая проекция,
     * нехватка памяти.
     */
    fun capture(rect: Rect): Bitmap? {
        if (projectionDead) return null
        // Не частим: если цикл попросил кадр раньше, чем экран успел
        // перерисоваться, отдаём тот же путь, но с короткой паузой — так
        // захват не отбирает кадры у самого приложения.
        val since = System.currentTimeMillis() - lastCaptureAt
        if (since in 1 until MIN_INTERVAL_MS) sleepQuietly(MIN_INTERVAL_MS - since)
        lastCaptureAt = System.currentTimeMillis()

        if (display == null && !ensureStarted()) return null

        val img = awaitImage() ?: return null
        return try {
            cropRegion(img, rect)
        } catch (t: Throwable) {
            // Битый кадр или нехватка памяти: один неудачный кадр не должен
            // ронять цикл чтения и сервис.
            Log.w(TAG, "crop failed", t)
            null
        } finally {
            runCatching { img.close() }
        }
    }

    /**
     * Ждёт кадр не дольше [FRAME_WAIT_MS].
     *
     * Старые кадры из очереди выбрасываются: после паузы в очереди ImageReader
     * может лежать «прошлый экран», и распознавать его — значит читать текст,
     * которого уже нет.
     */
    private fun awaitImage(): android.media.Image? {
        val deadline = System.currentTimeMillis() + FRAME_WAIT_MS
        var latest: android.media.Image? = null
        while (System.currentTimeMillis() < deadline) {
            val next = runCatching { reader?.acquireLatestImage() }.getOrNull()
            if (next == null) {
                if (latest != null) break
                sleepQuietly(FRAME_POLL_MS)
                continue
            }
            // Пришёл более свежий — предыдущий закрываем, чтобы не упереться
            // в maxImages (иначе acquireLatestImage начнёт бросать исключения).
            latest?.let { runCatching { it.close() } }
            latest = next
            // Один-два свежих кадра достаточно: ждём максимум ещё круг.
            break
        }
        return latest
    }

    private fun ensureStarted(): Boolean {
        if (display != null && reader != null) return true
        if (projectionDead) return false
        // Предыдущая попытка могла упасть уже после создания ImageReader:
        // тогда reader остался не-null, а display — null, и ensureStarted()
        // больше никогда не доходил до createVirtualDisplay (захват умирал
        // навсегда). Перед повтором разбираем half-initialized состояние.
        releaseCaptureResources()

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay
            .getRealMetrics(metrics)
        sizeW = metrics.widthPixels
        sizeH = metrics.heightPixels
        density = metrics.densityDpi
        screenWidth = sizeW
        screenHeight = sizeH
        if (sizeW <= 0 || sizeH <= 0) return false

        thread = HandlerThread("overlay_capture").also { it.start() }
        handler = Handler(thread!!.looper)
        reader = runCatching {
            // Три слота вместо двух: пока один кадр копируется, следующие не
            // заставляют производителя ждать — меньше «дропов» на экране.
            ImageReader.newInstance(sizeW, sizeH, PixelFormat.RGBA_8888, 3)
        }.onFailure { Log.e(TAG, "ImageReader failed", it) }.getOrNull()
        if (reader == null) {
            releaseCaptureResources()
            return false
        }
        display = try {
            projection.createVirtualDisplay(
                "yomikai_overlay_capture",
                sizeW,
                sizeH,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface,
                null,
                handler,
            )
        } catch (t: Throwable) {
            // SecurityException (проекция уже остановлена) и IllegalStateException
            // (дисплей не создан) раньше валили сервис — теперь просто «нет кадра».
            Log.e(TAG, "createVirtualDisplay failed", t)
            null
        }
        if (display == null) {
            // Чистим за собой, чтобы следующий capture() честно попробовал снова.
            releaseCaptureResources()
        }
        return display != null
    }

    /**
     * Гасит только собственные ресурсы захвата. MediaProjection не трогаем:
     * она принадлежит сервису, и её остановка погасила бы всю сессию.
     */
    private fun releaseCaptureResources() {
        runCatching { display?.release() }
        runCatching { reader?.close() }
        display = null
        reader = null
        runCatching { handler?.removeCallbacksAndMessages(null) }
        runCatching { thread?.quitSafely() }
        runCatching { thread?.join(500) }
        thread = null
        handler = null
        // Полноэкранный буфер тоже больше не нужен: при следующем старте
        // размер экрана может быть другим (поворот).
        runCatching { scratch?.takeIf { !it.isRecycled }?.recycle() }
        scratch = null
    }

    /**
     * Вырезает область из кадра без полноэкранных копий.
     *
     * Строки изображения лежат с шагом [rowStride], поэтому полноэкранный
     * битман собирается в один переиспользуемый буфер, а область вырезается
     * уже из него — на кадр остаётся одна аллокация размером с рамку.
     */
    private fun cropRegion(image: android.media.Image, rect: Rect): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0) return null
        val rowPadding = rowStride - pixelStride * image.width
        if (rowPadding < 0) return null
        val paddedWidth = image.width + rowPadding / pixelStride

        var aux = scratch
        if (aux == null || aux.isRecycled || aux.width != paddedWidth || aux.height != image.height) {
            if (aux != null && !aux.isRecycled) aux.recycle()
            aux = runCatching {
                Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            }.getOrNull() ?: return null
            scratch = aux
        }
        runCatching { aux.copyPixelsFromBuffer(buffer) }.onFailure {
            Log.w(TAG, "copyPixelsFromBuffer failed", it)
            return null
        }

        val left = rect.left.coerceIn(0, image.width - 1)
        val top = rect.top.coerceIn(0, image.height - 1)
        val right = rect.right.coerceIn(left + 1, image.width)
        val bottom = rect.bottom.coerceIn(top + 1, image.height)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return null

        val cropped = runCatching {
            Bitmap.createBitmap(aux, left, top, w, h)
        }.getOrNull() ?: return null

        // Для распознавания крупный кадр не нужен: модель всё равно ужимает
        // вход, а лишние пиксели — это лишние секунды и лишняя память.
        val longSide = maxOf(cropped.width, cropped.height)
        if (longSide <= MAX_OCR_SIDE) return cropped
        val k = MAX_OCR_SIDE.toFloat() / longSide
        val scaled = runCatching {
            Bitmap.createScaledBitmap(
                cropped,
                (cropped.width * k).toInt().coerceAtLeast(1),
                (cropped.height * k).toInt().coerceAtLeast(1),
                true,
            )
        }.getOrNull()
        if (scaled != null && scaled !== cropped) {
            if (!cropped.isRecycled) cropped.recycle()
            return scaled
        }
        return cropped
    }

    fun stop() {
        projectionDead = true
        releaseCaptureResources()
        runCatching { projection.stop() }
    }

    /**
     * Проекция остановлена системой (или пользователем в шторке).
     *
     * Помечаем её мёртвой, чтобы цикл чтения не ждал кадров и не жёг батарею.
     */
    fun markProjectionDead() {
        projectionDead = true
        releaseCaptureResources()
    }

    private fun sleepQuietly(ms: Long) {
        runCatching { Thread.sleep(ms.coerceIn(1L, 1_000L)) }
    }
}
