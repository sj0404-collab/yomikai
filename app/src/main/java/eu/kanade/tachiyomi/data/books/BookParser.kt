package eu.kanade.tachiyomi.data.books

import com.hippo.unifile.UniFile
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.ZipException
import java.util.zip.ZipFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

/**
 * Разбирает электронную книгу любого формата в список глав (название, текст).
 *
 * Поддерживаются «простые» текстовые форматы и контейнеры:
 *  • txt / md / markdown / rtf / srt / vtt / json и любые другие неизвестные
 *    расширения — читаются как текст с автоопределением кодировки
 *    (UTF-8, UTF-16 LE/BE, windows-1251 — русские книги без BOM);
 *  • fb2 — XML FictionBook (главы = разделы, текст = <p>);
 *  • html / htm — заголовки h1..h3 становятся главами;
 *  • epub — zip (container.xml → OPF → spine), каждая запись spine — глава;
 *  • docx — zip (word/document.xml), абзацы <w:p> конкатенируются.
 *
 * Импорт работает для ЛЮБОГО файла (любое расширение): файл добавляется в
 * библиотеку книг, а при открытии парсер честно пытается извлечь текст.
 * Если бинарный формат не читаем (pdf, djvu, mobi и т.п.) — бросается
 * [UnsupportedBookException] с понятным сообщением.
 */
object BookParser {

    class UnsupportedBookException(message: String) : Exception(message)

    data class ParsedBook(
        val title: String,
        val chapters: List<Pair<String, String>>,
    )

    private val TEXT_EXTS = setOf(
        "txt", "md", "markdown", "log", "text", "lrc", "srt", "vtt",
        "ini", "conf", "cfg", "csv", "json", "yaml", "yml", "xml", "rtf",
    )

