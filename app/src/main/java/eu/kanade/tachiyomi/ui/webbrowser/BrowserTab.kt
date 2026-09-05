package eu.kanade.tachiyomi.ui.webbrowser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.tachiyomi.data.tts.AutoReadEngine
import eu.kanade.tachiyomi.data.tts.TtsSpeaker
import kotlinx.coroutines.delay
import eu.kanade.tachiyomi.util.system.toast
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.runtime.rememberCoroutineScope
import eu.kanade.tachiyomi.util.ocr.toOcrImage
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.background
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import mihon.data.ocr.OcrNotificationManager
import kotlin.math.roundToInt

/**
 * Браузер с ПЕРСИСТЕНТНЫМ WebView: единственный экземпляр живёт, пока живо
 * приложение, и переиспользуется при каждом входе на вкладку. Переключение
 * на другие вкладки больше НЕ перезапускает страницу и не сбрасывает
 * позицию — раньше onRelease вызывал destroy() и всё начиналось заново.
 */
data object BrowserTab : Tab {

    private const val HOME_URL = "https://mangabuff.ru"

    @SuppressLint("StaticFieldLeak") // applicationContext — утечки нет
    private var sharedWebView: WebView? = null
    /** v1.9.39: фуллскрин-видео (onShowCustomView) и long-press скан картинки. */
    private var customView: android.view.View? = null
    private var customViewCallback: android.webkit.WebChromeClient.CustomViewCallback? = null
    private var hostActivity: android.app.Activity? = null
    private var imageLongPress: (() -> Unit)? = null

    private var urlState = mutableStateOf(HOME_URL)
    private var canGoBackState = mutableStateOf(false)
    private var progressState = mutableFloatStateOf(1f)
    private var autoscrollActive = mutableStateOf(false)
    private var autoscrollSpeed = mutableFloatStateOf(2f)

    /** Режим авточтения: скан кадра → озвучка → скролл на кадр → повтор. */
    private var autoReadActive = mutableStateOf(false)
    private var autoReadEngine: AutoReadEngine? = null

    /** Захват ТОЛЬКО содержимого WebView — плавающие кнопки и оверлеи
     *  приложения в кадр физически не попадают. */
    private fun captureWebView(): android.graphics.Bitmap? {
        val wv = sharedWebView ?: return null
        if (wv.width <= 0 || wv.height <= 0) return null
        return runCatching {
            val bmp = android.graphics.Bitmap.createBitmap(
                wv.width,
                wv.height,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
            val canvas = android.graphics.Canvas(bmp)
            // Рисуем с учётом текущего скролла: видимый кадр
            canvas.translate(-wv.scrollX.toFloat(), -wv.scrollY.toFloat())
            wv.draw(canvas)
            bmp
        }.getOrNull()
    }

    /**
     * Зона СТРАНИЦЫ КНИГИ во вьюпорте (доли 0..1): JS находит все крупные
     * <img>/<canvas> (страницы манги — читалки сайтов рисуют их именно так),
     * объединяет видимые прямоугольники и возвращает их границы. Шапки,
     * меню, комментарии и прочий интерфейс сайта в зону не попадают — OCR
     * получает уже обрезанный кадр.
     */
    private suspend fun detectBookZone(): android.graphics.RectF? {
        val wv = sharedWebView ?: return null
        val js = """
            (function() {
                var vh = window.innerHeight, vw = window.innerWidth;
                var minArea = vw * vh * 0.10; // картинка занимает >=10% экрана
                var top = vh, bottom = 0, left = vw, right = 0, found = false;
                var nodes = document.querySelectorAll('img, canvas');
                for (var i = 0; i < nodes.length; i++) {
                    var r = nodes[i].getBoundingClientRect();
                    var visW = Math.min(r.right, vw) - Math.max(r.left, 0);
                    var visH = Math.min(r.bottom, vh) - Math.max(r.top, 0);
                    if (visW <= 0 || visH <= 0) continue;
                    if (visW * visH < minArea) continue;
                    found = true;
                    top = Math.min(top, Math.max(r.top, 0));
                    bottom = Math.max(bottom, Math.min(r.bottom, vh));
                    left = Math.min(left, Math.max(r.left, 0));
                    right = Math.max(right, Math.min(r.right, vw));
                }
                if (!found) return "";
                return (left / vw) + "," + (top / vh) + "," + (right / vw) + "," + (bottom / vh);
            })()
        """.trimIndent()
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            try {
                wv.post {
                    wv.evaluateJavascript(js) { raw ->
                        val body = raw?.trim('"').orEmpty()
                        val parts = body.split(',').mapNotNull { it.toFloatOrNull() }
                        val rect = if (parts.size == 4 && parts[3] > parts[1] && parts[2] > parts[0]) {
                            android.graphics.RectF(parts[0], parts[1], parts[2], parts[3])
                        } else null
                        if (cont.isActive) cont.resume(rect) {}
                    }
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null) {}
            }
        }
    }

