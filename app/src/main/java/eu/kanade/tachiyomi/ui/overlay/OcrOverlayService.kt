package eu.kanade.tachiyomi.ui.overlay

import android.app.Activity
import android.app.Application
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
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import eu.kanade.presentation.reader.components.OcrControlFab
import eu.kanade.presentation.reader.components.OcrControlMenuCard
import eu.kanade.presentation.reader.components.OcrMenuAction
import eu.kanade.presentation.reader.components.OcrMenuActionButton
import eu.kanade.presentation.reader.components.OcrMenuRow
import eu.kanade.tachiyomi.data.tts.AutoReadEngine
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.cancel
import logcat.LogPriority
import mihon.domain.ocr.service.OcrPreferences
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.roundToInt

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

        // Вид панели теперь рисует общий компонент OcrControlMenuCard, поэтому
        // своих цветов и радиусов у оверлея больше нет: те же краски, что и в
        // плавающем меню читалки.

        /** Отступ от края окна до панели и до кнопки, dp. */
        private const val PANEL_PADDING_DP = 6f

        /** Зазор между карточкой меню и кнопкой, dp. */
        private const val PANEL_GAP_DP = 8f

        /**
         * Какую долю высоты экрана может занимать раскрытая панель.
         *
         * Раньше окно панели было фиксированной высоты (340 dp) и не умело
         * прокручиваться, поэтому нижние пункты просто обрезались, а поле ввода
         * не попадало на экран вообще. Теперь высота окна следует за содержимым,
         * но не должна закрывать экран целиком.
         */
        private const val MAX_PANEL_SCREEN_FRACTION = 0.72f

        /** Отступ от края экрана, куда прижимается панель. */
        private const val CORNER_MARGIN_DP = 8f

        /** Позиция окна оверлея: отдельные префы, чтобы не спорить с доменными. */
        private const val POSITION_PREFS = "yomikai_ocr_overlay"
        private const val KEY_POS_X = "pos_x"
        private const val KEY_POS_Y = "pos_y"

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

    // Панель нарисована общим компонентом OcrControlMenuCard, поэтому её вид
    // описывается состоянием, а не вьюхами: Compose перерисовывает только то,
    // что реально изменилось.
    private val menuExpanded = mutableStateOf(false)
    private val bubble = mutableStateOf("")
    private val inputText = mutableStateOf("")
    private val speakingState = mutableStateOf(false)
    private val readingState = mutableStateOf(false)
    private val sttState = mutableStateOf(false)
    private val regionLabelState = mutableStateOf("Область: авто")

    /** Показывается ли рамка зафиксированной области: нужно подписи кнопки. */
    private val frameVisibleState = mutableStateOf(false)

    private val readEngine by lazy { AutoReadEngine(applicationContext) }
    private var stt: GameSttManager? = null
    private var expandedH = 0
    private var ignoreNextClip = false
    private var lastClipText = ""

    /**
     * Compose живёт в окне WindowManager, а не в Activity, поэтому владельцев
     * приходится создавать вручную: без них не работали бы rememberSaveable
     * (нужен для поля ввода) и освобождение композиции при смерти сервиса.
     */
    private val panelLifecycleOwner = object : LifecycleOwner {
        // Реестр доступен наружу: currentState у интерфейса Lifecycle — только
        // для чтения, перевести жизненный цикл можно только через сам реестр.
        val registry: LifecycleRegistry = LifecycleRegistry(this)

        override val lifecycle: Lifecycle get() = registry
    }

    private val panelSavedStateOwner = object : SavedStateRegistryOwner {
        // SavedStateRegistryOwner — это ещё и LifecycleOwner, иначе объект не
        // реализует интерфейс и не скомпилируется.
        override val lifecycle: Lifecycle get() = panelLifecycleOwner.lifecycle

        // Публичного конструктора у SavedStateRegistry нет, только контроллер.
        // performAttach() допустим лишь пока жизненный цикл в INITIALIZED,
        // а он таким и остаётся до onCreate.
        private val controller = SavedStateRegistryController.create(this).also {
            it.performAttach()
        }

        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
    }

    private val panelViewModelOwner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

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
            // Не листаем молча: цикл чтения без Службы доступности провисит,
            // дожидаясь её включения, и читатель не понимает, чего не хватает.
            toast("Без Службы доступности листать нечем — включаю настройки")
            requestGestureService()
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
                    readingState.value = isReading
                }
            },
        )
        readingState.value = true
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
        readingState.value = false
        runCatching { readEngine.stop() }
    }

    val isReading: Boolean get() = readingState.value


    private val serviceScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate,
    )

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { panelLifecycleOwner.registry.currentState = Lifecycle.State.RESUMED }
        (applicationContext as? Application)?.registerActivityLifecycleCallbacks(ownActivityWatcher)
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
        // читалки: иначе полупрозрачная панель лежала на экране поверх текста.
        updatePanelVisibility()
        return START_STICKY
    }

    /** Свернуть панель в круглую кнопку у края экрана. */
    private fun collapseNow() {
        val lp = params ?: return
        if (!menuExpanded.value) return
        menuExpanded.value = false
        setWindowFocusable(false)
        lp.width = dp(BTN_DP)
        lp.height = dp(BTN_DP)
        clampToScreen(lp)
        snapToCorner(lp)
        savePanelPosition(lp)
        runCatching { root?.let { wm.updateViewLayout(it, lp) } }
    }

    /** Развернуть панель: ширина фиксированная, высоту досчитает [onPanelMeasured]. */
    private fun expandNow() {
        val lp = params ?: return
        if (menuExpanded.value) return
        menuExpanded.value = true
        // Раскрытая панель содержит поле ввода, а ввод в окне без
        // FLAG_NOT_FOCUSABLE невозможен: окно должно стать фокусируемым.
        // Свёрнутое — снова нефокусируемое, чтобы клавиатура уходила чужому
        // приложению, поверх которого оверлей висит.
        setWindowFocusable(true)
        lp.width = dp(BASE_W_DP)
        lp.height = expandedH.coerceAtMost(maxWindowHeightPx()).coerceAtLeast(dp(BASE_H_DP))
        clampToScreen(lp)
        snapToCorner(lp)
        runCatching { root?.let { wm.updateViewLayout(it, lp) } }
    }

    /**
     * Прячет панель, пока на экране само приложение.
     *
     * Сверху окна с разрешением SYSTEM_ALERT_WINDOW нельзя спрятать наше
     * собственное окно, но можно не рисовать панель поверх него: иначе
     * настройки оверлея открывались поверх самих себя.
     *
     * Раньше «мы на переднем плане» определялось через UsageStatsManager, и
     * без разрешения «usage access» (оно в приложение не запрашивается)
     * проверка всегда возвращала false — панель так и висела поверх наших
     * же настроек. Теперь это обычные колбэки жизненного цикла Activity:
     * ничего разрешать не нужно и ошибки быть не может.
     */
    private var ownActivitiesResumed = 0

    private val ownActivityWatcher = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (activity.packageName != packageName) return
            ownActivitiesResumed++
            updatePanelVisibility()
        }

        override fun onActivityPaused(activity: Activity) {
            if (activity.packageName != packageName) return
            ownActivitiesResumed = (ownActivitiesResumed - 1).coerceAtLeast(0)
            updatePanelVisibility()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private fun updatePanelVisibility() {
        val view = root ?: return
        val wanted = if (ownActivitiesResumed > 0) View.GONE else View.VISIBLE
        if (view.visibility != wanted) view.visibility = wanted
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

    private fun currentText(): String = bubble.value.trim()

    private fun setBubble(text: String) {
        uiHandler.post { bubble.value = text }
    }

    private fun speakText(text: String) {
        setBubble(text)
        runCatching { readEngine.speakSingle(text) }.onFailure {
            logcat(LogPriority.WARN, it) { "OcrOverlay TTS failed" }
            toast("Не удалось запустить озвучку")
        }
        speakingState.value = true
    }

    private fun toggleSpeak() {
        if (speakingState.value) {
            runCatching { readEngine.stop() }
            speakingState.value = false
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
        speakingState.value = true
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
        uiHandler.post { sttState.value = on }
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
        uiHandler.post { regionLabelState.value = regionModeLabel() }
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
        frameVisibleState.value = show
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
        expandedH = dp(BASE_H_DP)
        regionLabelState.value = regionModeLabel()

        val layout = FrameLayout(this)

        // Панель рисуется тем же компонентом, что и плавающее меню читалки:
        // одинаковая карточка, одинаковые круглые кнопки, одинаковый FAB.
        // Compose внутри окна WindowManager требует владельцев жизненного
        // цикла — без них не работал бы rememberSaveable у поля ввода.
        val compose = ComposeView(this).apply {
            setViewTreeLifecycleOwner(panelLifecycleOwner)
            setViewTreeSavedStateRegistryOwner(panelSavedStateOwner)
            setViewTreeViewModelStoreOwner(panelViewModelOwner)
            setComposeContent { OverlayPanel() }
        }
        layout.addView(
            compose,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

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
        // Позиция сохраняется: раньше после перезапуска сервиса панель
        // возвращалась в точку (20, 140) dp и читатель её не находил.
        val positionPrefs = getSharedPreferences(POSITION_PREFS, Context.MODE_PRIVATE)
        p.x = positionPrefs.getInt(KEY_POS_X, dp(20f))
        p.y = positionPrefs.getInt(KEY_POS_Y, dp(140f))
        params = p

        root = layout
        runCatching { wm.addView(layout, p) }.onFailure {
            toast("Нет разрешения показывать поверх приложений")
            stopSelf()
        }
    }

    // ---------- Панель: тот же вид, что меню читалки ----------

    /**
     * Панель оверлея.
     *
     * Свёрнута — круглая кнопка у края экрана, как в читалке. Раскрыта —
     * карточка пунктов над кнопкой; последняя строка убирает меню в кнопку
     * («скрыть») и выключает оверлей («закрыть»).
     */
    @Composable
    private fun OverlayPanel() {
        val expanded = menuExpanded.value
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Свёрнутая панель — это ровно кнопка 56dp в окне 60dp, любой
                // дополнительный отступ её бы обрезал.
                .padding(if (expanded) PANEL_PADDING_DP.dp else 0.dp),
            verticalArrangement = if (expanded) Arrangement.Bottom else Arrangement.Center,
            horizontalAlignment = Alignment.End,
        ) {
            if (expanded) {
                OcrControlMenuCard(
                    rows = menuRows(),
                    // Ширину окна держим фиксированной, а по высоте следим за
                    // содержимым: иначе карточка измеряется по ширине кнопки и
                    // высота неизвестна заранее.
                    modifier = Modifier.onSizeChanged { onPanelMeasured(it.height) },
                    maxHeight = maxPanelCardHeight().dp,
                    status = panelStatus(),
                    footer = "yomikai " + eu.kanade.tachiyomi.AppInfo.getVersionName(),
                    extraContent = { panelInput() },
                )
                Spacer(Modifier.height(PANEL_GAP_DP.dp))
            }
            OcrControlFab(
                expanded = expanded,
                onToggle = { if (expanded) collapseNow() else expandNow() },
                onDrag = ::dragPanelBy,
                onDragEnd = ::settlePanel,
                contentDescription = if (expanded) "Скрыть меню оверлея" else "Меню оверлея",
            )
        }
    }

    /** Высота окна с раскрытой панелью, px. */
    private fun maxWindowHeightPx(): Int =
        (resources.displayMetrics.heightPixels * MAX_PANEL_SCREEN_FRACTION).toInt()

    /** Высота, доступная карточке: из окна вычитаем кнопку и отступы, dp. */
    private fun maxPanelCardHeight(): Int {
        val overheadPx = dp(BTN_DP) + dp(PANEL_GAP_DP) + dp(PANEL_PADDING_DP) * 2
        val availablePx = (maxWindowHeightPx() - overheadPx).coerceAtLeast(dp(120f))
        // Делить на настоящую плотность экрана: dp(1f) округляется до целого и
        // на экране с density 2.75 дало бы завышенный лимит высоты.
        return (availablePx / resources.displayMetrics.density).toInt()
    }

    /**
     * Высота окна следует за высотой карточки.
     *
     * Раньше окно было фиксированной высоты 340dp без прокрутки, и нижние
     * пункты меню вместе с полем ввода просто обрезались.
     */
    private fun onPanelMeasured(cardHeightPx: Int) {
        val lp = params ?: return
        if (!menuExpanded.value || cardHeightPx <= 0) return
        val wanted = (cardHeightPx + dp(BTN_DP) + dp(PANEL_GAP_DP) + dp(PANEL_PADDING_DP) * 2)
            .coerceAtMost(maxWindowHeightPx())
        // Запоминаем: после сворачивания окно должно вернуться к этой же
        // высоте, а не прыгать на 340dp, пока карточка не измерится заново.
        expandedH = wanted
        if (lp.height == wanted) return
        lp.height = wanted
        clampToScreen(lp)
        runCatching { root?.let { wm.updateViewLayout(it, lp) } }
    }

    private fun panelStatus(): String = when {
        readingState.value && !OverlayGestureService.isEnabled() ->
            "Включите Службу доступности — жду, чтобы листать"
        readingState.value -> "Читаю рамку…"
        bubble.value.isNotBlank() -> bubble.value
        else -> "Текст пуст — вставьте из буфера (📋) или включите STT"
    }

    /** Пункты меню оверлея: те же действия, что в читалке, плюс свои. */
    private fun menuRows(): List<OcrMenuRow> {
        val reading = readingState.value
        val speaking = speakingState.value
        val listening = sttState.value
        return listOf(
            OcrMenuRow(
                label = if (reading) "Стоп-чтение" else "Читать рамку",
                action = OcrMenuAction(
                    icon = if (reading) Icons.Outlined.StopCircle else Icons.Outlined.PlayArrow,
                    active = reading,
                    contentDescription = if (reading) "Остановить чтение рамки" else "Читать рамку",
                    onClick = ::toggleFrameReading,
                ),
            ),
            OcrMenuRow(
                label = "Голос",
                action = OcrMenuAction(
                    icon = if (speaking) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    active = speaking,
                    contentDescription = if (speaking) "Остановить озвучку" else "Озвучить текст",
                    onClick = ::toggleSpeak,
                ),
            ),
            OcrMenuRow(
                label = "Голос ♀ / ♂ · движок",
                action = OcrMenuAction(
                    glyph = "♀",
                    active = voiceIsFemale(),
                    contentDescription = "Озвучить женским голосом",
                    onClick = { speakRole("female") },
                ),
                secondary = OcrMenuAction(
                    glyph = "♂",
                    active = !voiceIsFemale(),
                    contentDescription = "Озвучить мужским голосом",
                    onClick = { speakRole("male") },
                ),
                tertiary = OcrMenuAction(
                    glyph = "⚙",
                    contentDescription = "Настройки синтеза речи",
                    onClick = ::openVoiceChoice,
                ),
            ),
            OcrMenuRow(
                label = "STT · распознать речь",
                action = OcrMenuAction(
                    glyph = if (listening) "⏹" else "🎙",
                    active = listening,
                    contentDescription = if (listening) {
                        "Остановить распознавание речи"
                    } else {
                        "Распознать речь с микрофона"
                    },
                    onClick = { toggleStt() },
                ),
                secondary = OcrMenuAction(
                    glyph = "📋",
                    contentDescription = "Взять текст из буфера обмена",
                    onClick = { fromClipboard() },
                ),
            ),
            OcrMenuRow(
                label = regionLabelState.value,
                action = OcrMenuAction(
                    glyph = "✏",
                    contentDescription = "Выделить область вручную",
                    onClick = ::openSelector,
                ),
                secondary = OcrMenuAction(
                    glyph = "▦",
                    contentDescription = "Сменить режим области",
                    onClick = ::cycleRegionMode,
                ),
            ),
            OcrMenuRow(
                label = "Текст",
                action = OcrMenuAction(
                    glyph = "⧉",
                    contentDescription = "Скопировать текст",
                    onClick = ::copyBubble,
                ),
                secondary = OcrMenuAction(
                    glyph = "＋",
                    contentDescription = "Скопировать и открыть словарь",
                    onClick = ::addToDictionary,
                ),
            ),
            OcrMenuRow(
                label = "Панель",
                action = OcrMenuAction(
                    glyph = "▤",
                    active = frameVisibleState.value,
                    contentDescription = "Показать или скрыть рамку области",
                    onClick = ::toggleFrame,
                ),
                secondary = OcrMenuAction(
                    glyph = "⚙",
                    contentDescription = "Настройки оверлея",
                    onClick = ::openOverlaySettings,
                ),
            ),
            OcrMenuRow(
                label = "Скрыть / закрыть",
                action = OcrMenuAction(
                    icon = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = "Скрыть меню, оставить кнопку",
                    onClick = ::collapseNow,
                ),
                secondary = OcrMenuAction(
                    icon = Icons.Outlined.Close,
                    contentDescription = "Закрыть оверлей",
                    onClick = { stopSelf() },
                ),
            ),
        )
    }

    /**
     * Ручной ввод: в сторонней игре скопировать текст нечем, а положить его
     * в буфер можно и отсюда.
     */
    @Composable
    private fun panelInput() {
        OutlinedTextField(
            value = inputText.value,
            onValueChange = { inputText.value = it },
            label = { Text("Текст для бабла") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state -> uiHandler.post { setWindowFocusable(state.isFocused) } },
        )
        Spacer(Modifier.height(PANEL_GAP_DP.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp)) {
            OcrMenuActionButton(
                OcrMenuAction(
                    icon = Icons.Outlined.PlayArrow,
                    contentDescription = "Озвучить введённый текст",
                    onClick = {
                        val typed = inputText.value.trim()
                        if (typed.isNotBlank()) speakText(typed)
                    },
                ),
            )
            OcrMenuActionButton(
                OcrMenuAction(
                    icon = Icons.Outlined.Close,
                    contentDescription = "Очистить введённый текст",
                    onClick = {
                        inputText.value = ""
                        bubble.value = ""
                    },
                ),
            )
        }
    }

    /**
     * Чтение рамки: без Службы доступности листать нечем, поэтому сразу
     * отводим читателя в настройки, а не ограничиваемся тостом.
     */
    private fun toggleFrameReading() {
        if (isReading) {
            stopReading()
            return
        }
        if (!OverlayGestureService.isEnabled()) {
            toast("Без Службы доступности листать нечем — включаю настройки")
            requestGestureService()
            return
        }
        OcrCaptureActivity.request(applicationContext)
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

    private fun dragPanelBy(delta: Offset) {
        val lp = params ?: return
        lp.x += delta.x.roundToInt()
        lp.y += delta.y.roundToInt()
        clampToScreen(lp)
        runCatching { root?.let { wm.updateViewLayout(it, lp) } }
    }

    /**
     * Отпустили кнопку: прижимаем панель к краю и запоминаем место.
     *
     * Раньше кнопка оставалась там, где палец оторвался, и её легко было
     * не заметить или задеть.
     */
    private fun settlePanel() {
        val lp = params ?: return
        clampToScreen(lp)
        snapToCorner(lp)
        savePanelPosition(lp)
        runCatching { root?.let { wm.updateViewLayout(it, lp) } }
    }

    private fun savePanelPosition(lp: WindowManager.LayoutParams) {
        getSharedPreferences(POSITION_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_POS_X, lp.x)
            .putInt(KEY_POS_Y, lp.y)
            .apply()
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

    /**
     * Окну нужно фокусируемое состояние только пока в фокусе поле ввода:
     * иначе поле не раскроет клавиатуру. Пересоздавать флаги на каждый
     * щелчок нельзя — система зря дёргает окно.
     */
    private fun setWindowFocusable(value: Boolean) {
        if (windowFocusable == value) return
        windowFocusable = value
        makeFocusable(value)
    }

    private var windowFocusable = false


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
        (applicationContext as? Application)?.unregisterActivityLifecycleCallbacks(ownActivityWatcher)
        // Композиция панели держит ViewTreeLifecycleOwner: без перевода
        // жизненного цикла в DESTROYED она не освободится.
        runCatching { panelLifecycleOwner.registry.currentState = Lifecycle.State.DESTROYED }
        panelViewModelOwner.viewModelStore.clear()
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
