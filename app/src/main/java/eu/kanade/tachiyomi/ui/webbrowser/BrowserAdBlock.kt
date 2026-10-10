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
 *     [android.webkit.WebViewClient.shouldInterceptRequest] — скрипты,
 *     баннеры и «звуковые эффекты» рекламы («тук-тук» поверх диалога
 *     авточтения) вообще не приезжают.
 *
 *  2. **Косметический фильтр** ([CONTENT_SCRIPT]): контент-скрипт,
 *     вживляемый в каждую страницу. Убирает узлы, которые сайт отдаёт
 *     сам ( MangaLib-промо, карточки «В паках больше выбора», telegram-
 *     оверлеи, липкие баннеры). Реклама «всё время меняется» и подселяется
 *     динамически, поэтому фильтр висит на [MutationObserver] + таймере,
 *     а не разово отрабатывает на загрузке.
 *
 * Фильтр нарочно консервативен: читательские панели ридеров, навигация
 * «← том N глава M →», крупные картинки манги в белый список не попадают.
 * Крутилку «убрать» держит пользователь — тумблер «AdBlock» в меню браузера.
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
     * Рекламные куски в ПУТИ url (не только хост: MangaLib и подобные
     * раздают рекламу со своих же поддоменов вида `img.example.com/_ads/`).
     * Маркеры держим максимально конкретными — общий «/banner» чужие
     * страницы ломал бы (сайтовые шапки тоже называют баннерами).
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
     */
    fun shouldBlock(url: String?): Boolean {
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
     * Правила удаления (по убыванию уверенности):
     *  1. узлы с рекламными id/class (adv/banner/promo/adsbygoogle/ins/telegram) и
     *     iframe'ы рекламных сетей;
     *  2. фиксированные/липкие оверлеи с z-index >= 10 в нижних 75% экрана —
     *     так выглядят промо-карточки поверх манги;
     *  3. небольшие позиционированные карточки с заведомо рекламным текстом.
     *
     * Белый список (KEEP) спасает читательский UI: ридеры, навигация глав,
     * картинки страниц, плееры. Всё, что крупнее половины экрана, не трогаем.
     */
    private val CONTENT_SCRIPT: String = """
        (function(){
          if (window.__ykAbInstalled) return;
          var ON = true, swept = 0;
          var AD_SEL = '[id*="adv"],[class*="adv"],[id*="banner"],[class*="banner"],' +
            '[class*="promo"],[class*="adsbygoogle"],[class*="adb"],' +
            'iframe[src*="ads"],iframe[src*="doubleclick"],iframe[src*="adsterra"],' +
            'iframe[src*="clickadu"],iframe[src*="propellerads"],iframe[src*="hilltopads"],' +
            'iframe[src*="monetag"],iframe[src*="adcash"],iframe[src*="popads"],' +
            'iframe[src*="exoclick"],iframe[src*="adfox"],iframe[src*="adriver"],' +
            'iframe[src*="yandex"][src*="ads"],' +
            '[class*="telegram"],[id*="telegram"],ins';
          // Ничего читательского и контентного не удаляем.
          var KEEP = new RegExp('(reader|reading|manga|chapter|page|swiper|slide|img|image|' +
            'viewer|content|main|book|scroll|zoom|player|nav|menu|header|footer|' +
            'wrapper|container|card|toolbar|toast)', 'i');
          var PROMO = new RegExp('(' +
            'промокод|подпи\\w*вайт\\w*|подпишись|мы\\s+в\\s+телеграм|наш\\s+телеграм|' +
            'телеграм-?канал|скача\\w+\\s+приложени|установи\\w+\\s+приложени|' +
            'в\\s+паках\\s+больше|перейти\\s+в\\s+(?:наш|группу|канал)|' +
            'отключите\\s+блокировщик|рекламодател|advert)', 'i');

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
          function nuke(el){
            if (!el || !el.parentNode) return;
            el.parentNode.removeChild(el);
            swept++;
          }
          function sweep(){
            if (!ON || !document.body) return;
            var vh = window.innerHeight, vw = window.innerWidth;

            // 1) явно рекламные узлы + рекламные iframe
            try {
              var junk = document.querySelectorAll(AD_SEL);
              var list = [];
              for (var i = 0; i < junk.length; i++) {
                var el = junk[i];
                if (KEEP.test(name(el))) continue;
                var r = el.getBoundingClientRect();
                if (r.width * r.height > vw * vh * 0.5) continue; // вдруг контейнер всей страницы
                if (r.bottom < 0 || r.top > vh) continue;
                list.push(el);
              }
              for (var j = 0; j < list.length; j++) nuke(list[j]);
            } catch (e) {}

            // 2) липкие/фиксированные оверлеи поверх контента (z>=10, ниже шапки)
            try {
              var all = document.querySelectorAll('body *');
              var kill = [];
              for (var k = 0; k < all.length && k < 2500; k++) {
                var el = all[k];
                if (!vis(el)) continue;
                var cs = window.getComputedStyle(el);
                var pos = cs.position;
                if (pos !== 'fixed' && pos !== 'sticky') continue;
                var z = parseInt(cs.zIndex || '0', 10);
                if (z < 10) continue;
                var r = el.getBoundingClientRect();
                if (r.top < vh * 0.25) continue; // шапка/панель ридера сверху — не наше
                if (KEEP.test(name(el))) continue;
                var frac = (r.width * r.height) / (vw * vh);
                if (frac < 0.004 || frac > 0.35) continue;
                kill.push(el);
              }
              for (var m = 0; m < kill.length; m++) nuke(kill[m]);
            } catch (e) {}

            // 3) промо по тексту: маленькая карточка с заведомо рекламным текстом
            try {
              var nodes = document.querySelectorAll('body div,body span,body a,body section,body aside,body p');
              var kill3 = [];
              for (var n = 0; n < nodes.length && n < 3000; n++) {
                var el = nodes[n];
                if (!vis(el)) continue;
                var cs = window.getComputedStyle(el);
                if (cs.position === 'static') continue;
                var z3 = parseInt(cs.zIndex || '0', 10);
                if (cs.position === 'fixed' && z3 < 10) continue;
                if (cs.position === 'absolute' && z3 < 100) continue;
                var r = el.getBoundingClientRect();
                var frac = (r.width * r.height) / (vw * vh);
                if (frac < 0.002 || frac > 0.35) continue;
                if (KEEP.test(name(el))) continue;
                var t = (el.textContent || '').replace(/\s+/g, ' ').trim();
                if (t.length > 320) continue;
                if (PROMO.test(t)) kill3.push(el);
              }
              for (var q = 0; q < kill3.length; q++) nuke(kill3[q]);
            } catch (e) {}
          }

          function install(){
            window.__ykAbInstalled = true;
            window.__ykAbOn = function(v){ ON = v !== false; if (ON) sweep(); };
            window.__ykAbHits = function(){ return swept; };
            var scheduled = false;
            function kick(){
              if (scheduled) return;
              scheduled = true;
              setTimeout(function(){ scheduled = false; sweep(); }, 250);
            }
            try {
              new MutationObserver(kick).observe(document.documentElement, {
                childList: true, subtree: true, attributes: true,
                attributeFilter: ['style', 'class', 'id', 'src']
              });
            } catch (e) {}
            setInterval(sweep, 1500);
            window.addEventListener('scroll', kick, true);
            window.addEventListener('pageshow', sweep);
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
            // На случай SPA: документ уже живой — ставим сразу.
            setTimeout(function(){ if (!window.__ykAbInstalled) install(); }, 0);
          } else {
            install();
          }
        })()
    """.trimIndent()
}
