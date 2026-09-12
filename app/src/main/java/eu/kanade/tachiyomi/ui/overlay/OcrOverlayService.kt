package eu.kanade.tachiyomi.ui.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import mihon.domain.ocr.service.OcrPreferences
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Плавающая кнопка поверх ДРУГИХ приложений (v1.9.72).
 *
 * Это НЕ панель, а именно кнопка-пузырёк: тап разворачивает те же кнопки,
 * что в читалке (▶ Голос, ♀ ♂ 🎙, Выбрать, STT, 📋, Копировать, ＋ Словарь,
 * Закрыть). Источник текста — буфер обмена / ручной ввод / STT с микрофона
 * (английская речь игр → текст → русские голоса, реал-тайм).
 *
 * Области: auto — весь экран; manual/fixed — ручное выделение или
 * зафиксированная область (реплики игр), рамка рисуется поверх приложений.
 * В ридере баблы привязаны к панелям манги; здесь страницы нет, поэтому
 * кнопки и стиль — как в читалке, а текст берётся извне.
 *
 * Требует «Показ поверх других приложений» (SYSTEM_ALERT_WINDOW);
 * STT требует RECORD_AUDIO.
 */
class OcrOverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "ocr_overlay"
        private const val NOTIF_ID = 0x4F43
        private const val ACTION_SPEAK = "eu.kanade.tachiyomi.ocr.OVERLAY_SPEAK"
        private const val ACTION_STT_START = "eu.kanade.tachiyomi.ocr.OVERLAY_STT_START"
        private const val ACTION_STT_STOP = "eu.kanade.tachiyomi.ocr.OVERLAY_STT_STOP"
        private const val ACTION_REFRESH = "eu.kanade.tachiyomi.ocr.OVERLAY_REFRESH"
        private const val ACTION_SELECT_REGION = "eu.kanade.tachiyomi.ocr.OVERLAY_SELECT_REGION"
        private const val EXTRA_TEXT = "text"
        private const val BASE_W_DP = 320f
        private const val BASE_H_DP = 340f
        private const val BTN_DP = 60f

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

        fun hasRecordAudio(context: Context): Boolean {
            return context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        }

        private fun intentFor(context: Context): Intent =
            Intent(context, OcrOverlayService::class.java)

        private fun startWith(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(intent)
            }
        }

        fun start(context: Context) = startWith(context, intentFor(context))

        fun stop(context: Context) {
            runCatching { context.stopService(intentFor(context)) }
        }

        /** Показать текст в бабле и озвучить его поверх любого приложения. */
        fun speak(context: Context, text: String) {
            startWith(
                context,
                intentFor(context).setAction(ACTION_SPEAK).putExtra(EXTRA_TEXT, text),
            )
        }

        fun sttStart(context: Context) {
            startWith(context, intentFor(context).setAction(ACTION_STT_START))
        }

        fun sttStop(context: Context) {
            startWith(context, intentFor(context).setAction(ACTION_STT_STOP))
        }

        /** Перечитать префы (рамка области, слежка за буфером). */
        fun refresh(context: Context) {
            startWith(context, intentFor(context).setAction(ACTION_REFRESH))
        }

        /** Открыть ручной выбор области (нужен запущенный оверлей). */
        fun selectRegion(context: Context) {
            startWith(context, intentFor(context).setAction(ACTION_SELECT_REGION))
        }
    }

    private lateinit var wm: WindowManager
    private val uiHandler = Handler(Looper.getMainLooper())
    private val prefs: OcrPreferences by lazy { Injekt.get<OcrPreferences>() }

    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var bubbleText: TextView? = null
    private var input: EditText? = null
    private var contentView: LinearLayout? = null
    private var floatButton: TextView? = null
    private var sttButton: Button? = null
    private var regionButton: Button? = null

    private val readEngine by lazy { AutoReadEngine(applicationContext) }
    private var stt: GameSttManager? = null
    private var isSpeaking = false
    private var expandedW = 0
    private var expandedH = 0
    private var ignoreNextClip = false
    private var lastClipText = ""

    // Рамка зафиксированной области (отдельное окно, клики — сквозь).
    private var frameRoot: FrameLayout? = null
    private var frameParams: WindowManager.LayoutParams? = null

    // Селектор области (полноэкранное окно для ручного выделения).
    private var selectorRoot: FrameLayout? = null
    private var selectorDraw: RegionDrawView? = null

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
        when (intent?.action) {
            ACTION_SPEAK -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                if (text.isNotBlank()) {
                    expandNow()
                    speakText(text)
                }
            }
            ACTION_STT_START -> toggleStt(force = true)
            ACTION_STT_STOP -> toggleStt(force = false)
            ACTION_SELECT_REGION -> {
                expandNow()
                openSelector()
            }
            ACTION_REFRESH -> {
                applyClipboardWatch()
                applyFrame()
            }
        }
        applyClipboardWatch()
        applyFrame()
        return START_STICKY
    }

    private fun ensureForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "OCR-оверлей", NotificationManager.IMPORTANCE_LOW),
            )
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
            .setContentTitle("OCR-кнопка")
            .setContentText("Плавающая кнопка поверх приложений")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
        if (contentIntent != null) b.setContentIntent(contentIntent)
        startForeground(NOTIF_ID, b.build())
    }

    // ---------- Озвучка (тот же движок, что в читалке) ----------

    private fun currentText(): String = bubbleText?.text?.toString()?.trim().orEmpty()

    private fun setBubble(text: String) {
        uiHandler.post { bubbleText?.text = text }
    }

    private fun speakText(text: String) {
        setBubble(text)
        runCatching { readEngine.speakSingle(text) }.onFailure {
            logcat(LogPriority.WARN, it) { "OcrOverlay TTS failed" }
            toast("Не удалось запустить озвучку")
        }
        isSpeaking = true
    }

    private fun toggleSpeak() {
        if (isSpeaking) {
            runCatching { readEngine.stop() }
            isSpeaking = false
            return
        }
        val text = currentText()
        if (text.isBlank()) {
            toast("Текст пуст — вставьте из буфера (📋) или включите STT")
            return
        }
        speakText(text)
    }

    /** Озвучить текущее половой ролью, как кнопки ♀ ♂ 🎙 в читалке. */
    private fun speakRole(role: String) {
        val text = currentText()
        if (text.isBlank()) {
            toast("Текст пуст")
            return
        }
        setBubble(text)
        runCatching { readEngine.speakSingle(text, role) }.onFailure {
            toast("Не удалось запустить озвучку")
        }
        isSpeaking = true
    }

    /** «Выбрать» — системные настройки TTS: движок читалки следует за системой. */
    private fun openVoiceChoice() {
        runCatching {
            startActivity(
                Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            toast("Не открылись настройки синтеза речи")
        }
    }

    private fun copyBubble() {
        val text = currentText()
        if (text.isBlank()) {
            toast("Нечего копировать")
            return
        }
        ignoreNextClip = true
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ocr", text))
        uiHandler.postDelayed({ ignoreNextClip = false }, 1500)
        toast("Скопировано")
    }

    /** «＋ Словарь»: текст уже в буфере — открываем приложение к словарям. */
    private fun addToDictionary() {
        copyBubble()
        runCatching {
            val launch = packageManager.getLaunchIntentForPackage(packageName)
                ?: return toast("Скопировано — откройте словарь вручную")
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        }.onFailure {
            toast("Скопировано — откройте словарь вручную")
        }
    }

    /** Текст из буфера обмена (источник для сторонних приложений). */
    private fun fromClipboard(speak: Boolean = true) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            toast("Буфер обмена пуст")
        } else {
            lastClipText = text
            if (speak) speakText(text) else setBubble(text)
        }
    }

    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    private fun applyClipboardWatch() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipListener?.let { runCatching { cm.removePrimaryClipChangedListener(it) } }
        clipListener = null
        if (!prefs.overlayWatchClipboard().get()) return
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            if (ignoreNextClip) return@OnPrimaryClipChangedListener
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
            if (text.isNotBlank() && text != lastClipText) {
                lastClipText = text
                expandNow()
                speakText(text)
            }
        }
        clipListener = listener
        cm.addPrimaryClipChangedListener(listener)
    }

    // ---------- STT ----------

    private fun toggleStt(force: Boolean? = null) {
        val manager = stt
        val active = manager?.isListening == true
        val wantOn = force ?: !active
        if (wantOn && active) return
        if (!wantOn) {
            manager?.stop()
            updateSttButton(false)
            return
        }
        if (!hasRecordAudio(this)) {
            toast("Дайте доступ к микрофону в настройках оверлея")
            return
        }
        val m = manager ?: GameSttManager(applicationContext, prefs, readEngine).also {
            it.onPartial = { part -> setBubble(part) }
            it.onFinal = { final -> setBubble(final) }
            it.onError = { msg -> toast(msg) }
            it.onListeningChanged = { on -> updateSttButton(on) }
            stt = it
        }
        expandNow()
        setBubble("🎙 Слушаю…")
        m.start()
        updateSttButton(true)
    }

    private fun updateSttButton(on: Boolean) {
        uiHandler.post { sttButton?.text = if (on) "⏹ STT" else "🎙 STT" }
    }

    // ---------- Область: режимы, селектор, рамка ----------

    private data class Region(val l: Float, val t: Float, val r: Float, val b: Float)

    private fun parseRegion(): Region? {
        val parts = prefs.overlayFixedRegion().get().split(',').mapNotNull { it.toFloatOrNull() }
        if (parts.size != 4) return null
        val (l, t, r, b) = parts
        if (!(l in 0f..1f && t in 0f..1f && r in 0f..1f && b in 0f..1f && l < r && t < b)) return null
        return Region(l, t, r, b)
    }

    private fun regionModeLabel(): String = when (prefs.overlayRegionMode().get()) {
        "manual" -> "Область: ручная"
        "fixed" -> "Область: фикс"
        else -> "Область: авто"
    }

    private fun updateRegionButton() {
        uiHandler.post { regionButton?.text = regionModeLabel() }
    }

    private fun cycleRegionMode() {
        val next = when (prefs.overlayRegionMode().get()) {
            "auto" -> "manual"
            "manual" -> "fixed"
            else -> "auto"
        }
        prefs.overlayRegionMode().set(next)
        if (next != "auto" && parseRegion() == null) {
            toast("Сначала задайте область кнопкой ✏")
        }
        updateRegionButton()
        applyFrame()
    }

    /** Полноэкранный селектор: потяните пальцем прямоугольник области. */
    private fun openSelector() {
        if (selectorRoot != null) return
        val dm = resources.displayMetrics
        val layout = FrameLayout(this)
        layout.setBackgroundColor(0x88000000.toInt())
        val draw = RegionDrawView(this)
        layout.addView(draw, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        selectorDraw = draw

        val hint = TextView(this)
        hint.text = "Выделите область пальцем • тап — отмена"
        hint.setTextColor(0xFFFFFFFF.toInt())
        hint.textSize = 14f
        hint.gravity = Gravity.CENTER
        hint.setPadding(0, dp(48f), 0, 0)
        layout.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        var downX = 0f
        var downY = 0f
        var moved = false
        layout.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    draw.setRect(downX, downY, downX, downY)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    moved = true
                    draw.setRect(downX, downY, event.rawX, event.rawY)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved) {
                        closeSelector()
                    } else {
                        val r = draw.normalized(dm.widthPixels, dm.heightPixels)
                        if (r != null) {
                            prefs.overlayFixedRegion().set("${r.l},${r.t},${r.r},${r.b}")
                            prefs.overlayRegionMode().set("fixed")
                            updateRegionButton()
                            toast("Область зафиксирована")
                        }
                        closeSelector()
                        applyFrame()
                    }
                    true
                }
                else -> false
            }
        }

        val windowType = overlayType()
        val p = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        selectorRoot = layout
        runCatching { wm.addView(layout, p) }.onFailure {
            selectorRoot = null
            toast("Не удалось открыть выбор области")
        }
    }

    private fun closeSelector() {
        selectorRoot?.let { runCatching { wm.removeView(it) } }
        selectorRoot = null
        selectorDraw = null
    }

    /** Рамка зафиксированной области: тонкие границы, клики — сквозь. */
    private fun applyFrame() {
        val show = prefs.overlayShowFrame().get() &&
            prefs.overlayRegionMode().get() == "fixed" &&
            parseRegion() != null
        if (!show) {
            frameRoot?.let { runCatching { wm.removeView(it) } }
            frameRoot = null
            frameParams = null
            return
        }
        val region = parseRegion() ?: return
        val dm = resources.displayMetrics
        val x = (region.l * dm.widthPixels).toInt()
        val y = (region.t * dm.heightPixels).toInt()
        val w = ((region.r - region.l) * dm.widthPixels).toInt().coerceAtLeast(dp(24f))
        val h = ((region.b - region.t) * dm.heightPixels).toInt().coerceAtLeast(dp(24f))
        val thickness = dp(3f)
        val color = 0xFF4A7EAF.toInt()

        val box = FrameLayout(this)
        fun border(wPx: Int, hPx: Int, gravity: Int): View {
            val v = View(this)
            v.setBackgroundColor(color)
            box.addView(v, FrameLayout.LayoutParams(wPx, hPx, gravity))
            return v
        }
        border(ViewGroup.LayoutParams.MATCH_PARENT, thickness, Gravity.TOP)
        border(ViewGroup.LayoutParams.MATCH_PARENT, thickness, Gravity.BOTTOM)
        border(thickness, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
        border(thickness, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END)

        val existing = frameRoot
        if (existing != null && frameParams != null) {
            frameParams!!.x = x
            frameParams!!.y = y
            frameParams!!.width = w
            frameParams!!.height = h
            frameRoot = box
            runCatching {
                wm.removeView(existing)
                wm.addView(box, frameParams!!)
            }.onFailure {
                frameRoot = null
                frameParams = null
            }
            return
        }
        val p = WindowManager.LayoutParams(
            w,
            h,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = x
        p.y = y
        frameRoot = box
        frameParams = p
        runCatching { wm.addView(box, p) }.onFailure {
            frameRoot = null
            frameParams = null
        }
    }

    private fun toggleFrame() {
        prefs.overlayShowFrame().set(!prefs.overlayShowFrame().get())
        applyFrame()
        toast(if (prefs.overlayShowFrame().get()) "Рамка показана" else "Рамка скрыта")
    }

    // ---------- Построение окна ----------

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun buildOverlay() {
        expandedW = dp(BASE_W_DP)
        expandedH = dp(BASE_H_DP)

        val layout = FrameLayout(this)

        // ===== Развёрнутая панель =====
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setBackgroundColor(0xF214101B.toInt())
        content.setPadding(dp(6f), dp(4f), dp(6f), dp(6f))

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
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
        val collapseBtn = textButton("▾") { collapseNow() }
        val closeBtn = textButton("✕") { stopSelf() }
        header.addView(collapseBtn)
        header.addView(closeBtn)
        content.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30f)))

        val bubble = TextView(this)
        bubble.setTextColor(0xFFFFFFFF.toInt())
        bubble.textSize = 14f
        bubble.setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        bubble.maxLines = 6
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

        // Ряд 1 — как в читалке: голос, роли, выбор.
        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        row1.gravity = Gravity.CENTER_VERTICAL
        row1.addView(textButton("▶ Голос") { toggleSpeak() })
        row1.addView(textButton("♀") { speakRole("female") })
        row1.addView(textButton("♂") { speakRole("male") })
        row1.addView(textButton("🎙") { speakRole("narrator") })
        row1.addView(textButton("Выбрать") { openVoiceChoice() })
        content.addView(row1, rowParams())

        // Ряд 2 — STT, буфер, копировать, словарь, закрыть.
        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.gravity = Gravity.CENTER_VERTICAL
        sttButton = textButton("🎙 STT") { toggleStt() }
        row2.addView(sttButton)
        row2.addView(textButton("📋") { fromClipboard() })
        row2.addView(textButton("Копировать") { copyBubble() })
        row2.addView(textButton("＋ Словарь") { addToDictionary() })
        row2.addView(textButton("Закрыть") { collapseNow() })
        content.addView(row2, rowParams())

        // Ряд 3 — область: режим, задать, рамка.
        val row3 = LinearLayout(this)
        row3.orientation = LinearLayout.HORIZONTAL
        row3.gravity = Gravity.CENTER_VERTICAL
        regionButton = textButton(regionModeLabel()) { cycleRegionMode() }
        row3.addView(regionButton)
        row3.addView(textButton("✏") { openSelector() })
        row3.addView(textButton("▦") { toggleFrame() })
        content.addView(row3, rowParams())

        // Ручной ввод (если в другом приложении нельзя копировать).
        val inputEdit = EditText(this)
        inputEdit.hint = "Текст для бабла…"
        inputEdit.setTextColor(0xFFFFFFFF.toInt())
        inputEdit.setHintTextColor(0xAAE0E0E0.toInt())
        inputEdit.textSize = 13f
        inputEdit.setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
        content.addView(inputEdit, rowParams())
        input = inputEdit
        val sayRow = LinearLayout(this)
        sayRow.orientation = LinearLayout.HORIZONTAL
        sayRow.addView(textButton("Сказать") {
            val t = input?.text?.toString()?.trim().orEmpty()
            if (t.isNotBlank()) speakText(t)
        })
        sayRow.addView(textButton("Очистить") {
            bubble?.text = ""
            input?.text?.clear()
        })
        content.addView(sayRow, rowParams())

        layout.addView(
            content,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        contentView = content

        // ===== Плавающая кнопка (стартовое состояние) =====
        val floatBtn = TextView(this)
        floatBtn.text = "💬"
        floatBtn.textSize = 24f
        floatBtn.gravity = Gravity.CENTER
        val oval = GradientDrawable()
        oval.shape = GradientDrawable.OVAL
        oval.setColor(0xE614101B.toInt())
        oval.setStroke(dp(2f), 0xFF4A7EAF.toInt())
        floatBtn.background = oval
        floatBtn.setOnTouchListener { _, event -> handleFloatTouch(event) }
        layout.addView(floatBtn, FrameLayout.LayoutParams(dp(BTN_DP), dp(BTN_DP), Gravity.CENTER))
        floatButton = floatBtn
        content.visibility = View.GONE

        val p = WindowManager.LayoutParams(
            dp(BTN_DP),
            dp(BTN_DP),
            overlayType(),
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
        inputEdit.setOnClickListener { makeFocusable(true) }
        inputEdit.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) makeFocusable(false) }

        root = layout
        isCollapsed = true
        runCatching { wm.addView(layout, p) }.onFailure {
            toast("Нет разрешения показывать поверх приложений")
            stopSelf()
        }
    }

    private fun rowParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(2f) }
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

    private var floatRawX = 0f
    private var floatRawY = 0f
    private var floatStartX = 0
    private var floatStartY = 0
    private var floatDown = 0L

    /** Перетаскивание плавающей кнопки + тап-разворачивание. */
    private fun handleFloatTouch(event: MotionEvent): Boolean {
        val lp = params ?: return false
        val r = root ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                floatRawX = event.rawX
                floatRawY = event.rawY
                floatStartX = lp.x
                floatStartY = lp.y
                floatDown = System.currentTimeMillis()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                lp.x = floatStartX + (event.rawX - floatRawX).toInt()
                lp.y = floatStartY + (event.rawY - floatRawY).toInt()
                clampToScreen(lp)
                runCatching { wm.updateViewLayout(r, lp) }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val quick = System.currentTimeMillis() - floatDown < 350
                val dx = (event.rawX - floatRawX).toInt()
                val dy = (event.rawY - floatRawY).toInt()
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
        lp.width = dp(BTN_DP)
        lp.height = dp(BTN_DP)
        contentView?.visibility = View.GONE
        floatButton?.visibility = View.VISIBLE
        clampToScreen(lp)
        root?.let { runCatching { wm.updateViewLayout(it, lp) } }
    }

    private fun expandNow() {
        val lp = params ?: return
        if (!isCollapsed) return
        isCollapsed = false
        lp.width = expandedW.coerceAtLeast(dp(BASE_W_DP))
        lp.height = expandedH.coerceAtLeast(dp(BASE_H_DP))
        contentView?.visibility = View.VISIBLE
        floatButton?.visibility = View.GONE
        clampToScreen(lp)
        root?.let { runCatching { wm.updateViewLayout(it, lp) } }
    }

    private fun makeFocusable(value: Boolean) {
        val lp = params ?: return
        lp.flags = if (value) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        runCatching { root?.let { wm.updateViewLayout(it, lp) } }
    }

    private fun clampToScreen(lp: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        lp.x = lp.x.coerceIn(0, (dm.widthPixels - lp.width).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (dm.heightPixels - lp.height).coerceAtLeast(0))
    }

    private fun toast(msg: String) {
        uiHandler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        runCatching { stt?.destroy() }
        stt = null
        runCatching { readEngine.stop() }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipListener?.let { runCatching { cm.removePrimaryClipChangedListener(it) } }
        closeSelector()
        frameRoot?.let { runCatching { wm.removeView(it) } }
        frameRoot = null
        runCatching { root?.let { wm.removeView(it) } }
        root = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Вьюха ручного выделения области: затемнение + тянущийся прямоугольник. */
    private inner class RegionDrawView(context: Context) : View(context) {
        private val rect = RectF()
        private var hasRect = false
        private val fill = Paint().apply {
            color = 0x334A7EAF
            style = Paint.Style.FILL
        }
        private val stroke = Paint().apply {
            color = 0xFF4A7EAF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        fun setRect(x1: Float, y1: Float, x2: Float, y2: Float) {
            // Координаты касания — экранные; окно селектора полноэкранное
            // с (0,0) в левом верхнем углу, совпадает.
            rect.set(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
            hasRect = true
            invalidate()
        }

        /** Нормализованные доли экрана 0..1 или null, если слишком маленькая. */
        fun normalized(wPx: Int, hPx: Int): Region? {
            if (!hasRect || rect.width() < 40 || rect.height() < 40) return null
            return Region(
                (rect.left / wPx).coerceIn(0f, 1f),
                (rect.top / hPx).coerceIn(0f, 1f),
                (rect.right / wPx).coerceIn(0f, 1f),
                (rect.bottom / hPx).coerceIn(0f, 1f),
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!hasRect) return
            canvas.drawRect(rect, fill)
            canvas.drawRect(rect, stroke)
        }
    }
}
