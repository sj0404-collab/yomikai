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

data class CatalogBook(val id: String, val title: String, val author: String, val category: String = "Манга", val year: String = "", val cover: String = "https://archive.org/services/img/$id")
val curatedCatalog = listOf(
    CatalogBook("hokusaimanga10kats1912", "Хокусай. Манга, том X", "Кацусика Хокусай", year="1912"),
    CatalogBook("hokusaimanga06kats", "Хокусай. Манга, том VI", "Кацусика Хокусай", year="1878"),
    CatalogBook("hokusaimanga02kats", "Хокусай. Манга, том II", "Кацусика Хокусай", year="1912"),
    CatalogBook("BlueBeetle015", "Blue Beetle № 15", "Holyoke Publishing", "Комиксы", "1942")
)

class LibraryRepository(val context: Context) {
    val prefs = context.getSharedPreferences("koma", Context.MODE_PRIVATE)
    private val root = File(context.filesDir,"library").apply { mkdirs() }
    fun load(): List<Book> {
        val saved = prefs.getString("books",null)
        if (saved != null) return runCatching { JSONArray(saved).let { a -> List(a.length()) { Book.parse(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
        return listOf(seed("hokusai_6","Хокусай. Манга VI","Кацусика Хокусай","Манга",12,"rtl","hokusaimanga06kats"),seed("blue_beetle","Blue Beetle № 15","Holyoke Publishing","Комиксы",0,"ltr","BlueBeetle015"),seed("hokusai_2","Хокусай. Манга II","Кацусика Хокусай","Манга",9,"rtl","hokusaimanga02kats")).also { save(it) }
    }
    private fun seed(id: String,title: String,author: String,category: String,cover: Int,mode: String,sourceId: String) = Book(id,title,author,category,context.assets.list("koma/books/$id").orEmpty().sorted().map { "file:///android_asset/koma/books/$id/$it" },"https://archive.org/details/$sourceId",cover,mode=mode)
    fun save(books: List<Book>) { prefs.edit().putString("books",JSONArray().apply { books.forEach { put(it.json()) } }.toString()).apply() }
    fun delete(book: Book) { if (!book.pages.firstOrNull().orEmpty().startsWith("file:///android_asset")) File(root,book.id).deleteRecursively() }
    fun displayName(uri: Uri): String = context.contentResolver.query(uri,null,null,null,null)?.use { c -> if(c.moveToFirst()) c.getString(c.getColumnIndex(OpenableColumns.DISPLAY_NAME)) else null } ?: "Новая книга.cbz"
    suspend fun import(uri: Uri, category: String): Book = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val temp = File(context.cacheDir,"import_${UUID.randomUUID()}.${name.substringAfterLast('.',"cbz")}")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { out -> copyBounded(input,out,300_000_000) } } ?: error("Не удалось открыть файл")
            extract(temp,name.substringBeforeLast('.'),category)
        } finally { temp.delete() }
    }
    private fun naturalKey(s: String): String = Regex("\\d+").replace(s.lowercase()) { it.value.padStart(10,'0') }
    private fun extract(file: File, title: String, category: String, source: String = "Импорт с устройства"): Book {
        val id = UUID.randomUUID().toString(); val dir = File(root,id).apply { mkdirs() }; val files = mutableListOf<Pair<String,String>>()
        var bytes = 0L
        fun putPage(name: String, write: (File)->Unit) {
            if (name.startsWith("__MACOSX") || name.substringAfterLast('.').lowercase() !in listOf("jpg","jpeg","png","webp","gif","bmp")) return
            require(files.size < 3000) { "В архиве более 3000 страниц" }
            val target = File(dir,"${files.size}.${name.substringAfterLast('.').lowercase()}")
            write(target); bytes += target.length(); require(bytes < 1_000_000_000L) { "Распакованный архив слишком большой (лимит 1 ГБ)" }
            files.add(name to target.absolutePath)
        }
        try {
            val magic = file.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
            when {
                magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() -> ZipFile(file).use { zip ->
                    zip.entries().asSequence().filter { !it.isDirectory }.forEach { e ->
                        require(e.size <= 40_000_000L) { "Слишком большая страница" }
                        putPage(e.name) { target -> zip.getInputStream(e).use { i -> target.outputStream().use { o -> copyBounded(i,o,40_000_000) } } }
                    }
                }
                magic[0] == 0x52.toByte() && magic[1] == 0x61.toByte() -> Archive(file).use { rar ->
                    rar.fileHeaders.filter { !it.isDirectory }.forEach { h ->
                        require(h.fullUnpackSize <= 40_000_000L) { "Слишком большая страница" }
                        putPage(h.fileName) { target -> target.outputStream().use { rar.extractFile(h,it) } }
                    }
                }
                magic[0] == 0x25.toByte() -> ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { pdf ->
                    require(pdf.pageCount <= 1000) { "В PDF более 1000 страниц" }
                    repeat(pdf.pageCount) { n -> pdf.openPage(n).use { p ->
                        val b = Bitmap.createBitmap(1400,(1400f*p.height/p.width).toInt(),Bitmap.Config.ARGB_8888)
                        android.graphics.Canvas(b).drawColor(android.graphics.Color.WHITE); p.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        putPage("${n.toString().padStart(5,'0')}.jpg") { f -> f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG,88,it) } }; b.recycle()
                    } }
                } }
                else -> error("Нужен CBZ, CBR (RAR4) или PDF без пароля")
            }
            require(files.isNotEmpty()) { "В архиве нет изображений. RAR5 и архивы с паролем не поддерживаются." }
            return Book(id,title,"Локальный архив",category,files.sortedBy { naturalKey(it.first) }.map { it.second },source,mode=when(category){"Манга"->"rtl";"Манхва","Маньхуа"->"vertical";else->"ltr"})
        } catch(e: Exception) { dir.deleteRecursively(); throw e }
    }
    suspend fun search(query: String): List<CatalogBook> = withContext(Dispatchers.IO) {
        if(query.isBlank()) return@withContext curatedCatalog
        val q = "(title:(\"${query.replace("\"", " ")}\")) AND mediatype:texts AND year:[1800 TO 1930]"
        val response = getText("https://archive.org/advancedsearch.php?q=${URLEncoder.encode(q,"UTF-8")}&output=json&rows=30&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=year")
        val docs = JSONObject(response).getJSONObject("response").getJSONArray("docs")
        List(docs.length()) { val j=docs.getJSONObject(it); CatalogBook(j.getString("identifier"),j.optString("title"),j.optString("creator","Internet Archive"),"Комиксы",j.optString("year")) }
    }
    suspend fun download(item: CatalogBook): Book = withContext(Dispatchers.IO) {
        val data = JSONObject(getText("https://archive.org/metadata/${item.id}")); val a = data.getJSONArray("files")
        val candidates = List(a.length()) { a.getJSONObject(it) }.filter { f -> f.optString("name").substringAfterLast('.').lowercase() in listOf("cbz","cbr","pdf") && f.optString("size").toLongOrNull()?.let { it in 1..200_000_000L } == true && !f.optBoolean("private") }
        val f = candidates.minByOrNull { when(it.getString("name").substringAfterLast('.')) {"cbz"->0;"pdf"->1;else->2} } ?: error("В источнике нет доступного CBZ, CBR или PDF")
        val name = f.getString("name"); val tmp = File(context.cacheDir,"download.${name.substringAfterLast('.')}")
        try {
            val conn = URL("https://archive.org/download/${item.id}/${URLEncoder.encode(name,"UTF-8").replace("+","%20")}").openConnection() as HttpURLConnection
            conn.connectTimeout=20000; conn.readTimeout=60000
            try { conn.inputStream.use { i -> tmp.outputStream().use { o -> copyBounded(i,o,200_000_000) } } } finally { conn.disconnect() }
            extract(tmp,item.title,item.category,"https://archive.org/details/${item.id}").copy(author=item.author)
        } finally { tmp.delete() }
    }
    suspend fun downloadUrl(url: String, category: String): Book = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "Используйте защищённую ссылку HTTPS" }
        val tmp=File(context.cacheDir,"direct_download")
        val conn=URL(url).openConnection() as HttpURLConnection; conn.connectTimeout=20000;conn.readTimeout=60000
        try { conn.inputStream.use { i -> tmp.outputStream().use { o -> copyBounded(i,o,200_000_000) } }; extract(tmp,Uri.parse(url).lastPathSegment?.substringBeforeLast('.') ?: "Онлайн-книга",category,url) } finally { conn.disconnect();tmp.delete() }
    }
    private fun getText(url: String): String {
        val conn=URL(url).openConnection() as HttpURLConnection;conn.connectTimeout=15000;conn.readTimeout=25000
        return try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }
    }
    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, limit: Int) {
        val buffer=ByteArray(8192);var total=0L
        while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=limit){"Файл превышает допустимый размер"};output.write(buffer,0,n)}
    }
}
