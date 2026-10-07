package eu.kanade.tachiyomi.data.books

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.content.Context
import com.hippo.unifile.UniFile
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * Разбирает электронную книгу любого формата в список глав (название, текст).
 *
 * Поддерживаются:
 *  • txt / md / markdown / rtf / srt / vtt / json и любые другие — текст с автоопределением кодировки;
 *  • fb2 — XML FictionBook (главы = разделы, текст = <p>);
 *  • html / htm — заголовки h1..h4 становятся главами;
 *  • epub — zip (container.xml → OPF → spine), каждая запись spine — глава;
 *  • docx — zip (word/document.xml), абзацы <w:p> конкатенируются;
 *  • pdf — рендеринг страниц через Android PdfRenderer + OCR (если доступен).
 *
 * Извлекает метаданные: название, автор, описание, обложка (для EPUB/FB2/PDF).
 */
object BookParser {

    class UnsupportedBookException(message: String) : Exception(message)

    /**
     * Метаданные книги — аналог [tachiyomi.domain.manga.model.Manga]
     * для локальных электронных книг.
     */
    data class BookMetadata(
        val title: String,
        val author: String? = null,
        val description: String? = null,
        val genre: List<String>? = null,
        val coverImage: ByteArray? = null,
        val language: String? = null,
        val publisher: String? = null,
        val year: Int? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BookMetadata) return false
            return title == other.title && author == other.author
        }
        override fun hashCode(): Int = title.hashCode() * 31 + (author?.hashCode() ?: 0)
    }

    data class ParsedBook(
        val metadata: BookMetadata,
        val chapters: List<BookChapter>,
    ) {
        constructor(title: String, chapters: List<Pair<String, String>>) : this(
            metadata = BookMetadata(title = title),
            chapters = chapters.mapIndexed { idx, (name, text) ->
                BookChapter.create(
                    index = idx,
                    bookId = "",
                    title = name,
                    text = text,
                )
            },
        )
    }

    private val TEXT_EXTS = setOf(
        "txt", "md", "markdown", "log", "text", "lrc", "srt", "vtt",
        "ini", "conf", "cfg", "csv", "json", "yaml", "yml", "xml", "rtf",
    )

    private val ZIP_EXTS = setOf("epub", "docx")

    /** Лимиты иллюстраций EPUB: не распаковывать книгу на сотни картинок. */
    private const val MAX_CHAPTER_IMAGES = 64
    private const val MAX_CHAPTER_IMAGE_BYTES = 8 * 1024 * 1024

    private val PDF_MAGIC = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D) // %PDF-
    private val RTF_MAGIC = byteArrayOf(0x7B, 0x5C, 0x72, 0x74, 0x66) // RTF: {\rtf
    private val ZIP_LOCAL_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // PK\x03\x04
    private val ZIP_EMPTY_MAGIC = byteArrayOf(0x50, 0x4B, 0x05, 0x06) // PK\x05\x06
    private val ZIP_SPANNED_MAGIC = byteArrayOf(0x50, 0x4B, 0x07, 0x08) // PK\x07\x08

    fun parse(bookFile: UniFile, bookId: String = bookFile.uri.toString(), context: Context? = null): ParsedBook {
        val input = bookFile.openInputStream() ?: throw UnsupportedBookException("Не удалось открыть файл книги")
        val bytes = input.use { stream ->
            val buf = java.io.ByteArrayOutputStream()
            val tmp = ByteArray(8192)
            var totalRead = 0L
            while (true) {
                val read = stream.read(tmp)
                if (read < 0) break
                totalRead += read
                if (totalRead > 40_000_000L) {
                    throw UnsupportedBookException("Файл слишком большой для чтения (более 40 МБ)")
                }
                buf.write(tmp, 0, read)
            }
            buf.toByteArray()
        }
        if (bytes.isEmpty()) throw UnsupportedBookException("Файл пуст")
        val name = bookFile.name.orEmpty().substringBeforeLast('.').ifBlank { "Книга" }
        val ext = bookFile.extension().lowercase()
        // Формат определяется ПО СОДЕРЖИМОМУ, а не по расширению: бинарный
        // файл с расширением .pdf не должен трактоваться как PDF.
        val format = detectFormat(ext, bytes)

        return try {
            when (format) {
                "pdf" -> parsePdf(bytes, name, bookId)
                "fb2" -> parseFb2(bytes, name, bookId)
                "html" -> parseHtml(bytes, name, bookId)
                "epub" -> parseEpub(bytes, bookId, context)
                "docx" -> parseDocx(bytes, name, bookId)
                "plain", "rtf" -> parsePlain(bytes, name, bookId)
                else -> {
                    if (format == null) {
                        throw UnsupportedBookException(
                            "Формат файла не распознан (повреждённый или не книжный бинарный файл)",
                        )
                    }
                    parsePlain(bytes, name, bookId)
                }
            }
        } catch (e: UnsupportedBookException) {
            throw e
        } catch (e: ZipException) {
            throw UnsupportedBookException("Повреждённый контейнер ($format)")
        } catch (e: Exception) {
            throw UnsupportedBookException("Не удалось разобрать файл: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Определяет формат по сигнатуре/содержимому, а не по расширению.
     * Возвращает "pdf"/"epub"/"docx"/"fb2"/"html"/"rtf"/"plain" или null.
     */
    private fun detectFormat(ext: String, bytes: ByteArray): String? {
        // Строгие сигнатуры важнее расширения.
        if (startsWith(bytes, PDF_MAGIC)) return "pdf"
        if (startsWith(bytes, RTF_MAGIC)) return "rtf"
        if (startsWith(bytes, ZIP_LOCAL_MAGIC) ||
            startsWith(bytes, ZIP_EMPTY_MAGIC) ||
            startsWith(bytes, ZIP_SPANNED_MAGIC)
        ) {
            sniffZipKind(bytes)?.let { return it }
            return if (ext in ZIP_EXTS) ext else null
        }
        if (looksLikeFb2(bytes)) return "fb2"
        if (looksLikeHtml(bytes)) return "html"
        // Расширение обещает контейнер, а содержимое не подтверждает его.
        if (ext in ZIP_EXTS || ext == "pdf") return null
        if (ext in TEXT_EXTS || looksLikePlainText(bytes)) return "plain"
        return null
    }

    private fun startsWith(bytes: ByteArray, magic: ByteArray, offset: Int = 0): Boolean {
        if (offset + magic.size > bytes.size) return false
        for (i in magic.indices) if (bytes[offset + i] != magic[i]) return false
        return true
    }

    private fun looksLikeFb2(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(4096, bytes.size), Charsets.ISO_8859_1).lowercase()
        return head.contains("<fictionbook")
    }

    private fun looksLikeHtml(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(4096, bytes.size), Charsets.ISO_8859_1).lowercase()
        return head.contains("<!doctype html") || head.contains("<html") ||
            (head.startsWith("<?xml") && head.contains("<html"))
    }

    /** Текстовый ли файл по доле печатных символов в начале (RTF/JSON/CSV и т.п.). */
    private fun looksLikePlainText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val sample = minOf(bytes.size, 2048)
        var printable = 0
        for (i in 0 until sample) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0x09 || b == 0x0A || b == 0x0D || b in 0x20..0x7E || b >= 0x80) printable++
        }
        return printable > sample * 95 / 100
    }

    /** Отличает EPUB от DOCX (и прочих zip-контейнеров) по содержимому. */
    private fun sniffZipKind(bytes: ByteArray): String? {
        val tmp = File.createTempFile("book_", ".zip").apply { deleteOnExit() }
        try {
            tmp.writeBytes(bytes)
            return try {
                ZipFile(tmp).use { zip ->
                    when {
                        zip.getEntry("word/document.xml") != null -> "docx"
                        zip.getEntry("META-INF/container.xml") != null -> "epub"
                        zip.getEntry("mimetype") != null -> "epub"
                        else -> null
                    }
                }
            } catch (e: Exception) {
                null
            }
        } finally {
            tmp.delete()
        }
    }

    /**
     * Извлекает обложку книги (первую картинку) или рендерит первую страницу PDF.
     * Возвращает ByteArray PNG или null.
     */
    fun extractCover(context: Context, bookFile: UniFile): ByteArray? {
        val bytes = runCatching {
            val input = bookFile.openInputStream() ?: return null
            input.use { it.readBytes() }
        }.getOrNull() ?: return null
        val format = detectFormat(bookFile.name.orEmpty().substringAfterLast('.', "").lowercase(), bytes)
        return try {
            when (format) {
                "epub" -> extractEpubCover(bookFile)
                "fb2" -> extractFb2Cover(bookFile)
                "pdf" -> extractPdfCover(context, bookFile)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Извлекает метаданные книги (название, автор, описание) без парсинга глав.
     */
    fun extractMetadata(bookFile: UniFile): BookMetadata {
        val name = bookFile.name.orEmpty().substringBeforeLast('.').ifBlank { "Книга" }
        val bytes = bookFile.openInputStream()?.use { it.readBytes() } ?: return BookMetadata(title = name)
        if (bytes.isEmpty()) return BookMetadata(title = name)
        val format = detectFormat(bookFile.name.orEmpty().substringAfterLast('.', "").lowercase(), bytes)
        return try {
            when (format) {
                "epub" -> extractEpubMetadata(bytes)
                "fb2" -> extractFb2Metadata(bytes, name)
                "pdf" -> extractPdfMetadata(bytes, name)
                "docx" -> extractDocxMetadata(bytes, name)
                "html" -> extractHtmlMetadata(bytes, name)
                else -> BookMetadata(title = name)
            }
        } catch (e: Exception) {
            BookMetadata(title = name)
        }
    }

    // ---------- Плоский текст ----------

    private fun parsePlain(bytes: ByteArray, name: String, bookId: String): ParsedBook {
        val text = decodeText(bytes)
        val pages = splitIntoChapters(text)
        if (pages.isEmpty()) throw UnsupportedBookException("В файле нет текста")
        return ParsedBook(
            metadata = BookMetadata(title = name),
            chapters = pages.mapIndexed { idx, (title, content) ->
                BookChapter.create(idx, bookId, title, text = content)
            },
        )
    }

    // ---------- PDF ----------

    /**
     * Структура оглавления PDF: заголовок → страница назначения.
     */
    private data class PdfOutlineEntry(
        val title: String,
        val pageNumber: Int,
        val children: List<PdfOutlineEntry> = emptyList(),
    )

    private fun parsePdf(bytes: ByteArray, name: String, bookId: String): ParsedBook {
        val tmp = File.createTempFile("book_", ".pdf").apply { deleteOnExit() }
        tmp.writeBytes(bytes)
        return try {
            val pdfMetadata = extractPdfMetadata(bytes, name)
            val text = String(bytes, StandardCharsets.ISO_8859_1)
            val pageCount = extractPdfPageCount(text)
            val outline = extractPdfOutline(text)
                .filter { it.pageNumber in 1..pageCount.coerceAtLeast(1) }

            val chapters = if (outline.isNotEmpty() && pageCount > 0) {
                // Есть оглавление → создаём главы с диапазонами страниц
                buildPdfChaptersFromOutline(outline, pageCount, bookId, pdfMetadata.author)
            } else {
                // Нет оглавления → постранично
                (0 until pageCount).map { i ->
                    BookChapter.createPage(
                        index = i,
                        bookId = bookId,
                        pageNumber = i + 1,
                        totalPages = pageCount,
                    )
                }
            }
            if (chapters.isEmpty()) throw UnsupportedBookException("PDF не содержит страниц")
            ParsedBook(metadata = pdfMetadata, chapters = chapters)
        } finally {
            tmp.delete()
        }
    }

    /**
     * Извлекает оглавление PDF из bytecode через поиск /Outlines и /Dest.
     * Простой парсер: находит цепочки заголовков и страниц назначения.
     */
    private fun extractPdfOutline(text: String): List<PdfOutlineEntry> {
        val entries = mutableListOf<PdfOutlineEntry>()
        // Ищем /Outlines блок
        val outlinesIdx = text.indexOf("/Outlines")
        if (outlinesIdx < 0) return emptyList()
        // Ищем /Title и /Dest в блоке Outlines
        var pos = outlinesIdx
        val endBound = (pos + 50000).coerceAtMost(text.length)
        while (pos < endBound) {
            val titleIdx = text.indexOf("/Title", pos)
            if (titleIdx < 0 || titleIdx > endBound) break
            val title = extractPdfTag(text.substring(titleIdx), "/Title") ?: break
            // Ищем /Dest или /Page после заголовка
            val destIdx = text.indexOf("/Dest", titleIdx)
            val pageIdx = text.indexOf("/Page", titleIdx)
            val refIdx = text.indexOf("/D(", titleIdx)
            val pageNum = when {
                // /Dest [/PageNum /Fit]
                destIdx in titleIdx until titleIdx + 200 -> {
                    val destText = text.substring(destIdx, (destIdx + 80).coerceAtMost(text.length))
                    Regex("""/Page\s*(\d+)""").find(destText)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""\[(\d+)\s*/Fit""").find(destText)?.groupValues?.get(1)?.toIntOrNull()
                }
                // Прямая ссылка на страницу
                refIdx in titleIdx until titleIdx + 200 -> {
                    val refText = text.substring(refIdx, (refIdx + 40).coerceAtMost(text.length))
                    Regex("""\((\d+)\)""").find(refText)?.groupValues?.get(1)?.toIntOrNull()
                }
                else -> null
            }
            if (pageNum != null && pageNum > 0) {
                entries += PdfOutlineEntry(title, pageNum)
            }
            pos = titleIdx + 1
        }
        return entries.sortedBy { it.pageNumber }
    }

    /**
     * Строит главы из оглавления: каждая запись → диапазон страниц.
     * Страницы до первой записи (обложки/титулы) пропускаются.
     */
    private fun buildPdfChaptersFromOutline(
        outline: List<PdfOutlineEntry>,
        pageCount: Int,
        bookId: String,
        author: String?,
    ): List<BookChapter> {
        if (outline.isEmpty()) return emptyList()
        val chapters = mutableListOf<BookChapter>()
        for ((idx, entry) in outline.withIndex()) {
            val startPage = entry.pageNumber.coerceIn(1, pageCount)
            val endPage = if (idx < outline.lastIndex) {
                // До следующей записи
                (outline[idx + 1].pageNumber - 1).coerceIn(startPage, pageCount)
            } else {
                pageCount
            }
            val (vol, chap) = parseChapterNumbering(entry.title, idx)
            chapters += BookChapter.createSection(
                index = idx,
                bookId = bookId,
                title = entry.title,
                startPage = startPage,
                endPage = endPage,
                totalPages = pageCount,
                volume = vol,
                chapter = chap,
                scanlator = author,
            )
        }
        return chapters
    }

    /** Извлекает количество страниц из PDF-потока. */
    private fun extractPdfPageCount(text: String): Int {
        // /Count N в каталоге страниц
        val countMatch = Regex("""/Count\s+(\d+)""").find(text)
        return countMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun extractEpubMetadata(bytes: ByteArray): BookMetadata {
        val tmp = File.createTempFile("book_", ".epub").apply { deleteOnExit() }
        tmp.writeBytes(bytes)
        return try {
            ZipFile(tmp).use { zip ->
                val containerEntry = zip.getEntry("META-INF/container.xml")
                    ?: zip.getEntry("meta-inf/container.xml")
                    ?: return@use BookMetadata(title = "Книга")
                val container = Jsoup.parse(
                    zip.getInputStream(containerEntry).readBytes().toString(Charsets.UTF_8),
                    "", Parser.xmlParser(),
                )
                val opfPathRaw = firstDeepByLocal(container, "rootfile")?.attr("full-path")
                    ?: return@use BookMetadata(title = "Книга")
                val opfPath = decodeHref(opfPathRaw).trimStart('/')
                val base = opfPath.substringBeforeLast('/', "")
                val opfEntry = epubEntryOf(zip, "", opfPathRaw) ?: return@use BookMetadata(title = "Книга")
                // Метаданные и обложку читаем одним ns-толерантным проходом.
                val opf = Jsoup.parse(
                    zip.getInputStream(opfEntry).readBytes().toString(Charsets.UTF_8),
                    "", Parser.xmlParser(),
                )
                return@use extractEpubMetadataFromOpf(opf, zip, base)
            }
        } catch (e: Exception) {
            BookMetadata(title = bytes.toString(Charsets.UTF_8).substringAfterLast('/').substringBeforeLast('.').ifBlank { "Книга" })
        } finally {
            tmp.delete()
        }
    }

    private fun extractPdfMetadata(bytes: ByteArray, name: String): BookMetadata {
        // PDF метаданные извлекаются из byte数组 через простой поиск строк
        val text = String(bytes, StandardCharsets.ISO_8859_1)
        val title = extractPdfTag(text, "/Title") ?: name
        val author = extractPdfTag(text, "/Author")
        val subject = extractPdfTag(text, "/Subject")
        return BookMetadata(
            title = title.ifBlank { name },
            author = author,
            description = subject,
        )
    }

    private fun extractPdfTag(text: String, tag: String): String? {
        val idx = text.indexOf(tag)
        if (idx < 0) return null
        val start = text.indexOf('(', idx)
        val end = text.indexOf(')', start)
        if (start < 0 || end < 0 || end - start > 500) return null
        return text.substring(start + 1, end).takeIf { it.isNotBlank() }
    }

    private fun extractPdfCover(context: Context, bookFile: UniFile): ByteArray? {
        val tmp = File.createTempFile("book_", ".pdf").apply { deleteOnExit() }
        try {
            val input = bookFile.openInputStream() ?: return null
            FileOutputStream(tmp).use { out -> input.use { it.copyTo(out) } }
            val fd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val renderer = PdfRenderer(fd)
                try {
                    if (renderer.pageCount == 0) return null
                    val page = renderer.openPage(0)
                    try {
                        val bitmap = Bitmap.createBitmap(
                            (page.width * 1.5).toInt().coerceAtMost(800),
                            (page.height * 1.5).toInt().coerceAtMost(1200),
                            Bitmap.Config.ARGB_8888,
                        )
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null as android.graphics.Rect?, null as android.graphics.Matrix?, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val baos = java.io.ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 90, baos)
                        bitmap.recycle()
                        return baos.toByteArray()
                    } finally {
                        page.close()
                    }
                } finally {
                    renderer.close()
                }
            } finally {
                fd.close()
            }
        } catch (e: Exception) {
            return null
        } finally {
            tmp.delete()
        }
    }

    /**
     * Рендерит одну страницу PDF в Bitmap (для читалки).
     * Страницы нумеруются с 1. Рендер по требованию — без загрузки всего файла в память.
     */
    fun renderPage(context: Context, bookFile: UniFile, pageNumber: Int): Bitmap? {
        val tmp = File.createTempFile("book_", ".pdf").apply { deleteOnExit() }
        return try {
            val input = bookFile.openInputStream() ?: return null
            FileOutputStream(tmp).use { out -> input.use { it.copyTo(out) } }
            val fd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val renderer = PdfRenderer(fd)
                try {
                    if (pageNumber < 1 || pageNumber > renderer.pageCount) return null
                    val page = renderer.openPage(pageNumber - 1)
                    try {
                        val maxWidth = 1700
                        val maxHeight = 2400
                        val scale = minOf(
                            maxWidth.toFloat() / page.width,
                            maxHeight.toFloat() / page.height,
                            3.0f,
                        ).coerceAtLeast(0.5f)
                        val w = (page.width * scale).toInt()
                        val h = (page.height * scale).toInt()
                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null as android.graphics.Rect?, null as android.graphics.Matrix?, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        return bitmap
                    } finally {
                        page.close()
                    }
                } finally {
                    renderer.close()
                }
            } finally {
                fd.close()
            }
        } catch (e: Exception) {
            null
        } finally {
            tmp.delete()
        }
    }

    // ---------- FB2 ----------

    private fun parseFb2(bytes: ByteArray, name: String, bookId: String): ParsedBook {
        val doc = Jsoup.parse(decodeText(bytes), "", Parser.xmlParser())
        val metadata = extractFb2Metadata(bytes, name)
        val chapters = mutableListOf<BookChapter>()
        var volumeNumber = 1
        for (body in doc.select("body")) {
            for (section in body.select("section")) {
                val chapterTitle = section.selectFirst(":scope > title")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "Глава ${chapters.size + 1}"
                val text = section.select(":scope > p").joinToString("\n\n") { it.text().trim() }
                if (text.isNotBlank()) {
                    chapters += BookChapter.create(
                        index = chapters.size,
                        bookId = bookId,
                        title = chapterTitle,
                        volume = volumeNumber,
                        chapter = chapters.size + 1,
                        text = text,
                    )
                }
            }
            if (body.select("section").isNotEmpty()) volumeNumber++
        }
        if (chapters.isEmpty()) {
            val whole = doc.select("p").joinToString("\n\n") { it.text().trim() }
            if (whole.isNotBlank()) {
                chapters += BookChapter.create(0, bookId, "Книга", text = whole)
            } else {
                throw UnsupportedBookException("В FB2 нет текста")
            }
        }
        return ParsedBook(metadata = metadata, chapters = chapters)
    }

    private fun extractFb2Metadata(bytes: ByteArray, name: String): BookMetadata {
        val doc = Jsoup.parse(decodeText(bytes), "", Parser.xmlParser())
        val title = doc.selectFirst("description > title-info > book-title")?.text()
            ?.takeIf { it.isNotBlank() } ?: name
        val author = doc.selectFirst("description > title-info > author")?.let { a ->
            val firstName = a.selectFirst("first-name")?.text().orEmpty()
            val lastName = a.selectFirst("last-name")?.text().orEmpty()
            "$firstName $lastName".trim().ifBlank { null }
        }
        val description = doc.selectFirst("description > title-info > annotation")?.text()
            ?.takeIf { it.isNotBlank() }
        val genre = doc.select("description > title-info > genre").map { it.text() }.ifEmpty { null }
        val lang = doc.selectFirst("description > title-info > lang")?.text()
        val cover = extractFb2CoverFromDoc(doc)
        return BookMetadata(
            title = title,
            author = author,
            description = description,
            genre = genre,
            coverImage = cover,
            language = lang,
        )
    }

    private fun extractFb2Cover(bookFile: UniFile): ByteArray? {
        val input = bookFile.openInputStream() ?: return null
        val bytes = input.use { it.readBytes() }
        val doc = Jsoup.parse(decodeText(bytes), "", Parser.xmlParser())
        return extractFb2CoverFromDoc(doc)
    }

    private fun extractFb2CoverFromDoc(doc: Document): ByteArray? {
        val coverId = doc.selectFirst("description > title-info > coverpage > image")?.attr("l:href")
            ?.removePrefix("#") ?: return null
        val binary = doc.selectFirst("binary[id=$coverId]")
            ?: doc.select("binary").firstOrNull { it.attr("id") == coverId }
            ?: return null
        return try {
            android.util.Base64.decode(binary.text().trim(), android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            null
        }
    }

    // ---------- HTML ----------

    private fun parseHtml(bytes: ByteArray, name: String, bookId: String): ParsedBook {
        val doc = Jsoup.parse(decodeText(bytes))
        val title = doc.title().trim().takeIf { it.isNotBlank() } ?: name
        val chapters = htmlToChapters(doc)
        if (chapters.isEmpty()) throw UnsupportedBookException("В HTML нет текста")
        val author = doc.selectFirst("meta[name=author]")?.attr("content")?.takeIf { it.isNotBlank() }
        val description = doc.selectFirst("meta[name=description]")?.attr("content")?.takeIf { it.isNotBlank() }
        return ParsedBook(
            metadata = BookMetadata(title = title, author = author, description = description),
            chapters = chapters.mapIndexed { idx, (chName, text) ->
                BookChapter.create(idx, bookId, chName, text = text)
            },
        )
    }

    private fun htmlToChapters(
        doc: Document,
        imgBlock: ((src: String, caption: String) -> String?)? = null,
    ): List<Pair<String, String>> {
        val headingTags = setOf("h1", "h2", "h3", "h4")
        val order = mutableListOf<Pair<String?, String>>()
        val body = doc.body() ?: return emptyList()
        for (el in body.select("p,pre,blockquote,h1,h2,h3,h4,img")) {
            val tag = el.normalName()
            if (tag == "img") {
                // Картинка между абзацами. С imgBlock (EPUB) — реальный файл и
                // маркер в тексте (читалка покажет иллюстрацию на месте); без —
                // подпись (alt/title), чтобы картинка не терялась из текста.
                val src = el.attr("src").trim()
                val alt = el.attr("alt").trim()
                val title = el.attr("title").trim()
                val caption = if (alt.isNotBlank()) alt else title
                val marker = imgBlock?.let { it(src, caption) }
                if (marker != null) {
                    order += null to marker
                } else if (caption.isNotBlank()) {
                    order += null to "(иллюстрация: $caption)"
                }
                continue
            }
            if (tag in headingTags) {
                val t = el.text().trim()
                if (t.isNotBlank()) order += t to ""
            } else {
                val t = el.text().trim()
                if (t.isNotBlank()) order += null to t
            }
        }
        val chapters = mutableListOf<Pair<String, String>>()
        var currentTitle = ""
        val current = StringBuilder()
        for ((heading, para) in order) {
            if (heading != null) {
                flush(currentTitle, current, chapters)
                currentTitle = heading
            } else {
                if (current.isNotEmpty()) current.append('\n')
                current.append(para)
            }
        }
        flush(currentTitle, current, chapters)
        return chapters
    }

    private fun flush(title: String, sb: StringBuilder, out: MutableList<Pair<String, String>>) {
        val text = sb.toString().trim()
        if (text.isNotBlank()) {
            out += (title.ifBlank { "Глава ${out.size + 1}" }) to text
        }
        sb.setLength(0)
    }

    private fun extractHtmlMetadata(bytes: ByteArray, name: String): BookMetadata {
        val doc = Jsoup.parse(decodeText(bytes))
        val title = doc.title().trim().takeIf { it.isNotBlank() } ?: name
        val author = doc.selectFirst("meta[name=author], meta[property=book:author]")?.attr("content")?.takeIf { it.isNotBlank() }
        val description = doc.selectFirst("meta[name=description], meta[property=book:description]")?.attr("content")?.takeIf { it.isNotBlank() }
        return BookMetadata(title = title, author = author, description = description)
    }

    // ---------- EPUB ----------

    private fun parseEpub(bytes: ByteArray, bookId: String, context: Context? = null): ParsedBook {
        val tmp = File.createTempFile("book_", ".epub").apply { deleteOnExit() }
        tmp.writeBytes(bytes)
        return try {
            ZipFile(tmp).use { zip -> parseEpubZip(zip, bookId, context) }
        } finally {
            tmp.delete()
        }
    }

    private fun parseEpubZip(zip: ZipFile, bookId: String, context: Context? = null): ParsedBook {
        val containerEntry = zip.getEntry("META-INF/container.xml")
            ?: zip.getEntry("meta-inf/container.xml")
            ?: throw UnsupportedBookException("EPUB без container.xml")
        val container = Jsoup.parse(
            zip.getInputStream(containerEntry).readBytes().toString(Charsets.UTF_8),
            "",
            Parser.xmlParser(),
        )
        val opfPathRaw = firstDeepByLocal(container, "rootfile")?.attr("full-path")
            ?: throw UnsupportedBookException("EPUB без rootfile")
        val opfEntry = epubEntryOf(zip, "", opfPathRaw)
            ?: throw UnsupportedBookException("EPUB без OPF ($opfPathRaw)")
        val opfPath = decodeHref(opfPathRaw).trimStart('/')
        val opf = Jsoup.parse(
            zip.getInputStream(opfEntry).readBytes().toString(Charsets.UTF_8),
            "",
            Parser.xmlParser(),
        )
        val base = opfPath.substringBeforeLast('/', "")

        // Извлекаем метаданные
        val metadata = extractEpubMetadataFromOpf(opf, zip, base)

        // id -> (href, mediaType, properties)
        val manifest = mutableMapOf<String, Triple<String, String, String>>()
        firstDeepByLocal(opf, "manifest")?.let { man ->
            childrenByLocal(man, "item").forEach { item ->
                val id = item.attr("id")
                if (id.isNotBlank()) {
                    manifest[id] = Triple(item.attr("href"), item.attr("media-type"), item.attr("properties"))
                }
            }
        }
        var spine = firstDeepByLocal(opf, "spine")?.let { sp ->
            childrenByLocal(sp, "itemref").mapNotNull { it.attr("idref").takeIf { x -> x.isNotBlank() } }
        } ?: emptyList()
        if (spine.isEmpty()) {
            spine = manifest.keys.toList()
        }

        // Картинки-иллюстрации внутри глав: entryName → байты. Токен-маркер
        // «⟦entry⟧|подпись» встаёт в текст главы там, где стояла картинка.
        val images = mutableMapOf<String, ByteArray>()
        val rawChapters = mutableListOf<RawEpubChapter>()
        var spineIndex = 0
        for (idref in spine) {
            val (hrefRaw, mediaType, properties) = manifest[idref] ?: continue
            val chunk = runCatching {
                parseEpubItem(zip, base, hrefRaw, mediaType, properties, images)
            }.getOrNull()
            if (chunk != null) {
                rawChapters += RawEpubChapter(spineIndex, hrefRaw, chunk.first, chunk.second)
                spineIndex++
            }
        }
        if (rawChapters.isEmpty()) throw UnsupportedBookException("В EPUB нет текстовых глав")
        val chapters = materializeEpubChapters(rawChapters, images, bookId, context, metadata.author)
        return ParsedBook(metadata = metadata, chapters = chapters)
    }

    /** Сырая глава EPUB: текст ещё с токенами ⟦entry⟧ вместо путей картинок. */
    private data class RawEpubChapter(
        val index: Int,
        val href: String,
        val title: String,
        val text: String,
    )

    /**
     * Материализует картинки EPUB на диск и подставляет реальные пути в маркеры.
     *
     * Без [context] (юнит-тесты, чужие вызовы) маркеры заменяются текстовой
     * пометкой «(иллюстрация: подпись)» — поведение, как до введения картинок.
     */
    private fun materializeEpubChapters(
        rawChapters: List<RawEpubChapter>,
        images: Map<String, ByteArray>,
        bookId: String,
        context: Context?,
        author: String?,
    ): List<BookChapter> {
        val fileByEntry = mutableMapOf<String, String>()
        if (context != null && images.isNotEmpty()) {
            // Каталог картинок книги: filesDir/book_images/<хэш-книги>/…
            val bookKey = bookId.hashCode().toUInt().toString(16)
            val dir = File(File(context.filesDir, "book_images"), bookKey).apply { mkdirs() }
            var written = 0
            for ((entryName, bytes) in images) {
                if (bytes.isEmpty()) continue
                if (written >= MAX_CHAPTER_IMAGES) break
                val fname = entryName.replace(Regex("[^A-Za-zА-Яа-я0-9._-]"), "_")
                val out = File(dir, fname)
                runCatching {
                    out.writeBytes(bytes)
                    fileByEntry[entryName] = out.absolutePath
                    written++
                }
            }
        }
        return rawChapters.mapIndexed { idx, raw ->
            var text = raw.text
            for ((entryName, _) in images) {
                val marker = "⟦$entryName⟧"
                if (!text.contains(marker)) continue
                val path = fileByEntry[entryName]
                text = if (path != null) {
                    text.replace(marker, "⟦$path⟧")
                } else {
                    // Маркера на диске нет (лимит/нет контекста) — оставляем
                    // текст «(иллюстрация: подпись)» вместо пути.
                    text.replace(
                        Regex(Regex.escape(marker) + "\\|([^\\n]*)"),
                    ) { m ->
                        val cap = m.groupValues[1].trim()
                        if (cap.isBlank()) "(иллюстрация)" else "(иллюстрация: $cap)"
                    }
                        .replace(Regex(Regex.escape(marker)), "")
                }
            }
            val (vol, chap) = parseChapterNumbering(raw.title, idx)
            BookChapter.create(
                index = idx,
                bookId = bookId,
                title = raw.title,
                volume = vol,
                chapter = chap,
                url = raw.href,
                scanlator = author,
                text = text,
            )
        }
    }

    /** Извлекает номер тома и главы из названия (например, "Chapter 5" → (null, 5)). */
    private fun parseChapterNumbering(title: String, index: Int): Pair<Int?, Int?> {
        val chMatch = Regex("""(?i)(?:chapter|глава|ch\.?)\s*(\d+)""").find(title)
        val volMatch = Regex("""(?i)(?:volume|том|vol\.?)\s*(\d+)""").find(title)
        val vol = volMatch?.groupValues?.get(1)?.toIntOrNull()
        val chap = chMatch?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)
        return vol to chap
    }

    private fun extractEpubMetadataFromOpf(opf: Document, zip: ZipFile, base: String): BookMetadata {
        val meta = firstDeepByLocal(opf, "metadata") ?: return BookMetadata(title = "Книга")
        fun dc(local: String): String? = meta.children()
            .firstOrNull { tagIs(it, local) }
            ?.text()?.trim()?.takeIf { it.isNotBlank() }
        val title = dc("title") ?: "Книга"
        val creator = dc("creator")
        val description = dc("description")
        val language = dc("language")
        val publisher = dc("publisher")
        val date = dc("date")
        val year = date?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }

        // Ищем обложку: либо <meta name="cover" content="…"/> (EPUB 2),
        // либо item с properties="cover-image" (EPUB 3)
        val coverId = meta.children()
            .firstOrNull { tagIs(it, "meta") && it.attr("name").equals("cover", ignoreCase = true) }
            ?.attr("content")
            ?: firstDeepByLocal(opf, "manifest")?.let { man ->
                childrenByLocal(man, "item")
                    .firstOrNull { it.attr("properties").contains("cover-image", ignoreCase = true) }
                    ?.attr("id")
            }
        val coverImage = coverId?.let { findEpubImage(zip, base, it, opf) }

        return BookMetadata(
            title = title,
            author = creator,
            description = description,
            coverImage = coverImage,
            language = language,
            publisher = publisher,
            year = year,
        )
    }

    private fun findEpubImage(zip: ZipFile, base: String, id: String, opf: Document): ByteArray? {
        val href = firstDeepByLocal(opf, "manifest")
            ?.let { man -> childrenByLocal(man, "item").firstOrNull { it.attr("id") == id } }
            ?.attr("href")
            ?: return null
        val entry = epubEntryOf(zip, base, href) ?: return null
        return zip.getInputStream(entry).readBytes()
    }

    private fun extractEpubCover(bookFile: UniFile): ByteArray? {
        val tmp = File.createTempFile("book_", ".epub").apply { deleteOnExit() }
        try {
            val input = bookFile.openInputStream() ?: return null
            FileOutputStream(tmp).use { out -> input.use { it.copyTo(out) } }
            return ZipFile(tmp).use { zip ->
                val containerEntry = zip.getEntry("META-INF/container.xml") ?: return@use null
                val container = Jsoup.parse(
                    zip.getInputStream(containerEntry).use { it.readBytes().toString(Charsets.UTF_8) },
                    "", Parser.xmlParser(),
                )
                val opfPathRaw = firstDeepByLocal(container, "rootfile")?.attr("full-path") ?: return@use null
                val opfPath = decodeHref(opfPathRaw).trimStart('/')
                val base = opfPath.substringBeforeLast('/', "")
                val opfEntry = epubEntryOf(zip, "", opfPathRaw) ?: return@use null
                val opf = Jsoup.parse(
                    zip.getInputStream(opfEntry).use { it.readBytes().toString(Charsets.UTF_8) },
                    "", Parser.xmlParser(),
                )
                val coverId = firstDeepByLocal(opf, "metadata")?.children()
                    ?.firstOrNull { tagIs(it, "meta") && it.attr("name").equals("cover", ignoreCase = true) }
                    ?.attr("content")
                    ?: firstDeepByLocal(opf, "manifest")?.let { man ->
                        childrenByLocal(man, "item")
                            .firstOrNull { it.attr("properties").contains("cover-image", ignoreCase = true) }
                            ?.attr("id")
                    }
                coverId?.let { findEpubImage(zip, base, it, opf) }
            }
        } catch (e: Exception) {
            return null
        } finally {
            tmp.delete()
        }
    }

    private fun decodeHref(raw: String): String {
        return runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }

    // ---------- EPUB: namespace-переносимые запросы и пути ----------

    /**
     * Локальное имя тега без ns-префикса: «opf:manifest» → «manifest»,
     * «dc:title» → «title». Часть EPUB (особенно из fb2/kindle-конвертеров)
     * пишет OPF с префиксами — обычные selectFirst("manifest") на них молчит,
     * и книга падала с «В EPUB нет текстовых глав», хотя главы были.
     */
    private fun tagLocal(el: Element): String = el.tagName().substringAfter(':')

    private fun tagIs(el: Element, name: String): Boolean =
        tagLocal(el).equals(name, ignoreCase = true)

    private fun firstDeepByLocal(root: Element?, name: String): Element? {
        root ?: return null
        if (tagIs(root, name)) return root
        for (c in root.children()) {
            firstDeepByLocal(c, name)?.let { return it }
        }
        return null
    }

    private fun childrenByLocal(el: Element?, name: String): List<Element> =
        el?.children()?.filter { tagIs(it, name) }.orEmpty()

    /** Нормализует zip-путь: раскрывает «.»/«..», срезает ведущий слэш. */
    private fun normalizeZipPath(path: String): String {
        val out = ArrayDeque<String>()
        for (seg in decodeHref(path).trim().split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeLast()
                else -> out.addLast(seg)
            }
        }
        return out.joinToString("/")
    }

    /**
     * Запись EPUB-zip по href как в манифесте: находим и «OPS/x», и просто «x»,
     * и процентно-кодированные варианты, и абсолютные пути «/OPS/x» — у
     * разных сборщиков по-разному, и раньше глава просто молча пропадала.
     */
    private fun epubEntryOf(zip: ZipFile, base: String, rawHref: String): ZipEntry? {
        val raw = decodeHref(rawHref.substringBefore('#')).trim().trimStart('/')
        if (raw.isBlank()) return null
        val variants = LinkedHashSet<String>()
        if (base.isNotEmpty()) {
            variants += normalizeZipPath("$base/$raw")
            variants += "$base/${decodeHref(raw)}"
        }
        variants += normalizeZipPath(raw)
        variants += raw
        for (v in variants) {
            zip.getEntry(v)?.let { return it }
        }
        return null
    }

    private fun parseEpubItem(
        zip: ZipFile,
        base: String,
        hrefRaw: String,
        mediaType: String,
        properties: String,
        images: MutableMap<String, ByteArray>? = null,
    ): Pair<String, String>? {
        val href = decodeHref(hrefRaw.substringBefore('#')).trim().ifBlank { return null }
        val lowerHref = href.lowercase()
        val ext = lowerHref.substringAfterLast('.', "")
        if (mediaType.equals("application/x-dtbncx+xml", ignoreCase = true) || ext == "ncx") return null
        if ("nav" in properties.split(' ', '\t')) return null
        val textLike = mediaType.isBlank() ||
            mediaType.contains("html", ignoreCase = true) ||
            mediaType.contains("xml", ignoreCase = true) ||
            mediaType.startsWith("text/", ignoreCase = true) ||
            ext in setOf("xhtml", "html", "htm", "xml")
        if (!textLike) return null
        val name = href.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Глава" }
        val entry = epubEntryOf(zip, base, href) ?: return null
        val entryBytes = zip.getInputStream(entry).readBytes()
        val doc = Jsoup.parse(decodeText(entryBytes))
        val docTitle = doc.title().trim().takeIf { it.isNotBlank() }
        // Картинки-иллюстрации. src документа разрешается ОТНОСИТЕЛЬНО папки
        // самого документа (у ранобэ это часто «../Images/i1.jpg»), а zip-токен
        // — каноничное имя записи, чтобы потом заменить его на путь файла.
        val docDir = entry.name.substringBeforeLast('/', "")
        val imgBlock: ((String, String) -> String?)? = images?.let { bag ->
            { src, caption ->
                when {
                    src.startsWith("data:", ignoreCase = true) -> null
                    src.startsWith("http://", ignoreCase = true) ||
                        src.startsWith("https://", ignoreCase = true) -> null
                    bag.size >= MAX_CHAPTER_IMAGES -> null
                    else -> {
                        val joined = if (src.startsWith('/')) src else "$docDir/$src"
                        val entryName = normalizeZipPath(joined)
                        val imgEntry = zip.getEntry(entryName)
                        if (imgEntry == null) {
                            null
                        } else {
                            val bytes = bag.getOrPut(entryName) {
                                runCatching { zip.getInputStream(imgEntry).readBytes() }
                                    .getOrDefault(ByteArray(0))
                            }
                            if (bytes.isEmpty() || bytes.size > MAX_CHAPTER_IMAGE_BYTES) {
                                bag.remove(entryName)
                                null
                            } else {
                                "⟦$entryName⟧|$caption"
                            }
                        }
                    }
                }
            }
        }
        val chunk = htmlToChapters(doc, imgBlock)
        if (chunk.isEmpty()) {
            val t = doc.body()?.text()?.trim()
            if (t.isNullOrBlank()) return null
            return (docTitle ?: name) to t
        }
        return (docTitle ?: chunk.first().first) to chunk.joinToString("\n\n") { it.second }
    }

    // ---------- DOCX ----------

    private fun parseDocx(bytes: ByteArray, name: String, bookId: String): ParsedBook {
        val tmp = File.createTempFile("book_", ".docx").apply { deleteOnExit() }
        tmp.writeBytes(bytes)
        return try {
            ZipFile(tmp).use { zip ->
                val entry = zip.getEntry("word/document.xml")
                    ?: throw UnsupportedBookException("DOCX без word/document.xml")
                val doc = Jsoup.parse(
                    zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8),
                    "",
                    Parser.xmlParser(),
                )
val metadata = extractDocxMetadata(bytes, name)
                // Параграфы w:p и явные w:p (namespace у jsoup не снимается)
                val docxParas = doc.select("*")
                    .filter { it.tagName().substringAfter(':').equals("p", ignoreCase = true) }
                val text = docxParas.joinToString("\n\n") { p ->
                    p.select("*")
                        .filter { it.tagName().substringAfter(':').equals("t", ignoreCase = true) }
                        .joinToString("") { it.text().trim() }
                        .trim()
                }.trim()
                if (text.isEmpty()) throw UnsupportedBookException("В DOCX нет текста")
                val chapters = splitIntoChapters(text)
                if (chapters.isEmpty()) throw UnsupportedBookException("В DOCX нет текста")
                ParsedBook(
                    metadata = metadata,
                    chapters = chapters.mapIndexed { idx, (chTitle, chText) ->
                        BookChapter.create(idx, bookId, chTitle, scanlator = metadata.author, text = chText)
                    },
                )
            }
        } finally {
            tmp.delete()
        }
    }

    private fun extractDocxMetadata(bytes: ByteArray, name: String): BookMetadata {
        val tmp = File.createTempFile("book_", ".docx").apply { deleteOnExit() }
        tmp.writeBytes(bytes)
        return try {
            ZipFile(tmp).use { zip ->
                val coreEntry = zip.getEntry("docProps/core.xml") ?: return@use BookMetadata(title = name)
                val doc = Jsoup.parse(
                    zip.getInputStream(coreEntry).readBytes().toString(Charsets.UTF_8),
                    "", Parser.xmlParser(),
                )
                fun localText(localName: String): String? =
                    doc.select("*")
                        .firstOrNull { it.tagName().substringAfter(':').equals(localName, ignoreCase = true) }
                        ?.text()?.trim()?.takeIf { it.isNotBlank() }
                val title = localText("title") ?: name
                val author = localText("creator")
                val description = localText("description")
                BookMetadata(title = title, author = author, description = description)
            }
        } catch (e: Exception) {
            BookMetadata(title = name)
        } finally {
            tmp.delete()
        }
    }

    // ---------- Разбиение текста на главы ----------

    fun splitIntoChapters(text: String): List<Pair<String, String>> {
        val cleaned = text.replace("\r\n", "\n").replace('\u0000', ' ').trim()
        if (cleaned.isEmpty()) return emptyList()
        val lines = cleaned.split("\n")

        val heading = Regex(
            """^\s*(?:(?:глава|часть|том|книга|пролог|эпилог)[\s.:–—-].*|(?:chapter|part|book|prologue|epilogue)\s+.*|[#]+\s+.*)""",
            RegexOption.IGNORE_CASE,
        )

        val chapters = mutableListOf<Pair<String, String>>()
        var title = ""
        val buffer = StringBuilder()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.length in 2..80 && heading.matches(line)) {
                flushChapter(title, buffer, chapters)
                title = trimmed
                buffer.setLength(0)
            } else {
                if (buffer.isNotEmpty()) buffer.append('\n')
                buffer.append(trimmed)
            }
        }
        flushChapter(title, buffer, chapters)
        if (chapters.isEmpty()) {
            chapters += "Книга" to cleaned
        }
        return chapters
    }

    private fun flushChapter(title: String, sb: StringBuilder, out: MutableList<Pair<String, String>>) {
        val text = sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()
        if (text.isNotBlank()) {
            out += title.ifBlank { "Глава ${out.size + 1}" } to text
        }
        sb.setLength(0)
    }

    // ---------- Кодировки ----------

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        if (bytes.size >= 2) {
            val le = bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()
            val be = bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()
            if (le || be) {
                val charset = if (le) StandardCharsets.UTF_16LE else StandardCharsets.UTF_16BE
                return String(bytes, 2, bytes.size - 2, charset)
            }
        }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val decoded = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            String(bytes, Charset.forName("windows-1251"))
        }
        if (decoded.indexOf('\u0000') >= 0 || decoded.isBlank()) {
            throw UnsupportedBookException("Бинарный формат не читается как текст")
        }
        return decoded
    }

    private fun UniFile.extension(): String {
        return name.orEmpty().substringAfterLast('.', "").lowercase()
    }
}
