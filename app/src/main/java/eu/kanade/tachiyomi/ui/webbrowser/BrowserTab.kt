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
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.OpenInNew
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
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.foundation.layout.size
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.PhotoCamera
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

    private data class CatalogSite(val name: String, val desc: String, val url: String)
    private data class CatalogGroup(val title: String, val sites: List<CatalogSite>)

    // Каталог сайтов (меню «Сайты» в тулбаре): манга — по умолчанию, аниме/
    // дорамы/ранобэ/аудиокниги открываются здесь, во вкладке «Браузер». Отдельных
    // тип-вкладок больше нет.
    private val siteCatalog = listOf(
        CatalogGroup("Манга", listOf(
            CatalogSite("MangaBuff", "Домашняя страница манги", "https://mangabuff.ru"),
        )),
        CatalogGroup("Аниме / Дорамы", listOf(
            CatalogSite("Anilibria", "Аниме онлайн + официальное API", "https://anilibria.tv"),
            CatalogSite("Kodik", "Плеер и API для встраивания видео", "https://kodik.info"),
            CatalogSite("Shikimori", "База аниме/манги, расписание", "https://shikimori.one"),
            CatalogSite("DoramaLive", "Дорамы с субтитрами", "https://dorama.live"),
            CatalogSite("Dorama.kim", "Дорамы на русском", "https://dorama.kim"),
        )),
        CatalogGroup("Ранобэ", listOf(
            CatalogSite("RanobeLib", "Ранобэ на русском: каталог и чтение", "https://ranobelib.me"),
            CatalogSite("LibRead", "Книги и ранобэ на русском", "https://libread.me"),
            CatalogSite("Novel Updates", "Индекс новелл (EN)", "https://www.novelupdates.com"),
            CatalogSite("ReadNovelFull", "Сборник новелл (EN)", "https://readnovelfull.com"),
        )),
        CatalogGroup("Аудиокниги", listOf(
            CatalogSite("ЛитРес", "Легальные аудиокниги", "https://www.litres.ru"),
            CatalogSite("АКнига", "Клуб аудиокниг онлайн", "https://akniga.org"),
            CatalogSite("Audioteka", "Аудиокниги на русском", "https://audioteka.ru"),
            CatalogSite("LibriVox", "Свободные аудиокниги (EN)", "https://librivox.org"),
        )),
    )

    @SuppressLint("StaticFieldLeak") // applicationContext — утечки нет
    private var sharedWebView: WebView? = null
    /** v1.9.39: фуллскрин-видео (onShowCustomView) и long-press скан картинки. */
    private var customView: android.view.View? = null
    private var customViewCallback: android.webkit.WebChromeClient.CustomViewCallback? = null
    private var hostActivity: android.app.Activity? = null
    private var imageLongPress: (() -> Unit)? = null
    /** v1.9.41: живой WebView на каждую вкладку (до 4): переключение НЕ
     *  перезапускает страницу — вкладки держатся открытыми, как в Via/Chrome. */
    private val webViewPool = LinkedHashMap<String, WebView>()

    private var urlState = mutableStateOf(HOME_URL)
    private var canGoBackState = mutableStateOf(false)
    private var canGoForwardState = mutableStateOf(false)
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
                var minArea = vw * vh * 0.08; // картинка >=8% экрана
                var top = vh, bottom = 0, left = vw, right = 0, found = false;
                // Старница манги часто рисуется не только <img>/<canvas>, но и
                // <div> с background-image (полноэкранные/вебтун-ридеры). Ловим
                // и такие узлы, иначе зона не находится и сканируется весь экран
                // вместе с шапкой/подвалом сайта (жалоба пользователя).
                var nodes = document.querySelectorAll('img, canvas, [style*="background-image"], [data-src]');
                for (var i = 0; i < nodes.length; i++) {
                    var el = nodes[i];
                    var r = el.getBoundingClientRect();
                    if (r.width < 1 || r.height < 1) continue;
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
                // Вычесть фиксированные шапку/подвал ридера (навигация «— том N
                // глава M →», счётчик «N/M»): они обычно position:fixed/sticky.
                function shrinkToContent() {
                    // head = нижняя граница верхней панели (контент ниже её),
                    // foot = верхняя граница нижней панели (контент выше её).
                    var head = 0, foot = vh;
                    var chrome = document.querySelectorAll('header, footer, nav, [class*="header"], [class*="footer"], [class*="toolbar"], [class*="reader-top"], [class*="reader-bottom"]');
                    for (var k = 0; k < chrome.length; k++) {
                        var c = chrome[k];
                        var cs = window.getComputedStyle(c);
                        if (cs.position !== 'fixed' && cs.position !== 'sticky' && cs.position !== 'absolute') continue;
                        var cr = c.getBoundingClientRect();
                        if (cr.height < 10 || cr.height > vh * 0.35) continue;
                        if (cr.top >= 0 && cr.top < vh * 0.5) head = Math.max(head, Math.min(cr.bottom, vh));
                        if (cr.bottom <= vh && cr.bottom > vh * 0.5) foot = Math.min(foot, Math.max(cr.top, 0));
                    }
                    return [head, foot];
                }
                var hf = shrinkToContent();
                if (found) {
                    top = Math.max(top, hf[0]);
                    bottom = Math.min(bottom, hf[1]);
                    if (bottom - top > vh * 0.10) {
                        return (left / vw) + "," + (top / vh) + "," + (right / vw) + "," + (bottom / vh);
                    }
                } else if (hf[1] - hf[0] > vh * 0.30) {
                    // Зона не нашлась, но есть фиксированная шапка/подвал ридера —
                    // отдаём полосу контента между ними, а не весь экран.
                    return "0," + (hf[0] / vh) + ",1," + (hf[1] / vh);
                }
                return "";
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

    /**
     * Реклама/поповеры поверх изображения («+ алмазы», «Telegram-канал»,
     * «Перейти», крестик) — НЕ текст книги, но они лежат прямо на странице,
     * и OCR читал их вместе с мангой. DOM ищет такие узлы: явно-рекламные
     * по id/class/src (adv/banner/promo/telegram/ins) и фиксированные/липкие
     * оверлеи в нижней половине экрана. Возвращает прямоугольники в долях
     * вьюпорта (0..1) в том же формате, что detectBookZone.
     */
    private suspend fun detectOverlayJunkRects(): List<android.graphics.RectF> {
        val wv = sharedWebView ?: return emptyList()
        val js = """
            (function() {
                var vh = window.innerHeight, vw = window.innerWidth;
                var out = [];
                function push(r) {
                    var l = Math.max(0, r.left) / vw, t = Math.max(0, r.top) / vh;
                    var rr = Math.min(vw, r.right) / vw, bb = Math.min(vh, r.bottom) / vh;
                    if (rr - l < 0.02 || bb - t < 0.015) return;
                    out.push(l.toFixed(4) + "," + t.toFixed(4) + "," + rr.toFixed(4) + "," + bb.toFixed(4));
                }
                // 1) явно рекламные узлы по именам (MangaLib и аналоги).
                var junk = document.querySelectorAll(
                    '[id*="adv"],[class*="adv"],[id*="banner"],[class*="banner"],' +
                    '[class*="promo"],[class*="adsbygoogle"],[class*="adb"],' +
                    'iframe[src*="ads"],iframe[src*="doubleclick"],' +
                    '[class*="telegram"],[id*="telegram"],ins'
                );
                for (var i = 0; i < junk.length; i++) {
                    var r = junk[i].getBoundingClientRect();
                    if (r.width < 24 || r.height < 16) continue;
                    if (r.bottom < 0 || r.top > vh) continue;
                    if (r.width * r.height > vw * vh * 0.5) continue;
                    push(r);
                }
                // 1.5) модальные окна и диалоги поверх страницы («УДАЛИТЬ»,
                // «ЧЕРНОВИК», промо-боксы): OCR читал кнопки диалога вместо
                // манги, и первые кадры проходили холостыми.
                var modals = document.querySelectorAll('[role="dialog"],[role="alertdialog"],[class*="modal"],[class*="popup"],[class*="dialog"],[id*="modal"],[id*="popup"]');
                for (var m = 0; m < modals.length; m++) {
                    var mo = modals[m];
                    var csm = window.getComputedStyle(mo);
                    if (csm.display === 'none' || csm.visibility === 'hidden' || parseFloat(csm.opacity || '1') < 0.05) continue;
                    var rm = mo.getBoundingClientRect();
                    if (rm.width < 24 || rm.height < 12) continue;
                    if (rm.bottom < 0 || rm.top > vh) continue;
                    var am = rm.width * rm.height;
                    if (am < vw * vh * 0.01) continue;
                    if (am > vw * vh * 0.60) continue;
                    push(rm);
                }
                // 2) фиксированные/липкие слои в нижних 75% экрана: баннер
                // крепится поверх изображения, а не панель ридера.
                var all = document.querySelectorAll('body *');
                for (var k = 0; k < all.length && k < 2500; k++) {
                    var el = all[k];
                    var cs = window.getComputedStyle(el);
                    if (cs.position !== 'fixed' && cs.position !== 'sticky') continue;
                    if (cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity) < 0.05) continue;
                    var z = parseInt(cs.zIndex || '0', 10);
                    if (z < 10) continue;
                    var r2 = el.getBoundingClientRect();
                    if (r2.height < 12 || r2.height > vh * 0.35) continue;
                    if (r2.bottom < 0 || r2.top > vh) continue;
                    var area = r2.width * r2.height, frac = area / (vw * vh);
                    if (frac < 0.004 || frac > 0.5) continue;
                    if (r2.top < vh * 0.25) continue; // верхняя панель ридера — не трогаем
                    push(r2);
                }
                return out.join(";");
            })()
        """.trimIndent()
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            try {
                wv.post {
                    wv.evaluateJavascript(js) { raw ->
                        val body = raw?.trim('"').orEmpty()
                        val rects = body.split(';')
                            .mapNotNull { part ->
                                val nums = part.split(',').mapNotNull { it.toFloatOrNull() }
                                if (nums.size == 4 && nums[3] > nums[1] && nums[2] > nums[0]) {
                                    android.graphics.RectF(nums[0], nums[1], nums[2], nums[3])
                                } else {
                                    null
                                }
                            }
                            .take(24)
                        if (cont.isActive) cont.resume(rects) {}
                    }
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(emptyList()) {}
            }
        }
    }

    /** Залить рекламные прямоугольники чёрным, чтобы OCR их не видел. */
    /**
     * Суммарная площадь junk-прямоугольников (после зажима в 0..1). Если
     * детектор ошибся и «рекламой» объявлен почти весь кадр (тёмные страницы
     * ранобэ-читалок), возвращаем вход без изменений — раньше в OCR уходила
     * сплошная ЧЁРНАЯ страница и распознавался мусор вроде «МАЙН/ТУ».
     */
    private fun junkAreaFraction(junk: List<android.graphics.RectF>): Float {
        var area = 0f
        junk.forEach { r ->
            val w = (r.right.coerceIn(0f, 1f) - r.left.coerceIn(0f, 1f)).coerceAtLeast(0f)
            val h = (r.bottom.coerceIn(0f, 1f) - r.top.coerceIn(0f, 1f)).coerceAtLeast(0f)
            area += w * h
        }
        return area.coerceIn(0f, 1f)
    }

    private fun blankJunkRects(
        src: android.graphics.Bitmap,
        junk: List<android.graphics.RectF>,
    ): android.graphics.Bitmap {
        if (junk.isEmpty()) return src
        if (junkAreaFraction(junk) > 0.55f) return src // детектор промахнулся
        val out = android.graphics.Bitmap.createBitmap(
            src.width, src.height, android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(out)
        canvas.drawBitmap(src, 0f, 0f, null)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK }
        junk.forEach { r ->
            canvas.drawRect(
                (r.left * src.width).coerceIn(0f, src.width.toFloat()),
                (r.top * src.height).coerceIn(0f, src.height.toFloat()),
                (r.right * src.width).coerceIn(0f, src.width.toFloat()),
                (r.bottom * src.height).coerceIn(0f, src.height.toFloat()),
                paint,
            )
        }
        return out
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
                wv.post { wv.evaluateJavascript(js) { if (cont.isActive) cont.resume(Unit) {} } }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(Unit) {}
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
        sharedWebView?.evaluateJavascript("window.scrollTo(0,0);true", null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(context: Context, webView: WebView) {
        webView.apply {
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
            // v1.9.44: загрузки из веба через системный DownloadManager — файл
            // реально появляется в Download/ и видно уведомление системы
            // (раньше «скачивается», а файла нет).
            setDownloadListener { url, ua, contentDisposition, mimetype, _ ->
                runCatching {
                    val dm = context.getSystemService(android.app.DownloadManager::class.java)
                    val fname = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
                    val req = android.app.DownloadManager.Request(android.net.Uri.parse(url))
                        .setMimeType(mimetype)
                        .addRequestHeader("User-Agent", ua)
                        .setTitle(fname)
                        .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fname)
                    dm?.enqueue(req)
                    android.widget.Toast.makeText(context, "Загрузка: $fname (папка Download)", android.widget.Toast.LENGTH_LONG).show()
                }.onFailure {
                    android.widget.Toast.makeText(context, "Не удалось начать загрузку", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    if (view !== sharedWebView) return
                    progressState.floatValue = newProgress / 100f
                }

                // v1.9.44: название сайта во вкладке обновляется сразу (SPA,
                // редиректы), а не только по onPageFinished.
                override fun onReceivedTitle(view: WebView, title: String?) {
                    if (view !== sharedWebView) return
                    runCatching { WebStore.touchTab(view.context, view.url ?: "", title ?: "") }
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
                // Сетевая половина AdBlock: запросы рекламных сетей (включая
                // iframe-баннеры со звуком) не отдаём странице вовсе.
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                ): android.webkit.WebResourceResponse? {
                    if (BrowserAdBlock.isEnabled(view.context) &&
                        BrowserAdBlock.shouldBlock(request.url?.toString())
                    ) {
                        return BrowserAdBlock.emptyResponse()
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    if (view !== sharedWebView) return
                    if (!url.isNullOrBlank()) urlState.value = url
                    // Блокировщик рекламы разворачивается на СТАРТЕ страницы:
                    // иначе промо-карточка успевает мигнуть и отыграть звук
                    // («тук-тук» поверх диалога авточтения).
                    BrowserAdBlock.inject(view)
                }
                override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                    if (view !== sharedWebView) return
                    if (request.isForMainFrame) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            context.toast("Ошибка загрузки: ${error.description}")
                        }
                    }
                }
                override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                    if (view !== sharedWebView) return
                    if (getBrowserSslPolicy(view.context)) {
                        // Читательские сайты часто отдают цепочки с ручной подписью
                        // (primaryError 2 = SSL_IDMISMATCH, 3 = UNTRUSTED и т.п.).
                        // При включённой политике загружаем страницу дальше, иначе
                        // WebView молча бросает загрузку.
                        handler.proceed()
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            context.toast("SSL-проблема (${error.primaryError}) — продолжаю загрузку")
                        }
                    } else {
                        handler.cancel()
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            context.toast("SSL-ошибка: ${error.primaryError}")
                        }
                    }
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    // фоновая вкладка догрузилась — адрес/активную не трогаем
                    if (view !== sharedWebView) return
                    canGoBackState.value = view.canGoBack()
                    canGoForwardState.value = view.canGoForward()
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
                    // Косметический фильтр: страница догрузилась — промо-карточки,
                    // которые сайт досыпает после рендера, снимаем сразу.
                    BrowserAdBlock.inject(view)
                    url?.let { urlState.value = it }
                }
            }
        }
    }

    /**
     * Отдать живой WebView активной вкладки в произвольный контейнер
     * (например, в плавающий мини-плеер). Вкладка остаётся в пуле, страница
     * и скролл НЕ перезапускаются — вьюха просто переезжает в другой
     * FrameLayout. Возвращает webview, либо null если вкладок нет.
     */
    @SuppressLint("ViewConstructor")
    fun attachActiveWebView(container: ViewGroup): WebView? {
        val ctx = container.context
        val tabId = WebStore.activeTabId.value ?: WebStore.tabs.value.lastOrNull()?.id ?: return null
        val item = WebStore.tabs.value.firstOrNull { it.id == tabId }
        val wv = webViewForTab(ctx, tabId, item?.url ?: HOME_URL)
        sharedWebView = wv
        if (wv.parent === container) return wv
        runCatching { (wv.parent as? ViewGroup)?.removeView(wv) }
        container.removeAllViews()
        container.addView(wv, android.widget.FrameLayout.LayoutParams(-1, -1))
        wv.onResume()
        canGoBackState.value = wv.canGoBack()
        canGoForwardState.value = wv.canGoForward()
        wv.url?.let { urlState.value = it }
        return wv
    }

    /** Живой WebView вкладки: открытые ранее вкладки НЕ пересоздаются. */
    private fun webViewForTab(context: Context, tabId: String, url: String): WebView {
        webViewPool[tabId]?.let { return it }
        val wv = WebView(context.applicationContext)
        configureWebView(context, wv)
        webViewPool[tabId] = wv
        // Держим не больше 4 живых: старую фоновую освобождаем (вернётся из store)
        while (webViewPool.size > 4) {
            val victim = webViewPool.keys.firstOrNull { it != tabId && it != WebStore.activeTabId.value } ?: break
            webViewPool.remove(victim)?.let { old ->
                runCatching { (old.parent as? ViewGroup)?.removeView(old) }
                runCatching { old.destroy() }
            }
        }
        wv.post { wv.loadUrl(url) }
        return wv
    }

    /** Режим OCR в браузере: auto / online (Google Lens) / offline (Paddle кирилл.). */
    private fun browserOcrMode(context: Context): String =
        context.getSharedPreferences("browser_ocr", 0).getString("engine_mode", "auto") ?: "auto"

    private fun setBrowserOcrMode(context: Context, mode: String) {
        context.getSharedPreferences("browser_ocr", 0).edit()
            .putString("engine_mode", mode).apply()
    }

    /** SSL-политика браузера: true — принимать проблемные цепочки и грузить дальше. */
    private fun getBrowserSslPolicy(context: Context): Boolean =
        context.getSharedPreferences("browser_ocr", 0).getBoolean("accept_ssl", true)

    private fun setBrowserSslPolicy(context: Context, accept: Boolean) {
        context.getSharedPreferences("browser_ocr", 0).edit()
            .putBoolean("accept_ssl", accept).apply()
    }

    @Composable
    override fun Content() {
        var urlBar by urlState
        val canGoBack by canGoBackState
        val canGoForward by canGoForwardState
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
        // «Прочитать страницу» — один проход авточтения без перехода дальше.
        var oneShotRead by remember { mutableStateOf(false) }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        WebStore.load(ctx)
        // v1.9.44: вкладки/активная восстанавливаются СИНХРОННО до первого кадра —
        // старт и новая вкладка показывают последний открытый сайт, а не дефолт.
        if (WebStore.tabs.value.isEmpty()) WebStore.addTab(ctx, HOME_URL, "Новая вкладка")
        if (WebStore.tabs.value.none { it.id == WebStore.activeTabId.value }) {
            WebStore.activeTabId.value = WebStore.tabs.value.lastOrNull()?.id
        }
        hostActivity = ctx as? android.app.Activity
        var moreOpen by remember { mutableStateOf(false) }
        var sitesOpen by remember { mutableStateOf(false) }
        var libOpen by remember { mutableStateOf(false) }
        var marksOpen by remember { mutableStateOf(false) }
        var histOpen by remember { mutableStateOf(false) }
        var tabsOpen by remember { mutableStateOf(false) }
        var cacheOpen by remember { mutableStateOf(false) }
        var downloadsOpen by remember { mutableStateOf(false) }
        var voiceOpen by remember { mutableStateOf(false) }
        var webBookmarked by remember { mutableStateOf(false) }
        val webPages by WebStore.pages.collectAsState()
        val webMarks by WebStore.marks.collectAsState()
        val webHist by WebStore.history.collectAsState()
        val webTabs by WebStore.tabs.collectAsState()
        val activeTabId by WebStore.activeTabId.collectAsState()
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
            urlBar = target
            WebStore.touchTab(ctx, target, target)
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
        // v1.9.41: распознанный текст живёт НА кадре (рамки + текст без заливки)
        var ocrFrame by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
        var ocrRegions by remember { mutableStateOf<List<Pair<mihon.domain.ocr.model.OcrBoundingBox, String>>>(emptyList()) }
        var showOcrCard by remember { mutableStateOf(false) }
        val pip by WebStore.pipMode
        var immersive by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        var saving by remember { mutableStateOf(false) }
        var saveMsg by remember { mutableStateOf<String?>(null) }

        /** «Скриншот сейчас» (как в читалке манги): текущий кадр WebView в PNG. */
        fun saveScreenshotNow() {
            val bmp = captureWebView()
            if (bmp == null) {
                ctx.toast("Не удалось захватить кадр")
                return
            }
            try {
                val dir = java.io.File(
                    ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
                        ?: ctx.filesDir,
                    "screenshots",
                ).apply { mkdirs() }
                val out = java.io.File(dir, "web-${System.currentTimeMillis()}.png")
                out.outputStream().use { stream ->
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
                }
                ctx.toast("Скриншот сохранён: ${out.absolutePath}")
            } catch (e: Exception) {
                ctx.toast("Не удалось сохранить скриншот: ${e.javaClass.simpleName}")
            }
        }

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
                ocrRegions = emptyList()
                ocrFrame = raw // результат появится ПРЯМО НА КАДРЕ
                try {
                    // Реклама поверх картинки («+ алмазы», «Перейти») к книге
                    // не относится — вычищаем её из кадра до OCR.
                    val junk = detectOverlayJunkRects()
                    // (ocrFrame оставляем исходным кадром — результат
                    // показываем на нём, а не на вычищенном.)
                    val clean = if (junk.isNotEmpty()) blankJunkRects(raw, junk) else raw
                    val l = rect.left.toInt().coerceIn(0, clean.width - 1)
                    val t = rect.top.toInt().coerceIn(0, clean.height - 1)
                    val r = rect.right.toInt().coerceIn(l + 1, clean.width)
                    val b = rect.bottom.toInt().coerceIn(t + 1, clean.height)
                    val cropped = if (r - l < 40 || b - t < 40) {
                        clean
                    } else {
                        android.graphics.Bitmap.createBitmap(clean, l, t, r - l, b - t)
                    }
                    // v1.9.42: оверлей покажет сам фрагмент: текст ляжет точно
                    // на свои рамки (раньше координаты области растягивались на
                    // весь экран и «плывали» по странице).
                    if (cropped !== clean) ocrFrame = cropped
                    val prefsN = Injekt.get<mihon.domain.ocr.service.OcrPreferences>()
                    var result = ocrWithPreprocess(ctx, cropped)
                    if (result.first.isBlank() && cropped !== clean) {
                        // v1.9.41: ретрай на увеличении — мелкий/бледный текст
                        val up = android.graphics.Bitmap.createScaledBitmap(
                            cropped,
                            (cropped.width * 1.5f).toInt().coerceAtLeast(1),
                            (cropped.height * 1.5f).toInt().coerceAtLeast(1),
                            true,
                        )
                        result = ocrWithPreprocess(ctx, up)
                        runCatching { up.recycle() }
                    }
                    val (text, regions) = result
                    ocrText = text
                    ocrRegions = regions
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
        // Рамки распознанных реплик кадра — для per-bubble значков 🔊.
        val frameRegions by readEngine.frameRegions.collectAsState()
        val voicePrefs = remember { Injekt.get<mihon.domain.ocr.service.OcrPreferences>() }
        val roleMode by voicePrefs.voiceMode().changes().collectAsState(initial = voicePrefs.voiceMode().get())
        val voiceEngine by voicePrefs.voiceEngine().changes().collectAsState(initial = voicePrefs.voiceEngine().get())
        val voiceIcons by voicePrefs.voiceIcons().changes().collectAsState(initial = voicePrefs.voiceIcons().get())

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
            // Движок из меню браузера: без этого авточтение шло дефолтным
            // CYRILLIC (локальный TFLite), даже когда выбран Google Lens.
            applyBrowserOcrMode(ctx)
            readEngine.clearHistory()
            var stuckCounter = 0
            // Рекламные прямоугольники DOM-ом обновляем каждые ~4 кадра,
            // чтобы не просить JS на каждом тике.
            var junkFrameSkip = 0
            var cachedJunk: List<android.graphics.RectF> = emptyList()
            while (isAutoRead) {
                val wv = sharedWebView
                val raw0 = captureWebView()
                if (wv == null || raw0 == null) { delay(500); continue }

                // Только страница книги: зона крупных картинок, без UI сайта
                val zone = detectBookZone()
                readEngine.highlightZone = zone
                if (junkFrameSkip % 4 == 0) {
                    cachedJunk = detectOverlayJunkRects()
                }
                junkFrameSkip++
                val bmp = cropToZone(blankJunkRects(raw0, cachedJunk), zone)

                var finished = false
                readEngine.readFrame(
                    bitmap = bmp,
                    chapterId = -1L,
                    pageIndex = wv.scrollY,
                    onPageFinished = { finished = true },
                )
                // Правильный цикл: СНАЧАЛА кадр целиком читается (скан →
                // распознавание → озвучка), и только ПОТОМ плавная автопрокрутка
                // к следующему фрагменту. Раньше скролл шёл ПАРАЛЛЕЛЬНО чтению и
                // успевал уехать вперёд голоса: строки уходили из поля зрения
                // раньше, чем их успевали произнести.
                while (!finished && isAutoRead) {
                    // Блокинг (нет голоса / OCR падает): onPageFinished не вызовется,
                    // ждать вечность нечего — показываем причину и останавливаемся.
                    if (readEngine.voiceBlock.value != null) break
                    delay(16)
                }
                readEngine.voiceBlock.value?.let { blocking ->
                    isAutoRead = false
                    ctx.toast(blocking)
                    break
                }
                if (!isAutoRead) break

                // «Прочитать страницу»: только текущий кадр, дальше не идём.
                if (oneShotRead) { oneShotRead = false; isAutoRead = false; break }

                val prefs = Injekt.get<mihon.domain.ocr.service.OcrPreferences>()
                if (!prefs.autoReadAutoAdvance().get()) { isAutoRead = false; break }

                // Плавная автопрокрутка к следующему фрагменту: серия мелких шагов
                // по 16 мс со скоростью, заданной для автопрокрутки браузера.
                // Шаг — до 45% высоты вьюпорта, чтобы следующий кадр перекрывал
                // текущий и текст на стыке не терялся (дубли отсекает история).
                val posBefore = readScrollPos(wv)
                val rateNow = voicePrefs.speechRate().get().takeIf { it > 0f } ?: 1f
                val speedPx = ((speed * 3 * rateNow).roundToInt()).coerceAtLeast(1)
                val targetAdvancePx = (wv.height * 0.45f).roundToInt().coerceAtLeast(64)
                var advancedPx = 0
                while (isAutoRead && advancedPx < targetAdvancePx) {
                    // К концу шага плавно тормозим — стык кадров встречаем мягко.
                    val remain = (targetAdvancePx - advancedPx).toFloat() / targetAdvancePx
                    val step = (speedPx * (0.15f + 0.85f * remain)).roundToInt().coerceAtLeast(1)
                    wv.scrollBy(0, step)
                    advancedPx += step
                    delay(16)
                }
                if (!isAutoRead) break

                // scrollBy не сдвинул (контейнер внутри страницы) — добиваем JS.
                var posAfter = readScrollPos(wv)
                if (posAfter - posBefore <= 0f) {
                    scrollJs(wv, (wv.height * 0.5f).roundToInt().coerceAtLeast(1))
                    delay(220)
                    posAfter = readScrollPos(wv)
                }
                // Пустой кадр (без текста) не задерживает чтение: только лёгкая
                // пауза после текстового, чтобы глаз не рыскал.
                if (readEngine.lastFrameHadText) delay(140)

                if (posAfter - posBefore <= 0f) {
                    // Не сдвинулись — конец страницы (или контент короче экрана)
                    stuckCounter++
                    if (stuckCounter >= 4) { isAutoRead = false; break }
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
                }
                // Движок держит собственный CoroutineScope: гасим его вместе с
                // вкладкой, иначе фоновый OCR доживал до конца после ухода.
                readEngine.destroy()
                autoReadEngine = null
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
                if (canGoForward) {
                    IconButton(onClick = { sharedWebView?.goForward() }) {
                        Icon(Icons.Outlined.ArrowForward, contentDescription = "Вперёд")
                    }
                }
                OutlinedTextField(
                    value = urlBar,
                    onValueChange = { urlBar = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    placeholder = { Text("Адрес или поиск", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = {
                        IconButton(onClick = { loadUrlInput(urlBar) }) {
                            Icon(Icons.Outlined.Language, contentDescription = "Перейти")
                        }
                    },
                    trailingIcon = {
                        val isCurrentUrl = sharedWebView?.url?.trim() == urlBar.trim()
                        IconButton(onClick = {
                            if (isCurrentUrl) {
                                sharedWebView?.reload()
                            } else {
                                loadUrlInput(urlBar)
                            }
                        }) {
                            Icon(
                                if (isCurrentUrl) Icons.Outlined.Refresh else Icons.Outlined.ArrowForward,
                                contentDescription = if (isCurrentUrl) "Обновить" else "Перейти",
                            )
                        }
                    },
                )
                IconButton(onClick = { tabsOpen = true }) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(webTabs.size.toString(), style = MaterialTheme.typography.labelSmall)
                    }
                }
                IconButton(onClick = { sitesOpen = true }) {
                    Icon(Icons.Outlined.Menu, contentDescription = "Каталог сайтов")
                }
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
            ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize(),
                factory = { ctx -> android.widget.FrameLayout(ctx) },
                update = { fl ->
                    // v1.9.41: вкладка = свой живой WebView: переключение только
                    // переставляет вьюху, страница и скролл НЕ перезапускаются.
                    // v1.9.56: вкладка ВСЕГДА держит свой WebView — отдельный
                    // мини-браузер внутри приложения. Единственный плавающий
                    // мини-плеер — системный (MiniOverlayService, поверх всех
                    // приложений) с СОБСТВЕННЫМ WebView, поэтому конфликтов
                    // «двух панелей за один WebView» больше нет.
                    val tabId = activeTabId ?: webTabs.lastOrNull()?.id ?: return@AndroidView
                    val item = webTabs.firstOrNull { it.id == tabId }
                    val wv = webViewForTab(fl.context, tabId, item?.url ?: HOME_URL)
                    sharedWebView = wv
                    if (wv.parent !== fl) {
                        (wv.parent as? ViewGroup)?.removeView(wv)
                        fl.removeAllViews()
                        fl.addView(wv, android.widget.FrameLayout.LayoutParams(-1, -1))
                        // БЕЗ onPause()/onResume-потерь: музыка продолжает играть
                        wv.onResume()
                        canGoBackState.value = wv.canGoBack()
                        wv.url?.let { urlState.value = it }
                    }
                },
                onRelease = { fl ->
                    // Убрать полноэкранное видео при смене вкладки
                    customViewCallback?.onCustomViewHidden()
                    customView = null
                    customViewCallback = null
                    (fl as? android.widget.FrameLayout)?.removeAllViews()
                },
            )
            }
        }

        // Линейка чтения (как в AlReader): подсветка текущей реплики
        currentRegion?.let { region ->
            eu.kanade.presentation.reader.components.AutoReadHighlight(region = region, engine = readEngine)
        }

        // Per-bubble значки 🔊 на рамках распознанных реплик. Тап — озвучить
        // именно этот текст (жёлоба: «голосовой значок не работает в вебе»).
        // Показываются по тому же переключателю, что и в читалке.
        if (voiceIcons && frameRegions.isNotEmpty()) {
            eu.kanade.presentation.reader.components.OcrBubbleVoiceOverlay(
                regions = frameRegions,
                onSpeakRegion = { text, _ ->
                    readEngine.speakSingle(text)
                },
                perBubble = true,
                draggable = true,
            )
        }

        // Выбор голосов и ролей прямо в браузере: 1 / 2 / много голосов и
        // движок (Авто = веб онлайн / локальный оффлайн).
        if (voiceOpen) {
            VoiceQuickDialog(
                onDismiss = { voiceOpen = false },
                roleMode = roleMode,
                voiceEngine = voiceEngine,
                voiceIcons = voiceIcons,
                onRoleMode = { m -> voicePrefs.voiceMode().set(m) },
                onVoiceEngine = { e -> voicePrefs.voiceEngine().set(e) },
                onVoiceIcons = { b -> voicePrefs.voiceIcons().set(b) },
            )
        }

        // Плавающее SAO-меню браузера: автоскролл, наверх, закрыть
        if (!pip) BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomEnd,
        ) {
            val maxOffsetX = with(LocalDensity.current) { -(maxWidth - 56.dp).roundToPx() }
            val maxOffsetY = with(LocalDensity.current) { -(maxHeight - 56.dp).roundToPx() }
            Column(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            fabX.roundToInt().coerceIn(maxOffsetX, 0),
                            fabY.roundToInt().coerceIn(maxOffsetY, 0),
                        )
                    }
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
                            // Скорость авточтения: 50%…200%, −/+ (п.1).
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                var rateUi by remember {
                                    mutableStateOf(voicePrefs.speechRate().get())
                                }
                                Text(
                                    "Скорость: ${(rateUi * 100).roundToInt()}%  ",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                TextButton(onClick = {
                                    rateUi = (rateUi - 0.25f).coerceAtLeast(0.5f)
                                    voicePrefs.speechRate().set(rateUi)
                                }) { Text("−") }
                                TextButton(onClick = {
                                    rateUi = (rateUi + 0.25f).coerceAtMost(2.0f)
                                    voicePrefs.speechRate().set(rateUi)
                                }) { Text("+") }
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

                            // ---- Те же кнопки, что в нативной читалке манги ----
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (isAuto) "Стоп автопрокрутки  " else "Автопрокрутка  ",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                SmallFloatingActionButton(onClick = {
                                    isAuto = !isAuto
                                }) {
                                    Icon(
                                        if (isAuto) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                                        contentDescription = "Автопрокрутка",
                                        tint = if (isAuto) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                            // Скорость плавной прокрутки: ×1…×10 (работает и для
                            // автопрокрутки, и для шага авточтения между кадрами).
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Прокрутка: ×${speed.roundToInt()}  ", style = MaterialTheme.typography.labelMedium)
                                TextButton(onClick = { speed = (speed - 1f).coerceAtLeast(1f) }) { Text("−") }
                                TextButton(onClick = { speed = (speed + 1f).coerceAtMost(10f) }) { Text("+") }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Прочитать страницу  ", style = MaterialTheme.typography.labelMedium)
                                SmallFloatingActionButton(onClick = {
                                    menuOpen = false
                                    isAuto = false
                                    oneShotRead = true
                                    isAutoRead = true
                                }) {
                                    Icon(Icons.Outlined.RecordVoiceOver, contentDescription = "Прочитать страницу")
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Стоп чтения  ", style = MaterialTheme.typography.labelMedium)
                                SmallFloatingActionButton(onClick = {
                                    oneShotRead = false
                                    isAutoRead = false
                                    readEngine.stop()
                                }) {
                                    Icon(Icons.Outlined.Stop, contentDescription = "Стоп чтения")
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Скриншот сейчас  ", style = MaterialTheme.typography.labelMedium)
                                SmallFloatingActionButton(onClick = {
                                    menuOpen = false
                                    saveScreenshotNow()
                                }) {
                                    Icon(Icons.Outlined.PhotoCamera, contentDescription = "Скриншот")
                                }
                            }
                            // Движок OCR: не только онлайн — кликом листается Авто/Онлайн/Офлайн.
                            var ocrMode by remember { mutableStateOf(browserOcrMode(ctx)) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val ocrLabel = when (ocrMode) {
                                    "online" -> "OCR: Онлайн (Google Lens)  "
                                    "offline" -> "OCR: Офлайн (Paddle кирилл.)  "
                                    else -> "OCR: Авто  "
                                }
                                Text(ocrLabel, style = MaterialTheme.typography.labelMedium)
                                SmallFloatingActionButton(onClick = {
                                    ocrMode = when (ocrMode) {
                                        "online" -> "offline"
                                        "offline" -> "auto"
                                        else -> "online"
                                    }
                                    setBrowserOcrMode(ctx, ocrMode)
                                    // Применяем сразу: следующий кадр авточтения
                                    // должен идти уже новым движком.
                                    applyBrowserOcrMode(ctx)
                                }) {
                                    Icon(Icons.Outlined.Tune, contentDescription = "Движок OCR")
                                }
                            }
                            // SSL: принимать проблемные сертификаты (для сайтов с
                            // ручной подписью, у которых иначе не грузится страница).
                            var acceptSsl by remember { mutableStateOf(getBrowserSslPolicy(ctx)) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (acceptSsl) "SSL: принимать  " else "SSL: блокировать  ",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                SmallFloatingActionButton(onClick = {
                                    acceptSsl = !acceptSsl
                                    setBrowserSslPolicy(ctx, acceptSsl)
                                }) {
                                    Icon(Icons.Outlined.Lock, contentDescription = "SSL-политика")
                                }
                            }
                            // AdBlock: рекламные сети режем по сети, промо-карточки
                            // («В паках больше выбора» и подобные) убирает контент-
                            // скрипт — попап больше не лезет в диалог авточтения и
                            // не отыгрывает звуки поверх речи.
                            var adBlockOn by remember { mutableStateOf(BrowserAdBlock.isEnabled(ctx)) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (adBlockOn) "AdBlock: вкл  " else "AdBlock: выкл  ",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                SmallFloatingActionButton(onClick = {
                                    adBlockOn = !adBlockOn
                                    BrowserAdBlock.setEnabled(ctx, adBlockOn)
                                    if (adBlockOn) {
                                        // Новая страница развернёт фильтр сама;
                                        // на открытой — включаем обратно на месте.
                                        BrowserAdBlock.inject(sharedWebView)
                                        BrowserAdBlock.setEnabled(sharedWebView, true)
                                        ctx.toast("AdBlock включён — реклама убирается со страницы")
                                    } else {
                                        BrowserAdBlock.setEnabled(sharedWebView, false)
                                        ctx.toast("AdBlock выключен — страница как есть")
                                    }
                                }) {
                                    Icon(
                                        Icons.Outlined.Block,
                                        contentDescription = "AdBlock",
                                        tint = if (adBlockOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (voiceIcons) "Значки озвучки: вкл  " else "Значки озвучки: выкл  ",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                SmallFloatingActionButton(onClick = {
                                    voicePrefs.voiceIcons().set(!voiceIcons)
                                }) {
                                    Icon(
                                        Icons.Outlined.RecordVoiceOver,
                                        contentDescription = "Значки озвучки",
                                        tint = if (voiceIcons) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Голоса  ", style = MaterialTheme.typography.labelMedium)
                                SmallFloatingActionButton(onClick = {
                                    menuOpen = false
                                    voiceOpen = true
                                }) {
                                    Icon(Icons.Outlined.RecordVoiceOver, contentDescription = "Голоса")
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
                            fabX += dragAmount.x
                            fabY += dragAmount.y
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
                        TextButton(onClick = { moreOpen = false; downloadsOpen = true }) { Text("Загрузки") }
                        TextButton(onClick = { moreOpen = false; cacheOpen = true }) { Text("Кэш и данные") }
                        TextButton(onClick = { moreOpen = false; saveHtmlPage() }) { Text("Сохранить страницу (HTML)") }
                        TextButton(onClick = {
                            moreOpen = false
                            // v1.9.55: плавающий мини-плеер ПОВЕРХ ВСЕХ приложений —
                            // настоящее окно поверх любого приложения. Если разрешение
                            // «Показ поверх других приложений» не выдано, открываем
                            // системные настройки; иначе запускаем foreground-сервис.
                            val url = sharedWebView?.url ?: urlBar
                            if (MiniOverlayService.canDrawOverlays(ctx)) {
                                MiniOverlayService.start(ctx, url)
                                ctx.toast("Мини-плеер поверх всех приложений")
                            } else {
                                MiniOverlayService.requestPermission(ctx)
                                ctx.toast("Разрешите показ поверх других приложений")
                            }
                        }) { Text("Мини-плеер поверх всех приложений") }
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
        if (sitesOpen) {
            AlertDialog(
                onDismissRequest = { sitesOpen = false },
                confirmButton = { TextButton(onClick = { sitesOpen = false }) { Text("Закрыть") } },
                title = { Text("Каталог сайтов") },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        siteCatalog.forEach { group ->
                            Text(
                                group.title,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                            )
                            group.sites.forEach { site ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            sitesOpen = false
                                            loadUrlInput(site.url)
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(site.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(site.desc, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Icon(
                                        Icons.Outlined.OpenInNew,
                                        contentDescription = "Открыть",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
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
                rows = webMarks.sortedBy { it.title }.map { Triple(it.id, it.title, it.url) },
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
                    WebStore.switchTab(ctx, id)
                    tabsOpen = false
                },
                onDelete = { id ->
                    WebStore.closeTab(ctx, id)
                    webViewPool.remove(id)?.let { old ->
                        runCatching { (old.parent as? ViewGroup)?.removeView(old) }
                        runCatching { old.destroy() }
                    }
                },
                newLabel = "＋ Новая вкладка",
                onNew = {
                    // v1.9.44: новая вкладка продолжает последний открытый сайт.
                    val last = WebStore.tabs.value.lastOrNull { it.url.isNotBlank() }
                    if (last != null) WebStore.addTab(ctx, last.url, last.title) else WebStore.addTab(ctx, HOME_URL, "Новая вкладка")
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
        if (downloadsOpen) {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val files = remember {
                downloadsDir.listFiles()?.sortedByDescending { it.lastModified() }?.take(100) ?: emptyList()
            }
            AlertDialog(
                onDismissRequest = { downloadsOpen = false },
                confirmButton = { TextButton(onClick = { downloadsOpen = false }) { Text("Закрыть") } },
                title = { Text("Загрузки (${files.size})") },
                text = {
                    if (files.isEmpty()) {
                        Text("Папка Download пуста")
                    } else {
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .heightIn(max = 400.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            files.forEach { f ->
                                val sizeKb = f.length() / 1024
                                val sizeStr = if (sizeKb > 1024) String.format("%.1f МБ", sizeKb / 1024.0) else "$sizeKb КБ"
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            runCatching {
                                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                                    ctx, "${ctx.packageName}.provider", f,
                                                )
                                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                                    setDataAndType(uri, ctx.contentResolver.getType(uri) ?: "*/*")
                                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                ctx.startActivity(intent)
                                            }
                                        }
                                        .padding(vertical = 6.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(f.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(sizeStr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Icon(Icons.Outlined.OpenInNew, contentDescription = "Открыть", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
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
        // v1.9.41: распознавание СРАЗУ НА КАДРЕ: замороженный кадр, прогресс на нём,
        // текст реплик поверх своих рамок — без заливки и без шторки уведомлений.
        // v1.9.44: результат скана — компактная карточка СВЕРХУ СБОКУ (как просил
        // пользователь): ничего не рисуется поверх страницы, никаких рамок.
        if (ocrBusy || !ocrText.isNullOrBlank()) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp, start = 48.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
                        RoundedCornerShape(12.dp),
                    )
                    .padding(10.dp)
                    .zIndex(20f),
            ) {
                if (ocrBusy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Text("Распознавание…", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text(
                        text = ocrText ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .heightIn(max = 220.dp),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    if (!ocrBusy && !ocrText.isNullOrBlank()) {
                        TextButton(onClick = {
                            val clipboard = ctx.getSystemService(android.content.ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(null, ocrText))
                        }) { Text("Копир.") }
                        TextButton(onClick = { TtsSpeaker.speak(ctx, ocrText ?: "") }) { Text("Голос") }
                    }
                    TextButton(onClick = {
                        ocrJob?.cancel()
                        ocrBusy = false
                        ocrText = null
                        ocrRegions = emptyList()
                        showOcrCard = false
                        ocrFrame?.let { runCatching { it.recycle() } }
                        ocrFrame = null
                    }) { Text("Закрыть") }
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

/** v1.9.41: препроцесс (grayscale + контраст) — бледный текст читается. */
private fun preprocessForOcr(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val out = android.graphics.Bitmap.createBitmap(src.width, src.height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val gray = android.graphics.Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(
                android.graphics.ColorMatrix(
                    floatArrayOf(
                        0.33f, 0.33f, 0.33f, 0f, 0f,
                        0.33f, 0.33f, 0.33f, 0f, 0f,
                        0.33f, 0.33f, 0.33f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }
        canvas.drawBitmap(src, 0f, 0f, gray)
        val k = 1.6f
        val tr = 128 * (1 - k)
        val contrast = android.graphics.Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(
                android.graphics.ColorMatrix(
                    floatArrayOf(
                        k, 0f, 0f, 0f, tr,
                        0f, k, 0f, 0f, tr,
                        0f, 0f, k, 0f, tr,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }
        canvas.drawBitmap(out, 0f, 0f, contrast)
        return out
    }

/**
 * Применить выбор движка из меню браузера (browser_ocr/engine_mode) к общему
 * префу `pref_ocr_model`, по которому scanPage и берёт движок.
 *
 * Раньше меню писало только в engine_mode, а `ocrWithPreprocess` применял его
 * лишь при РУЧНОМ скане: авточтение (readEngine.readFrame) всё время шло
 * дефолтным CYRILLIC, даже когда выбрали «Google Lens».
 */
private fun applyBrowserOcrMode(context: android.content.Context) {
    val mode = context.getSharedPreferences("browser_ocr", 0)
        .getString("engine_mode", "auto") ?: "auto"
    val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
    val net = cm?.activeNetwork
    val online = net != null &&
        (cm.getNetworkCapabilities(net)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false)
    val want = when {
        mode == "offline" -> mihon.domain.ocr.model.OcrModel.CYRILLIC
        mode == "online" -> mihon.domain.ocr.model.OcrModel.GLENS
        online -> mihon.domain.ocr.model.OcrModel.GLENS
        else -> mihon.domain.ocr.model.OcrModel.CYRILLIC
    }
    Injekt.get<mihon.domain.ocr.service.OcrPreferences>().ocrModel().set(want)
}

/** OCR с препроцессом: текст + рамки реплик (нормализованные 0..1). */
private suspend fun ocrWithPreprocess(context: android.content.Context, src: android.graphics.Bitmap): Pair<String, List<Pair<mihon.domain.ocr.model.OcrBoundingBox, String>>> {
        val pre = preprocessForOcr(src)
        val bmp = if (pre.width < 1200) {
            val k = minOf(3f, 1200f / pre.width)
            android.graphics.Bitmap.createScaledBitmap(pre, (pre.width * k).toInt(), (pre.height * k).toInt(), true)
        } else {
            pre
        }
        return try {
            val ocr = Injekt.get<mihon.domain.ocr.interactor.ScanPageOcr>()
            // Режим из меню браузера применяем к общему префу — по нему scanPage
            // и выбирает движок (общий путь для ручного скана и авточтения).
            applyBrowserOcrMode(context)
            val prefsO = Injekt.get<mihon.domain.ocr.service.OcrPreferences>()
            val want = prefsO.ocrModel().get()
            val pgIdx = (System.currentTimeMillis() % 1_000_000L).toInt()
            var regions = withTimeout(60_000) {
                ocr.await(chapterId = -1L, pageIndex = pgIdx, image = bmp.toOcrImage())
            }.let(::ocrFixedRegions)
            if (regions.isEmpty() && want == mihon.domain.ocr.model.OcrModel.GLENS) {
                // Онлайн не ответил (лимит/сеть) — откат на офлайн-кириллицу.
                prefsO.ocrModel().set(mihon.domain.ocr.model.OcrModel.CYRILLIC)
                regions = withTimeout(180_000) {
                    ocr.await(chapterId = -1L, pageIndex = pgIdx, image = bmp.toOcrImage())
                }.let(::ocrFixedRegions)
            }
            regions.joinToString("\n") { it.second } to regions
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "" to emptyList()
        }
    }

/** v1.9.44: фиксер похожих символов (M E Ч / А Т М / 5→Б) до вывода. */
private fun ocrFixedRegions(
    res: mihon.domain.ocr.model.OcrPageResult,
): List<Pair<mihon.domain.ocr.model.OcrBoundingBox, String>> =
    res.regions
        .map { it.boundingBox to mihon.data.ocr.CyrillicTranslitFixer.autoFixCyrillic(it.text).trim() }
        .filter { it.second.isNotBlank() }


/**
 * Быстрый выбор голосов в мини-браузере: роли (1 / 2 / много голосов), движок
 * (Авто — веб онлайн / локальный оффлайн) и значки 🔊 на репликах. Пишет в те же
 * преференсы, что и настройки озвучки читалки, поэтому выбор согласован.
 */
@Composable
private fun VoiceQuickDialog(
    onDismiss: () -> Unit,
    roleMode: String,
    voiceEngine: String,
    voiceIcons: Boolean,
    onRoleMode: (String) -> Unit,
    onVoiceEngine: (String) -> Unit,
    onVoiceIcons: (Boolean) -> Unit,
) {
    val modeOptions = listOf(
        eu.kanade.tachiyomi.data.tts.VoiceModeResolver.Mode.SINGLE to "Один голос",
        eu.kanade.tachiyomi.data.tts.VoiceModeResolver.Mode.DUAL to "Два голоса",
        eu.kanade.tachiyomi.data.tts.VoiceModeResolver.Mode.MULTI to "Много голосов",
    )
    val engineOptions = listOf(
        eu.kanade.tachiyomi.data.tts.TtsSpeaker.ENGINE_AUTO to "Авто (веб онлайн / локально оффлайн)",
        eu.kanade.tachiyomi.data.tts.TtsSpeaker.ENGINE_GOOGLE_WEB to "Веб (Google, без ключа)",
        eu.kanade.tachiyomi.data.tts.TtsSpeaker.ENGINE_EDGE_TTS to "Edge TTS (Microsoft, без ключа)",
        eu.kanade.tachiyomi.data.tts.TtsSpeaker.ENGINE_SYSTEM to "Локально (системные)",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
        title = { Text("Голоса в браузере") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Роли", style = MaterialTheme.typography.labelLarge)
                modeOptions.forEach { (m, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onRoleMode(m.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = roleMode == m.id, onClick = { onRoleMode(m.id) })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    "Движок",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 6.dp),
                )
                engineOptions.forEach { (e, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onVoiceEngine(e) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = voiceEngine == e, onClick = { onVoiceEngine(e) })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    "Значки озвучки",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onVoiceIcons(!voiceIcons) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = voiceIcons, onClick = { onVoiceIcons(!voiceIcons) })
                    Text(
                        if (voiceIcons) "Показывать 🔊 на репликах" else "Значки выключены",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
    )
}
