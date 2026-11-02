package eu.kanade.tachiyomi.ui.koma

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.github.junrar.Archive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.zip.ZipFile

val categories = listOf("Все", "Манга", "Манхва", "Маньхуа", "Комиксы")

data class Book(val id: String, val title: String, val author: String, val category: String, val pages: List<String>, val source: String = "Импорт с устройства", val coverIndex: Int = 0, val progress: Int = 0, val mode: String = "ltr", val favorite: Boolean = false) {
    val cover: String get() = pages.getOrElse(coverIndex) { pages.firstOrNull().orEmpty() }
    fun json() = JSONObject().put("id", id).put("title",title).put("author",author).put("category",category).put("pages",JSONArray(pages)).put("source",source).put("cover",coverIndex).put("progress",progress).put("mode",mode).put("favorite",favorite)
    companion object {
        fun parse(j: JSONObject): Book = Book(j.getString("id"),j.getString("title"),j.optString("author"),j.optString("category","Манга"),j.getJSONArray("pages").let { a -> List(a.length()) { a.getString(it) } },j.optString("source"),j.optInt("cover"),j.optInt("progress"),j.optString("mode","rtl"),j.optBoolean("favorite"))
    }
}

/**
 * Откуда берётся книга каталога. Все источники бесплатные и открытые; для
 * каждого известен способ получить файл (CBZ/PDF/EPUB).
 */
enum class CatalogSource(val title: String, val note: String) {
    ARCHIVE("Internet Archive", "Сканы комиксов и манги, 1800–1930"),
    GUTENBERG("Project Gutenberg", "Книги в EPUB/TXT, 70 000+ томов"),
    OPENLIBRARY("Open Library", "Каталог книг, файл — с Internet Archive"),
    ;
}

data class CatalogBook(
    val id: String,
    val title: String,
    val author: String,
    val category: String = "Манга",
    val year: String = "",
    val cover: String = "https://archive.org/services/img/$id",
    /** Источник; от него зависит способ загрузки файла. */
    val source: CatalogSource = CatalogSource.ARCHIVE,
    /** Готовая прямая ссылка на файл (Gutenberg отдаёт её сразу). */
    val directUrl: String? = null,
)
val curatedCatalog = listOf(
    CatalogBook("hokusaimanga10kats1912", "Хокусай. Манга, том X", "Кацусика Хокусай", year="1912"),
    CatalogBook("hokusaimanga06kats", "Хокусай. Манга, том VI", "Кацусика Хокусай", year="1878"),
    CatalogBook("hokusaimanga02kats", "Хокусай. Манга, том II", "Кацусика Хокусай", year="1912"),
    CatalogBook("BlueBeetle015", "Blue Beetle № 15", "Holyoke Publishing", "Комиксы", "1942")
)

