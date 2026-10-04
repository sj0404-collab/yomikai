package eu.kanade.tachiyomi.data.books

/**
 * Прогресс по книге — чистый расчёт, без Compose и без диска.
 *
 * Вынесен из читалки, потому что считался прямо в composable и был сломан:
 * у постраничной главы (PDF/DJVU) текст появляется только после локального
 * распознавания, а на момент сохранения `resolvedText` пуст, число предложений
 * равно нулю, и весь файл сводился к одной «единице». Процент прыгал между 0 и
 * 100, а сохранённая позиция восстанавливалась не туда.
 *
 * Единицы у глав разные, и это нормально: текстовая глава меряется
 * предложениями, постраничная — страницами. Складывать их можно, потому что
 * процент — это доля от суммы, а не сравнение разных величин.
 */
object BookProgress {

    /** Единиц в главе: страницы для постраничной, предложения иначе. */
    fun unitsIn(chapter: BookChapter, sentencesInText: Int): Int =
        if (chapter.isPageBased) chapter.pageCount else sentencesInText.coerceAtLeast(0)

    /**
     * Процент прочитанного, 0..100.
     *
     * @param chapters главы книги по порядку.
     * @param sentencesInText число предложений текстовой главы; для
     *   постраничной игнорируется.
     * @param currentIndex текущая глава.
     * @param positionInChapter сколько единиц в текущей главе уже пройдено:
     *   индекс реплики для текстовой, номер страницы для постраничной.
     */
    fun percent(
        chapters: List<BookChapter>,
        sentencesInText: (Int) -> Int,
        currentIndex: Int,
        positionInChapter: Int,
    ): Int {
        if (chapters.isEmpty()) return 0
        val index = currentIndex.coerceIn(0, chapters.lastIndex)
        val units = chapters.mapIndexed { i, chapter ->
            unitsIn(chapter, sentencesInText(i))
        }
        val total = units.sum().coerceAtLeast(1)
        val before = units.subList(0, index).sum()
        val position = positionInChapter.coerceAtLeast(0)
        return (((before + position).toLong() * 100) / total).toInt().coerceIn(0, 100)
    }
}