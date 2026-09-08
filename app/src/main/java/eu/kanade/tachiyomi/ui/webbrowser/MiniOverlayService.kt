package eu.kanade.tachiyomi.ui.webbrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/**
 * Мини-плеер ПОВЕРХ ВСЕХ приложений (v1.9.55).
 *
 * В отличие от встроенного MiniWebOverlay (живёт только внутри yomikai), этот
 * сервис поднимает настоящее плавающее окно через [WindowManager] с типом
 * `TYPE_APPLICATION_OVERLAY`, поэтому оно видно над любым приложением и
 * пользователь может смотреть видео, листая что угодно в других приложениях.
 *
 * Требует разрешения «Показ поверх других приложений» (SYSTEM_ALERT_WINDOW):
 * [MiniOverlayService.requestPermission] открывает системные настройки, а
 * [MiniOverlayService.canDrawOverlays] проверяет выдано ли оно.
 *
 * ВАЖНО про видео: WebView рисует <video> через аппаратный оверлей. Поэтому
 * здесь НЕ накладывается прозрачность на сам WebView (иначе видео будет
 * останавливаться). Слайдер прозрачности меняет только фон панели управления,
 * а содержимое страницы остаётся непрозрачным и стабильным.
 *
 * Примечание: это ОТДЕЛЬНЫЙ экземпляр WebView (Android не даёт перенести
 * WebView из окна приложения в окно поверх других приложений). Он открывает
 * текущий адрес веб-вкладки — тот же контент, но независимая сессия.
 */
class MiniOverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "mini_overlay"
        private const val NOTIF_ID = 0x4D4E
        private const val EXTRA_URL = "url"
        private const val BASE_W_DP = 220f
        private const val BASE_H_DP = 340f

        /** Разрешено ли рисовать поверх других приложений. */
        fun canDrawOverlays(context: Context): Boolean =
            Settings.canDrawOverlays(context)

        /** Открыть системные настройки «Показ поверх других приложений». */
        fun requestPermission(context: Context) {
            runCatching {
                val pkg = "package:${context.packageName}"
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse(pkg),
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }.onFailure {
                runCatching {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            }
        }

        /** Запустить плавающий мини-плеер поверх всех приложений. */
        fun start(context: Context, url: String) {
            val intent = Intent(context, MiniOverlayService::class.java)
                .putExtra(EXTRA_URL, url.takeIf { it.isNotBlank() } ?: "https://mangabuff.ru")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(intent)
            }
        }

        /** Остановить плавающий мини-плеер. */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, MiniOverlayService::class.java)) }
        }
    }

    private lateinit var wm: WindowManager
    private var root: View? = null
    private var webView: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private var currentUrl: String = "https://mangabuff.ru"

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentUrl = intent?.getStringExtra(EXTRA_URL) ?: currentUrl
        if (!canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureForeground()
        if (root == null) buildOverlay()
        return START_STICKY
    }

    private fun ensureForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Мини-плеер", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = if (launch != null) {
            PendingIntent.getActivity(
                this,
                0,
                launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        } else {
            null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val b = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Мини-плеер")
                .setContentText("Веб-вкладка поверх всех приложений")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
            if (contentIntent != null) b.setContentIntent(contentIntent)
            startForeground(NOTIF_ID, b.build())
        } else {
            @Suppress("DEPRECATION")
            val b = Notification.Builder(this)
                .setContentTitle("Мини-плеер")
                .setContentText("Веб-вкладка поверх всех приложений")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
            if (contentIntent != null) b.setContentIntent(contentIntent)
            startForeground(NOTIF_ID, b.build())
        }
    }

    private fun buildOverlay() {
        val density = resources.displayMetrics.density
        fun dp(v: Float): Int = (v * density).toInt().coerceAtLeast(1)

        var scale = 1f
        var chromeAlpha = 0.92f

        // Корневой слой: вертикальный — панель управления сверху, WebView ниже.
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setBackgroundColor(0xDD11101F.toInt())
        layout.setPadding(dp(6f), dp(6f), dp(6f), dp(6f))

        // Панель управления (шапка). Здесь же — драг, зум и прозрачность.
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(0x33FFFFFF)
        bar.setPadding(dp(4f), dp(0f), dp(4f), dp(0f))

        fun makeButton(text: String, onClick: () -> Unit): Button {
            val b = Button(this)
            b.text = text
            b.textSize = 12f
            b.setPadding(dp(6f), dp(0f), dp(6f), dp(0f))
            b.setOnClickListener { onClick() }
            return b
        }

        val title = TextView(this)
        title.text = "⦿ Мини-плеер"
        title.textSize = 12f
        title.setTextColor(0xFFFFFFFF.toInt())
        val titleParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        bar.addView(title, titleParams)

        val minus = makeButton("−") {
            scale = (scale - 0.2f).coerceIn(0.5f, 2.2f)
            resize(dp(BASE_W_DP * scale), dp(BASE_H_DP * scale))
        }
        bar.addView(minus)
        val plus = makeButton("+") {
            scale = (scale + 0.2f).coerceIn(0.5f, 2.2f)
            resize(dp(BASE_W_DP * scale), dp(BASE_H_DP * scale))
        }
        bar.addView(plus)

        val slider = SeekBar(this)
        slider.max = 70 // 0.30..1.00
        slider.progress = 69
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                chromeAlpha = 0.30f + progress / 100f
                val a = (0x33 + (0xCC * ((chromeAlpha - 0.30f) / 0.70f)).toInt()).coerceIn(0x33, 0xFF)
                bar.setBackgroundColor((a shl 24) or 0xFFFFFF)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        val sliderParams = LinearLayout.LayoutParams(dp(70f), LinearLayout.LayoutParams.WRAP_CONTENT)
        sliderParams.leftMargin = dp(4f)
        bar.addView(slider, sliderParams)

        val close = makeButton("✕") { stopSelf() }
        bar.addView(close)

        layout.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34f)))

        // WebView (не прозрачный — иначе видео останавливается).
        val wv = WebView(this)
        wv.setBackgroundColor(0xFF000000.toInt())
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.databaseEnabled = true
        wv.settings.loadWithOverviewMode = true
        wv.settings.useWideViewPort = true
        wv.settings.setSupportZoom(false)
        wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // Музыка/видео стартуют без тапа и играют в фоне.
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean {
                if (url != null) view.loadUrl(url)
                return true
            }
        }
        wv.loadUrl(currentUrl)
        layout.addView(wv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        webView = wv

        // Тип окна: APPLICATION_OVERLAY на API26+, иначе PHONE.
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val p = WindowManager.LayoutParams(
            dp(BASE_W_DP),
            dp(BASE_H_DP),
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = dp(28f)
        p.y = dp(120f)
        params = p

        // Перетаскивание за панель управления.
        bar.setOnTouchListener(object : View.OnTouchListener {
            private var initialRawX = 0f
            private var initialRawY = 0f
            private var initialX = 0
            private var initialY = 0
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val lp = params ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialRawX = event.rawX
                        initialRawY = event.rawY
                        initialX = lp.x
                        initialY = lp.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = initialX + (event.rawX - initialRawX).toInt()
                        lp.y = initialY + (event.rawY - initialRawY).toInt()
                        runCatching { wm.updateViewLayout(layout, lp) }
                        return true
                    }
                    else -> return false
                }
            }
        })

        root = layout
        runCatching { wm.addView(layout, p) }.onFailure {
            Toast.makeText(this, "Нет разрешения показывать поверх приложений", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun resize(width: Int, height: Int) {
        val lp = params ?: return
        val r = root ?: return
        lp.width = width
        lp.height = height
        runCatching { wm.updateViewLayout(r, lp) }
    }

    override fun onDestroy() {
        runCatching { root?.let { wm.removeView(it) } }
        root = null
        runCatching { webView?.destroy() }
        webView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
