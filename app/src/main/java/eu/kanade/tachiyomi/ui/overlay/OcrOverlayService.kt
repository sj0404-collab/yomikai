package eu.kanade.tachiyomi.ui.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.cancel
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

        // Цвета меню — те же роли, что у Material в читалке: карточка,
        // круглая кнопка, кнопка в активном состоянии, текст и подпись.
        private const val CARD_BG = 0xF5232A3A.toInt()
        private const val FAB_BG = 0xF52E4A6B.toInt()
        private const val BTN_BG = 0xFF3A4A63.toInt()
        private const val PRIMARY_ACTIVE_BG = 0xFF4A7EAF.toInt()
        private const val ON_SURFACE = 0xFFF2F4F8.toInt()
        private const val ON_SURFACE_VARIANT = 0xFFB9C4D6.toInt()
        private const val CARD_RADIUS_DP = 14f
        private const val FAB_RADIUS_DP = 30f
        private const val BTN_RADIUS_DP = 18f
        private const val BTN_SIZE_DP = 36f

        /** Отступ от края экрана, куда прижимается панель. */
        private const val CORNER_MARGIN_DP = 8f

        /** Как часто проверять, не открыто ли наше собственное приложение. */
        private const val FOREGROUND_POLL_MS = 700L

        /** Действие: начать чтение рамки с уже полученным разрешением. */
        private const val ACTION_START_READING = "eu.kanade.tachiyomi.ocr.START_READING"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"

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

        /**
         * Начать чтение рамки с уже полученным разрешением на захват.
         *
         * Разрешение добывает [OcrCaptureActivity]: сервис не может
         * показать системный диалог и получить его результат.
         */
        fun startReading(context: Context, resultCode: Int, data: Intent) {
            startWith(
                context,
                intentFor(context)
                    .setAction(ACTION_START_READING)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_RESULT_DATA, data),
            )
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
    private var sttButton: TextView? = null
    private var regionButton: TextView? = null

    /** Подпись строки режима области: на круглой кнопке она не помещается. */
    private var regionRowLabel: TextView? = null
    /** Подпись строки чтения рамки — там же, где и состояние кнопки. */
    private var readRowLabel: TextView? = null

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

    // ---- Чтение рамки поверх чужого приложения ----
    private var capture: OverlayCaptureEngine? = null
    private var autoReader: OverlayAutoReader? = null
    private var readJob: kotlinx.coroutines.Job? = null
    private var readProjection: android.media.projection.MediaProjection? = null
    private var readButton: TextView? = null

    /** Прямоугольник зафиксированной области в пикселях экрана. */
    private fun fixedRegionRect(): Rect? {
        val region = parseRegion() ?: return null
        val dm = resources.displayMetrics
        return Rect(
            (region.l * dm.widthPixels).toInt(),
            (region.t * dm.heightPixels).toInt(),
            (region.r * dm.widthPixels).toInt(),
            (region.b * dm.heightPixels).toInt(),
        ).takeIf { it.width() > 0 && it.height() > 0 }
    }

    /**
     * Включает режим чтения.
     *
     * Захват экрана разрешается системным диалогом, поэтому его нельзя
     * начать из фонового сервиса молча: вызывающий обязан получить
     * [MediaProjection] из [MediaProjectionManager.getMediaProjection].
     */
    fun startReading(projection: android.media.projection.MediaProjection) {
        val rect = fixedRegionRect()
        if (rect == null) {
            toast("Сначала задайте область рамки")
            return
        }
        if (!OverlayGestureService.isEnabled()) {
            toast("Включите Службу доступности, чтобы листать самому")
        }
        stopReading()
        readProjection = projection
        val engine = OverlayCaptureEngine(applicationContext, projection)
        capture = engine
        val reader = OverlayAutoReader(applicationContext, engine)
        autoReader = reader
        readJob = reader.start(
            scope = serviceScope,
            region = rect,
            onPage = { text -> uiHandler.post { setBubble(text) } },
            onNote = { note ->
                uiHandler.post {
                    setBubble(note)
                    readButton?.text = if (isReading) "⏹" else "▶"
                    readButton?.setBackgroundColor((if (isReading) PRIMARY_ACTIVE_BG else BTN_BG).toInt())
                    readRowLabel?.text = if (isReading) "Стоп-чтение" else "Читать рамку"
                }
            },
        )
        toast("Чтение: ${reader.engineTitle()}")
    }

    fun stopReading() {
        readJob?.cancel()
        readJob = null
        runCatching { capture?.stop() }
        capture = null
        runCatching { readProjection?.stop() }
        readProjection = null
        autoReader = null
        runCatching { readEngine.stop() }
    }

    val isReading: Boolean get() = readJob?.isActive == true

    private val serviceScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate,
    )

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
            ACTION_START_READING -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (data == null) {
                    toast("Разрешение на захват не получено")
                } else {
                    val projection = runCatching {
                        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                            as android.media.projection.MediaProjectionManager
                        mpm.getMediaProjection(code, data)
                    }.getOrNull()
                    if (projection == null) {
                        toast("Не удалось начать захват экрана")
                    } else {
                        startReading(projection)
                    }
                }
            }
        }
        applyClipboardWatch()
        applyFrame()
        // Панель не должна висеть поверх наших собственных настроек и
        // читалки: раньше оверлей рисовался поверх приложения, открывшего
        // его, и полупрозрачная панель лежала на экране поверх текста.
        startForegroundWatcher()
        return START_STICKY
    }

    private val foregroundHandler = Handler(Looper.getMainLooper())
    private val foregroundCheck = object : Runnable {
        override fun run() {
            applyForegroundVisibility()
            foregroundHandler.postDelayed(this, FOREGROUND_POLL_MS)
        }
    }

    private fun startForegroundWatcher() {
        foregroundHandler.removeCallbacks(foregroundCheck)
        foregroundHandler.post(foregroundCheck)
    }

    /**
     * Прячет панель, пока на экране само приложение.
     *
     * Сверху окна с разрешением SYSTEM_ALERT_WINDOW не спрятать наше
     * собственное окно нельзя, но можно не рисовать панель поверх него:
     * иначе настройки оверлея открывались поверх самих себя.
     */
    private fun applyForegroundVisibility() {
        val rootView = root ?: return
        val foreground = runCatching {
            val usage = getSystemService("usage") as? UsageStatsManager
            val now = System.currentTimeMillis()
            val app = usage?.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 3_600_000, now)
                ?.maxByOrNull { it.lastTimeUsed }
            val inForeground = app != null &&
                app.lastTimeUsed > now - 2_000 &&
                packageManager.getLaunchIntentForPackage(app.packageName)
                    ?.component?.packageName == packageName
            inForeground
        }.getOrDefault(false)

        if (foreground) {
            if (rootView.visibility != View.GONE) {
                rootView.visibility = View.GONE
            }
        } else if (rootView.visibility != View.VISIBLE) {
            rootView.visibility = View.VISIBLE
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Тип mediaProjection обязателен: без него на Android 14+ система
            // не отдаст MediaProjection и захват рамки не запустится.
            startForeground(
                NOTIF_ID,
                b.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIF_ID, b.build())
        }
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
        uiHandler.post {
            sttButton?.text = if (on) "⏹" else "🎙"
            sttButton?.setBackgroundColor((if (on) PRIMARY_ACTIVE_BG else BTN_BG).toInt())
        }
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

    /**
     * Обновить подпись режима области.
     *
     * Надпись живёт в строке меню, а не на кнопке: на круглой кнопке она не
     * помещалась, и режим области переставал быть виден.
     */
    private fun updateRegionButton() {
        uiHandler.post { regionRowLabel?.text = regionModeLabel() }
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

        // ===== Развёрнутая панель: как меню читалки, а не «экран» =====
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        // Панель лежит поверх чужого приложения, и её фон обязан быть
        // непрозрачным: раньше он был 0xF2 (95%), и сквозь него отлично
        // читался текст игры — надписи панели сливались с чужими, и
        // разобрать, где чьё, было невозможно. Светлый текст даже 5%
        // просвечивания давали читаемую картинку.
        content.background = rounded(CARD_BG, CARD_RADIUS_DP)
        content.setPadding(dp(6f), dp(4f), dp(6f), dp(6f))

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val handle = TextView(this)
        handle.text = "≡ "
        handle.textSize = 14f
        handle.setTextColor(ON_SURFACE.toInt())
        header.addView(handle)
        val title = TextView(this)
        title.text = "OCR-бабл"
        title.setTextColor(ON_SURFACE_VARIANT.toInt())
        title.textSize = 12f
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val collapseBtn = textButton("▾") { collapseNow() }
        val closeBtn = textButton("✕") { stopSelf() }
        header.addView(collapseBtn)
        header.addView(closeBtn)
        content.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30f)))

        val bubble = TextView(this)
        bubble.setTextColor(ON_SURFACE.toInt())
        bubble.textSize = 14f
        bubble.setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        bubble.maxLines = 6
        val bubbleBg = GradientDrawable()
        bubbleBg.cornerRadius = dp(10f).toFloat()
        bubbleBg.setColor(0xFF232A3A.toInt())
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

        // Вертикальное меню — как плавающее меню читалки: подпись слева,
        // круглая кнопка справа. Раньше здесь были ряды прямоугольных кнопок
        // с текстом, и панель выглядела отдельным экраном поверх игры.
        val menu = LinearLayout(this)
        menu.orientation = LinearLayout.VERTICAL

        readButton = roundButton(if (isReading) "⏹" else "▶", active = isReading) {
            if (isReading) {
                stopReading()
                refreshReadButton()
            } else {
                OcrCaptureActivity.request(applicationContext)
            }
        }
        val readRow = menuRow(if (isReading) "Стоп-чтение" else "Читать рамку", readButton)
        readRowLabel = readRow.label()
        menu.addView(readRow, rowParams())

        menu.addView(
            menuRow("Голос", roundButton(if (isSpeaking) "⏹" else "▶", active = isSpeaking) { toggleSpeak() }),
            rowParams(),
        )

        menu.addView(
            menuRow(
                "Голос ♀ / ♂ · движок",
                roundButton("♀", active = voiceIsFemale()) { speakRole("female") },
                roundButton("♂", active = !voiceIsFemale()) { speakRole("male") },
                roundButton("⚙", active = false) { openVoiceChoice() },
            ),
            rowParams(),
        )

        sttButton = roundButton("🎙", active = false) { toggleStt() }
        menu.addView(
            menuRow(
                "STT · распознать речь",
                sttButton,
                roundButton("📋", active = false) { fromClipboard() },
            ),
            rowParams(),
        )

        regionButton = roundButton("▦", active = false) { cycleRegionMode() }
        val regionRow = menuRow(regionModeLabel(), roundButton("✏", active = false) { openSelector() }, regionButton)
        regionRowLabel = regionRow.label()
        menu.addView(regionRow, rowParams())

        menu.addView(
            menuRow(
                "Текст",
                roundButton("⧉", active = false) { copyBubble() },
                roundButton("＋", active = false) { addToDictionary() },
            ),
            rowParams(),
        )

        menu.addView(
            menuRow(
                "Панель",
                roundButton("▤", active = false) { toggleFrame() },
                roundButton("⚙", active = false) { openOverlaySettings() },
            ),
            rowParams(),
        )

        content.addView(menu, rowParams())

        // Ручной ввод (если в другом приложении нельзя копировать).
        val inputEdit = EditText(this)
        inputEdit.hint = "Текст для бабла…"
        inputEdit.setTextColor(ON_SURFACE.toInt())
        inputEdit.setHintTextColor(ON_SURFACE_VARIANT.toInt())
        inputEdit.textSize = 13f
        inputEdit.setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
        content.addView(inputEdit, rowParams())
        input = inputEdit
        val sayRow = LinearLayout(this)
        sayRow.orientation = LinearLayout.HORIZONTAL
        sayRow.addView(
            roundButton("▶", active = false) {
                val t = input?.text?.toString()?.trim().orEmpty()
                if (t.isNotBlank()) speakText(t)
            },
        )
        sayRow.addView(
            roundButton("✕", active = false) {
                bubble.text = ""
                input?.text?.clear()
            },
        )
        content.addView(sayRow, rowParams())

        layout.addView(
            content,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        contentView = content

        // ===== Плавающая кнопка (стартовое состояние) =====
        // Круглая, как FAB читалки: в углу, перетаскивается, тап —
        // развернуть меню. Прямоугольная кнопка с эмодзи читалась как
        // «штука поверх экрана», круглая — как часть управления.
        val floatBtn = TextView(this)
        floatBtn.text = "☰"
        floatBtn.textSize = 22f
        floatBtn.gravity = Gravity.CENTER
        floatBtn.background = rounded(FAB_BG, FAB_RADIUS_DP)
        floatBtn.setTextColor(ON_SURFACE.toInt())
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

    /**
     * Строка меню: подпись слева, круглые кнопки справа.
     *
     * Так устроено плавающее меню читалки, и оверлей повторяет его: читатель
     * уже знает, что это за вид, и не разбирается заново, что означает
     * кнопка без подписи.
     */
    private fun menuRow(label: String, vararg buttons: TextView): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val text = TextView(this)
        text.text = label
        text.textSize = 12f
        text.setTextColor(ON_SURFACE_VARIANT.toInt())
        row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.forEach { row.addView(it) }
        row.tag = text
        return row
    }

    /** Подпись строки: нужно, чтобы обновлять её, не пересобирая меню. */
    private fun LinearLayout.label(): TextView? = tag as? TextView

    /**
     * Круглая кнопка меню — как SmallFloatingActionButton читалки.
     *
     * @param active подсвеченное состояние: кнопка показывает, что действие
     *   уже идёт (чтение, озвучка, STT)
     */
    private fun roundButton(glyph: String, active: Boolean = false, onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.text = glyph
        b.textSize = 15f
        b.gravity = Gravity.CENTER
        b.setTextColor(ON_SURFACE.toInt())
        // Круг получается только при ширине, равной высоте: иначе скругление
        // рисуется по большой стороне и кнопка выходит прямоугольной.
        b.background = rounded(if (active) PRIMARY_ACTIVE_BG else BTN_BG, BTN_RADIUS_DP)
        b.setOnClickListener { onClick() }
        b.layoutParams = LinearLayout.LayoutParams(dp(BTN_SIZE_DP), dp(BTN_SIZE_DP))
        return b
    }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = dp(radiusDp).toFloat()
        d.setColor(color)
        return d
    }

    /** Кнопка чтения: подпись и состояние должны совпадать с тем, что идёт. */
    private fun refreshReadButton() {
        uiHandler.post {
            val on = isReading
            readButton?.text = if (on) "⏹" else "▶"
            readButton?.setBackgroundColor((if (on) PRIMARY_ACTIVE_BG else BTN_BG).toInt())
            readRowLabel?.text = if (on) "Стоп-чтение" else "Читать рамку"
        }
    }

    /** Женский ли голос выбран в настройках — от этого зависит вид кнопок. */
    private fun voiceIsFemale(): Boolean {
        val gender = runCatching { prefs.voicePresetGender().get() }.getOrNull()
        return gender != "male"
    }

    private fun openOverlaySettings() {
        // Экран настроек живёт внутри приложения, а сервис оверлея —
        // снаружи. Открываем приложение: оверлей сам спрячется, как только
        // заметит, что мы на переднем плане.
        runCatching {
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            launch?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            launch?.let { startActivity(it) }
        }.onFailure { toast("Открой настройки оверлея в приложении") }
    }

    private fun textButton(symbol: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = symbol
        b.textSize = 11f
        b.setAllCaps(false)
        b.setPadding(dp(5f), 0, dp(5f), 0)
        b.setBackgroundColor(0xFF2E4A6B.toInt())
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
                if (quick && (dx * dx + dy * dy) < 200) {
                    expandNow()
                } else {
                    // Отпустили после перетаскивания — прижать к углу, иначе
                    // кнопка остаётся висеть там, где палец оторвался, и её
                    // легко не заметить или случайно задеть.
                    clampToScreen(lp)
                    snapToCorner(lp)
                    runCatching { wm.updateViewLayout(r, lp) }
                }
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
        snapToCorner(lp)
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
        snapToCorner(lp)
        root?.let { runCatching { wm.updateViewLayout(it, lp) } }
    }

    /**
     * Прижать к краю экрана — как кнопка в углу у читалки.
     *
     * Раньше панель оставалась там, куда её перетащили, и у краёв экрана
     * наполовину уезжала за него. Теперь она всегда стоит у края: по
     * горизонтали — к ближайшему боку, по вертикали — к нижней трети, где
     * до кнопки не достаёт большой палец.
     */
    private fun snapToCorner(lp: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        val margin = dp(CORNER_MARGIN_DP)
        val toLeft = lp.x + lp.width / 2 < dm.widthPixels / 2
        lp.x = if (toLeft) margin else (dm.widthPixels - lp.width - margin)
        val bottomZone = dm.heightPixels * 2 / 3
        lp.y = if (lp.y < bottomZone) {
            margin
        } else {
            (dm.heightPixels - lp.height - margin).coerceAtLeast(margin)
        }
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
        stopReading()
        // Цикл чтения живёт в serviceScope: без отмены задача продолжала бы
        // держать сервис и захват после его смерти.
        serviceScope.cancel()
        foregroundHandler.removeCallbacks(foregroundCheck)
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
