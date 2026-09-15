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
 * Кольцевой буфер скриншотов авточтения — лёгкий (только текст, ~1-5 KB на запись).
 *
 * Хранит до [MAX_ENTRIES] последних записей в JSON-файле.
 * Физических изображений НЕТ — галерея отрисовывает текст по координатам
 * из [OcrScreenshotEntry.regions], исходное изображение берётся из кэша
 * загруженных страниц (coil/subsampling).
 *
 * При превышении лимита самая старая запись удаляется.
 */
object OcrScreenshotBuffer {

    private const val MAX_ENTRIES = 50
    private const val FILE_NAME = "screenshots.json"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    private val _entries = MutableStateFlow<List<OcrScreenshotEntry>>(emptyList())
    val entries: StateFlow<List<OcrScreenshotEntry>> = _entries.asStateFlow()

    /** Последний добавленный скриншот — для индикатора в углу читалки. */
    private val _lastEntry = MutableStateFlow<OcrScreenshotEntry?>(null)
    val lastEntry: StateFlow<OcrScreenshotEntry?> = _lastEntry.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var persistFile: File? = null
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        persistFile = File(context.filesDir, FILE_NAME)
        load()
    }

    // ---- Публичные методы ----

    /**
     * Добавить запись скриншота (только текст + регионы, ~1-5 KB).
     * Если буфер полон — самая старая запись удаляется.
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
        scanRegion: String = "viewport",
    ): OcrScreenshotEntry {
        val id = System.currentTimeMillis()
        val serializableRegions = regions.map { SerializableOcrRegion.fromOcrRegion(it) }
        val summaryText = regions.joinToString(" ") { it.text.trim() }.trim()
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
            scanRegion = scanRegion,
        )

        val current = _entries.value.toMutableList()
        if (current.size >= MAX_ENTRIES) {
            current.removeFirst()
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
        _entries.value = emptyList()
        _lastEntry.value = null
        persist()
    }

    /** Очистить скриншоты одной главы. */
    fun clearChapter(chapterId: Long) {
        val (keep, remove) = _entries.value.partition { it.chapterId != chapterId }
        _entries.value = keep
        if (_lastEntry.value?.chapterId == chapterId) _lastEntry.value = null
        persist()
    }

    /** Текущее количество записей в буфере. */
    fun size(): Int = _entries.value.size

    /** Примерный размер JSON-файла на диске (в байтах). */
    fun diskSizeBytes(): Long = persistFile?.length() ?: 0L

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