    /** Кадр, обрезанный до зоны книги (если зона найдена). */
    private fun cropToZone(src: android.graphics.Bitmap, zone: android.graphics.RectF?): android.graphics.Bitmap {
        if (zone == null) return src
        val l = (zone.left * src.width).toInt().coerceIn(0, src.width - 1)
        val t = (zone.top * src.height).toInt().coerceIn(0, src.height - 1)
        val r = (zone.right * src.width).toInt().coerceIn(l + 1, src.width)
        val b = (zone.bottom * src.height).toInt().coerceIn(t + 1, src.height)
        if (r - l < 64 || b - t < 64) return src
        val cropped = android.graphics.Bitmap.createBitmap(src, l, t, r - l, b - t)
        if (cropped !== src && !src.isRecycled) src.recycle()
        return cropped
    }

    /** Позиция скролла страницы: окно + внутренний скролл-контейнер. */
    private suspend fun readScrollPos(wv: WebView): Float =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val js = "(function(){var el=document.elementFromPoint(innerWidth/2,innerHeight*0.65);" +
                "while(el&&!(el.scrollHeight>el.clientHeight+4))el=el.parentElement;" +
                "return (el?el.scrollTop:0)+window.scrollY;})()"
            try {
                wv.post { wv.evaluateJavascript(js) { r -> if (cont.isActive) cont.resume(r?.toFloatOrNull() ?: 0f) {} } }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(0f) {}
            }
        }

    /** Скролл на dy px: сайт может скроллиться внутренним контейнером. */
    private suspend fun scrollJs(wv: WebView, dy: Int) =
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            val js = "(function(dy){function sc(el){return el&&el.scrollHeight>el.clientHeight+4;}" +
                "var el=document.elementFromPoint(innerWidth/2,innerHeight*0.65);" +
                "while(el&&!sc(el))el=el.parentElement;" +
                "if(el){el.scrollTop+=dy;}else{window.scrollBy(0,dy);}return 1;})($dy)"
            try {
                wv.post { wv.evaluateJavascript(js) { if (cont.isActive) cont.resume(Unit) } }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(Unit)
            }
        }

    override val options: TabOptions
        @Composable
        get() {
            return TabOptions(
                index = 7u,
                title = "Браузер",
                icon = rememberVectorPainter(Icons.Outlined.Language),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun obtainWebView(context: Context): WebView {
        sharedWebView?.let { return it }

        val webView = WebView(context.applicationContext).apply {
            setBackgroundColor(Color.parseColor("#13141F"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            // Музыка/видео со страницы стартуют без тапа и играют в фоне.
            settings.mediaPlaybackRequiresUserGesture = false
            // Сохранённые HTML веб-библиотеки лежат в нашем внешнем каталоге:
            // с API 30 allowFileAccess по умолчанию false → ERR_ACCESS_DENIED.
            @Suppress("DEPRECATION")
            runCatching { settings.allowFileAccess = true }

            // v1.9.40: long-press в любом месте страницы = выбор области скана
            // (раньше срабатывало только на картинках, а на тексте WebView
            // открывал системное выделение текста с меню «Копировать»).
            setOnLongClickListener {
                imageLongPress?.invoke()
                true
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progressState.floatValue = newProgress / 100f
                }

                // Видео во весь экран: кастомная вьюха поверх контента активности.
                override fun onShowCustomView(view: android.view.View, callback: CustomViewCallback) {
                    val root = hostActivity?.findViewById<android.widget.FrameLayout>(android.R.id.content)
                    if (root == null) {
                        callback.onCustomViewHidden()
                        return
                    }
                    customView?.let { root.removeView(it) }
                    customView = view
                    customViewCallback = callback
                    root.addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
                }

                override fun onHideCustomView() {
                    val root = hostActivity?.findViewById<android.widget.FrameLayout>(android.R.id.content)
                    customView?.let { root?.removeView(it) }
                    customView = null
                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    canGoBackState.value = view.canGoBack()
                    runCatching { WebStore.addHistory(context, url ?: "", view.title ?: "") }
                    runCatching { WebStore.touchTab(context, url ?: "", view.title ?: "") }
                    // v1.9.40: системное выделение текста мешало выбору области:
                    // long-press открывал меню «Копировать/Поделиться» вместо скана.
                    runCatching {
                        view.evaluateJavascript(
                            "(function(){if(!document.getElementById('yk-noselect')){var s=document.createElement('style');s.id='yk-noselect';s.textContent='*{-webkit-user-select:none!important;user-select:none!important}';document.documentElement.appendChild(s);}})()",
                            null,
                        )
                    }
                    url?.let { urlState.value = it }
                }
            }
        }
        sharedWebView = webView
        // Загрузка стартовой страницы — ПОСЛЕ первого кадра вкладки:
        // раньше loadUrl в момент создания фризил переход (инициализация
        // сети/рендера WebView блокировала главный поток при открытии).
        webView.post { webView.loadUrl(HOME_URL) }
        return webView
    }

    @Composable
    override fun Content() {
        var urlBar by urlState
        val canGoBack by canGoBackState
        val progress by progressState

        BackHandler(enabled = canGoBack) {
            sharedWebView?.goBack()
        }

        var menuOpen by remember { mutableStateOf(false) }
        val ctorContext = androidx.compose.ui.platform.LocalContext.current
        val ctorVersion by eu.kanade.tachiyomi.data.ui.UiConstructorStore.version.collectAsState()
        val ctorUiCtx = androidx.compose.ui.platform.LocalContext.current
        val ctorUiPrefs = ctorUiCtx.getSharedPreferences("yomikai_ctor_ui", android.content.Context.MODE_PRIVATE)
        var ctorExpanded by remember { mutableStateOf(ctorUiPrefs.getBoolean("menu_ctor_expanded", true)) }
        val userActs = remember(ctorVersion) { eu.kanade.tachiyomi.data.ui.UiActionRegistry.forPlacement(ctorUiCtx, mihon.data.ui.UiPlacement.FLOATING_MENU) }
        val hiddenM = remember(ctorVersion) {
            eu.kanade.tachiyomi.data.ui.UiConstructorStore.moduleHidden(ctorContext)
        }
        var isAuto by autoscrollActive
        var speed by autoscrollSpeed
        var fabX by remember { mutableFloatStateOf(0f) }
        var fabY by remember { mutableFloatStateOf(0f) }

        // Автоскролл страницы: плавно, скорость 1..10
        LaunchedEffect(isAuto, speed) {
            while (isAuto) {
                sharedWebView?.scrollBy(0, (speed * 3).roundToInt())
                delay(16)
            }
        }

        var isAutoRead by autoReadActive
        val ctx = androidx.compose.ui.platform.LocalContext.current
        WebStore.load(ctx)
        hostActivity = ctx as? android.app.Activity
        var moreOpen by remember { mutableStateOf(false) }
        var libOpen by remember { mutableStateOf(false) }
        var marksOpen by remember { mutableStateOf(false) }
        var histOpen by remember { mutableStateOf(false) }
        var tabsOpen by remember { mutableStateOf(false) }
        var cacheOpen by remember { mutableStateOf(false) }
        var webBookmarked by remember { mutableStateOf(false) }
        val webPages by WebStore.pages.collectAsState()
        val webMarks by WebStore.marks.collectAsState()
        val webHist by WebStore.history.collectAsState()
        val webTabs by WebStore.tabs.collectAsState()
        androidx.compose.runtime.LaunchedEffect(urlBar) {
            webBookmarked = WebStore.isBookmarked(urlBar)
        }
        fun loadUrlInput(raw: String) {
            val input = raw.trim()
            val target = when {
                input.startsWith("http://") || input.startsWith("https://") -> input
                input.contains('.') && !input.contains(' ') -> "https://$input"
                else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(input, "UTF-8")
            }
            WebStore.addTab(ctx, target, target)
            sharedWebView?.loadUrl(target)
        }
        fun saveHtmlPage() {
            val wv = sharedWebView ?: return
            val currentUrl = wv.url ?: urlBar
            val currentTitle = wv.title ?: currentUrl
            wv.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { json ->
                val ok = runCatching {
                    val html = org.json.JSONTokener(json).nextValue() as? String
                    if (html.isNullOrBlank()) false else WebStore.savePage(ctx, currentUrl, currentTitle, html) != null
                }.getOrDefault(false)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    ctx.toast(if (ok) "Страница сохранена в веб-библиотеку" else "Не удалось сохранить страницу")
                }
            }
        }
        var ocrBusy by remember { mutableStateOf(false) }
        var ocrText by remember { mutableStateOf<String?>(null) }
        var ocrJob by remember { mutableStateOf<Job?>(null) }
        // v1.9.40: замороженный кадр для выбора области скана пальцем
        var areaFrame by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
        val pip by WebStore.pipMode
        var immersive by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        var saving by remember { mutableStateOf(false) }
        var saveMsg by remember { mutableStateOf<String?>(null) }

        fun manualScan() {
            imageLongPress = { manualScan() }
            ocrJob?.cancel()
            // v1.9.40: НЕ автоскан всего экрана (с шапкой и рекламой) и НЕ только
            // картинки: пользователь сам рисует область пальцем на замороженном кадре.
            val raw = captureWebView()
            if (raw == null) {
                ctx.toast("Не удалось захватить кадр для скана")
                return
            }
            areaFrame = raw
        }

        /** OCR произвольной области кадра (прямоугольник в пикселях bitmap). */
        fun runOcrCrop(raw: android.graphics.Bitmap, rect: android.graphics.RectF) {
            ocrJob?.cancel()
            ocrJob = scope.launch {
                ocrBusy = true
                ocrText = null
                try {
                    val l = rect.left.toInt().coerceIn(0, raw.width - 1)
                    val t = rect.top.toInt().coerceIn(0, raw.height - 1)
                    val r = rect.right.toInt().coerceIn(l + 1, raw.width)
                    val b = rect.bottom.toInt().coerceIn(t + 1, raw.height)
                    val cropped = if (r - l < 40 || b - t < 40) {
                        raw
                    } else {
                        android.graphics.Bitmap.createBitmap(raw, l, t, r - l, b - t)
                    }
                    val prefsN = Injekt.get<mihon.domain.ocr.service.OcrPreferences>()
                    val bmp = if (cropped.width < 1200) {
                        val k = minOf(3f, 1200f / cropped.width)
                        android.graphics.Bitmap.createScaledBitmap(
                            cropped,
                            (cropped.width * k).toInt(),
                            (cropped.height * k).toInt(),
                            true,
                        ).also { if (it !== cropped) cropped.recycle() }
                    } else {
                        cropped
                    }
                    val text = withTimeout(90_000) {
                        Injekt.get<mihon.domain.ocr.interactor.OcrProcessor>().getText(bmp.toOcrImage())
                    }
                    if (bmp !== raw) bmp.recycle()
                    raw.recycle()
                    ocrText = text
                    if (prefsN.ocrToNotification().get() && text.isNotBlank()) {
                        OcrNotificationManager.show(ctx.applicationContext, text)
                    }
                    if (prefsN.ocrStreamingHighlight().get() && text.isNotBlank()) {
                        mihon.data.ocr.OcrHistoryStore.addStreamingScan(text.take(120), page = urlBar.take(40))
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ocrText = ""
                } finally {
                    ocrBusy = false
                }
            }
        }

        fun saveAsLocal() {
            if (saving) return
            val wv = sharedWebView ?: return
            scope.launch {
                saving = true
                saveMsg = null
                try {
                    val url = urlBar
                    val result = WebLocalSaver.saveAsLocalChapter(
                        context = ctx.applicationContext,
                        webView = wv,
                        url = url,
                        title = wv.title,
                    ) { /* progress */ }
                    saveMsg = if (result != null) "Сохранено: ${result.name} (папка сайта: ${result.parentFile?.parentFile?.name})" else "Не удалось сохранить"
                    ctx.toast(saveMsg ?: "")
                } catch (e: Exception) {
                    saveMsg = "Ошибка: ${e.message}"
                    ctx.toast(saveMsg ?: "")
                } finally {
                    saving = false
                    kotlinx.coroutines.delay(3000)
                    saveMsg = null
                }
            }
        }

        // Полный экран «как в читалке»: прячем системные бары
        LaunchedEffect(immersive) {
            val act = ctx as? android.app.Activity ?: return@LaunchedEffect
            val controller = androidx.core.view.WindowCompat.getInsetsController(act.window, act.window.decorView)
            if (immersive) {
                controller.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }

        val readEngine = remember { autoReadEngine ?: AutoReadEngine(ctx.applicationContext).also { autoReadEngine = it } }
        val currentRegion by readEngine.currentRegion.collectAsState()

        // Цикл авточтения:
        // • кадр = ПОЛНЫЙ видимый вьюпорт;
        // • скролл на 60% кадра => соседние кадры перекрываются на 40%,
        //   текст на стыке гарантированно попадает в один из кадров целиком
        //   (дубликаты отсекает нечёткая история движка);
        // • скролл ПЛАВНЫЙ (тот же механизм, что «Автопрокрутка»: мелкие шаги
        //   каждые 16мс) и идёт ТОЛЬКО после полного прочтения кадра;
        // • пустые кадры (нет нового текста) проходятся сразу, без задержек.
        LaunchedEffect(isAutoRead) {
            if (!isAutoRead) { readEngine.stop(); return@LaunchedEffect }
            readEngine.clearHistory()
            var stuckCounter = 0
            while (isAutoRead) {
                val wv = sharedWebView
                val raw = captureWebView()
                if (wv == null || raw == null) { delay(500); continue }

                // Только страница книги: зона крупных картинок, без UI сайта
                val zone = detectBookZone()
                readEngine.highlightZone = zone
                val bmp = cropToZone(raw, zone)

                var finished = false
                readEngine.readFrame(bmp, chapterId = -1L, pageIndex = wv.scrollY) { finished = true }
                while (!finished && isAutoRead) delay(120)
                if (!isAutoRead) break

                val prefs = Injekt.get<mihon.domain.ocr.service.OcrPreferences>()
                if (!prefs.autoReadAutoAdvance().get()) { isAutoRead = false; break }

                // v1.9.40: УМНЫЙ скролл на 60% высоты кадра (перекрытие 40%):
                // сайт может скроллиться внутренним контейнером (window.scrollY
                // стоит на месте) — раньше авточтение «не прокручивало» именно так.
                val posBefore = readScrollPos(wv)
                val step = (wv.height * 0.6f).roundToInt().coerceAtLeast(1)
                var scrolled = 0
                while (scrolled < step && isAutoRead) {
                    val d = minOf(24, step - scrolled) // мелкий шаг = плавно, без рывков
                    scrollJs(wv, d)
                    scrolled += d
                    delay(28)
                }
                delay(150)
                var posAfter = readScrollPos(wv)
                if (posAfter - posBefore <= 0f) {
                    // JS не сдвинул (нет контейнера) — старый путь scrollBy
                    var s2 = 0
                    while (s2 < step && isAutoRead) {
                        val d = minOf(6, step - s2)
                        wv.scrollBy(0, d)
                        s2 += d
                        delay(16)
                    }
                    delay(150)
                    posAfter = readScrollPos(wv)
                }
                // Пустой кадр — не ждём дорисовку, идём дальше сразу
                if (readEngine.lastFrameHadText) delay(350)

                if (posAfter - posBefore <= 0f) {
                    // Не сдвинулись — конец страницы (или контент короче экрана)
                    stuckCounter++
                    if (stuckCounter >= 2) { isAutoRead = false; break }
                } else {
                    stuckCounter = 0
                }
            }
        }

        // Живучесть стопа: уход с вкладки/сворачивание = полная остановка
        DisposableEffect(Unit) {
            onDispose {
                if (autoReadActive.value) {
                    autoReadActive.value = false
                    readEngine.stop()
                }
            }
        }
        DisposableEffect(Unit) {
            onDispose { /* WebView живёт дальше, скролл остановится сам по isAuto */ }
        }

        Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
        ) {
            if (!immersive && !pip) Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canGoBack) {
                    IconButton(onClick = { sharedWebView?.goBack() }) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад")
                    }
                }
                OutlinedTextField(
                    value = urlBar,
                    onValueChange = { urlBar = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    placeholder = { Text("Адрес или поиск", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = {
                        IconButton(onClick = { loadUrlInput(urlBar) }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Перейти/обновить")
                        }
                    },
                )
                IconButton(onClick = {
                    webBookmarked = WebStore.toggleBookmark(ctx, urlBar, sharedWebView?.title ?: urlBar)
                }) {
                    Icon(if (webBookmarked) Icons.Outlined.Star else Icons.Outlined.StarBorder, contentDescription = "Закладка")
                }
                IconButton(onClick = { moreOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "Ещё")
                }
            }
            if (!immersive && !pip && progress < 1f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                factory = { ctx ->
                    val webView = obtainWebView(ctx)
                    // Отцепляем от прошлого родителя, если вкладку пересоздали
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    webView.onResume()
                    webView
                },
                update = { webView ->
                    canGoBackState.value = webView.canGoBack()
                },
                onRelease = { webView ->
                    // v1.9.39: БЕЗ onPause() — музыка и видео с открытой страницы
                    // продолжают играть в фоне и при уходе с вкладки.
                    // Страница и позиция скролла сохраняются до возврата.
                    (webView.parent as? ViewGroup)?.removeView(webView)
                },
            )
        }

        // Линейка чтения (как в AlReader): подсветка текущей реплики
        currentRegion?.let { region ->
            eu.kanade.presentation.reader.components.AutoReadHighlight(region = region, engine = readEngine)
        }

        // Плавающее SAO-меню браузера: автоскролл, наверх, закрыть
        if (!pip) Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomEnd,
        ) {
            Column(
                modifier = Modifier
                    .offset { IntOffset(fabX.roundToInt(), fabY.roundToInt()) }
                    .padding(16.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AnimatedVisibility(
                    visible = menuOpen,
                    enter = fadeIn() + scaleIn(initialScale = 0.8f),
                    exit = fadeOut() + scaleOut(targetScale = 0.8f),
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f),
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .heightIn(max = 480.dp)
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalAlignment = Alignment.End,
                        ) {
                            // Продвинутый компакт: оставлены только 4 ключевые кнопки.
                            // Остальные (язык, перевод, скорость) вынесены в настройки,
                            // чтобы не загромождать FAB-меню. Конструктор всё ещё может скрыть любую.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (isAutoRead) "Стоп авточтения  " else "Авточтение  ",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                SmallFloatingActionButton(onClick = {
                                    if (isAutoRead) {
                                        isAutoRead = false
                                        readEngine.stop()
                                    } else {
                                        isAuto = false
                                        isAutoRead = true
                                        menuOpen = false
                                    }
                                }) {
                                    Icon(
                                        if (isAutoRead) Icons.Outlined.Stop else Icons.Outlined.RecordVoiceOver,
                                        contentDescription = "Авточтение",
                                        tint = if (isAutoRead) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Скан OCR  ", style = MaterialTheme.typography.labelMedium)
                                SmallFloatingActionButton(onClick = {
                                    menuOpen = false
                                    manualScan()
                                }) {
                                    Icon(Icons.Outlined.DocumentScanner, contentDescription = "OCR")
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Сохранить HTML  ", style = MaterialTheme.typography.labelMedium)
                                SmallFloatingActionButton(onClick = { saveHtmlPage() }) {
                                    if (saving) CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp))
                                    else Icon(Icons.Outlined.Download, contentDescription = "Сохранить")
                                }
                            }
                            if (saveMsg != null) {
                                Text(saveMsg ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (immersive) "Обычный экран  " else "Полный экран  ", style = MaterialTheme.typography.labelMedium)
                                SmallFloatingActionButton(onClick = { immersive = !immersive; menuOpen = false }) {
                                    Icon(if (immersive) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen, contentDescription = "Экран")
                                }
                            }
                            if (userActs.isNotEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Кнопки конструктора  ", style = MaterialTheme.typography.labelMedium)
                                    IconButton(onClick = {
                                        ctorExpanded = !ctorExpanded
                                        ctorUiPrefs.edit().putBoolean("menu_ctor_expanded", ctorExpanded).apply()
                                    }) {
                                        Icon(if (ctorExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, contentDescription = "Скрыть или показать кнопки конструктора")
                                    }
                                }
                                if (ctorExpanded) {
                                    userActs.filterNot { it.title.startsWith("Пресет") }.forEach { act ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(act.title + "  ", style = MaterialTheme.typography.labelMedium)
                                            SmallFloatingActionButton(onClick = {
                                                ctx.toast(eu.kanade.tachiyomi.data.ui.UiActionRegistry.apply(ctx, act))
                                            }) {
                                                Icon(Icons.Outlined.Tune, contentDescription = act.title)
                                            }
                                        }
                                    }
                                }
                            }
                            Text(
                                "yomikai " + eu.kanade.tachiyomi.AppInfo.getVersionName(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            )
                        }
                    }
                }

                FloatingActionButton(
                    onClick = { menuOpen = !menuOpen },
                    containerColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            fabX = (fabX + dragAmount.x).coerceAtMost(0f)
                            fabY = (fabY + dragAmount.y).coerceAtMost(0f)
                        }
                    },
                ) {
                    Icon(
                        if (menuOpen) Icons.Outlined.Close else Icons.Outlined.Menu,
                        contentDescription = "Меню браузера",
                    )
                }
            }
        }

        if (moreOpen) {
            AlertDialog(
                onDismissRequest = { moreOpen = false },
                confirmButton = { TextButton(onClick = { moreOpen = false }) { Text("Закрыть") } },
                title = { Text("Мини-браузер") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(onClick = { moreOpen = false; tabsOpen = true }) { Text("Вкладки (${webTabs.size})") }
                        TextButton(onClick = { moreOpen = false; marksOpen = true }) { Text("Закладки (${webMarks.size})") }
                        TextButton(onClick = { moreOpen = false; libOpen = true }) { Text("Библиотека веб-страниц (${webPages.size})") }
                        TextButton(onClick = { moreOpen = false; histOpen = true }) { Text("История просмотра") }
                        TextButton(onClick = { moreOpen = false; cacheOpen = true }) { Text("Кэш и данные") }
                        TextButton(onClick = { moreOpen = false; saveHtmlPage() }) { Text("Сохранить страницу (HTML)") }
                        TextButton(onClick = {
                            moreOpen = false
                            runCatching {
                                (ctx as? android.app.Activity)?.enterPictureInPictureMode(
                                    android.app.PictureInPictureParams.Builder()
                                        .setAspectRatio(android.util.Rational(16, 9))
                                        .build(),
                                )
                            }
                        }) { Text("Плавающий плеер (PiP)") }
                        if (!hiddenM.contains("b_urlscan")) {
                            TextButton(onClick = { moreOpen = false; manualScan() }) { Text("Скан текста (OCR)") }
                        }
                        if (!hiddenM.contains("b_urlfull")) {
                            TextButton(onClick = { moreOpen = false; immersive = true }) { Text("Полный экран") }
                        }
                    }
                },
            )
        }
        // v1.9.40: оверлей выбора области скана (кадр + прямоугольник пальцем)
        areaFrame?.let { frame ->
            AreaPickOverlay(
                frame = frame,
                onDismiss = {
                    areaFrame = null
                    runCatching { frame.recycle() }
                },
                onConfirm = { rect ->
                    areaFrame = null
                    runOcrCrop(frame, rect)
                },
            )
        }
        if (libOpen) {
            WebListDialog(
                title = "Веб-библиотека (по источнику и id)",
                rows = webPages.sortedByDescending { it.savedAt }.map { Triple(it.id, it.host + " · " + it.title, it.url) },
                onPick = { id ->
                    webPages.firstOrNull { it.id == id }?.let { p ->
                        // WebView с API 30+ не пускает в file:// (ERR_ACCESS_DENIED):
                        // читаем файл сами (наш каталог — доступ разрешён) и отдаём
                        // содержимое через loadDataWithBaseURL с базой исходного url.
                        val opened = runCatching {
                            val html = java.io.File(p.file).readText()
                            sharedWebView?.loadDataWithBaseURL(p.url, html, "text/html", "UTF-8", p.url)
                            true
                        }.getOrDefault(false)
                        if (!opened) sharedWebView?.loadUrl("file://" + p.file)
                        libOpen = false
                    }
                },
                onDelete = { id -> webPages.firstOrNull { it.id == id }?.let { WebStore.deletePage(ctx, it) } },
                onDismiss = { libOpen = false },
            )
        }
        if (marksOpen) {
            WebListDialog(
                title = "Закладки",
                rows = webMarks.map { Triple(it.id, it.title, it.url) },
                onPick = { id ->
                    webMarks.firstOrNull { it.id == id }?.let { m ->
                        sharedWebView?.loadUrl(m.url)
                        marksOpen = false
                    }
                },
                onDelete = { id -> webMarks.firstOrNull { it.id == id }?.let { WebStore.deleteMark(ctx, it) } },
                onDismiss = { marksOpen = false },
            )
        }
        if (histOpen) {
            WebListDialog(
                title = "История просмотра",
                rows = webHist.map {
                    Triple(
                        it.url,
                        it.title,
                        java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it.at)),
                    )
                },
                onPick = { u -> sharedWebView?.loadUrl(u); histOpen = false },
                onDismiss = { histOpen = false },
            )
        }
        if (tabsOpen) {
            WebListDialog(
                title = "Вкладки",
                rows = webTabs.map { Triple(it.id, it.title, it.url) },
                onPick = { id ->
                    // v1.9.40: сначала текущую страницу — в АКТИВНУЮ вкладку,
                    // затем открываем выбранную (раньше url писался в последнюю
                    // вкладку списка и сайты «переезжали» между вкладками).
                    runCatching { WebStore.touchTab(ctx, sharedWebView?.url ?: "", sharedWebView?.title ?: "") }
                    val target = WebStore.switchTab(ctx, id)
                    if (!target.isNullOrBlank()) sharedWebView?.loadUrl(target)
                    tabsOpen = false
                },
                onDelete = { id -> WebStore.closeTab(ctx, id) },
                newLabel = "＋ Новая вкладка",
                onNew = {
                    WebStore.addTab(ctx, HOME_URL, "Новая вкладка")
                    sharedWebView?.loadUrl(HOME_URL)
                    tabsOpen = false
                },
                onDismiss = { tabsOpen = false },
            )
        }
        if (cacheOpen) {
            AlertDialog(
                onDismissRequest = { cacheOpen = false },
                confirmButton = {
                    TextButton(onClick = {
                        runCatching { sharedWebView?.clearCache(true) }
                        WebStore.clearCache(ctx)
                        cacheOpen = false
                    }) { Text("Очистить кэш") }
                },
                dismissButton = { TextButton(onClick = { cacheOpen = false }) { Text("Закрыть") } },
                title = { Text("Кэш и данные") },
                text = {
                    Text(
                        "Кэш WebView/приложения: " +
                            String.format(java.util.Locale.getDefault(), "%.1f МБ", WebStore.cacheSizeBytes(ctx) / 1048576.0) +
                            "\nОчистка не трогает веб-библиотеку и закладки.",
                    )
                },
            )
        }
        if (immersive) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SmallFloatingActionButton(onClick = { manualScan() }) {
                        Icon(Icons.Outlined.DocumentScanner, contentDescription = "OCR")
                    }
                    SmallFloatingActionButton(onClick = { immersive = false }) {
                        Icon(Icons.Outlined.FullscreenExit, contentDescription = "Выход")
                    }
                }
            }
        }

        // Карточка результата ручного скана: компактная, по центру, с отменой.
        if (ocrBusy || ocrText != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .padding(24.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (ocrBusy) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator()
                                Text("Распознавание… (закрытие = отмена)")
                            }
                        } else {
                            Text(
                                text = ocrText?.ifBlank { "Не удалось распознать текст на странице" } ?: "",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState())
                                    .heightIn(max = 360.dp),
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            if (!ocrBusy && !ocrText.isNullOrBlank()) {
                                TextButton(onClick = { TtsSpeaker.speak(ctx, ocrText ?: "") }) {
                                    Text("Голос")
                                }
                                TextButton(onClick = { TtsSpeaker.speakWithVoice(ctx, ocrText ?: "", TtsSpeaker.slotVoiceSpec("female")) }) {
                                    Text("♀")
                                }
                                TextButton(onClick = { TtsSpeaker.speakWithVoice(ctx, ocrText ?: "", TtsSpeaker.slotVoiceSpec("male")) }) {
                                    Text("♂")
                                }
                                TextButton(onClick = { TtsSpeaker.speakWithVoice(ctx, ocrText ?: "", TtsSpeaker.slotVoiceSpec("narrator")) }) {
                                    Text("🎙")
                                }
                                TextButton(onClick = {
                                    val clipboard = ctx.getSystemService(android.content.ClipboardManager::class.java)
                                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(null, ocrText))
                                }) {
                                    Text("Копировать")
                                }
                            }
                            TextButton(onClick = {
                                ocrJob?.cancel()
                                ocrBusy = false
                                ocrText = null
                            }) {
                                Text("Закрыть")
                            }
                        }
                    }
                }
            }
        }
        }
    }
}
/**
 * v1.9.40: выбор области скана пальцем: замороженный кадр на весь экран,
 * пользователь рисует прямоугольник; «Скан области» — OCR выделенного.
 * Заменяет автоскан «всего экрана с шапкой и рекламой» и системное выделение
 * текста по long-press.
 */