class LibraryRepository(val context: Context) {

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0 Mobile Safari/537.36 Yomikai/KOMA"
    val prefs = context.getSharedPreferences("koma", Context.MODE_PRIVATE)
    private val root = File(context.filesDir,"library").apply { mkdirs() }
    fun load(): List<Book> {
        val saved = prefs.getString("books",null)
        if (saved != null) return runCatching { JSONArray(saved).let { a -> List(a.length()) { Book.parse(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
        return listOf(seed("hokusai_6","Хокусай. Манга VI","Кацусика Хокусай","Манга",12,"rtl","hokusaimanga06kats"),seed("blue_beetle","Blue Beetle № 15","Holyoke Publishing","Комиксы",0,"ltr","BlueBeetle015"),seed("hokusai_2","Хокусай. Манга II","Кацусика Хокусай","Манга",9,"rtl","hokusaimanga02kats")).also { save(it) }
    }
    private fun seed(id: String,title: String,author: String,category: String,cover: Int,mode: String,sourceId: String) = Book(id,title,author,category,context.assets.list("koma/books/$id").orEmpty().sorted().map { "file:///android_asset/koma/books/$id/$it" },"https://archive.org/details/$sourceId",cover,mode=mode)
    fun save(books: List<Book>) { prefs.edit().putString("books",JSONArray().apply { books.forEach { put(it.json()) } }.toString()).apply() }
    /**
     * Удаление книги: убираем исходный файл и индекс. Копий страниц больше нет —
     * удалять нечего, поэтому «удалить книгу» снова значит «освободить место».
     */
    fun delete(book: Book) {
        if (book.pages.firstOrNull().orEmpty().startsWith("file:///android_asset")) return
        val id = book.id.removePrefix("book/")
        listOf("cbz", "zip", "cbr", "rar", "pdf").forEach { ext ->
            File(root, "$id.$ext").delete()
        }
        File(root, id).deleteRecursively()
        KomaPageStore.unregister(book.id)
        runCatching {
            File(KomaPageStore.indexDir(context), book.id.replace(Regex("[^\\w.-]"), "_") + ".json").delete()
        }
    }

    /** Сколько места занимают исходники библиотеки (индекс — отдельно, он в КБ). */
    fun sizeBytes(): Long = KomaPageStore.sizeOf(root)
    fun displayName(uri: Uri): String = context.contentResolver.query(uri,null,null,null,null)?.use { c -> if(c.moveToFirst()) c.getString(c.getColumnIndex(OpenableColumns.DISPLAY_NAME)) else null } ?: "Новая книга.cbz"
    /**
     * Импорт книги с устройства — БЕЗ распаковки.
     *
     * Раньше архив раскладывался постранично в `filesDir/library/<id>/`, то есть
     * книга занимала место дважды (архив + все страницы). Для локальной
     * библиотеки из десятка томов это и давало гигабайты при почти пустой
     * библиотеке. Теперь в `library/` лежит ОДИН файл — сам архив, — а страницы
     * читаются из него в момент показа (KomaPageStore). На диске дополнительно
     * только индекс: `filesDir/koma-index/book.<id>.json` с числом страниц.
     */
    suspend fun import(uri: Uri, category: String): Book = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val ext = name.substringAfterLast('.', "cbz").lowercase()
        require(ext in setOf("cbz", "zip", "cbr", "rar", "pdf")) {
            "Нужен CBZ, CBR или PDF. Файл «$name» не подходит."
        }
        val temp = File(context.cacheDir, "import_${UUID.randomUUID()}.$ext")
        try {
            val copied: Long = context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { out -> copyBounded(input, out, 1_500_000_000L) }
            } ?: error("Не удалось открыть файл «$name»")
            require(copied > 0) { "Файл «$name» пустой" }
            adopt(temp, name.substringBeforeLast('.'), ext, category, "Импорт с устройства")
        } finally {
            temp.delete()
        }
    }

    /**
     * Кладёт ОДИН файл в библиотеку и регистрирует его страницы.
     *
     * Общая точка для импорта с устройства и скачивания из каталога: копия
     * архива — это максимум места, который занимает книга.
     */
    /** Расширение из имени или ссылки: только буквы и цифры, по умолчанию cbz. */
    private fun extOf(name: String): String {
        val raw = name.substringAfterLast('/', name).substringAfterLast('.', "cbz")
            .filter { it.isLetterOrDigit() }.lowercase()
        return if (raw.length in 2..4) raw else "cbz"
    }

    private fun adopt(temp: File, rawTitle: String, rawExt: String, category: String, source: String): Book {
        val ext = if (rawExt.lowercase() in setOf("cbz", "zip", "cbr", "rar", "pdf")) {
            rawExt.lowercase()
        } else {
            "cbz"
        }
        val title = rawTitle
        val id = UUID.randomUUID().toString()
        val key = "book/$id"
        root.mkdirs()
        val stored = File(root, "$id.$ext")
        temp.copyTo(stored, overwrite = true)
        try {
            val mode = when (category) {
                "Манга" -> "rtl"
                "Манхва", "Маньхуа" -> "vertical"
                else -> "ltr"
            }
            val pages: List<String> = when (ext) {
                "pdf" -> {
                    require(KomaPageStore.registerPdf(key, stored)) { "PDF не открылся или в нём нет страниц" }
                    val count = KomaPageStore.count(key)
                    KomaPageStore.saveIndex(
                        context, key, count,
                        file = stored.relativeTo(context.filesDir).path,
                        kind = KomaPageStore.KIND_PDF,
                    )
                    (0 until count).map { KomaPageStore.pathOf(key, it) }
                }
                else -> {
                    val names = KomaPageStore.archiveNames(stored)
                    require(names.isNotEmpty()) {
                        "В архиве нет изображений. RAR5 и архивы с паролем не поддерживаются."
                    }
                    require(names.size <= 3000) { "В архиве более 3000 страниц" }
                    val sorted = names.sortedBy { naturalKey(it) }
                    KomaPageStore.register(key, KomaPageStore.archivePages(stored, sorted))
                    KomaPageStore.saveIndex(
                        context, key, sorted.size,
                        file = stored.relativeTo(context.filesDir).path,
                        kind = KomaPageStore.KIND_ARCHIVE,
                    )
                    sorted.indices.map { KomaPageStore.pathOf(key, it) }
                }
            }
            return Book(
                id = key,
                title = title.ifBlank { "Без названия" },
                author = "Локальный архив",
                category = category,
                pages = pages,
                source = source,
                mode = mode,
            )
        } catch (e: Exception) {
            stored.delete()
            KomaPageStore.unregister(key)
            throw e
        }
    }

    /**
     * Поиск по выбранному источнику.
     *
     * Раньше здесь был один archive.org-запрос без User-Agent: Cloudflare
     * отвечал 403, а любой сбой превращался в «Каталог недоступен, проверьте
     * интернет». Теперь UA/редиректы выставлены явно, а текст ошибки —
     * настоящая причина (код ответа или тип сетевой ошибки).
     */
    suspend fun search(
        query: String,
        source: CatalogSource = CatalogSource.ARCHIVE,
    ): List<CatalogBook> = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            return@withContext when (source) {
                CatalogSource.ARCHIVE -> curatedCatalog
                else -> emptyList()
            }
        }
        when (source) {
            CatalogSource.ARCHIVE -> searchArchive(query)
            CatalogSource.GUTENBERG -> searchGutenberg(query)
            CatalogSource.OPENLIBRARY -> searchOpenLibrary(query)
        }
    }

