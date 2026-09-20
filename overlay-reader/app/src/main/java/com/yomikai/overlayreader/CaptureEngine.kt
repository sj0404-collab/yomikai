package com.yomikai.overlayreader

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
 * VirtualDisplay создаётся один раз и держится до остановки — это
 * единственный надёжный путь на Android 14+, где пересоздание дисплеев
 * одноразовыми проекциями запрещено.
 */
class CaptureEngine(
    context: Context,
    private val projection: android.media.projection.MediaProjection,
) {
    private val appContext = context.applicationContext

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var sizeW: Int = 0
    private var sizeH: Int = 0
    private var density: Int = 0

    /** Размер захватываемого экрана в px (актуальны после первого capture). */
    var screenWidth: Int = 0
        private set
    var screenHeight: Int = 0
        private set

    /** Захватить текущий кадр и вернуть битмап области [rect] (в экранных px). */
    fun capture(rect: Rect): Bitmap? {
        if (display == null && !ensureStarted()) return null

        var image = reader?.acquireLatestImage()
        if (image == null) {
            // Ждём ближайший кадр: статичный экран может не отрисовываться.
            Thread.sleep(120)
            image = reader?.acquireLatestImage()
        }
        val img = image ?: return null
        return try {
            val full = copyImage(img) ?: return null
            try {
                val left = rect.left.coerceIn(0, full.width - 1)
                val top = rect.top.coerceIn(0, full.height - 1)
                val right = rect.right.coerceIn(left + 1, full.width)
                val bottom = rect.bottom.coerceIn(top + 1, full.height)
                Bitmap.createBitmap(full, left, top, right - left, bottom - top)
            } finally {
                if (!full.isRecycled) full.recycle()
            }
        } finally {
            img.close()
        }
    }

    private fun ensureStarted(): Boolean {
        if (reader != null) return true

        val metrics = DisplayMetrics()
        appContext.getSystemService(Context.WINDOW_SERVICE)
            .let { (it as WindowManager) }
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
        reader = ImageReader.newInstance(sizeW, sizeH, PixelFormat.RGBA_8888, 2)
        display = try {
            projection.createVirtualDisplay(
                "overlay_reader_capture",
                sizeW,
                sizeH,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface,
                null,
                handler,
            )
        } catch (e: Exception) {
            Log.e("OverlayCapture", "createVirtualDisplay failed", e)
            null
        }
        return display != null
    }

    private fun copyImage(image: android.media.Image): Bitmap? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        if (rowPadding < 0) return null

        val aux = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888,
        )
        aux.copyPixelsFromBuffer(buffer)
        val bmp = Bitmap.createBitmap(aux, 0, 0, image.width, image.height)
        if (!aux.isRecycled) aux.recycle()
        return bmp
    }

    fun stop() {
        runCatching { display?.release() }
        runCatching { reader?.close() }
        display = null
        reader = null
        handler?.looper?.quitSafely()
        thread?.join(500)
        thread = null
        runCatching { projection.stop() }
    }
}