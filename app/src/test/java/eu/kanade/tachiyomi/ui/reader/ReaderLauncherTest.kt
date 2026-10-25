package eu.kanade.tachiyomi.ui.reader

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Жалоба: «он читает неправильный вариант, а правильный уже не читает».
 * Две читалки должны быть подписаны по-разному и не путаться между собой.
 */
class ReaderLauncherTest {

    @Test
    fun `two modes are different values`() {
        ReaderLauncher.MODE_PAGES shouldNotBe ReaderLauncher.MODE_STRIP
    }

    @Test
    fun `page mode is labelled as manga`() {
        ReaderLauncher.PAGES.mode shouldBe ReaderLauncher.MODE_PAGES
        ReaderLauncher.PAGES.title shouldContain "Манга"
        ReaderLauncher.PAGES.title shouldContain "постранично"
    }

    @Test
    fun `strip mode is labelled as manhwa and mentions KOMA`() {
        ReaderLauncher.STRIP.mode shouldBe ReaderLauncher.MODE_STRIP
        ReaderLauncher.STRIP.title shouldContain "Манхва"
        ReaderLauncher.STRIP.title shouldContain "лента"
        ReaderLauncher.STRIP.subtitle shouldContain "KOMA"
    }

    @Test
    fun `both readers promise frames and voice`() {
        // Жалоба владельца: «зачем мне рамки, а ты от них отказываешься».
        // Рамки реплик есть в ОБЕИХ читалках, и подписи обязаны это говорить.
        ReaderLauncher.PAGES.subtitle shouldContain "Рамки"
        ReaderLauncher.STRIP.subtitle shouldContain "Рамки"
        ReaderLauncher.PAGES.subtitle shouldNotContain "Без рамок"
    }

    @Test
    fun `labels do not claim the same thing`() {
        ReaderLauncher.PAGES.title shouldNotBe ReaderLauncher.STRIP.title
        ReaderLauncher.PAGES.subtitle shouldNotBe ReaderLauncher.STRIP.subtitle
    }
}
