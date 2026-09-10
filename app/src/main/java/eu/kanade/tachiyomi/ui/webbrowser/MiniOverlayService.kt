package eu.kanade.tachiyomi.ui.webbrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
import android.widget.Space
import android.widget.TextView
import android.widget.Toast

/**
 * ЕДИНЫЙ мини-плеер (v1.9.56) — «мини-браузер внутри приложения», видимый
 * ПОВЕРХ ВСЕХ приложений.
 *
 * Раньше было ДВА независимых плеера — встроенный «мини-плеер внутри
 * приложения» и этот системный (поверх всех приложений). Они сосуществовали
 * и показывали по два окна одновременно. Теперь мини-плеер ровно один — этот.
 * Встроенный дубликат удалён; web-вкладка всегда держит собственный WebView.
 *
 * Это полноценный само-браузер: отдельный WebView, свой URL-бар, кнопки
 * назад/вперёд/обновить, зум, прозрачность панели и перетаскивание за шапку.
 *
 * v1.9.57:
 *  - рамку можно уменьшать/увеличивать кнопками −/+ (не обрезаются: панель
 *    переработана в отдельные строки), а сам плеер не обрезается границами экрана.
 *  - кнопка «▾» сворачивает плеер в маленький плавающий пузырёк «▶». WebView при
 *    этом НЕ уничтожается и остаётся видимым, поэтому музыка/видео продолжают
 *    играть в фоне. Тап по пузырьку разворачивает плеер обратно.
 *
 * Требует разрешения «Показ поверх других приложений» (SYSTEM_ALERT_WINDOW):
 * [MiniOverlayService.requestPermission] открывает системные настройки, а
 * [MiniOverlayService.canDrawOverlays] проверяет выдано ли оно.
 *
 * ВАЖНО про видео: WebView рисует <video> через аппаратный оверлей. Поэтому
 * на сам WebView НЕ накладывается прозрачность (иначе видео останавливается).
 * Слайдер меняет прозрачность только фона панели управления.
 */
class MiniOverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "mini_overlay"
        private const val NOTIF_ID = 0x4D4E
        private const val EXTRA_URL = "url"
        private const val BASE_W_DP = 250f
        private const val BASE_H_DP = 400f
        private const val MIN_SCALE = 0.4f
        private const val MAX_SCALE = 2.5f
        private const val BUBBLE_W_DP = 56f
        private const val BUBBLE_H_DP = 56f

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
    private var root: FrameLayout? = null
    private var webView: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private var currentUrl: String = "https://mangabuff.ru"
    private var urlView: TextView? = null
    private var backBtn: Button? = null
    private var fwdBtn: Button? = null
    private var panel: LinearLayout? = null
    private var bubble: TextView? = null

    private var scale = 1f
    private var chromeAlpha = 0.92f
    private var isCollapsed = false
    private var expandedW = 0
    private var expandedH = 0

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt().coerceAtLeast(1)

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
                .setContentText("Мини-браузер поверх всех приложений")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
            if (contentIntent != null) b.setContentIntent(contentIntent)
            startForeground(NOTIF_ID, b.build())
        } else {
            @Suppress("DEPRECATION")
            val b = Notification.Builder(this)
                .setContentTitle("Мини-плеер")
                .setContentText("Мини-браузер поверх всех приложений")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
            if (contentIntent != null) b.setContentIntent(contentIntent)
            startForeground(NOTIF_ID, b.build())
        }
    }

    private fun buildOverlay() {
        expandedW = dp(BASE_W_DP)
        expandedH = dp(BASE_H_DP)

        fun chromeColor(): Int {
            val a = (0xFF * chromeAlpha).toInt().coerceIn(0, 0xFF)
            return (a shl 24) or 0x12101A
        }

        // Корневой контейнер — FrameLayout: WebView заполняет всё, панель и
        // пузырёк ложатся поверх; при сворачивании WebView остаётся видимым.
        val layout = FrameLayout(this)
        layout.setBackgroundColor(0xFF000000.toInt())

        // ===== WebView (непрозрачный — иначе видео останавливается) =====
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
        wv.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                if (title.isNullOrBlank()) return
                urlView?.text = title
            }
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean {
                if (url != null) view.loadUrl(url)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                urlView?.text = url ?: ""
            }

            override fun onPageFinished(view: WebView, url: String?) {
                urlView?.text = view.title?.takeIf { it.isNotBlank() } ?: url
                backBtn?.isEnabled = view.canGoBack()
                fwdBtn?.isEnabled = view.canGoForward()
            }
        }
        wv.loadUrl(currentUrl)
        layout.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        webView = wv

        // ===== Панель управления (кладётся сверху) =====
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(chromeColor())
        panel.setPadding(0, 0, 0, 0)
        this.panel = panel

        fun textButton(symbol: String, onClick: () -> Unit): Button {
            val b = Button(this)
            b.text = symbol
            b.textSize = 12f
            b.setAllCaps(false)
            b.setPadding(dp(6f), dp(0f), dp(6f), dp(0f))
            b.setBackgroundColor(0x33FFFFFF)
            b.setOnClickListener { onClick() }
            return b
        }

        // ----- РОВ 1: ручка-перетаскивание + название + свернуть + закрыть -----
        val titleBar = LinearLayout(this)
        titleBar.orientation = LinearLayout.HORIZONTAL
        titleBar.gravity = Gravity.CENTER_VERTICAL
        titleBar.setPadding(dp(6f), dp(2f), dp(4f), dp(2f))

        val handle = TextView(this)
        handle.text = "≡   "
        handle.textSize = 14f
        handle.setTextColor(0xFFFFFFFF.toInt())
        titleBar.addView(handle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val urlTv = TextView(this)
        urlTv.setTextColor(0xFFE0E0E0.toInt())
        urlTv.textSize = 11f
        urlTv.maxLines = 1
        urlTv.ellipsize = android.text.TextUtils.TruncateAt.END
        titleBar.addView(urlTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        urlView = urlTv

        val collapseBtn = textButton("▾") { toggleCollapse() }
        titleBar.addView(collapseBtn)

        val close = textButton("✕") { stopSelf() }
        titleBar.addView(close)

        panel.addView(titleBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30f)))

        // ----- РОВ 2: назад / вперёд / обновить + −/+ (зум всей рамки) -----
        val navRow = LinearLayout(this)
        navRow.orientation = LinearLayout.HORIZONTAL
        navRow.gravity = Gravity.CENTER_VERTICAL
        navRow.setPadding(dp(4f), dp(0f), dp(4f), dp(0f))

        val back = textButton("‹") { webView?.let { if (it.canGoBack()) it.goBack() } }
        val fwd = textButton("›") { webView?.let { if (it.canGoForward()) it.goForward() } }
        val reload = textButton("⟳") { webView?.reload() }
        backBtn = back
        fwdBtn = fwd
        navRow.addView(back)
        navRow.addView(fwd)
        navRow.addView(reload)

        // Распорка растягивается, прижимая − / + к правому краю (не обрезаются).
        navRow.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))

        val minus = textButton("−") { changeScale(-0.2f) }
        val plus = textButton("+") { changeScale(0.2f) }
        navRow.addView(minus)
        navRow.addView(plus)

        panel.addView(navRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30f)))

        // ----- РОВ 3: слайдер прозрачности панели -----
        val sliderRow = LinearLayout(this)
        sliderRow.orientation = LinearLayout.HORIZONTAL
        sliderRow.gravity = Gravity.CENTER_VERTICAL
        val slider = SeekBar(this)
        slider.max = 70 // 0.30..1.00
        slider.progress = 69
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                chromeAlpha = 0.30f + progress / 100f
                panel.setBackgroundColor(chromeColor())
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        val sp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        sp.leftMargin = dp(4f)
        sp.rightMargin = dp(4f)
        sliderRow.addView(slider, sp)
        panel.addView(sliderRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26f)))

        // Панель сверху поверх WebView (прозрачность только её фон — видео не трогаем).
        layout.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // ===== Пузырёк (когда плеер свёрнут) =====
        val bubbleView = TextView(this)
        bubbleView.text = "▶"
        bubbleView.textSize = 20f
        bubbleView.setTextColor(0xFFFFFFFF.toInt())
        bubbleView.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(0xCC12101A.toInt())
        bg.setStroke(dp(2f), 0xFFFFFFFF.toInt())
        bubbleView.background = bg
        bubbleView.visibility = View.GONE
        bubbleView.setOnTouchListener { _, event -> handleBubbleTouch(event) }
        val bubbleLp = FrameLayout.LayoutParams(dp(BUBBLE_W_DP), dp(BUBBLE_H_DP), Gravity.CENTER)
        layout.addView(bubbleView, bubbleLp)
        bubble = bubbleView

        // Тип окна: APPLICATION_OVERLAY на API26+, иначе PHONE.
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val p = WindowManager.LayoutParams(
            expandedW,
            expandedH,
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

        // Перетаскивание за шапку (не за WebView, чтобы листать страницу).
        titleBar.setOnTouchListener(object : View.OnTouchListener {
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
                        clampToScreen(lp)
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

    /** Перетаскивание пузырька + тап-разворачивание. */
    private fun handleBubbleTouch(event: MotionEvent): Boolean {
        val lp = params ?: return false
        val r = root ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragBubbleInitialRawX = event.rawX
                dragBubbleInitialRawY = event.rawY
                dragBubbleInitialX = lp.x
                dragBubbleInitialY = lp.y
                dragBubbleDownTime = System.currentTimeMillis()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                lp.x = dragBubbleInitialX + (event.rawX - dragBubbleInitialRawX).toInt()
                lp.y = dragBubbleInitialY + (event.rawY - dragBubbleInitialRawY).toInt()
                clampToScreen(lp)
                runCatching { wm.updateViewLayout(r, lp) }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = (event.rawX - dragBubbleInitialRawX).toInt()
                val dy = (event.rawY - dragBubbleInitialRawY).toInt()
                val quick = System.currentTimeMillis() - dragBubbleDownTime < 350
                if (quick && (dx * dx + dy * dy) < 200) expandNow()
                return true
            }
            else -> return false
        }
    }

    private var dragBubbleInitialRawX = 0f
    private var dragBubbleInitialRawY = 0f
    private var dragBubbleInitialX = 0
    private var dragBubbleInitialY = 0
    private var dragBubbleDownTime = 0L

    /** Свернуть в плавающий пузырёк (WebView остаётся живым и играет дальше). */
     private fun collapseNow() {
        val lp = params ?: return
        if (isCollapsed) return
        // Запоминаем текущий развёрнутый размер, чтобы вернуть его обратно.
        expandedW = lp.width
        expandedH = lp.height
        isCollapsed = true
        lp.width = dp(BUBBLE_W_DP)
        lp.height = dp(BUBBLE_H_DP)
        panel?.visibility = View.GONE
        bubble?.visibility = View.VISIBLE
        clampToScreen(lp)
        root?.let { runCatching { wm.updateViewLayout(it, lp) } }
        // Пауза WebView: останавливаем видео/аудио при сворачивании в пузырёк.
        // Visibility оставляем VISIBLE чтобы WebView не пересоздавался при развороте.
        webView?.onPause()
    }

    /** Развернуть пузырёк обратно в полноценный плеер. */
    private fun expandNow() {
        val lp = params ?: return
        if (!isCollapsed) return
        isCollapsed = false
        lp.width = expandedW.coerceAtLeast(dp(BASE_W_DP))
        lp.height = expandedH.coerceAtLeast(dp(BASE_H_DP))
        panel?.visibility = View.VISIBLE
        bubble?.visibility = View.GONE
        clampToScreen(lp)
        root?.let { runCatching { wm.updateViewLayout(it, lp) } }
        webView?.let { it.onResume() }
    }

    /** Переключить свёрнутое/развёрнутое состояние. */
    private fun toggleCollapse() {
        if (isCollapsed) expandNow() else collapseNow()
    }

    /** Уменьшить/увеличить рамку плеера кнопками −/+ . */
    private fun changeScale(delta: Float) {
        scale = (scale + delta).coerceIn(MIN_SCALE, MAX_SCALE)
        val w = dp(BASE_W_DP * scale)
        val h = dp(BASE_H_DP * scale)
        expandedW = w
        expandedH = h
        resize(w, h)
    }

    private fun resize(width: Int, height: Int) {
        val lp = params ?: return
        if (isCollapsed) return
        lp.width = width
        lp.height = height
        clampToScreen(lp)
        root?.let { runCatching { wm.updateViewLayout(it, lp) } }
    }

    /** Не даём окну уходить за границы экрана. */
    private fun clampToScreen(lp: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        val maxX = (dm.widthPixels - lp.width).coerceAtLeast(0)
        val maxY = (dm.heightPixels - lp.height).coerceAtLeast(0)
        lp.x = lp.x.coerceIn(0, maxX)
        lp.y = lp.y.coerceIn(0, maxY)
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
