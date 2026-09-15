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
 * Кольцевой буфер скриншотов авточтения.
 *
 * Хранит до [MAX_ENTRIES] последних скринов:
 *  - In-memory: [_entries] (StateFlow) — для мгновенного обновления UI
 *  - On-disk: `screenshots.json` + JPEG-файлы в `screenshots/`
 *
 * JPEG-файлы именуются по id (timestamp), поэтому при лимите в 50 скринов
 * старые файлы автоматически удаляются при добавлении нового.
 *
 * Все методы safe: битый JSON → пустой список, диск-ошибка → logcat.
 */
object OcrScreenshotBuffer {

    private const val MAX_ENTRIES = 50
    private const val FILE_NAME = "screenshots.json"
    private const val DIR_NAME = "screenshots"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    private val _entries = MutableStateFlow<List<OcrScreenshotEntry>>(emptyList())
    val entries: StateFlow<List<OcrScreenshotEntry>> = _entries.asStateFlow()

    /** Последний добавленный скриншот — для индикатора в углу. */
    private val _lastEntry = MutableStateFlow<OcrScreenshotEntry?>(null)
    val lastEntry: StateFlow<OcrScreenshotEntry?> = _lastEntry.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var persistFile: File? = null
    private var screenshotsDir: File? = null
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val file = File(context.filesDir, FILE_NAME)
        persistFile = file
        val dir = File(context.filesDir, DIR_NAME).apply { runCatching { mkdirs() } }
        screenshotsDir = dir
        load()
    }

    // ---- Публичные методы ----

    /**
     * Добавить скриншот. Если буфер полон — самый старый запись
     * удаляется с диска и из списка.
     *
     * @return Созданная запись (уже с присвоенным id).
     */
    fun add(
        context: Context,
        chapterId: Long,
        pageIndex: Int,
        scrollFraction: Float,
        bitmap: android.graphics.Bitmap,
        regions: List<OcrRegion>,
        engineUsed: String,
        scanRegion: String = "viewport",
    ): OcrScreenshotEntry? {
        init(context)
        val dir = screenshotsDir ?: return null

        val id = System.currentTimeMillis()
        val file = File(dir, "$id.jpg")
        try {
            file.outputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "OcrScreenshotBuffer: failed to save JPEG" }
            return null
        }

        val serializableRegions = regions.map { SerializableOcrRegion.fromOcrRegion(it) }
        val summaryText = regions.joinToString(" ") { it.text.trim() }.trim()
        val entry = OcrScreenshotEntry(
            id = id,
            chapterId = chapterId,
            pageIndex = pageIndex,
            scrollFraction = scrollFraction,
            timestamp = id,
            imagePath = file.absolutePath,
            regions = serializableRegions,
            engineUsed = engineUsed,
            summaryText = summaryText,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            scanRegion = scanRegion,
        )

        val current = _entries.value.toMutableList()
        // Удаляем самую старую запись, если лимит достигнут
        while (current.size >= MAX_ENTRIES) {
            val oldest = current.removeFirst()
            runCatching { File(oldest.imagePath).delete() }
                .onFailure { logcat(LogPriority.WARN) { "Failed to delete old screenshot: ${oldest.imagePath}" } }
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

    /** Очистить буфер и удалить все JPEG-файлы. */
    fun clear(context: Context) {
        init(context)
        val dir = screenshotsDir ?: return
        _entries.value.forEach { entry ->
            runCatching { File(entry.imagePath).delete() }
        }
        _entries.value = emptyList()
        _lastEntry.value = null
        persist()
    }

    /** Очистить скриншоты одной главы. */
    fun clearChapter(context: Context, chapterId: Long) {
        init(context)
        val (keep, remove) = _entries.value.partition { it.chapterId != chapterId }
        remove.forEach { entry ->
            runCatching { File(entry.imagePath).delete() }
        }
        _entries.value = keep
        if (_lastEntry.value?.chapterId == chapterId) _lastEntry.value = null
        persist()
    }

    /** Текущее количество скринов в буфере. */
    fun size(): Int = _entries.value.size

    /** Общий размер JPEG-файлов на диске (в байтах). */
    fun diskSizeBytes(): Long {
        val dir = screenshotsDir ?: return 0L
        return dir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    // ---- Внутренние ----

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
        scope.launch {
            try {
                val text = file.readText()
                if (text.isBlank()) return@launch
                val loaded = json.decodeFromString<List<OcrScreenshotEntry>>(text)
                // Фильтруем записи с несуществующими JPEG-файлами
                val valid = loaded.filter { entry ->
                    val f = File(entry.imagePath)
                    if (f.exists()) true else {
                        logcat(LogPriority.VERBOSE) { "OcrScreenshotBuffer: missing file ${entry.imagePath}" }
                        false
                    }
                }
                _entries.value = valid
                _lastEntry.value = valid.lastOrNull()
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "OcrScreenshotBuffer: load failed" }
            }
        }
    }
}
