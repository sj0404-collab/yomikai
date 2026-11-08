package eu.kanade.tachiyomi.ui.webbrowser

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class UrlDisplayTest {

    @Test
    fun `normalize drops scheme, www and trailing slash`() {
        UrlDisplay.normalize("https://www.mangabuff.ru/manga/operaciya/") shouldBe
            "mangabuff.ru/manga/operaciya"
        UrlDisplay.normalize("http://mangabuff.ru/manga/operaciya") shouldBe
            "mangabuff.ru/manga/operaciya"
        UrlDisplay.normalize("HTTPS://Mangabuff.RU/Manga/Operaciya") shouldBe
            "mangabuff.ru/manga/operaciya"
    }

    @Test
    fun `normalize makes two tabs of the same page equal`() {
        val a = "https://mangabuff.ru/manga/operaciya"
        val b = "https://www.mangabuff.ru/manga/operaciya/"
        UrlDisplay.normalize(a) shouldBe UrlDisplay.normalize(b)
    }

    @Test
    fun `pretty hides scheme so the address bar is readable`() {
        UrlDisplay.pretty("https://mangabuff.ru/manga/operaciya") shouldBe "mangabuff.ru/manga/operaciya"
        UrlDisplay.pretty("https://www.google.com/") shouldBe "google.com"
    }

    @Test
    fun `pretty elides a long tail instead of clipping at the scheme`() {
        val long = "https://mangabuff.ru/manga/operaciya-nastoyashchaya-lyubov-0-glava"
        val pretty = UrlDisplay.pretty(long)
        pretty.startsWith("mangabuff.ru") shouldBe true
        pretty.endsWith("…") shouldBe true
    }

    @Test
    fun `pretty of blank url is empty`() {
        UrlDisplay.pretty(null) shouldBe ""
        UrlDisplay.pretty("   ") shouldBe ""
    }
}