    fun parse(bookFile: UniFile): ParsedBook {
        val input = bookFile.openInputStream() ?: throw UnsupportedBookException("Не удалось открыть файл книги")
        val bytes = input.use { it.readBytes() }
        if (bytes.isEmpty()) throw UnsupportedBookException("Файл пуст")
        if (bytes.size > 40_000_000L) {
            throw UnsupportedBookException("Файл слишком большой для чтения (более 40 МБ)")
        }
        val name = bookFile.name.orEmpty().substringBeforeLast('.').ifBlank { "Книга" }
        val ext = bookFile.extension().lowercase()

        return try {
            when (ext) {
                "fb2" -> parseFb2(bytes, name)
                "htm", "html" -> parseHtml(bytes, name)
                "epub" -> parseEpub(bytes)
                "docx" -> parseDocx(bytes, name)
                else -> parsePlain(bytes, name)
            }
        } catch (e: UnsupportedBookException) {
            throw e
        } catch (e: ZipException) {
            throw UnsupportedBookException("Повреждённый контейнер ($ext)")
        } catch (e: Exception) {
            throw UnsupportedBookException("Не удалось разобрать файл: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---------- Плоский текст ----------

    private fun parsePlain(bytes: ByteArray, name: String): ParsedBook {
        val text = decodeText(bytes)
        val pages = splitIntoChapters(text)
        if (pages.isEmpty()) throw UnsupportedBookException("В файле нет текста")
        return ParsedBook(name, pages)
    }

    // ---------- FB2 ----------

    private fun parseFb2(bytes: ByteArray, name: String): ParsedBook {
        val doc = Jsoup.parse(decodeText(bytes), "", Parser.xmlParser())
        val title = doc.selectFirst("description > title-info > book-title")?.text()
            ?.takeIf { it.isNotBlank() }
            ?: name
        val chapters = mutableListOf<Pair<String, String>>()
        for (body in doc.select("section")) {
            val chapterTitle = body.selectFirst(":scope > title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "Глава ${chapters.size + 1}"
            val text = body.select(":scope > p").joinToString("\n\n") { it.text().trim() }
            if (text.isNotBlank()) chapters += chapterTitle to text
        }
        if (chapters.isEmpty()) {
            val whole = doc.select("p").joinToString("\n\n") { it.text().trim() }
            if (whole.isNotBlank()) chapters += "Книга" to whole
            else throw UnsupportedBookException("В FB2 нет текста")
        }
        return ParsedBook(title, chapters)
    }

    // ---------- HTML ----------

    private fun parseHtml(bytes: ByteArray, name: String): ParsedBook {
        val doc = Jsoup.parse(decodeText(bytes))
        val title = doc.title().trim().takeIf { it.isNotBlank() } ?: name
        val chapters = htmlToChapters(doc)
        if (chapters.isEmpty()) throw UnsupportedBookException("В HTML нет текста")
        return ParsedBook(title, chapters)
    }

    private fun htmlToChapters(doc: Document): List<Pair<String, String>> {
        val headingTags = setOf("h1", "h2", "h3", "h4")
        val order = mutableListOf<Pair<String?, String>>() // (заголовок, параграф)
        val body = doc.body() ?: return emptyList()
        // Выбираем только блочные текстовые элементы: без «div» и вложенности
        // (иначе текст параграфов задваивается через родителя).
        for (el in body.select("p,pre,blockquote,h1,h2,h3,h4")) {
            val tag = el.normalName()
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

    // ---------- EPUB ----------

    private fun parseEpub(bytes: ByteArray): ParsedBook {
        // UniFile не даёт дескриптор для ZipFile — копируем во временный файл.
        val tmp = java.io.File.createTempFile("book_", ".epub").apply { deleteOnExit() }
        tmp.writeBytes(bytes)
        return try {
            parseEpubZip(ZipFile(tmp))
        } finally {
            tmp.delete()
        }
    }

    private fun parseEpubZip(zip: ZipFile): ParsedBook {
        val containerEntry = zip.getEntry("META-INF/container.xml")
            ?: zip.getEntry("meta-inf/container.xml")
            ?: throw UnsupportedBookException("EPUB без container.xml")
        val container = Jsoup.parse(
            zip.getInputStream(containerEntry).readBytes().toString(Charsets.UTF_8),
            "",
            Parser.xmlParser(),
        )
        val opfPathRaw = container.selectFirst("rootfile")?.attr("full-path")
            ?: throw UnsupportedBookException("EPUB без rootfile")
        // Пути в OPF часто процент-кодированы (%D0%B3%D0%BB... для русских
        // имён) — без декодирования getEntry не находит файлы и книга
        // целиком «без текста» (баг с устройства).
        val opfPath = decodeHref(opfPathRaw)
        val opfEntry = zip.getEntry(opfPath)
            ?: throw UnsupportedBookException("EPUB без OPF ($opfPathRaw)")
        val opf = Jsoup.parse(
            zip.getInputStream(opfEntry).readBytes().toString(Charsets.UTF_8),
            "",
            Parser.xmlParser(),
        )
        val base = opfPath.substringBeforeLast('/', "")
        val title = opf.selectFirst("metadata")?.children()
            ?.firstOrNull { it.normalName().equals("title", ignoreCase = true) }
            ?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: "Книга"

        // id -> (href, mediaType, properties)
        val manifest = mutableMapOf<String, Triple<String, String, String>>()
        opf.selectFirst("manifest")?.children()?.forEach { item ->
            val id = item.attr("id")
            if (id.isNotBlank()) {
                manifest[id] = Triple(item.attr("href"), item.attr("media-type"), item.attr("properties"))
            }
        }
        var spine = opf.selectFirst("spine")?.children()
            ?.mapNotNull { it.attr("idref").takeIf { x -> x.isNotBlank() } }
            ?: emptyList()
        if (spine.isEmpty()) {
            // Кривой EPUB без spine: берём все текстовые файлы манифеста.
            spine = manifest.keys.toList()
        }

        val chapters = mutableListOf<Pair<String, String>>()
        for (idref in spine) {
            val (hrefRaw, mediaType, properties) = manifest[idref] ?: continue
            val chunk = runCatching { parseEpubItem(zip, base, hrefRaw, mediaType, properties) }.getOrNull()
            if (chunk != null) chapters += chunk
        }
        if (chapters.isEmpty()) throw UnsupportedBookException("В EPUB нет текстовых глав")
        return ParsedBook(title, chapters)
    }

    /** Процент-декодирование href из OPF (русские имена файлов и пробелы). */
    private fun decodeHref(raw: String): String {
        return runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }

    /**
     * Одна запись spine → (название, текст) или null, если файл не текстовый
     * или не прочитался. Один битый файл больше не убивает всю книгу.
     */
    private fun parseEpubItem(
        zip: ZipFile,
        base: String,
        hrefRaw: String,
        mediaType: String,
        properties: String,
    ): Pair<String, String>? {
        val href = decodeHref(hrefRaw.substringBefore('#')).trim().ifBlank { return null }
        val lowerHref = href.lowercase()
        val ext = lowerHref.substringAfterLast('.', "")
        // Служебные и нетекстовые записи пропускаем: NCX, навигация EPUB3,
        // картинки/аудио/шрифты.
        if (mediaType.equals("application/x-dtbncx+xml", ignoreCase = true) || ext == "ncx") return null
        if ("nav" in properties.split(' ', '\t')) return null
        val textLike = mediaType.isBlank() ||
            mediaType.contains("html", ignoreCase = true) ||
            mediaType.contains("xml", ignoreCase = true) ||
            mediaType.startsWith("text/", ignoreCase = true) ||
            ext in setOf("xhtml", "html", "htm", "xml")
        if (!textLike) return null
        val name = href.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Глава" }
        val entry = zip.getEntry(if (base.isEmpty()) href else "$base/$href")
            ?: zip.getEntry(href)
            ?: return null
        val entryBytes = zip.getInputStream(entry).readBytes()
        val doc = Jsoup.parse(decodeText(entryBytes))
        val docTitle = doc.title().trim().takeIf { it.isNotBlank() }
        val chunk = htmlToChapters(doc)
        if (chunk.isEmpty()) {
            val t = doc.body()?.text()?.trim()
            if (t.isNullOrBlank()) return null
            return (docTitle ?: name) to t
        }
        // Первый заголовок файла = название главы, остальное — текст.
        return (docTitle ?: chunk.first().first) to chunk.joinToString("\n\n") { it.second }
    }

    // ---------- DOCX ----------

    private fun parseDocx(bytes: ByteArray, name: String): ParsedBook {
        val tmp = java.io.File.createTempFile("book_", ".docx").apply { deleteOnExit() }
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
                val title = parseCoreTitle(zip) ?: name
                val paras = doc.select("p")
                val text = paras.joinToString("\n\n") { p ->
                    p.select("t").joinToString("") { it.text() }.trim()
                }.trim()
                if (text.isEmpty()) throw UnsupportedBookException("В DOCX нет текста")
                val chapters = splitIntoChapters(text)
                if (chapters.isEmpty()) throw UnsupportedBookException("В DOCX нет текста")
                ParsedBook(title, chapters)
            }
        } finally {
            tmp.delete()
        }
    }

    private fun parseCoreTitle(zip: ZipFile): String? {
        return runCatching {
            val entry = zip.getEntry("docProps/core.xml") ?: return null
            val doc = Jsoup.parse(
                zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8),
                "",
                Parser.xmlParser(),
            )
            doc.select("title").firstOrNull()?.text()?.trim()?.takeIf { it.isNotBlank() }
        }.getOrNull()
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
            // Заголовков нет — одна глава целиком.
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
        // BOM
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
        // Строгий UTF-8; если не читается — windows-1251 (типичная кодировка
        // русских книг без BOM). На выходе контролируем «бинарность».
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