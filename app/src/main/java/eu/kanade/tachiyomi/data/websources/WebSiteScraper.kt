package eu.kanade.tachiyomi.data.websources

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Разбор страницы сайта в список карточек.
 *
 * Зачем: каталог сайтов умел только «открыть страницу поиска в браузере», то есть
 * результат был не в родной сетке. Здесь из HTML достаются обложка, название и
 * ссылка — и результат показывается так же, как выдача расширения.
 *
 * Парсер намеренно **двухуровневый**:
 *
 * 1. Если у сайта заданы CSS-селекторы ([WebSite.itemSelector] и т. д.) — берём их.
 *    Это точный путь, и селекторы правятся в приложении, без сборки.
 * 2. Если селекторов нет — работает эвристика: ищем ссылки, похожие на страницу
 *    тайтла (путь вида `/manga/…`, `/ranobe/…`, есть картинка, текст похож на
 *    название). Это «обычно работает», но может ошибаться — поэтому первый
 *    вариант всегда доступен.
 *
 * Разбор отделён от сети: на вход идёт HTML-строка. Так его можно проверить
 * тестами на сохранённых страницах, не ходя в интернет.
 */
data class WebItem(
    val title: String,
    val url: String,
    val cover: String?,
)

object WebSiteScraper {

    /** Куски пути, по которым видно страницу тайтла у большинства сайтов. */
    private val TITLE_PATH_HINTS = listOf(
        "/manga/", "/manga-", "/comic/", "/comics/", "/ranobe/", "/novel/",
        "/book/", "/anime/", "/series/", "/title/", "/catalog/", "/read/",
    )

    /** Признаки служебных блоков: навигация, фильтры, пагинация. */
    private val NOISE_CLASS_HINTS = listOf(
        "pagination", "breadcrumb", "navbar", "nav-", "menu", "footer", "header",
        "sidebar", "genre", "tag-list", "social", "comments", "advert",
    )

    private const val MAX_ITEMS = 40

    /**
     * Разобрать страницу.
     *
     * @param html исходный HTML
     * @param baseUrl адрес страницы (для относительных ссылок и картинок)
     * @param site сайт — из него берутся необязательные селекторы
     */
    fun parse(html: String, baseUrl: String, site: WebSite? = null): List<WebItem> {
        val doc = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull() ?: return emptyList()

        site?.itemSelector?.takeIf { it.isNotBlank() }?.let { selector ->
            val fromSelector = parseBySelector(doc, selector, site)
            if (fromSelector.isNotEmpty()) return fromSelector
        }

        return parseGenerically(doc)
    }

    private fun parseBySelector(doc: Document, selector: String, site: WebSite): List<WebItem> {
        val cards = runCatching { doc.select(selector) }.getOrNull() ?: return emptyList()
        return cards.mapNotNull { card -> itemFromCard(card, site) }
            .distinctBy { it.url }
            .take(MAX_ITEMS)
    }

    private fun itemFromCard(card: Element, site: WebSite): WebItem? {
        val titleSelector = site.titleSelector?.takeIf { it.isNotBlank() }
        val titleElement = titleSelector?.let { sel -> runCatching { card.selectFirst(sel) }.getOrNull() }

        val link = titleElement?.absUrl("href")?.takeIf { it.isNotBlank() }
            ?: card.selectFirst("a[href]")?.absUrl("href")?.takeIf { it.isNotBlank() }
            ?: return null

        val title = titleElement?.text()?.takeIf { it.isNotBlank() }
            ?: card.selectFirst("a[href][title]")?.attr("title")?.takeIf { it.isNotBlank() }
            ?: card.selectFirst("img[alt]")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: card.selectFirst("a[href]")?.text()?.takeIf { it.isNotBlank() }
            ?: return null

        val cover = site.coverSelector
            ?.takeIf { it.isNotBlank() }
            ?.let { sel -> runCatching { card.selectFirst(sel) }.getOrNull() }
            ?.let(::imageUrl)
            ?: card.selectFirst("img")?.let(::imageUrl)

        return WebItem(title.trim(), link, cover)
    }

    private fun parseGenerically(doc: Document): List<WebItem> {
        val items = mutableListOf<WebItem>()
        val seen = mutableSetOf<String>()

        for (anchor in doc.select("a[href]")) {
            if (items.size >= MAX_ITEMS) break
            if (isNoise(anchor)) continue

            val href = anchor.absUrl("href")
            if (href.isBlank() || !href.startsWith("http")) continue
            if (!seen.add(href)) continue
            val lower = href.lowercase()
            if ("/genre/" in lower || "/tag/" in lower || "?page=" in lower) continue

            val img = anchor.selectFirst("img")
            val title = when {
                anchor.attr("title").isNotBlank() -> anchor.attr("title")
                img?.attr("alt")?.isNotBlank() == true -> img.attr("alt")
                else -> anchor.text()
            }.trim()

            if (title.length !in 2..120) continue

            // Картинка рядом (внутри ссылки или в её родителе) — почти всегда карточка.
            val cover = img?.let(::imageUrl)
                ?: anchor.parent()?.selectFirst("img")?.let(::imageUrl)

            val looksLikeTitle = TITLE_PATH_HINTS.any { it in lower }
            if (!looksLikeTitle && cover == null) continue

            items += WebItem(title, href, cover)
        }

        return items.take(MAX_ITEMS)
    }

    private fun imageUrl(img: Element): String? {
        val raw = listOf("data-src", "data-original", "data-lazy-src", "src")
            .map { img.attr(it) }
            .firstOrNull { it.isNotBlank() }
            ?: return null
        if (raw.startsWith("http")) return raw
        val absolute = img.absUrl("src")
        return absolute.takeIf { it.startsWith("http") }
    }

    private fun isNoise(anchor: Element): Boolean {
        var node: Element? = anchor
        var depth = 0
        while (node != null && depth < 6) {
            val tag = node.tagName().lowercase()
            if (tag == "nav" || tag == "footer" || tag == "header") return true
            val cls = node.className().lowercase()
            if (cls.isNotEmpty() && NOISE_CLASS_HINTS.any { it in cls }) return true
            node = node.parent()
            depth++
        }
        return false
    }
}
