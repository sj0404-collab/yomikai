package eu.kanade.tachiyomi.data.websources

import android.content.Context
import eu.kanade.domain.source.service.SourcePreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Каталог сайтов-источников (манга, аниме, ранобэ, книги).
 *
 * Зачем: расширения для RU-сайтов регулярно умирают — сайт переезжает на новый
 * домен, Cloudflare меняет правила, расширение перестаёт обновляться. Тогда
 * «источник» в приложении показывает пустоту и молчит. Каталог хранит не парсер,
 * а **адреса**: где сайт живёт сейчас, как выглядит его страница поиска и
 * страница новинок. Поэтому:
 *
 *  - домен не «зашит» — он берётся из каталога, и его можно поменять в приложении;
 *  - если домен умер, есть список зеркал, и приложение само переходит на живое;
 *  - поиск и «новинки» открываются на самом сайте, значит редизайн сайта ничего
 *    не ломает (парсить нечего);
 *  - у каждого типа контента (манга/аниме/ранобэ/книги/дорамы) свой список.
 *
 * Шаблоны: `{q}` подставляется как URL-кодированный запрос, `{host}` — текущий
 * домен сайта. Пользовательские правки (домен, шаблоны, включён/выключен) лежат
 * в prefs и переживают обновление приложения.
 */
data class WebSite(
    val id: String,
    val name: String,
    /** MANGA / ANIME / RANOBE / BOOKS / DRAMAS — совпадает с ContentType.name. */
    val type: String,
    /** Домены: первый — основной, дальше зеркала в порядке предпочтения. */
    val hosts: List<String>,
    /** Шаблон страницы поиска (`{host}` и `{q}`), null — на сайте нет поиска по URL. */
    val search: String?,
    /** Шаблон страницы новинок, null — неизвестен. */
    val latest: String?,
    /** Предупреждение для интерфейса (например «нужна авторизация»). */
    val note: String? = null,
    /**
     * CSS-селектор карточки результата в выдаче сайта. Если задан, разбор идёт
     * строго по нему — это надёжнее эвристики и правится прямо в приложении.
     */
    val itemSelector: String? = null,
    /** Селектор названия/ссылки внутри карточки. */
    val titleSelector: String? = null,
    /** Селектор обложки внутри карточки. */
    val coverSelector: String? = null,
)

object WebSiteCatalog {

