package eu.kanade.tachiyomi.ui.koma

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID

/** Категории каталога KOMA (порядок как в прежней сборке). */
val categories = listOf("Все", "Манга", "Манхва", "Маньхуа", "Комиксы")

/**
 * Архив, лежащий в локальной библиотеке приложения.
 *
 * Раньше KOMA держала свою полку в `filesDir/library` и открывала её своей
 * читалкой. Теперь книги живут там же, где их видит главная библиотека
 * (`local/`), и читаются ГЛАВНОЙ читалкой — она единственная.
 */
data class LocalArchive(
    val file: File,
    val title: String,
    val size: Long,
) {
    val extension: String get() = file.extension.lowercase(Locale.ROOT)
}

/** Книга каталога Internet Archive. */
data class CatalogBook(
    val id: String,
    val title: String,
    val author: String,
    val category: String = "Манга",
    val year: String = "",
    val cover: String = "https://archive.org/services/img/$id",
)

val curatedCatalog = listOf(
    CatalogBook("hokusaimanga10kats1912", "Хокусай. Манга, том X", "Кацусика Хокусай", year = "1912"),
    CatalogBook("hokusaimanga06kats", "Хокусай. Манга, том VI", "Кацусика Хокусай", year = "1878"),
    CatalogBook("hokusaimanga02kats", "Хокусай. Манга, том II", "Кацусика Хокусай", year = "1912"),
    CatalogBook("BlueBeetle015", "Blue Beetle № 15", "Holyoke Publishing", "Комиксы", "1942"),
)

/**
 * Импорт книг в локальную библиотеку.
 *
 * Файл кладётся как есть (CBZ/ZIP/CBR/PDF) в корень `local/`: главная библиотека
 * показывает такой файл как мангу с одной главой и открывает его в главной
 * читалке — со всеми её режимами, OCR-баблами и авточтением. Распаковка на
 * страницы больше не нужна: её делает читалка на лету.
 */
class LibraryRepository(val context: Context) {

    val prefs = context.getSharedPreferences("koma", Context.MODE_PRIVATE)

    private val storageManager: StorageManager by lazy { Injekt.get() }

    /** Корень локальной библиотеки (`<хранилище>/local`). */
    fun localRoot(): File? {
        val dir = storageManager.getLocalSourceDirectory() ?: return null
        val path = dir.filePath ?: return null
        return File(path).apply { mkdirs() }
    }