@androidx.compose.runtime.Composable
private fun AreaPickOverlay(
    frame: android.graphics.Bitmap,
    onDismiss: () -> Unit,
    onConfirm: (android.graphics.RectF) -> Unit,
) {
    val st = remember {
        object {
            var ax = 0f
            var ay = 0f
            var bx = 0f
            var by = 0f
            var active = false
        }
    }
    var tick by remember { mutableStateOf(0) }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { off ->
                        st.ax = off.x
                        st.ay = off.y
                        st.bx = off.x
                        st.by = off.y
                        st.active = true
                        tick++
                    },
                    onDrag = { _, drag ->
                        st.bx = (st.bx + drag.x).coerceIn(0f, size.width.toFloat())
                        st.by = (st.by + drag.y).coerceIn(0f, size.height.toFloat())
                        tick++
                    },
                )
            },
    ) {
        val bw = constraints.maxWidth
        val bh = constraints.maxHeight
        Image(
            bitmap = frame.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        if (st.active) {
            val l = minOf(st.ax, st.bx)
            val t = minOf(st.ay, st.by)
            val r = maxOf(st.ax, st.bx)
            val b = maxOf(st.ay, st.by)
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    color = androidx.compose.ui.graphics.Color(0x6633B5E5),
                    topLeft = Offset(l, t),
                    size = Size(r - l, b - t),
                )
                drawRect(
                    color = androidx.compose.ui.graphics.Color(0xFF33B5E5),
                    style = Stroke(4f),
                    topLeft = Offset(l, t),
                    size = Size(r - l, b - t),
                )
            }
        }
        Row(
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.BottomCenter)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text("Отмена")
            }
            androidx.compose.material3.TextButton(
                onClick = {
                    val fx = frame.width.toFloat() / bw.toFloat()
                    val fy = frame.height.toFloat() / bh.toFloat()
                    onConfirm(
                        android.graphics.RectF(
                            minOf(st.ax, st.bx) * fx,
                            minOf(st.ay, st.by) * fy,
                            maxOf(st.ax, st.bx) * fx,
                            maxOf(st.ay, st.by) * fy,
                        ),
                    )
                },
            ) {
                androidx.compose.material3.Text("Скан области")
            }
        }
    }
}