    /**
     * Засеянный список. Домены проверялись запросом с телефона/сервера 25.10.2026:
     * живые идут первыми, мёртвые (mangalib.to, novelib.me, readmanga.eu,
     * manga-chan.me, usagi.one, yaoilib.com, desu.me) в каталог НЕ включены.
     */
    val SEED: List<WebSite> = listOf(
        // ── манга ────────────────────────────────────────────────────────────
        WebSite(
            id = "mangalib",
            name = "MangaLib",
            type = "MANGA",
            hosts = listOf("mangalib.org", "mangalib.me", "mangalib.mobi"),
            search = "https://{host}/search?q={q}",
            latest = "https://{host}/manga?sort=last_chapter_at",
            note = "Часто закрыт Cloudflare: если пусто — откройте через зеркало или прокси.",
        ),
        WebSite(
            id = "mangahub",
            name = "MangaHub",
            type = "MANGA",
            hosts = listOf("mangahub.io"),
            // Параметр именно «q»: в реальной выдаче ссылки выглядят как
            // /search?q=…&order=…, а «query=» сайт молча игнорирует.
            search = "https://{host}/search?q={q}",
            latest = "https://{host}/manga-list/latest-manga",
            // Селекторы проверены 25.10.2026 на живой странице выдачи.
            itemSelector = ".media-manga, .media",
            titleSelector = "h4.media-heading a[href]",
            coverSelector = "img.manga-thumb",
        ),
        WebSite(
            id = "remanga",
            name = "Remanga",
            type = "MANGA",
            hosts = listOf("remanga.org"),
            search = "https://{host}/search?query={q}",
            latest = "https://{host}/manga?ordering=-chapter_date",
        ),
        WebSite(
            id = "mangabuff",
            name = "Mangabuff",
            type = "MANGA",
            hosts = listOf("mangabuff.ru"),
            search = "https://{host}/search?q={q}",
            latest = "https://{host}/manga?sort=new",
        ),
        WebSite(
            id = "acomics",
            name = "Acomics",
            type = "MANGA",
            hosts = listOf("acomics.ru"),
            search = "https://{host}/search?q={q}",
            latest = "https://{host}/comics?sort=update",
        ),
        WebSite(
            id = "1manga",
            name = "1Manga",
            type = "MANGA",
            hosts = listOf("1manga.co"),
            search = "https://{host}/search?q={q}",
            latest = "https://{host}/latest",
        ),
        // ── ранобэ ──────────────────────────────────────────────────────────
        WebSite(
            id = "ruranobe",
            name = "RuRanobe",
            type = "RANOBE",
            hosts = listOf("ruranobe.ru"),
            search = "https://{host}/search?q={q}",
            latest = "https://{host}/ranobe?sort=last_chapter",
        ),
        WebSite(
            id = "ranobelib",
            name = "RanobeLib",
            type = "RANOBE",
            hosts = listOf("ranobelib.me"),
            search = "https://{host}/search?q={q}",
            latest = "https://{host}/ranobe?sort=last_chapter_at",
        ),
        WebSite(
            id = "ranobehub",
            name = "RanobeHub",
            type = "RANOBE",
            hosts = listOf("ranobehub.org"),
            search = "https://{host}/search?query={q}",
            latest = "https://{host}/ranobe?sort=lastChapter",
        ),
        WebSite(
            id = "author_today",
            name = "Author.Today",
            type = "BOOKS",
            hosts = listOf("author.today"),
            search = "https://{host}/search?q={q}",
            latest = "https://{host}/new",
        ),
        // ── аниме ───────────────────────────────────────────────────────────
        WebSite(
            id = "animevost",
            name = "AnimeVost",
            type = "ANIME",
            hosts = listOf("animevost.org"),
            search = "https://{host}/?do=search&subaction=search&story={q}",
            latest = "https://{host}/",
        ),
        WebSite(
            id = "anilibria",
            name = "AniLibria",
            type = "ANIME",
            hosts = listOf("anilibria.tv"),
            search = "https://{host}/?do=search&subaction=search&story={q}",
            latest = "https://{host}/",
        ),
        WebSite(
            id = "animego",
            name = "AnimeGO",
            type = "ANIME",
            hosts = listOf("animego.org"),
            search = "https://{host}/search/anime?q={q}",
            latest = "https://{host}/anime",
        ),
        WebSite(
            id = "animelib",
            name = "Animelib",
            type = "ANIME",
            hosts = listOf("animelib.org"),
            search = "https://{host}/search?q={q}",
            latest = "https://{host}/",
        ),
    )

    private const val PREFS_HOSTS = "web_catalog_hosts"
    private const val PREFS_TEMPLATES = "web_catalog_templates"
    private const val PREFS_DISABLED = "web_catalog_disabled"
    private const val PREFS_SELECTORS = "web_catalog_selectors"

    /** Все сайты с учётом пользовательских правок. */
    fun all(prefs: SourcePreferences, context: Context? = null): List<WebSite> =
        SEED.map { withOverrides(prefs, it) }

    /** Сайты одного типа контента (MANGA/ANIME/…). */
    fun ofType(prefs: SourcePreferences, type: String): List<WebSite> =
        all(prefs).filter { it.type == type }

    /** Включён ли сайт (выключенные не участвуют в поиске по всем сайтам). */
    fun isEnabled(prefs: SourcePreferences, id: String): Boolean =
        !runCatching { JSONArray(prefs.webCatalogDisabled.get()) }.getOrDefault(JSONArray())
            .let { arr -> (0 until arr.length()).any { arr.optString(it) == id } }

    fun setEnabled(prefs: SourcePreferences, id: String, enabled: Boolean) {
        val arr = runCatching { JSONArray(prefs.webCatalogDisabled.get()) }.getOrDefault(JSONArray())
        val ids = (0 until arr.length()).map { arr.optString(it) }.toMutableSet()
        if (enabled) ids.remove(id) else ids.add(id)
        prefs.webCatalogDisabled.set(JSONArray(ids.toList()).toString())
    }

    /** Текущий домен сайта: пользовательский → первый из каталога. */
    fun hostOf(prefs: SourcePreferences, site: WebSite): String = withOverrides(prefs, site).hosts.first()

    /**
     * Сменить домен вручную. Пользовательский домен ставится первым, остальные
     * зеркала остаются как запасные — следующая смена произойдёт автоматически.
     */
    fun setHost(prefs: SourcePreferences, id: String, host: String) {
        val clean = host.trim().removePrefix("https://").removePrefix("http://").removeSuffix("/")
        if (clean.isBlank()) return
        val root = runCatching { JSONObject(prefs.webCatalogHosts.get()) }.getOrDefault(JSONObject())
        root.put(id, clean)
        prefs.webCatalogHosts.set(root.toString())
    }

