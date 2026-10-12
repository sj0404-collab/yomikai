package eu.kanade.tachiyomi.ui.webbrowser

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import android.webkit.WebView
import java.io.ByteArrayInputStream

/**
 * Блокировка рекламы во встроенном браузере (v1.9.x).
 *
 * Расширения Chrome (uBlock/AdGuard) в WebView не загружаются принципиально:
 * у WebView нет Extension API. Поэтому «то же расширение» делается нативно,
 * из двух половин, как и любой блокировщик:
 *
 *  1. **Сетевой фильтр** ([shouldBlock] + [shouldInterceptRequest]):
 *     запросы на домены рекламных сетей и рекламные пути режутся в
 *     [android.webkit.WebViewClient.shouldInterceptRequest]. Главный документ
 *     (main frame) НИКОГДА не блокируется — иначе страница станет пустой.
 *
 *  2. **Косметический фильтр** ([CONTENT_SCRIPT]): контент-скрипт,
 *     вживляемый в каждую страницу. Убирает только заведомо рекламные узлы.
 *
 * ВАЖНО (исправление регрессии): раньше фильтр сканировал весь DOM каждые
 * 1.5 с, на каждую мутацию И на каждый скролл, вызывая `getComputedStyle` +
 * `getBoundingClientRect` для тысяч узлов. На тяжёлых страницах это грузило
 * рендер-процесс WebView, он падал, и вью показывала только фон (пользователь
 * видел «синий экран», после которого не открывался ни один сайт). Теперь:
 *  - нет `setInterval` и scroll-hook;
 *  - MutationObserver только на childList (без attributes), с debounce и
 *    лимитом прогонов;
 *  - селекторы узкие (реклама по advert/data-ad + рекламные iframe);
 *  - никогда не удаляются крупные узлы (>25% экрана) и контейнеры с
 *    настоящей картинкой/видео/канвасом.
 */
object BrowserAdBlock {

    private const val TAG = "BrowserAdBlock"

    /** Домены рекламных сетей и трекеров (хоста-совпадение: сам домен и поддомены). */
    private val AD_DOMAINS: Set<String> = setOf(
        // Google
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "googletagmanager.com", "googletagservices.com", "2mdn.net",
        "adservice.google.com", "partner.googleadservices.com",
        // SSP/биржажи
        "adnxs.com", "adsrvr.org", "pubmatic.com", "rubiconproject.com",
        "openx.net", "smartadserver.com", "adform.net", "ads.yahoo.com",
        "casalemedia.com", "sharethrough.com", "teads.tv", "spotxchange.com",
        "spotx.tv", "freewheel.tv", "zemanta.com", "lijit.com",
        // Тонкие сети/антибот-баннеры, которыми кормят читалки
        "adsterra.com", "clickadu.com", "propellerads.com", "hilltopads.com",
        "monetag.com", "adcash.com", "popads.net", "popcash.net",
        "exoclick.com", "exosrv.com", "juicyads.com", "trafficjunky.net",
        "trafficfactory.com", "clickunderad.com", "admediatex.net",
        "bidvertiser.com", "revcontent.com", "mgid.com", "zcoup.com",
        "adcaser.com", "visitweb.com", "onclickalgo.com", "onclicksuper.com",
        "onclckdsom.com", "adnium.com", "bidgear.com", "chromehearts.com",
        // Яндекс и СНГ-сети
        "an.yandex.ru", "adfox.ru", "adfox.yandex.ru", "mc.yandex.ru",
        "awaps.yandex.ru", "admetrica.ru", "adriver.ru", "begun.ru",
        "sape.ru", "link.ru", "directadvert.ru", "marketgid.com",
        "lentainform.com", "smi2.net", "smi2.ru", "adwad.ru", "redtram.com",
        "tencom.store", "infox.sg", "fractionalmedia.com",
    )

