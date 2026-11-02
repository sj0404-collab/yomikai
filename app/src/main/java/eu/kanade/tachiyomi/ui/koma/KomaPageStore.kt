package eu.kanade.tachiyomi.ui.koma

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.github.junrar.Archive
import eu.kanade.tachiyomi.data.ocr.OcrPageInput
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import java.util.zip.ZipFile

/**
 * Страницы книги читаются НАПРЯМУЮ из папки или архива — без копий на диск.
 *
 * Что было не так (жалоба «в оригинале yomikai занято почти 2 ГБ, хотя у меня
 * только локальная библиотека»): каждая глава и каждый импортированный архив
 * раскладывались постранично в `filesDir` (`koma-pages/…` и `library/<id>/…`).
 * Для 20 глав манхвы это гигабайты, причём копии — это ТЕ ЖЕ байты, что уже
 * лежат в архиве/папке. В cdisplay ex так не делают: он запоминает только
 * СПИСОК файлов в папке (индекс), а картинку читает при открытии страницы.
 *
 * Как теперь:
 *  • в памяти живёт только открытая книга: список страниц (OcrPageInput) и
 *    последние 3 декодированные страницы (LruCache);
 *  • на диске — только индекс: `filesDir/koma-index/<id>.json` с числом
 *    страниц и путём архива. Для главы это несколько сотен байт, а не сотни
 *    мегабайт;
 *  • архив открывается на каждую страницу заново (центральный каталог ZIP
 *    читается за миллисекунды) и закрывается сразу — файловых дескрипторов
 *    не течёт;
 *  • поток страницы отдаётся обёрткой [ZipEntryStream], которая закрывает
 *    архив вместе с потоком: иначе закрытие ZipFile рвало бы чтение.
 *
 * Виртуальный путь выглядит как `koma://<scope>/<id>/<index>`; он же лежит в
 * [Book.pages], поэтому экран, распознавание и озвучка работают без изменений.
 */
object KomaPageStore {

    const val SCHEME = "koma://"

    /** Сколько декодированных страниц держим в памяти (экран + распознавание). */
    private const val BITMAP_CACHE = 3

    fun isVirtual(path: String): Boolean = path.startsWith(SCHEME)

    fun pathOf(id: String, index: Int): String = "$SCHEME$id/$index"

    /** Ключ книги из виртуального пути: `koma://book/x/3` → `book/x`. */
    fun keyOf(path: String): String = path.removePrefix(SCHEME).substringBeforeLast('/')

    private fun indexOf(path: String): Int = path.substringAfterLast('/').toIntOrNull() ?: -1

    private class Container(
        val pages: List<OcrPageInput>,
        val close: () -> Unit,
    )

    private val containers = ConcurrentHashMap<String, Container>()

    private val bitmaps = object : android.util.LruCache<String, Bitmap>(BITMAP_CACHE) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    /** Регистрирует открытую книгу. Возвращает виртуальные пути страниц. */
    fun register(id: String, pages: List<OcrPageInput>, close: () -> Unit = {}): List<String> {
        containers[id]?.let { runCatching { it.close() } }
        containers[id] = Container(pages, close)
        return pages.indices.map { pathOf(id, it) }
    }

    fun unregister(id: String) {
        containers.remove(id)?.let { runCatching { it.close() } }
        bitmaps.evictAll()
    }

    /** Оставляет в памяти только перечисленные книги (остальные закрываем). */
    fun keepOnly(ids: Set<String>) {
        containers.keys.filter { it !in ids }.forEach(::unregister)
    }

    fun count(id: String): Int = containers[id]?.pages?.size ?: 0

    fun hasPages(id: String): Boolean = (containers[id]?.pages?.size ?: 0) > 0

