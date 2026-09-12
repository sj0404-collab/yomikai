package eu.kanade.tachiyomi.data.books

/**
 * Глава книги — поддерживает вложенность: Том → Глава → Страницы.
 *
 * Два режима:
 *  • **Текстовые главы** (EPUB, FB2, HTML, DOCX, TXT) — текст хранится в [text],
 *    страниц нет.
 *  • **Страницы** (PDF, DJVU) — глава содержит список [pages], текст каждой
 *    страницы хранится в [BookPage.text].
 *
 * Поддерживает структуру ранобэ/книг:
 *  • Том 1 → Глава 1: Пролог → страницы 1-5 (обложки/титулы тоже показываются)
 *  • Том 1 → Глава 1: Пролог → страницы 6-12 (текст главы)
 *
 * @param isPageBased true = страницы (PDF), false = текстовые главы (EPUB/FB2/...)
 * @param volume Номер тома (null если нет томов).
 * @param chapter Номер главы (null если нет нумерации).
 * @param subChapter Подглава / секция.
 * @param skipPages Количество страниц для пропуска в начале (обложки/титулы).
 *                  Оставлено для совместимости; страницы показываются все.
 */
data class BookChapter(
    val id: Long,
    val bookId: String,
    val name: String,
    val isPageBased: Boolean = false,
    val volume: Int? = null,
    val chapter: Int? = null,
    val subChapter: Int? = null,
    val URL: String = "",
    val chapterNumber: Double = -1.0,
    val scanlator: String? = null,
    val dateUpload: Long = System.currentTimeMillis(),
    val read: Boolean = false,
    val bookmark: Boolean = false,
    val lastPageRead: Long = 0,
    val text: String = "",
    val pages: List<BookPage> = emptyList(),
    val skipPages: Int = 0,
) {
    val isRecognizedNumber: Boolean
        get() = chapterNumber >= 0.0

    /** Количество страниц в главе (для page-based). */
    val pageCount: Int get() = pages.size

    /** Страницы главы (обложки/титулы тоже показываются, не пропускаются). */
    val readablePages: List<BookPage>
        get() = pages

    /** Текст главы: для text-based берётся из [text], для page-based конкатенируется из страниц. */
    val resolvedText: String
        get() = if (isPageBased) {
            readablePages.joinToString("\n\n") { it.text }.ifBlank { text }
        } else {
            text
        }

    /**
     * Отображаемое название.
     * Примеры:
     *  • PDF без оглавления: "Страница 3 из 120"
     *  • EPUB: "Том 1, Глава 5: Пролог"
     *  • PDF с оглавлением: "Глава 1: Пролог (стр. 3-12)"
     */
    val displayTitle: String
        get() = buildString {
            if (isPageBased) {
                if (volume != null || chapter != null) {
                    // Есть оглавление — показываем структуру
                    if (volume != null) append("Том $volume")
                    if (chapter != null) {
                        if (isNotEmpty()) append(", ")
                        append("Глава $chapter")
                    }
                    if (subChapter != null) append(".$subChapter")
                    if (name.isNotBlank() && name != "Страница ${pages.firstOrNull()?.pageNumber ?: 1}") {
                        if (isNotEmpty()) append(": ")
                        append(name)
                    }
                    // Диапазон страниц
                    val first = pages.firstOrNull()?.pageNumber
                    val last = pages.lastOrNull()?.pageNumber
                    if (first != null && last != null && first != last) {
                        append(" (стр. $first–$last)")
                    }
                } else {
                    // Нет оглавления — просто номер страницы
                    val p = pages.firstOrNull()?.pageNumber ?: (id.toInt() + 1)
                    val total = pages.firstOrNull()?.totalPages ?: p
                    append("Страница $p из $total")
                }
            } else {
                // Текстовые главы
                if (volume != null) append("Том $volume")
                if (chapter != null) {
                    if (isNotEmpty()) append(", ")
                    append("Глава $chapter")
                }
                if (subChapter != null) append(".$subChapter")
                if (isNotEmpty()) append(": ")
                append(name.ifBlank { "Текст" })
            }
        }

    /** Строка для шапки читалки. */
    val headerLine: String
        get() = if (isPageBased) {
            if (volume != null || chapter != null) {
                displayTitle
            } else {
                val p = pages.firstOrNull()?.pageNumber ?: (id.toInt() + 1)
                val total = pages.firstOrNull()?.totalPages ?: p
                "Страница $p из $total"
            }
        } else {
            displayTitle
        }

    companion object {
        /**
         * Создаёт текстовую главу (EPUB, FB2, HTML, DOCX, TXT).
         */
        fun create(
            index: Int,
            bookId: String,
            title: String,
            volume: Int? = null,
            chapter: Int? = null,
            subChapter: Int? = null,
            url: String = "",
            scanlator: String? = null,
            text: String = "",
        ): BookChapter {
            val chapterNumber = when {
                chapter != null && volume != null -> volume * 100.0 + chapter
                chapter != null -> chapter.toDouble()
                else -> index.toDouble()
            }
            return BookChapter(
                id = index.toLong(),
                bookId = bookId,
                name = title,
                isPageBased = false,
                volume = volume,
                chapter = chapter,
                subChapter = subChapter,
                URL = url,
                chapterNumber = chapterNumber,
                scanlator = scanlator,
                text = text,
            )
        }

        /**
         * Создаёт главу-страницу (PDF без оглавления — просто страница).
         */
        fun createPage(
            index: Int,
            bookId: String,
            pageNumber: Int,
            totalPages: Int,
            text: String = "",
        ): BookChapter {
            return BookChapter(
                id = index.toLong(),
                bookId = bookId,
                name = "Страница $pageNumber",
                isPageBased = true,
                chapterNumber = index.toDouble(),
                pages = listOf(
                    BookPage(
                        pageNumber = pageNumber,
                        totalPages = totalPages,
                        text = text,
                    ),
                ),
            )
        }

        /**
         * Создаёт главу-раздел с несколькими страницами (PDF с оглавлением).
         *
         * @param startPage Первая страница главы (1-based).
         * @param endPage Последняя страница главы (включительно).
         */
        fun createSection(
            index: Int,
            bookId: String,
            title: String,
            startPage: Int,
            endPage: Int,
            volume: Int? = null,
            chapter: Int? = null,
            subChapter: Int? = null,
            scanlator: String? = null,
            skipBefore: Int = 0,
        ): BookChapter {
            val chapterNumber = when {
                chapter != null && volume != null -> volume * 100.0 + chapter
                chapter != null -> chapter.toDouble()
                else -> index.toDouble()
            }
            val pageList = (startPage..endPage).map { p ->
                BookPage(pageNumber = p, totalPages = endPage)
            }
            return BookChapter(
                id = index.toLong(),
                bookId = bookId,
                name = title,
                isPageBased = true,
                volume = volume,
                chapter = chapter,
                subChapter = subChapter,
                chapterNumber = chapterNumber,
                scanlator = scanlator,
                pages = pageList,
                skipPages = skipBefore.coerceAtLeast(0),
            )
        }
    }
}

/**
 * Одна страница книги (для PDF/DJVU).
 *
 * @param pageNumber Порядковый номер страницы в файле (1-based).
 * @param totalPages Общее число страниц в файле.
 * @param text Текст страницы (извлечённый через OCR или описание).
 * @param image Пиксели страницы (Bitmap bytes) для отображения.
 */
data class BookPage(
    val pageNumber: Int,
    val totalPages: Int = 0,
    val text: String = "",
    val image: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BookPage) return false
        return pageNumber == other.pageNumber
    }
    override fun hashCode(): Int = pageNumber
}