    /**
     * Рекламные куски в ПУТИ url (не только хост). Маркеры держим максимально
     * конкретными — общий «/banner» чужие страницы ломал бы.
     */
    private val AD_PATH_MARKERS: List<String> = listOf(
        "/adsbygoogle", "/googlesyndication", "/doubleclick", "/adservice",
        "/adsterra", "/clickadu", "/propellerads", "/hilltopads", "/monetag",
        "/adcash", "/popunder", "/popads", "/popupunder", "/exoclick",
        "/juicyads", "/trafficjunky", "/trafficfactory", "/adfox",
        "/adriver", "/begun", "/ads.js", "/advert.", "/advertisment",
    )

    /** Куда деть заблокированный запрос: пустышку, страница не должна падать. */
    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /**
     * Запрос к рекламе? Хост совпал с сетью (или её поддоменом), либо в
     * пути есть заведомо рекламный маркер.
     *
     * [mainFrame] — запрос главного документа. Его блокировать нельзя:
     * возврат пустышки на main frame даёт пустую (фоновую) страницу.
     */
    fun shouldBlock(url: String?, mainFrame: Boolean = false): Boolean {
        if (mainFrame) return false
        val raw = url?.takeIf { it.isNotBlank() } ?: return false
        val host = runCatching { Uri.parse(raw).host?.lowercase() }.getOrNull()
        if (host != null && host != "localhost") {
            for (domain in AD_DOMAINS) {
                if (host == domain || host.endsWith(".$domain")) return true
            }
        }
        val lower = raw.lowercase()
        for (marker in AD_PATH_MARKERS) {
            if (lower.contains(marker)) return true
        }
        return false
    }

    /** Вживить косметический фильтр в живой WebView (идемпотентно: страница копит один раз). */
    fun inject(view: WebView?) {
        val wv = view ?: return
        runCatching { wv.evaluateJavascript(CONTENT_SCRIPT, null) }
    }

    /** Включить/выключить фильтр на УЖЕ открытой странице (тумблер в меню). */
    fun setEnabled(view: WebView?, enabled: Boolean) {
        val wv = view ?: return
        runCatching {
            wv.evaluateJavascript("if(window.__ykAbOn)window.__ykAbOn(${enabled});true", null)
        }
    }

    /** Настройка тумблера: одна «browser_ocr» с остальными настройками браузера. */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("browser_ocr", 0).getBoolean("adblock_enabled", true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("browser_ocr", 0).edit()
            .putBoolean("adblock_enabled", enabled).apply()
    }