    fun withOverrides(prefs: SourcePreferences, site: WebSite): WebSite {
        val hostsRoot = runCatching { JSONObject(prefs.webCatalogHosts.get()) }.getOrDefault(JSONObject())
        val manual = hostsRoot.optString(site.id, "").takeIf { it.isNotBlank() }
        val hosts = if (manual == null) site.hosts else listOf(manual) + site.hosts.filter { it != manual }
        val tplRoot = runCatching { JSONObject(prefs.webCatalogTemplates.get()) }.getOrDefault(JSONObject())
        val obj = tplRoot.optJSONObject(site.id)
        val selRoot = runCatching { JSONObject(prefs.webCatalogSelectors.get()) }.getOrDefault(JSONObject())
        val sel = selRoot.optJSONObject(site.id)
        return site.copy(
            hosts = hosts,
            search = obj?.optString("search")?.takeIf { it.isNotBlank() } ?: site.search,
            latest = obj?.optString("latest")?.takeIf { it.isNotBlank() } ?: site.latest,
            itemSelector = sel?.optString("item")?.takeIf { it.isNotBlank() } ?: site.itemSelector,
            titleSelector = sel?.optString("title")?.takeIf { it.isNotBlank() } ?: site.titleSelector,
            coverSelector = sel?.optString("cover")?.takeIf { it.isNotBlank() } ?: site.coverSelector,
        )
    }

    /** Пользовательский шаблон поиска/новинок — «исправить один раз». */
    fun setTemplate(prefs: SourcePreferences, id: String, search: String?, latest: String?) {
        val root = runCatching { JSONObject(prefs.webCatalogTemplates.get()) }.getOrDefault(JSONObject())
        root.put(
            id,
            JSONObject().apply {
                search?.takeIf { it.isNotBlank() }?.let { put("search", it) }
                latest?.takeIf { it.isNotBlank() }?.let { put("latest", it) }
            },
        )
        prefs.webCatalogTemplates.set(root.toString())
    }

    /** Адрес поиска на сайте; null — у сайта нет шаблона, открываем главную. */
    fun searchUrl(site: WebSite, query: String): String? =
        site.search?.let { tpl ->
            tpl.replace("{host}", site.hosts.first())
                .replace("{q}", android.net.Uri.encode(query.trim()))
        }

    fun latestUrl(site: WebSite): String? =
        site.latest?.replace("{host}", site.hosts.first())
            ?: site.hosts.first().let { "https://$it/" }

    fun homeUrl(site: WebSite): String = "https://${site.hosts.first()}/"

    /** Сохранить CSS-селекторы разбора (пусто — вернуть каталог/эвристику). */
    fun setSelectors(prefs: SourcePreferences, id: String, item: String?, title: String?, cover: String?) {
        val root = runCatching { JSONObject(prefs.webCatalogSelectors.get()) }.getOrDefault(JSONObject())
        root.put(
            id,
            JSONObject().apply {
                item?.takeIf { it.isNotBlank() }?.let { put("item", it) }
                title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
                cover?.takeIf { it.isNotBlank() }?.let { put("cover", it) }
            },
        )
        prefs.webCatalogSelectors.set(root.toString())
    }

    fun clearSelectors(prefs: SourcePreferences, id: String) {
        runCatching {
            val root = JSONObject(prefs.webCatalogSelectors.get())
            root.remove(id)
            prefs.webCatalogSelectors.set(root.toString())
        }
    }

    /** Забыть пользовательский домен — вернуть каталог. */
    fun clearHost(prefs: SourcePreferences, id: String) {
        runCatching {
            val root = JSONObject(prefs.webCatalogHosts.get())
            root.remove(id)
            prefs.webCatalogHosts.set(root.toString())
        }
    }

    /** Забыть пользовательские шаблоны поиска/новинок. */
    fun clearTemplate(prefs: SourcePreferences, id: String) {
        runCatching {
            val root = JSONObject(prefs.webCatalogTemplates.get())
            root.remove(id)
            prefs.webCatalogTemplates.set(root.toString())
        }
    }

    /** Другой домен того же сайта — для ручного «попробовать зеркало». */
    fun nextHost(prefs: SourcePreferences, site: WebSite): String? {
        val current = withOverrides(prefs, site).hosts
        if (current.size < 2) return null
        return current.drop(1).firstOrNull()
    }
}
