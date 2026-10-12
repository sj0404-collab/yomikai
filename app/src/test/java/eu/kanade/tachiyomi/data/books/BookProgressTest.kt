package eu.kanade.tachiyomi.data.books

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Прогресс по книге.
 *
 * Регент на сломанный расчёт: у постраничных глав (PDF/DJVU) `resolvedText`
 * пуст до локального распознавания, поэтому «число предложений» для них всегда
 * было нулём. Считая так, вся книга сводилась к одной единице и процент
 * показывал то 0, то 100.
 */
class BookProgressTest {

    private fun textChapter(index: Int, sentences: Int): BookChapter =
        BookChapter.create(index = index, bookId = "b", title = "Глава $index", text = "  ".repeat(sentences))

    private fun pageChapter(index: Int, pages: Int): BookChapter =
        BookChapter(
            id = index.toLong(),
            bookId = "b",
            name = "Раздел $index",
            isPageBased = true,
            pages = (1..pages).map { BookPage(pageNumber = it, totalPages = pages) },
        )

    @Test
    fun `page-based chapter counts pages, not sentences`() {
        val chapter = pageChapter(index = 0, pages = 20)
        // Текст у постраничной главы пуст: если бы считались предложения,
        // единиц в главе было бы ноль.
        assertEquals(20, BookProgress.unitsIn(chapter, sentencesInText = 0))
    }

    @Test
    fun `text chapter counts sentences`() {
        assertEquals(7, BookProgress.unitsIn(textChapter(0, 7), sentencesInText = 7))
    }

    @Test
    fun `percent of a pdf advances with the page`() {
        val chapters = listOf(pageChapter(0, pages = 100))
        assertEquals(0, BookProgress.percent(chapters, { 0 }, currentIndex = 0, positionInChapter = 0))
        assertEquals(50, BookProgress.percent(chapters, { 0 }, currentIndex = 0, positionInChapter = 50))
        assertEquals(99, BookProgress.percent(chapters, { 0 }, currentIndex = 0, positionInChapter = 99))
    }

    @Test
    fun `percent mixes text and page chapters`() {
        val chapters = listOf(
            textChapter(0, sentences = 100),
            pageChapter(1, pages = 100),
        )
        assertEquals(25, BookProgress.percent(chapters, { 100 }, currentIndex = 0, positionInChapter = 50))
        assertEquals(50, BookProgress.percent(chapters, { 100 }, currentIndex = 1, positionInChapter = 0))
        assertEquals(75, BookProgress.percent(chapters, { 100 }, currentIndex = 1, positionInChapter = 50))
    }

    @Test
    fun `empty book and out-of-range position are clamped`() {
        assertEquals(0, BookProgress.percent(emptyList(), { 0 }, currentIndex = 0, positionInChapter = 0))
        val chapters = listOf(textChapter(0, 10))
        // Индекс главы за пределами книги прижимается к последней, а не
        // читает за её пределами: глава одна, начинаем с её нуля — 0%.
        assertEquals(0, BookProgress.percent(chapters, { 10 }, currentIndex = 5, positionInChapter = 0))
        assertEquals(100, BookProgress.percent(chapters, { 10 }, currentIndex = 0, positionInChapter = 999))
        assertEquals(0, BookProgress.percent(chapters, { 10 }, currentIndex = -3, positionInChapter = 0))
    }
}