package mihon.data.ocr

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.domain.ocr.model.OcrRegion
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Кольцевой буфер скриншотов авточтения — текст регионов (~1-5 KB) плюс
 * необязательный JPEG-кадр каждого скриншота.
 *
 * Хранит до [MAX_ENTRIES] последних записей в JSON-файле. Если запись создана
 * с JPEG-кадром ([OcrScreenshotEntry.add]), картинка пишется в каталог
 * `filesDir/screenshots_images/<id>.jpg`, а в записи сохраняется её путь —
 * галерея показывает реальное изображение с оверлеем регионов.
 *
 * При превышении лимита самая старая запись и её файл удаляются.
 */
object OcrScreenshotBuffer {

    private const val DEFAULT_MAX_ENTRIES = 50
    private const val FILE_NAME = "screenshots.json"
    private const val IMAGE_DIR_NAME = "screenshots_images"

    /** Лимит записей (кольцевой буфер). Меняется через dev-панель. */
    @kotlin.jvm.Volatile
    var maxEntries: Int = DEFAULT_MAX_ENTRIES

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    private val _entries = MutableStateFlow<List<OcrScreenshotEntry>>(emptyList())
    val entries: StateFlow<List<OcrScreenshotEntry>> = _entries.asStateFlow()

    /** Последний добавленный скриншот — для индикатора в углу читалки. */
    private val _lastEntry = MutableStateFlow<OcrScreenshotEntry?>(null)
    val lastEntry: StateFlow<OcrScreenshotEntry?> = _lastEntry.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var persistFile: File? = null
    private var imagesDir: File? = null
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        persistFile = File(context.filesDir, FILE_NAME)
        imagesDir = File(context.filesDir, IMAGE_DIR_NAME).apply { mkdirs() }
        load()
    }

    // ---- Публичные методы ----

    /**
     * Добавить запись скриншота (текст + регионы ~1-5 KB, плюс необязательный
     * JPEG-кадр [imageJpeg], который пишется файлом и показывается в галерее).
     * Если буфер полон — самая старая запись (и её файл) удаляется.
     *
     * @return Созданная запись.
     */
    fun add(
        chapterId: Long,
        pageIndex: Int,
        scrollFraction: Float,
        regions: List<OcrRegion>,
        engineUsed: String,
        imageWidth: Int = 0,
        imageHeight: Int = 0,
        imageJpeg: ByteArray? = null,
        scanRegion: String = "viewport",
    ): OcrScreenshotEntry {
        val id = System.currentTimeMillis()
        val serializableRegions = regions.map { SerializableOcrRegion.fromOcrRegion(it) }
        val summaryText = regions.joinToString(" ") { it.text.trim() }.trim()
        val imagePath = imageJpeg?.let { jpeg ->
            imagesDir?.let { dir ->
                File(dir, "$id.jpg").apply { writeBytes(jpeg) }.absolutePath
            }
        }
        val entry = OcrScreenshotEntry(
            id = id,
            chapterId = chapterId,
            pageIndex = pageIndex,
            scrollFraction = scrollFraction,
            timestamp = id,
            regions = serializableRegions,
            engineUsed = engineUsed,
            summaryText = summaryText,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            imagePath = imagePath,
            scanRegion = scanRegion,
        )

        val current = _entries.value.toMutableList()
        val limit = maxEntries.coerceAtLeast(1)
        if (current.size >= limit) {
            val evicted = current.removeFirst()
            deleteImageFile(evicted)
        }
        current.add(entry)
        _entries.value = current
        _lastEntry.value = entry
        persist()
        return entry
    }

    /** Все скриншоты для конкретной главы (в порядке добавления). */
    fun forChapter(chapterId: Long): List<OcrScreenshotEntry> =
        _entries.value.filter { it.chapterId == chapterId }

    /** Все скриншоты для конкретной страницы. */
    fun forPage(chapterId: Long, pageIndex: Int): List<OcrScreenshotEntry> =
        _entries.value.filter { it.chapterId == chapterId && it.pageIndex == pageIndex }

    /** Очистить буфер. */
    fun clear() {
        _entries.value.forEach { deleteImageFile(it) }
        _entries.value = emptyList()
        _lastEntry.value = null
        persist()
    }

    /** Очистить скриншоты одной главы. */
    fun clearChapter(chapterId: Long) {
        val (keep, remove) = _entries.value.partition { it.chapterId != chapterId }
        remove.forEach { deleteImageFile(it) }
        _entries.value = keep
        if (_lastEntry.value?.chapterId == chapterId) _lastEntry.value = null
        persist()
    }

    /** Текущее количество записей в буфере. */
    fun size(): Int = _entries.value.size

    /** Размер файлов скриншотов (JSON + JPEG-кадры) в байтах. */
    fun diskSizeBytes(): Long {
        val jsonSize = persistFile?.length() ?: 0L
        val imagesSize = _entries.value.sumOf { it.imagePath?.let { p -> runCatching { File(p).length() }.getOrDefault(0L) } ?: 0L }
        return jsonSize + imagesSize
    }

    // ---- Внутренние ----

    private fun deleteImageFile(entry: OcrScreenshotEntry) {
        entry.imagePath?.let { path ->
            runCatching { File(path).delete() }.onFailure {
                logcat(LogPriority.WARN, it) { "OcrScreenshotBuffer: delete image failed" }
            }
        }
    }

    private fun persist() {
        val file = persistFile ?: return
        val entries = _entries.value
        scope.launch {
            try {
                file.writeText(json.encodeToString(entries))
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "OcrScreenshotBuffer: persist failed" }
            }
        }
    }

    private fun load() {
        val file = persistFile ?: return
        if (!file.exists()) return
        try {
            val text = file.readText()
            if (text.isBlank()) return
            val loaded = json.decodeFromString<List<OcrScreenshotEntry>>(text)
            _entries.value = loaded
            _lastEntry.value = loaded.lastOrNull()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "OcrScreenshotBuffer: load failed" }
        }
    }
}
