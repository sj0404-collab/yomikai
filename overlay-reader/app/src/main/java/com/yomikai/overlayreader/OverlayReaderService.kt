package com.yomikai.overlayreader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.yomikai.overlayreader.ocr.OcrManager
import com.yomikai.overlayreader.ocr.OcrResult
import com.yomikai.overlayreader.tts.ReaderTts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Фоновый сервис читалки: полноэкранный оверлей с рамкой выбора, захват экрана
 * через MediaProjection, распознавание (онлайн/локально) и озвучка.
 *
 * Дополнительно — маленький «пузырь» управления (кнопка-точка): он всегда
 * доступен и переключает пропуск касаний (читалка скрыта / читалка видна).
 */
class OverlayReaderService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var wm: WindowManager
    private var overlay: OverlayView? = null
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var projection: MediaProjection? = null
    private var capture: CaptureEngine? = null
    private val ocrManager = OcrManager()
    private var tts: ReaderTts? = null

    private val screenMetrics = android.util.DisplayMetrics()

    // ----------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.getRealMetrics(screenMetrics)
        Prefs.init(this)
        createNotification(
            "Overlay Reader активен",
            "Читалка работает поверх экрана",
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopEverything()
            else -> startReader(intent)
        }
        return START_NOT_STICKY
    }

    private fun startReader(intent: Intent?) {
        if (projection != null) return
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA) ?: return
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)

        val proj = mediaProjectionManager.getMediaProjection(resultCode, data) ?: return
        projection = proj
        proj.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stopEverything()
                }
            },
            null,
        )

        capture = CaptureEngine(this, proj)
        tts = ReaderTts(this).also { it.init(null) }

        val overlayView = OverlayView(this, overlayCallback)
        addOverlayWindow(overlayView)
        addBubbleWindow()
        overlay = overlayView
    }

    private val overlayCallback = object : OverlayView.Callback {
        override fun onScan() = scan()
        override fun onSpeak() {
            val text = overlay?.resultText.orEmpty()
            if (text.isNotBlank()) tts?.speak(currentRegions())
        }

        override fun onCycleVoice() {
            val count = tts?.availableVoiceCount() ?: 0
            val index = tts?.cycleVoice() ?: 0
            overlay?.statusText = "Голос ${index + 1}/$count"
            overlay?.invalidate()
        }

        override fun onOpacity(delta: Float) {
            val next = (Prefs.overlayOpacity() + delta)
            Prefs.setOverlayOpacity(next.coerceIn(0.12f, 0.85f))
            overlay?.invalidate()
        }

        override fun onPassThrough() {
            overlay?.hidden = true
            overlay?.invalidate()
        }

        override fun onResetFrame() {
            overlay?.resetFrame()
        }

        override fun onExit() = stopEverything()

        override fun onFrameChanged(f: RectFBean) {
            Prefs.saveFrame(f)
        }

        override fun onTapWhileHidden() {
            overlay?.hidden = false
            overlay?.invalidate()
        }
    }

    // ----------------------------------------------------------- scanning

    private var lastRegions: List<com.yomikai.overlayreader.ocr.OcrRegion> = emptyList()

    private fun currentRegions(): List<com.yomikai.overlayreader.ocr.OcrRegion> = lastRegions

    private fun scan() {
        val overlayView = overlay ?: return
        val capturer = capture ?: run {
            overlayView.statusText = "Захват недоступен"
            overlayView.invalidate()
            return
        }
        val screenW = capturer.screenWidth
        val screenH = capturer.screenHeight
        if (screenW <= 0 || screenH <= 0) {
            overlayView.statusText = "Ошибка захвата экрана"
            overlayView.invalidate()
            return
        }

        scope.launch {
            overlayView.scanning = true
            overlayView.statusText = "Распознаю текст…"
            overlayView.invalidate()

            val frameBean = Prefs.frame()
            val rect = Rect(
                (frameBean.left * screenW).toInt(),
                (frameBean.top * screenH).toInt(),
                (frameBean.right * screenW).toInt(),
                (frameBean.bottom * screenH).toInt(),
            )
            val bitmap: Bitmap? = withContext(Dispatchers.Default) { capturer.capture(rect) }
            if (bitmap == null) {
                overlayView.scanning = false
                overlayView.statusText = "Не удалось получить кадр"
                overlayView.invalidate()
                return@launch
            }

            val result: OcrResult = try {
                ocrManager.recognize(bitmap, Prefs.ocrMode() ?: "auto")
            } catch (e: Exception) {
                Log.e(TAG, "OCR failed", e)
                OcrResult.empty()
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }

            overlayView.scanning = false
            lastRegions = result.regions
            if (result.text.isBlank()) {
                overlayView.resultText = ""
                overlayView.statusText =
                    if (Prefs.ocrMode() == "auto") "Текст не распознан (проверьте сеть)"
                    else "Текст не распознан"
            } else {
                overlayView.resultText = result.text
                overlayView.statusText = "${result.regions.size} реплик(и) · движок ${engineLabel()}"
            }
            overlayView.invalidate()

            if (Prefs.autoTts() && result.regions.isNotEmpty()) {
                tts?.speak(result.regions)
            }
        }
    }

    private fun engineLabel(): String = when (Prefs.ocrMode()) {
        "local" -> "локальный"
        "online" -> "Google Lens"
        else -> "авто"
    }

    // ----------------------------------------------------------- windows

    private fun addOverlayWindow(view: View) {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        overlayParams = lp
        wm.addView(view, lp)
    }

    private fun addBubbleWindow() {
        val view = object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E91E63") }
            private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = px(20f)
            }

            override fun onDraw(canvas: Canvas) {
                val c = width / 2f
                canvas.drawCircle(c, c, c, paint)
                canvas.drawText("Ч", c, c + px(7f), textPaint)
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    toggleOverlayVisibility()
                    return true
                }
                return super.onTouchEvent(event)
            }
        }
        val size = px(44f).toInt()
        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = px(8f).toInt()
            y = computeBubbleY()
        }
        bubbleParams = lp
        bubbleView = view
        wm.addView(view, lp)
    }

    private fun toggleOverlayVisibility() {
        val current = overlay ?: return
        current.hidden = !current.hidden
        current.invalidate()
    }

    private fun computeBubbleY(): Int {
        val toolbarH = px(44f)
        return toolbarH.toInt() + px(12f).toInt()
    }

    private fun px(v: Float): Float = v * resources.displayMetrics.density

    // ----------------------------------------------------------- teardown

    private fun stopEverything() {
        Log.i(TAG, "stopping overlay reader")
        try {
            bubbleView?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }
        try {
            overlay?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }
        bubbleView = null
        overlay = null
        tts?.shutdown()
        tts = null
        capture?.stop()
        capture = null
        projection = null
        stopSelf()
    }

    override fun onDestroy() {
        stopEverything()
        ocrManager.close()
        scope.cancel()
        super.onDestroy()
    }

    // ----------------------------------------------------------- notification

    private fun createNotification(title: String, text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Overlay Reader", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pending)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pending)
                .setOngoing(true)
                .build()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "OverlayReader"
        const val ACTION_START = "com.yomikai.overlayreader.START"
        const val ACTION_STOP = "com.yomikai.overlayreader.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        private const val CHANNEL_ID = "overlay_reader_channel"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, OverlayReaderService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            Intent(context, OverlayReaderService::class.java).apply {
                action = ACTION_STOP
                context.startService(this)
            }
        }
    }
}