    private fun searchArchive(query: String): List<CatalogBook> {
        val q = "(title:(\"${query.replace("\"", " ")}\") OR subject:(\"${query.replace("\"", " ")}\")" +
            ") AND mediatype:texts"
        val url = "https://archive.org/advancedsearch.php?q=${URLEncoder.encode(q, "UTF-8")}" +
            "&output=json&rows=30&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=year"
        val docs = JSONObject(getText(url)).getJSONObject("response").getJSONArray("docs")
        return List(docs.length()) {
            val json = docs.getJSONObject(it)
            CatalogBook(
                id = json.getString("identifier"),
                title = json.optString("title"),
                author = json.optString("creator", "Internet Archive"),
                category = "Комиксы",
                year = json.optString("year"),
                source = CatalogSource.ARCHIVE,
            )
        }
    }

    /** Gutendex — открытый JSON-API Project Gutenberg. EPUB идёт в «Книги». */
    private fun searchGutenberg(query: String): List<CatalogBook> {
        val url = "https://gutendex.com/books/?search=${URLEncoder.encode(query, "UTF-8")}"
        val results = JSONObject(getText(url)).optJSONArray("results") ?: JSONArray()
        return List(results.length()) { index ->
            val book = results.getJSONObject(index)
            val authors = book.optJSONArray("authors")
            val author = authors?.let { list ->
                (0 until list.length())
                    .mapNotNull { list.getJSONObject(it).optString("name").takeIf(String::isNotBlank) }
                    .joinToString(", ")
            }.orEmpty()
            val formats = book.optJSONObject("formats")
            val direct = formats?.optString("application/epub+zip")
                ?.takeIf { it.startsWith("http") }
                ?: formats?.optString("text/plain; charset=utf-8")?.takeIf { it.startsWith("http") }
            CatalogBook(
                id = "gutendex-${book.getInt("id")}",
                title = book.optString("title"),
                author = author.ifBlank { "Project Gutenberg" },
                category = "Книги",
                year = "",
                cover = formats?.optString("image/jpeg")?.takeIf { it.startsWith("http") }.orEmpty(),
                source = CatalogSource.GUTENBERG,
                directUrl = direct,
            )
        }.filter { it.title.isNotBlank() }
    }

    /**
     * Open Library: метаданные и (если есть) доступный скан на Internet Archive.
     * Книга без `ia` — только описание, скачивать нечего, поэтому не показываем.
     */
    private fun searchOpenLibrary(query: String): List<CatalogBook> {
        val url = "https://openlibrary.org/search.json?q=${URLEncoder.encode(query, "UTF-8")}" +
            "&limit=30&fields=key,title,author_name,first_publish_year,cover_i,ia"
        val docs = JSONObject(getText(url)).optJSONArray("docs") ?: JSONArray()
        return List(docs.length()) { index ->
            val book = docs.getJSONObject(index)
            val ia = book.optJSONArray("ia")?.optString(0).orEmpty()
            val coverId = book.optInt("cover_i", 0)
            CatalogBook(
                id = ia,
                title = book.optString("title"),
                author = book.optJSONArray("author_name")?.optString(0).orEmpty()
                    .ifBlank { "Open Library" },
                category = "Книги",
                year = book.optInt("first_publish_year", 0).takeIf { it > 0 }?.toString().orEmpty(),
                cover = if (coverId > 0) "https://covers.openlibrary.org/b/id/$coverId-M.jpg" else "",
                source = CatalogSource.OPENLIBRARY,
            )
        }.filter { it.id.isNotBlank() && it.title.isNotBlank() }
    }

    suspend fun download(item: CatalogBook): Book = withContext(Dispatchers.IO) {
        // Gutenberg отдаёт прямую ссылку на EPUB/TXT — качаем её, а не архив.
        item.directUrl?.let { direct ->
            require(direct.startsWith("https://")) { "Источник отдал небезопасную ссылку" }
            val extension = if (direct.contains(".epub")) "epub" else "txt"
            return@withContext downloadFile(direct, "${item.title}.$extension", item.category, "Gutenberg")
        }
        val data = JSONObject(getText("https://archive.org/metadata/${item.id}")); val a = data.getJSONArray("files")
        val candidates = List(a.length()) { a.getJSONObject(it) }.filter { f -> f.optString("name").substringAfterLast('.').lowercase() in listOf("cbz","cbr","pdf") && f.optString("size").toLongOrNull()?.let { it in 1..200_000_000L } == true && !f.optBoolean("private") }
        val f = candidates.minByOrNull { when(it.getString("name").substringAfterLast('.')) {"cbz"->0;"pdf"->1;else->2} } ?: error("В источнике нет доступного CBZ, CBR или PDF")
        val name = f.getString("name"); val tmp = File(context.cacheDir,"download.${name.substringAfterLast('.')}")
        try {
            val conn = URL("https://archive.org/download/${item.id}/${URLEncoder.encode(name,"UTF-8").replace("+","%20")}").openConnection() as HttpURLConnection
            conn.connectTimeout=20000; conn.readTimeout=60000
            try { conn.inputStream.use { i -> tmp.outputStream().use { o -> copyBounded(i,o,200_000_000L) } } } finally { conn.disconnect() }
            adopt(tmp,item.title,extOf(name),item.category,"https://archive.org/details/${item.id}").copy(author=item.author)
        } finally { tmp.delete() }
    }
    suspend fun downloadUrl(url: String, category: String): Book = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "Используйте защищённую ссылку HTTPS" }
        val tmp=File(context.cacheDir,"direct_download")
        val conn=URL(url).openConnection() as HttpURLConnection; conn.connectTimeout=20000;conn.readTimeout=60000
        try { conn.inputStream.use { i -> tmp.outputStream().use { o -> copyBounded(i,o,200_000_000L) } }; adopt(tmp,Uri.parse(url).lastPathSegment?.substringBeforeLast('.') ?: "Онлайн-книга",extOf(url),category,url) } finally { conn.disconnect();tmp.delete() }
    }
    /**
     * Скачивание по прямой ссылке в локальную библиотеку KOMA (свой формат
     * страниц). EPUB/TXT разбирает BookParser главной читалки, поэтому такие
     * файлы кладём в «Книги» и не трогаем.
     */
    private fun downloadFile(url: String, fileName: String, category: String, source: String): Book {
        val tmp = File(context.cacheDir, "dl_${UUID.randomUUID()}.${fileName.substringAfterLast('.')}")
        try {
            val conn = open(url)
            conn.inputStream.use { input -> tmp.outputStream().use { output -> copyBounded(input, output, 200_000_000) } }
            return adopt(tmp, fileName.substringBeforeLast('.'), extOf(fileName), category, source)
        } finally {
            tmp.delete()
        }
    }

    /**
     * HTTP-запрос с нормальными заголовками.
     *
     * Без User-Agent archive.org (Cloudflare) отвечает 403, а HttpURLConnection
     * на Android не следует за редиректами сам — отсюда были «нет соединения»
     * на живом интернете.
     */
    private fun open(url: String): HttpURLConnection {
        var current = url
        var redirects = 0
        while (true) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 40_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "*/*")
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location") ?: return conn
                conn.disconnect()
                current = if (location.startsWith("http")) location else URL(URL(current), location).toString()
                if (++redirects > 5) error("Слишком много перенаправлений")
                continue
            }
            if (code >= 400) {
                conn.disconnect()
                error("Источник ответил HTTP $code. Попробуйте другой источник или повторите позже.")
            }
            return conn
        }
    }

    private fun getText(url: String): String {
        val conn = open(url)
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: java.io.IOException) {
            throw java.io.IOException("Нет связи с источником: ${e.message ?: "сеть недоступна"}", e)
        } finally {
            conn.disconnect()
        }
    }
    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, limit: Long): Long {
        val buffer=ByteArray(8192);var total=0L
        while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=limit){"Файл превышает допустимый размер"};output.write(buffer,0,n)}
        return total
    }
}