    fun pageSize(path: String): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val stream = openStream(path) ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return bounds.outWidth to bounds.outHeight
    }

    /**
     * Поток страницы. Для папки это файл, для архива — запись внутри него.
     * Ничего не копируется: байты идут из исходника.
     */
    fun openStream(path: String): InputStream? {
        val input = inputOf(path) ?: return null
        return runCatching { runBlocking { input.openStream?.invoke() } }.getOrNull()
    }

    /** Байты страницы: нужны, когда декодеру нужен весь файл (полоса страницы). */
    fun openBytes(path: String): ByteArray? =
        openStream(path)?.use { stream ->
            runCatching {
                ByteArrayOutputStream().use { out ->
                    stream.copyTo(out, 1 shl 16)
                    out.toByteArray()
                }
            }.getOrNull()
        }

    fun bitmap(path: String): Bitmap? {
        bitmaps.get(path)?.takeIf { !it.isRecycled }?.let { return it }
        val decoded = runCatching { decode(path) }.getOrNull() ?: return null
        bitmaps.put(path, decoded)
        return decoded
    }

    /**
     * Обложка: уменьшенная первая страница. В кэш страниц не кладётся — иначе
     * обложки книг вытесняли бы из памяти ту страницу, которую сейчас читают.
     */
    fun cover(path: String, maxSide: Int = 480): Bitmap? {
        if (!isVirtual(path)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(path)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        return openStream(path)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

    private fun decode(path: String): Bitmap? {
        val input = inputOf(path) ?: return null
        // Источники, у которых нет потока (PDF и подобные), умеют отдать
        // готовый растр — это дешевле, чем уговаривать их на байты.
        if (input.openStream == null) {
            return runCatching { runBlocking { input.openBitmap() } }.getOrNull()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(path)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (
            bounds.outWidth.toLong() * bounds.outHeight / (sample.toLong() * sample) > 16_000_000L ||
            bounds.outWidth / sample > 2600
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        if (bounds.outWidth / sample > 1400) options.inPreferredConfig = Bitmap.Config.RGB_565
        return openStream(path)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun inputOf(path: String): OcrPageInput? {
        if (!isVirtual(path)) return null
        val key = keyOf(path)
        val index = indexOf(path)
        if (key.isEmpty() || index < 0) return null
        val container = containers[key] ?: return null
        return container.pages.getOrNull(index)
    }

    // ── Индекс на диске: «кэшируется только папка» ──────────────────────────

    fun indexDir(context: Context): File = File(context.filesDir, "koma-index")

    private fun indexFile(context: Context, id: String): File =
        File(indexDir(context), id.replace(Regex("[^\\w.-]"), "_") + ".json")

    fun saveIndex(
        context: Context,
        id: String,
        count: Int,
        file: String? = null,
        kind: String = KIND_CHAPTER,
    ) {
        runCatching {
            indexDir(context).mkdirs()
            val json = JSONObject()
                .put("v", 2)
                .put("count", count)
                .put("kind", kind)
                .put("file", file ?: "")
                .put("updated", System.currentTimeMillis())
            indexFile(context, id).writeText(json.toString())
        }
    }

    /**
     * Открывает книгу, которую в этой сессии ещё не открывали (после перезапуска
     * приложения). Строится она из ИСХОДНОГО файла по индексу — распаковки нет.
     *
     * Для глав источник перечитывается загрузчиком (там нужны база и источник),
     * поэтому здесь возвращается false: экран покажет понятную ошибку, а не
     * упадёт.
     */
    fun ensure(context: Context, id: String): Boolean {
        if (hasPages(id)) return true
        val index = readIndex(context, id) ?: return false
        val kind = index.optString("kind", KIND_CHAPTER)
        val file = index.optString("file").takeIf { it.isNotBlank() } ?: return false
        val source = File(context.filesDir, file)
        if (!source.isFile) return false
        return when (kind) {
            KIND_ARCHIVE -> {
                val names = archiveNames(source)
                if (names.isEmpty()) return false
                register(id, archivePages(source, names))
                true
            }
            KIND_PDF -> registerPdf(id, source)
            else -> false
        }
    }

    /** PDF: страница рендерится в момент показа, копий нет. */
    fun registerPdf(id: String, file: File): Boolean {
        val count = runCatching {
            android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                .use { fd -> android.graphics.pdf.PdfRenderer(fd).use { it.pageCount } }
        }.getOrNull() ?: return false
        if (count <= 0) return false
        val pages = (0 until count).map { index ->
            OcrPageInput(
                pageIndex = index,
                openBitmap = { renderPdfPage(file, index) },
                openBitmapRegion = { rect ->
                    val full = renderPdfPage(file, index)
                    if (full == null) {
                        null
                    } else {
                        runCatching {
                            val left = rect.left.coerceIn(0, full.width - 1)
                            val top = rect.top.coerceIn(0, full.height - 1)
                            val right = rect.right.coerceIn(left + 1, full.width)
                            val bottom = rect.bottom.coerceIn(top + 1, full.height)
                            Bitmap.createBitmap(full, left, top, right - left, bottom - top)
                        }.getOrNull()
                    }
                },
                openStream = null,
            )
        }
        register(id, pages)
        return true
    }

    private fun renderPdfPage(file: File, index: Int): Bitmap? = runCatching {
        android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            .use { fd ->
                android.graphics.pdf.PdfRenderer(fd).use { pdf ->
                    pdf.openPage(index).use { page ->
                        val width = 1400
                        val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        android.graphics.Canvas(bitmap).drawColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
    }.getOrNull()

    const val KIND_CHAPTER = "chapter"
    const val KIND_ARCHIVE = "archive"
    const val KIND_PDF = "pdf"

    fun readIndex(context: Context, id: String): JSONObject? =
        runCatching { JSONObject(indexFile(context, id).readText()) }.getOrNull()

    fun indexedCount(context: Context, id: String): Int =
        readIndex(context, id)?.optInt("count", 0) ?: 0

    /** Сколько занимает индекс (это килобайты — и должно оставаться килобайтами). */
    fun indexBytes(context: Context): Long =
        indexDir(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clearIndex(context: Context) {
        runCatching { indexDir(context).deleteRecursively() }
    }

    /**
     * Убирает старые постраничные копии (KomaChapterCache и распакованные
     * архивы библиотеки). Возвращает освобождённые байты.
     *
     * Это те самые гигабайты: копии страниц — не данные человека, а кэш,
     * который читается заново из исходной папки или архива.
     */
    fun cleanupLegacy(context: Context): Long {
        var freed = 0L
        for (dir in legacyDirs(context)) {
            freed += sizeOf(dir)
            runCatching { dir.deleteRecursively() }
        }
        return freed
    }

    /** Сколько занимают те же старые копии — для настроек («освободит …»). */
    fun legacyBytes(context: Context): Long = legacyDirs(context).sumOf { sizeOf(it) }

    /**
     * Старые постраничные копии: `filesDir/koma-pages/` (главы) и распакованные
     * папки внутри `filesDir/library/`. Папку библиотеки считаем копией только
     * тогда, когда рядом лежит исходный архив: если архива нет, папка — это
     * единственная копия книги, и её удалять нельзя.
     */
    private fun legacyDirs(context: Context): List<File> {
        val result = ArrayList<File>(2)
        val legacyChapters = File(context.filesDir, "koma-pages")
        if (legacyChapters.exists()) result += legacyChapters
        val library = File(context.filesDir, "library")
        library.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            if (File(library, dir.name + ".cbz").exists() ||
                File(library, dir.name + ".cbr").exists() ||
                File(library, dir.name + ".pdf").exists()
            ) {
                result += dir
            }
        }
        return result
    }

    fun sizeOf(file: File): Long =
        if (file.isFile) file.length() else file.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ── Архивы: страницы читаются из архива, а не из копий ──────────────────

    /** Записи картинок в архиве в порядке чтения. */
    fun archiveNames(file: File): List<String> = runCatching {
        when {
            isZip(file) -> ZipFile(file).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && isImage(it.name) }
                    .map { it.name }
                    .toList()
            }
            isRar(file) -> Archive(file).use { rar ->
                rar.fileHeaders.filter { !it.isDirectory && isImage(it.fileName) }
                    .map { it.fileName }
            }
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    /** Страницы архива: байты читаются при открытии страницы. */
    fun archivePages(file: File, names: List<String>): List<OcrPageInput> =
        names.mapIndexed { index, name ->
            OcrPageInput(
                pageIndex = index,
                openBitmap = { openEntryStream(file, name)?.use { BitmapFactory.decodeStream(it) } },
                openBitmapRegion = { rect ->
                    val bytes = readEntry(file, name)
                    if (bytes == null) {
                        null
                    } else {
                        android.graphics.BitmapRegionDecoder.newInstance(bytes, 0, bytes.size).use { decoder ->
                            runCatching {
                                val left = rect.left.coerceIn(0, decoder.width - 1)
                                val top = rect.top.coerceIn(0, decoder.height - 1)
                                val right = rect.right.coerceIn(left + 1, decoder.width)
                                val bottom = rect.bottom.coerceIn(top + 1, decoder.height)
                                decoder.decodeRegion(android.graphics.Rect(left, top, right, bottom), null)
                            }.getOrNull()
                        }
                    }
                },
                openStream = { openEntryStream(file, name) },
            )
        }

    /** Поток записи архива: закрытие потока закрывает и архив. */
    private fun openEntryStream(file: File, name: String): InputStream? = runCatching<InputStream?> {
        if (isZip(file)) {
            return@runCatching openZipEntry(file, name)
        }
        if (isRar(file)) {
            return@runCatching openRarEntry(file, name)
        }
        null
    }.getOrNull()

    private fun openZipEntry(file: File, name: String): InputStream? {
        val zip = ZipFile(file)
        val entry = zip.getEntry(name)
        if (entry == null) {
            zip.close()
            return null
        }
        return ZipEntryStream(zip, zip.getInputStream(entry))
    }

    /** RAR4: junrar не умеет отдавать поток записи, поэтому читаем в память. */
    private fun openRarEntry(file: File, name: String): InputStream? {
        val rar = Archive(file)
        val header = rar.fileHeaders.firstOrNull { it.fileName == name }
        if (header == null) {
            rar.close()
            return null
        }
        return try {
            val out = ByteArrayOutputStream()
            rar.extractFile(header, out)
            out.toByteArray().inputStream()
        } finally {
            rar.close()
        }
    }

    private fun readEntry(file: File, name: String): ByteArray? =
        openEntryStream(file, name)?.use { it.readBytes() }

    private fun isZip(file: File): Boolean {
        val magic = magicOf(file, 2)
        return magic.size >= 2 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()
    }

    private fun isRar(file: File): Boolean {
        val magic = magicOf(file, 2)
        return magic.size >= 2 && magic[0] == 0x52.toByte() && magic[1] == 0x61.toByte()
    }

    private fun magicOf(file: File, n: Int): ByteArray = runCatching {
        file.inputStream().use { input -> ByteArray(n).also { input.read(it) } }
    }.getOrDefault(ByteArray(0))

    fun isImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in
            setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    /** Поток записи ZIP, который закрывает архив вместе с собой. */
    private class ZipEntryStream(
        private val zip: ZipFile,
        stream: InputStream,
    ) : FilterInputStream(stream) {
        override fun close() {
            runCatching { super.close() }
            runCatching { zip.close() }
        }
    }

    /** Через сколько миллисекунд страница считается «зависшей» (для экрана). */
    const val LOAD_HINT_MS = 4_000L
}