    /**
     * Контент-скрипт косметического фильтра. Никаких `$` — Kotlin raw string,
     * ещё и в WebView исполняется как обычный ES5-кусок.
     *
     * Удаляем только:
     *  1. узлы с явно рекламными id/class/атрибутами (advert, adsbygoogle,
     *     data-ad-slot/data-ad-client) и рекламные iframe;
     *  2. очень маленькие липкие/фиксированные плашки поверх контента
     *     (z-index >= 500 в нижней половине экрана).
     *
     * НЕ удаляем: крупные узлы (>25% экрана), контейнеры с настоящей
     * картинкой/видео/канвасом, всё из белого списка (ридеры, страницы,
     * навигация, плееры).
     */
    private val CONTENT_SCRIPT: String = """
        (function(){
          if (window.__ykAbInstalled) return;
          var ON = true, swept = 0, runs = 0, MAX_RUNS = 40;
          var AD_SEL = '[id*="advert"],[class*="advert"],' +
            '[class*="adsbygoogle"],[data-ad-slot],[data-ad-client],.adsbygoogle,' +
            'iframe[src*="ads"],iframe[src*="doubleclick"],iframe[src*="adsterra"],' +
            'iframe[src*="clickadu"],iframe[src*="propellerads"],iframe[src*="hilltopads"],' +
            'iframe[src*="monetag"],iframe[src*="adcash"],iframe[src*="popads"],' +
            'iframe[src*="exoclick"],iframe[src*="adfox"],iframe[src*="adriver"],' +
            'iframe[src*="amazon-adsystem"],iframe[src*="adnxs"],iframe[src*="googlesyndication"]';
          var KEEP = new RegExp('(reader|reading|manga|chapter|page|swiper|slide|img|image|' +
            'viewer|content|main|book|scroll|zoom|player|nav|menu|header|footer|' +
            'wrapper|container|card|toolbar|toast)', 'i');

          function name(el){
            return ((el.id || '') + ' ' + (typeof el.className === 'string' ? el.className : '')).toLowerCase();
          }
          function vis(el){
            var cs = window.getComputedStyle(el);
            if (cs.display === 'none' || cs.visibility === 'hidden') return false;
            if (parseFloat(cs.opacity || '1') < 0.05) return false;
            var r = el.getBoundingClientRect();
            return r.width > 24 && r.height > 16 && r.bottom > 0 && r.top < window.innerHeight;
          }
          // Контейнер с настоящей картинкой/видео/канвасом трогать нельзя.
          function hasMedia(el){
            try {
              var m = el.querySelectorAll('img,video,canvas');
              for (var i = 0; i < m.length; i++) {
                var r = m[i].getBoundingClientRect();
                if (r.width * r.height > window.innerWidth * window.innerHeight * 0.08) return true;
              }
            } catch (e) {}
            return false;
          }
          function within(el, frac){
            var r = el.getBoundingClientRect();
            return r.width * r.height <= window.innerWidth * window.innerHeight * frac;
          }
          function nuke(el){
            if (!el || !el.parentNode) return;
            el.parentNode.removeChild(el);
            swept++;
          }
          function sweep(){
            if (!ON || !document.body) return;
            if (runs++ > MAX_RUNS) return;

            // 1) явно рекламные узлы + рекламные iframe
            try {
              var junk = document.querySelectorAll(AD_SEL);
              var list = [];
              for (var i = 0; i < junk.length; i++) {
                var el = junk[i];
                if (KEEP.test(name(el))) continue;
                if (!vis(el)) continue;
                if (!within(el, 0.25)) continue; // крупное — не реклама
                if (hasMedia(el)) continue;      // внутри контент-картинка
                list.push(el);
              }
              for (var j = 0; j < list.length; j++) nuke(list[j]);
            } catch (e) {}

            // 2) очень маленькие липкие промо-плашки поверх контента
            try {
              var all = document.querySelectorAll('body *');
              var kill = [];
              for (var k = 0; k < all.length && k < 1500; k++) {
                var el2 = all[k];
                if (!vis(el2)) continue;
                var cs = window.getComputedStyle(el2);
                if (cs.position !== 'fixed' && cs.position !== 'sticky') continue;
                if (parseInt(cs.zIndex || '0', 10) < 500) continue;
                if (KEEP.test(name(el2))) continue;
                var r2 = el2.getBoundingClientRect();
                var frac = (r2.width * r2.height) / (window.innerWidth * window.innerHeight);
                if (frac < 0.002 || frac > 0.10) continue;
                if (r2.top < window.innerHeight * 0.4) continue;
                if (hasMedia(el2)) continue;
                kill.push(el2);
              }
              for (var m = 0; m < kill.length; m++) nuke(kill[m]);
            } catch (e) {}
          }

          function install(){
            window.__ykAbInstalled = true;
            window.__ykAbOn = function(v){ ON = v !== false; if (ON) sweep(); };
            window.__ykAbHits = function(){ return swept; };
            window.__ykAbScan = function(){ sweep(); };
            var scheduled = false;
            function kick(){
              if (scheduled) return;
              scheduled = true;
              setTimeout(function(){ scheduled = false; if (ON) sweep(); }, 400);
            }
            try {
              new MutationObserver(kick).observe(document.documentElement, {
                childList: true, subtree: true
              });
            } catch (e) {}
            document.addEventListener('DOMContentLoaded', sweep);
            window.addEventListener('load', sweep);
            sweep();
          }

          if (document.readyState === 'loading' && document.documentElement) {
            document.addEventListener('readystatechange', function onRs(){
              if (document.readyState !== 'loading') {
                document.removeEventListener('readystatechange', onRs);
                install();
              }
            });
            setTimeout(function(){ if (!window.__ykAbInstalled) install(); }, 0);
          } else {
            install();
          }
        })()
    """.trimIndent()
}