    /** Архивы, уже лежащие в локальной библиотеке (свежие сверху). */
    fun archives(): List<LocalArchive> {
        val root = localRoot() ?: return emptyList()
        return root.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase(Locale.ROOT) in SUPPORTED_EXTENSIONS }
            .sortedByDescending { it.lastModified() }
            .map { LocalArchive(it, it.nameWithoutExtension, it.length()) }
    }

    fun hasArchive(title: String): Boolean {
        val target = title.lowercase(Locale.ROOT)
        return archives().any { it.title.lowercase(Locale.ROOT) == target }
    }

    fun displayName(uri: Uri): String = context.contentResolver.query(uri, null, null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
            } else {
                null
            }
        } ?: "Новая книга.cbz"

    /**
     * Копирует выбранный файл в локальную библиотеку.
     *
     * Имя сохраняется (без перезаписи существующего): если файл с таким именем
     * уже есть, добавляется «(2)». Расширение проверяется — читалка понимает
     * CBZ/ZIP, CBR (RAR4) и PDF.
     */
    suspend fun importUri(uri: Uri): LocalArchive = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(extension in SUPPORTED_EXTENSIONS) {
            "Нужен CBZ, CBR (RAR4) или PDF — файл «$name» не подходит"
        }
        val root = localRoot() ?: error("Хранилище недоступно: выберите папку в настройках хранилища")
        val target = uniqueFile(root, name.substringBeforeLast('.', name), extension)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> copyBounded(input, output, MAX_IMPORT_BYTES) }
        } ?: error("Не удалось открыть файл")
        // PDF главная читалка не открывает (её архивный путь — ZIP/RAR), поэтому
        // страницы рендерим в папку-главу: дальше это обычная локальная манга.
        if (extension == "pdf") {
            val rendered = runCatching { renderPdfAsChapter(target, name.substringBeforeLast('.', name)) }.getOrNull()
            if (rendered != null) {
                target.delete()
                return@withContext rendered
            }
        }
        LocalArchive(target, target.nameWithoutExtension, target.length())
    }

    /**
     * PDF → папка `<название>/Глава 1/001.jpg`: так книгу видит и открывает
     * главная библиотека, а KOMA не тащит свою читалку ради PdfRenderer.
     */
    private fun renderPdfAsChapter(pdf: File, title: String): LocalArchive {
        val root = localRoot() ?: error("Хранилище недоступно")
        val safe = sanitize(title)
        var folder = File(root, safe)
        var index = 2
        while (folder.exists()) {
            folder = File(root, "$safe ($index)")
            index++
        }
        val chapter = File(folder, "Глава 1").apply { mkdirs() }
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                require(renderer.pageCount <= 2000) { "В PDF больше 2000 страниц" }
                repeat(renderer.pageCount) { pageIndex ->
                    renderer.openPage(pageIndex).use { page ->
                        val width = 1400
                        val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        Canvas(bitmap).drawColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        File(chapter, pageIndex.toString().padStart(5, '0') + ".jpg").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)
                        }
                        bitmap.recycle()
                    }
                }
            }
        }
        require(chapter.listFiles().orEmpty().isNotEmpty()) { "Не удалось отрисовать PDF" }
        return LocalArchive(chapter, folder.name, chapter.listFiles().orEmpty().sumOf { it.length() })
    }

    suspend fun search(query: String): List<CatalogBook> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext curatedCatalog
        val q = "(title:(\"${query.replace("\"", " ")}\")) AND mediatype:texts AND year:[1800 TO 1930]"
        val response = getText(
            "https://archive.org/advancedsearch.php?q=${URLEncoder.encode(q, "UTF-8")}" +
                "&output=json&rows=30&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=year",
        )
        val docs = JSONObject(response).getJSONObject("response").getJSONArray("docs")
        List(docs.length()) {
            val json = docs.getJSONObject(it)
            CatalogBook(
                id = json.getString("identifier"),
                title = json.optString("title"),
                author = json.optString("creator", "Internet Archive"),
                category = "Комиксы",
                year = json.optString("year"),
            )
        }
    }

    /** Скачивает книгу из Internet Archive прямо в локальную библиотеку. */
    suspend fun download(item: CatalogBook): LocalArchive = withContext(Dispatchers.IO) {
        val data = JSONObject(getText("https://archive.org/metadata/${item.id}"))
        val files = data.getJSONArray("files")
        val candidates = List(files.length()) { files.getJSONObject(it) }.filter { file ->
            file.optString("name").substringAfterLast('.').lowercase(Locale.ROOT) in SUPPORTED_EXTENSIONS &&
                file.optString("size").toLongOrNull()?.let { it in 1..MAX_IMPORT_BYTES } == true &&
                !file.optBoolean("private")
        }
        val chosen = candidates.minByOrNull {
            when (it.getString("name").substringAfterLast('.').lowercase(Locale.ROOT)) {
                "cbz" -> 0
                "pdf" -> 1
                else -> 2
            }
        } ?: error("В источнике нет доступного CBZ, CBR или PDF")
        val name = chosen.getString("name")
        val extension = name.substringAfterLast('.').lowercase(Locale.ROOT)
        val root = localRoot() ?: error("Хранилище недоступно")
        val target = uniqueFile(root, item.title, extension)
        val url = "https://archive.org/download/${item.id}/${URLEncoder.encode(name, "UTF-8").replace("+", "%20")}"
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        try {
            connection.inputStream.use { input ->
                target.outputStream().use { output -> copyBounded(input, output, MAX_IMPORT_BYTES) }
            }
        } finally {
            connection.disconnect()
        }
        LocalArchive(target, target.nameWithoutExtension, target.length())
    }

    /** Книга по прямой HTTPS-ссылке (CBZ/CBR/PDF). */
    suspend fun downloadUrl(url: String): LocalArchive = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "Используйте защищённую ссылку HTTPS" }
        val guess = Uri.parse(url).lastPathSegment?.substringBeforeLast('.') ?: "Онлайн-книга"
        val extension = Uri.parse(url).lastPathSegment?.substringAfterLast('.', "cbz")
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it in SUPPORTED_EXTENSIONS } ?: "cbz"
        val root = localRoot() ?: error("Хранилище недоступно")
        val target = uniqueFile(root, guess, extension)
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        try {
            connection.inputStream.use { input ->
                target.outputStream().use { output -> copyBounded(input, output, MAX_IMPORT_BYTES) }
            }
        } finally {
            connection.disconnect()
        }
        LocalArchive(target, target.nameWithoutExtension, target.length())
    }

    fun deleteArchive(archive: LocalArchive): Boolean = runCatching { archive.file.delete() }.getOrDefault(false)

    /** Имя папки/файла без символов, которые нельзя использовать в файловой системе. */
    private fun sanitize(raw: String): String =
        raw.replace(Regex("""[\\/:*?"<>|]"""), " ").trim().ifBlank { "Книга" }.take(80)

    private fun uniqueFile(root: File, rawTitle: String, extension: String): File {
        val safe = sanitize(rawTitle)
        var candidate = File(root, "$safe.$extension")
        var index = 2
        while (candidate.exists()) {
            candidate = File(root, "$safe ($index).$extension")
            index++
        }
        return candidate
    }

    private fun getText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 25_000
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "Файл больше ${limit / (1024 * 1024)} МБ" }
            output.write(buffer, 0, read)
        }
    }

    /**
     * Переносит книги прежней полки KOMA (`filesDir/library`) в локальную
     * библиотеку: у пользователя, который уже импортировал книги в старой
     * сборке, они не пропадут после перехода на главную читалку.
     */
    suspend fun migrateOldShelf(): Int = withContext(Dispatchers.IO) {
        val legacy = File(context.filesDir, "library")
        if (!legacy.isDirectory) return@withContext 0
        val root = localRoot() ?: return@withContext 0
        // Прежняя полка хранилась в SharedPreferences("koma") под ключом "books".
        val raw = prefs.getString("books", null).orEmpty()
        var moved = 0
        runCatching {
            if (raw.isBlank()) return@runCatching
            val books = JSONArray(raw)
            for (index in 0 until books.length()) {
                val book = books.getJSONObject(index)
                val title = book.optString("title").ifBlank { "KOMA ${index + 1}" }
                val pages = book.optJSONArray("pages") ?: continue
                if (pages.length() == 0) continue
                val firstPage = File(pages.getString(0).removePrefix("file://"))
                val bookDir = firstPage.parentFile ?: continue
                if (!bookDir.path.startsWith(legacy.path)) continue
                val folder = File(root, sanitize(title))
                if (folder.exists()) continue
                folder.mkdirs()
                // Страницы -> папка-глава: главная библиотека показывает такую
                // папку главой и открывает её в главной читалке.
                val chapter = File(folder, "Глава 1").apply { mkdirs() }
                bookDir.listFiles().orEmpty().sortedBy { it.name }.forEach { page ->
                    page.copyTo(File(chapter, page.name), overwrite = false)
                }
                moved++
            }
        }
        moved
    }

    private companion object {
        val SUPPORTED_EXTENSIONS = setOf("cbz", "cbr", "pdf", "zip")
        const val MAX_IMPORT_BYTES = 400L * 1024 * 1024
    }
}
