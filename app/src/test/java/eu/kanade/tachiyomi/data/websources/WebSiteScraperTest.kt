package eu.kanade.tachiyomi.data.websources

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Разбор выдачи сайта проверяется на сохранённой разметке, без интернета.
 *
 * Фикстуры повторяют реальную вёрстку (mangahub: карточки `.media-manga` с
 * `h4.media-heading` и картинкой `img.manga-thumb`, вокруг — навигация с
 * жанрами), чтобы регрессии в эвристике и селекторах ловились тестом, а не
 * пользователем.
 */
class WebSiteScraperTest {

    private val base = "https://mangahub.io/search?q=berserk"

    private val html = """
        <html><body>
        <nav class="navbar"><a href="https://mangahub.io/search">Search</a>
          <a href="https://mangahub.io/genre/action">Action</a></nav>
        <div class="row">
          <div class="_1KYcM col-sm-6">
            <div class="media-manga media">
              <div class="media-left">
                <a href="https://mangahub.io/manga/martial-peak">
                  <img loading="lazy" src="https://thumb.mghcdn.com/rm/martial-peak.jpg" alt="Martial Peak"/>
                </a>
              </div>
              <div class="media-body">
                <h4 class="media-heading">
                  <a href="https://mangahub.io/manga/martial-peak">Martial Peak</a>
                </h4>
              </div>
            </div>
          </div>
          <div class="_1KYcM col-sm-6">
            <div class="media-manga media">
              <div class="media-left">
                <a href="https://mangahub.io/manga/solo-leveling_105">
                  <img loading="lazy" src="https://thumb.mghcdn.com/mh/solo-leveling.jpg"/>
                </a>
              </div>
              <div class="media-body">
                <h4 class="media-heading">
                  <a href="https://mangahub.io/manga/solo-leveling_105">Solo Leveling</a>
                </h4>
              </div>
            </div>
          </div>
        </div>
        <ul class="pagination"><li><a href="https://mangahub.io/search?page=2">2</a></li></ul>
        </body></html>
    """.trimIndent()

    private val site = WebSite(
        id = "mangahub",
        name = "MangaHub",
        type = "MANGA",
        hosts = listOf("mangahub.io"),
        search = "https://{host}/search?q={q}",
        latest = null,
        itemSelector = ".media-manga, .media",
        titleSelector = "h4.media-heading a[href]",
        coverSelector = "img.manga-thumb, img",
    )

    @Test
    fun `selector path reads titles, links and covers`() {
        val items = WebSiteScraper.parse(html, base, site)
        items.size shouldBe 2
        items[0].title shouldBe "Martial Peak"
        items[0].url shouldBe "https://mangahub.io/manga/martial-peak"
        items[0].cover shouldBe "https://thumb.mghcdn.com/rm/martial-peak.jpg"
        items[1].title shouldBe "Solo Leveling"
    }

    @Test
    fun `generic path skips navigation, genres and pagination`() {
        val items = WebSiteScraper.parse(html, base)
        items.map { it.title } shouldBe listOf("Martial Peak", "Solo Leveling")
        items.none { "/genre/" in it.url } shouldBe true
        items.none { "page=" in it.url } shouldBe true
        items.none { it.title == "Search" } shouldBe true
    }

    @Test
    fun `generic path resolves relative links`() {
        val relative = """
            <div><div class="card"><a href="/manga/berserk">
              <img data-src="/img/berserk.jpg" alt="Berserk"/></a></div></div>
        """.trimIndent()
        val items = WebSiteScraper.parse(relative, "https://mangalib.org/search?q=b")
        items.size shouldBe 1
        items[0].url shouldBe "https://mangalib.org/manga/berserk"
        items[0].cover shouldBe "https://mangalib.org/img/berserk.jpg"
    }

    @Test
    fun `duplicate links collapse into one card`() {
        val duplicated = """
            <div class="media-manga media">
              <a href="https://mangahub.io/manga/x"><img src="https://t/x.jpg"/></a>
              <h4 class="media-heading"><a href="https://mangahub.io/manga/x">X</a></h4>
            </div>
        """.trimIndent()
        WebSiteScraper.parse(duplicated, base, site).size shouldBe 1
    }

    @Test
    fun `broken html does not throw`() {
        WebSiteScraper.parse("<div><a href=", base).size shouldBe 0
    }
}
