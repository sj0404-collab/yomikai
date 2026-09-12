package eu.kanade.tachiyomi.ui.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
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
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import eu.kanade.tachiyomi.data.tts.AutoReadEngine
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Системный OCR-оверлей «бабл» для ДРУГИХ приложений (v1.9.70).
 *
 * Плавающее окно поверх любого приложения, работающее как ридеровская
 * озвучка-бабл: показывает текст в пузыре и читает его вслух. Источник
 * текста — буфер обмена (кнопка «📋») или поле ручного ввода — это удобно
 * в сторонних приложениях, где нет встроенной читалки.
 *
 * В ридере баблы рисуются по координатам распознанных панелей манги; здесь
 * (нет страницы) тот же визуальный стиль бабла, но текст берём извне.
 * Требует разрешения «Показ поверх других приложений» (SYSTEM_ALERT_WINDOW).
 */
class OcrOverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "ocr_overlay"
        private const val NOTIF_ID = 0x4F43
        private const val ACTION_SPEAK = "eu.kanade.tachiyomi.ocr.OVERLAY_SPEAK"
        private const val EXTRA_TEXT = "text"
        private const val BASE_W_DP = 300f
        private const val BUBBLE_W_DP = 60f
        private const val BUBBLE_H_DP = 60f

        fun canDrawOverlays(context: Context): Boolean =
            Settings.canDrawOverlays(context)

        fun requestPermission(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, OcrOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, OcrOverlayService::class.java)) }
        }

        /** Показать текст в бабле и озвучить его поверх любого приложения. */
        fun speak(context: Context, text: String) {
            val intent = Intent(context, OcrOverlayService::class.java)
                .setAction(ACTION_SPEAK)
                .putExtra(EXTRA_TEXT, text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(intent)
            }
        }
    }

    private lateinit var wm: WindowManager
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null

    private var bubbleText: TextView? = null
    private var input: EditText? = null
    private var contentView: LinearLayout? = null
    private var collapsedView: TextView? = null

    // Только через ленивую инициализацию: конструктор требует Injekt-одиночек,
    // доступных после старта приложения (как делает BrowserTab).
    private val readEngine by lazy { AutoReadEngine(applicationContext) }
    private var isSpeaking = false
    private var expandedW = 0
    private var expandedH = 0

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureForeground()
        if (root == null) buildOverlay()
        if (intent?.action == ACTION_SPEAK) {
            val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
            if (text.isNotBlank()) speakText(text)
        }
        return START_STICKY
    }

    private fun ensureForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "OCR-оверлей", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val b = builder
            .setContentTitle("OCR-оверлей")
            .setContentText("Бабл поверх других приложений")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
        if (contentIntent != null) b.setContentIntent(contentIntent)
        startForeground(NOTIF_ID, b.build())
    }

    /** Озвучить текст через тот же движок, что в ридере. */
    private fun speakText(text: String) {
        bubbleText?.text = text
        runCatching { readEngine.speakSingle(text) }.onFailure {
            logcat(LogPriority.WARN, it) { "OcrOverlay TTS failed" }
            Toast.makeText(this, "Не удалось запустить озвучку", Toast.LENGTH_SHORT).show()
        }
        isSpeaking = true
    }

    private fun toggleSpeak() {
        if (isSpeaking) {
            runCatching { readEngine.stop() }
            isSpeaking = false
            return
        }
        val text = bubbleText?.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, "Текст пуст", Toast.LENGTH_SHORT).show()
            return
        }
        speakText(text)
    }

    /** Текст из буфера обмена (источник для сторонних приложений). */
    private fun fromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val text = clip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
        } else {
            speakText(text)
        }
    }

    private fun buildOverlay() {
        expandedW = dp(BASE_W_DP)
        expandedH = dp(220f)

        val layout = FrameLayout(this)
        layout.setBackgroundColor(0xCC14101B.toInt())

        // ===== Содержимое =====
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL

        // Шапка: ручка-перетаскивание + заголовок + «▾» (свернуть) + «✕».
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(6f), dp(2f), dp(4f), dp(2f))

        val handle = TextView(this)
        handle.text = "≡ "
        handle.textSize = 14f
        handle.setTextColor(0xFFFFFFFF.toInt())
        header.addView(handle)

        val title = TextView(this)
        title.text = "OCR-бабл"
        title.setTextColor(0xFFE0E0E0.toInt())
        title.textSize = 12f
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val speakBtn = textButton("▶/⏸") { toggleSpeak() }
        val clipBtn = textButton("📋") { fromClipboard() }
        val collapseBtn = textButton("▾") { collapseNow() }
        val closeBtn = textButton("✕") { stopSelf() }
        header.addView(speakBtn)
        header.addView(clipBtn)
        header.addView(collapseBtn)
        header.addView(closeBtn)

        content.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30f)))

        // Бабл с текстом (как в читалке поверх панели).
        val bubble = TextView(this)
        bubble.setTextColor(0xFFFFFFFF.toInt())
        bubble.textSize = 14f
        bubble.setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        bubble.maxLines = 8
        val bubbleBg = GradientDrawable()
        bubbleBg.cornerRadius = dp(10f).toFloat()
        bubbleBg.setColor(0x33234A6F.toInt())
        bubbleBg.setStroke(dp(1f), 0xFF4A7EAF.toInt())
        bubble.background = bubbleBg
        content.addView(
            bubble,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(2f) },
        )
        bubbleText = bubble

        // Ручной ввод текста (удобно, если в другом приложении нельзя копировать).
        val inputEdit = EditText(this)
        inputEdit.hint = "Текст для бабла…"
        inputEdit.setTextColor(0xFFFFFFFF.toInt())
        inputEdit.setHintTextColor(0xAAE0E0E0.toInt())
        inputEdit.textSize = 13f
        inputEdit.setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
        content.addView(
            inputEdit,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(2f) },
        )
        input = inputEdit

        val sayRow = LinearLayout(this)
        sayRow.orientation = LinearLayout.HORIZONTAL
        val sayBtn = textButton("Сказать") {
            val t = input?.text?.toString()?.trim().orEmpty()
            if (t.isNotBlank()) speakText(t)
        }
        val clearBtn = textButton("Очистить") {
            bubble?.text = ""
            input?.text?.clear()
        }
        sayRow.addView(sayBtn)
        sayRow.addView(clearBtn)
        content.addView(
            sayRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(2f) },
        )

        layout.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        contentView = content

        // Пузырёк в свёрнутом состоянии.
        val collapsed = TextView(this)
        collapsed.text = "👁"
        collapsed.textSize = 22f
        collapsed.setTextColor(0xFFFFFFFF.toInt())
        collapsed.gravity = Gravity.CENTER
        val oval = GradientDrawable()
        oval.shape = GradientDrawable.OVAL
        oval.setColor(0xCC14101B.toInt())
        oval.setStroke(dp(2f), 0xFF4A7EAF.toInt())
        collapsed.background = oval
        collapsed.visibility = View.GONE
        collapsed.setOnTouchListener { _, event -> handleBubbleTouch(event) }
        layout.addView(collapsed, FrameLayout.LayoutParams(dp(BUBBLE_W_DP), dp(BUBBLE_H_DP), Gravity.CENTER))
        collapsedView = collapsed

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
        p.x = dp(20f)
        p.y = dp(140f)
        params = p

        header.setOnTouchListener(dragListener())
        // Поле ввода: тап — включаем фокус/клавиатуру, уход из поля — возвращаем.
        inputEdit.setOnClickListener { makeFocusable(true) }
        inputEdit.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) makeFocusable(false) }

        root = layout
        runCatching { wm.addView(layout, p) }.onFailure {
            Toast.makeText(this, "Нет разрешения показывать поверх приложений", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun textButton(symbol: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = symbol
        b.textSize = 11f
        b.setAllCaps(false)
        b.setPadding(dp(5f), 0, dp(5f), 0)
        b.setBackgroundColor(0x334A7EAF.toInt())
        b.setOnClickListener { onClick() }
        return b
    }

    private fun dragListener(): View.OnTouchListener {
        return object : View.OnTouchListener {
            private var startRawX = 0f
            private var startRawY = 0f
            private var startX = 0
            private var startY = 0
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val lp = params ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = event.rawX
                        startRawY = event.rawY
                        startX = lp.x
                        startY = lp.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = startX + (event.rawX - startRawX).toInt()
                        lp.y = startY + (event.rawY - startRawY).toInt()
                        clampToScreen(lp)
                        root?.let { runCatching { wm.updateViewLayout(it, lp) } }
                        return true
                    }
                    else -> return false
                }
            }
        }
    }

    private var dragBubbleRawX = 0f
    private var dragBubbleRawY = 0f
    private var dragBubbleStartX = 0
    private var dragBubbleStartY = 0
    private var dragBubbleDown = 0L

    private fun handleBubbleTouch(event: MotionEvent): Boolean {
        val lp = params ?: return false
        val r = root ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragBubbleRawX = event.rawX
                dragBubbleRawY = event.rawY
                dragBubbleStartX = lp.x
                dragBubbleStartY = lp.y
                dragBubbleDown = System.currentTimeMillis()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                lp.x = dragBubbleStartX + (event.rawX - dragBubbleRawX).toInt()
                lp.y = dragBubbleStartY + (event.rawY - dragBubbleRawY).toInt()
                clampToScreen(lp)
                runCatching { wm.updateViewLayout(r, lp) }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val quick = System.currentTimeMillis() - dragBubbleDown < 350
                val dx = (event.rawX - dragBubbleRawX).toInt()
                val dy = (event.rawY - dragBubbleRawY).toInt()
                if (quick && (dx * dx + dy * dy) < 200) expandNow()
                return true
            }
            else -> return false
        }
    }

    private var isCollapsed = false

    private fun collapseNow() {
        val lp = params ?: return
        if (isCollapsed) return
        expandedW = lp.width
        expandedH = lp.height
        isCollapsed = true
        lp.width = dp(BUBBLE_W_DP)
        lp.height = dp(BUBBLE_H_DP)
        contentView?.visibility = View.GONE
        collapsedView?.visibility = View.VISIBLE
        clampToScreen(lp)
        root?.let { runCatching { wm.updateViewLayout(it, lp) } }
    }

    private fun expandNow() {
        val lp = params ?: return
        if (!isCollapsed) return
        isCollapsed = false
        lp.width = expandedW.coerceAtLeast(dp(BASE_W_DP))
        lp.height = expandedH.coerceAtLeast(dp(220f))
        contentView?.visibility = View.VISIBLE
        collapsedView?.visibility = View.GONE
        clampToScreen(lp)
        root?.let { runCatching { wm.updateViewLayout(it, lp) } }
    }

    private fun makeFocusable(value: Boolean) {
        val lp = params ?: return
        lp.flags = if (value) {
            // Режим редактирования: окно получает фокус для клавиатуры.
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        runCatching { root?.let { wm.updateViewLayout(it, lp) } }
    }

    /** Не даём окну уходить за границы экрана. */
    private fun clampToScreen(lp: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        lp.x = lp.x.coerceIn(0, (dm.widthPixels - lp.width).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (dm.heightPixels - lp.height).coerceAtLeast(0))
    }

    override fun onDestroy() {
        runCatching { readEngine.stop() }
        runCatching { root?.let { wm.removeView(it) } }
        root = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}