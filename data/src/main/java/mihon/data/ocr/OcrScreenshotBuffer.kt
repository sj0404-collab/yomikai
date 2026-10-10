package mihon.data.ocr

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    /** Публичная папка в «Pictures», куда публикуются скриншоты для Галереи. */
    private const val GALLERY_DIR_NAME = "Yomikai"
    private const val JPEG_MIME = "image/jpeg"

    /** Префикс имени файла в галерее: по нему кадр узнаётся среди прочих. */
    private const val GALLERY_NAME_PREFIX = "Yomikai"

    /** Ограничение длины DISPLAY_NAME: длинные имена обрезаются системой. */
    private const val MAX_GALLERY_NAME_LENGTH = 80

    /** Лимит записей (кольцевой буфер). Меняется через dev-панель. */
    @kotlin.jvm.Volatile
    var maxEntries: Int = DEFAULT_MAX_ENTRIES

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    private val _entries = MutableStateFlow<List<OcrScreenshotEntry>>(emptyList())
    val entries: StateFlow<List<OcrScreenshotEntry>> = _entries.asStateFlow()

    /** Последний добавленный скриншот — для индикатора в углу читалки. */
    private val _lastEntry = MutableStateFlow<OcrScreenshotEntry?>(null)
    val lastEntry: StateFlow<OcrScreenshotEntry?> = _lastEntry.asStateFlow()

    /**
     * Счётчик изменений буфера: вьюха страницы рисует пометки порядка чтения
     * на каждом кадре, а фильтровать список записей заново 60 раз в секунду
     * при прокрутке длинного вебтуна незачем. Кэш вьюхи инвалидируется этим
     * числом, а не подпиской на поток из анимации.
     */
    @Volatile
    var version: Int = 0
        private set

    // Одно поток-исполнитель: add() из авточтения и clear()/clearChapter()
    // из UI иначе писали бы один JSON параллельно и перемешивали байты.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var persistFile: File? = null
    private var imagesDir: File? = null
    private var initialized = false

    /** Контекст приложения: удаление копий из галереи идёт через ContentResolver. */
    private var appContext: Context? = null

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
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
    /**
     * Добавить запись в буфер.
     *
     * synchronized не лишний: писателей стало два — авточтение пишет кадр за
     * кадром, а кнопка «Скриншот сейчас» добавляет запись из отдельной
     * корутины. Без блокировки оба собирали список от одного и того же
     * состояния, и одна запись молча пропадала.
     *
     * [sourceWidth]/[sourceHeight]/[scanCrop] — геометрия того, какая часть
     * файла страницы попала в кадр: без неё нормализованные координаты
     * [regions] относятся к уменьшенному и обрезанному OCR-битмапу, и в книге
     * по ним нельзя нарисовать ничего. Оставленные значения по умолчанию означают
     * «геометрии нет»: запись читается как раньше, проекция для неё
     * не выполняется.
     */
    @Synchronized
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
        sourceWidth: Int = 0,
        sourceHeight: Int = 0,
        scanCrop: SerializableNormalizedRect = SerializableNormalizedRect(),
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
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            scanCrop = scanCrop,
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
        version++
        persist()
        return entry
    }
    /**
     * Опубликовать JPEG-кадр в общем хранилище: `Pictures/Yomikai`, то есть
     * ровно там, где его видят «Галерея», «Файлы» и любой файловый менеджер.
     *
     * Зачем это нужно рядом с [add]: картинки буфера лежат в приватном
     * `filesDir/screenshots_images/`, куда не смотрят ни MediaStore, ни
     * FileProvider, ни галерея телефона. Читатель нажимал «Скриншот сейчас» и
     * не находил файл НИГДЕ. Поэтому тот же JPEG дополнительно уходит в
     * публичное хранилище, а внутренняя копия остаётся для вкладки «Скриншоты»
     * (там кадр показывается вместе с оверлеем распознанных регионов).
     *
     * На Android 10+ (MediaStore) разрешение на запись не нужно вовсе, а при
     * targetSdk 36 писать в публичные папки через `File` уже нельзя. Ветка для
     * API < 29 нужна только для Android 8–9: там пишем файлом, но лишь если
     * WRITE_EXTERNAL_STORAGE реально выдан — с targetSdk 36 система может не
     * выдать его вовсе, и тогда возвращаем null, а не падаем.
     *
     * Ошибки никогда не пробрасываются: публикация — дополнение, из-за неё
     * скриншот не должен теряться (в logcat пишем причину).
     *
     * @return Uri опубликованного файла или null, если публикация не вышла.
     */
    fun publishToGallery(context: Context, jpeg: ByteArray, displayName: String): Uri? {
        val name = galleryFileName(displayName)
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                insertIntoMediaStore(context, jpeg, name)
            } else {
                writeToPublicPictures(context, jpeg, name)
            }
        }.getOrElse { e ->
            logcat(LogPriority.WARN, e) { "OcrScreenshotBuffer: gallery publish failed for $name" }
            null
        }
    }

    /**
     * Опубликовать в общую галерею JPEG уже сохранённой записи буфера — кнопка
     * «Сохранить в галерею» во вкладке «Скриншоты» идёт через неё.
     * Файл не перекодируется: в галерею уходит ровно тот кадр, который видит
     * читатель (вместе с оверлеем регионов он остаётся во внутренней копии).
     *
     * @return Uri опубликованного файла или null — у записи нет картинки, файл
     * не читается либо публикация не вышла.
     */
    fun publishEntryToGallery(context: Context, entry: OcrScreenshotEntry): Uri? {
        val path = entry.imagePath
        if (path == null) {
            logcat(LogPriority.WARN) { "OcrScreenshotBuffer: entry ${entry.id} has no image" }
            return null
        }
        val bytes = runCatching { File(path).readBytes() }.getOrElse { e ->
            logcat(LogPriority.WARN, e) { "OcrScreenshotBuffer: read image failed for $path" }
            return null
        }
        val uri = publishToGallery(context, bytes, galleryDisplayName(entry.pageIndex, entry.timestamp))
        // Запомнили опубликованную копию в ЗАПИСИ: иначе удаление скриншота
        // оставляло бы её в галерее висеть («удалил только в приложении»).
        if (uri != null) markGalleryUri(entry.id, uri.toString())
        return uri
    }

    /**
     * Запомнить, что запись опубликована в галерее под [uri]: удаление записи
     * потом уберёт и эту копию. Журнал сразу персистится — перезапуск не
     * должен «забывать» связь с галереей.
     */
    @Synchronized
    fun markGalleryUri(id: Long, uri: String) {
        val updated = _entries.value.map { if (it.id == id) it.copy(galleryUri = uri) else it }
        if (updated == _entries.value) return
        _entries.value = updated
        _lastEntry.value?.let { last -> if (last.id == id) _lastEntry.value = updated.firstOrNull { it.id == id } }
        version++
        persist()
    }

    /**
     * Удалить запись НАСОВСЕМ: внутренний JPEG + копию в галерее (если
     * публиковалась) + саму запись. Ровно то, чего не хватало: кнопка
     * удаления оставляла файл в `Pictures/Yomikai`.
     */
    @Synchronized
    fun remove(id: Long) {
        val entry = _entries.value.firstOrNull { it.id == id }
        if (entry != null) deleteEverywhere(entry)
        _entries.value = _entries.value.filter { it.id != id }
        if (_lastEntry.value?.id == id) _lastEntry.value = null
        version++
        persist()
    }

    /** То же для набора записей (мультивыбор на вкладке «Скриншоты»). */
    @Synchronized
    fun removeAll(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val doomed = _entries.value.filter { it.id in ids }
        doomed.forEach { deleteEverywhere(it) }
        _entries.value = _entries.value.filterNot { it.id in ids }
        if (_lastEntry.value?.id in ids) _lastEntry.value = null
        version++
        persist()
    }

    /**
     * Читаемое имя файла в галерее: `Yomikai_20260930_143512_p12`.
     *
     * Читатель ищет кадр глазами — в «Файлах», в «Галерее», в «Моих файлах» — и
     * ориентируется по имени. Папка из сотни безликих `image.jpg` ни о чём не
     * говорит, поэтому в имени есть приложение, дата, время и номер страницы.
     * Публикуется это же имя, только с расширением `.jpg`.
     */
    fun galleryDisplayName(pageIndex: Int, timestamp: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestamp))
        return "${GALLERY_NAME_PREFIX}_${stamp}_p${pageIndex + 1}"
    }

    /** Все скриншоты для конкретной главы (в порядке добавления). */
    fun forChapter(chapterId: Long): List<OcrScreenshotEntry> =
        _entries.value.filter { it.chapterId == chapterId }

    /** Все скриншоты для конкретной страницы. */
    fun forPage(chapterId: Long, pageIndex: Int): List<OcrScreenshotEntry> =
        _entries.value.filter { it.chapterId == chapterId && it.pageIndex == pageIndex }

    /** Очистить буфер. */
    fun clear() {
        _entries.value.forEach { deleteEverywhere(it) }
        _entries.value = emptyList()
        _lastEntry.value = null
        version++
        persist()
    }

    /** Очистить скриншоты одной главы. */
    fun clearChapter(chapterId: Long) {
        val (keep, remove) = _entries.value.partition { it.chapterId != chapterId }
        remove.forEach { deleteEverywhere(it) }
        _entries.value = keep
        if (_lastEntry.value?.chapterId == chapterId) _lastEntry.value = null
        version++
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

    /**
     * Вставка в MediaStore (Android 10+). Само разрешение на запись в
     * `Pictures` приложению не нужно — картинка сразу становится видна
     * Галерее. Если запись в поток не удалась, вставку откатываем: иначе в
     * `Pictures/Yomikai` остаётся файл нулевого размера, который галерея
     * показывает как «битую» картинку.
     */
    private fun insertIntoMediaStore(context: Context, jpeg: ByteArray, name: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, JPEG_MIME)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$GALLERY_DIR_NAME",
            )
            put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
        }
        val uri = resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values,
        ) ?: run {
            logcat(LogPriority.WARN) { "OcrScreenshotBuffer: MediaStore insert returned null for $name" }
            return null
        }
        return try {
            val output = resolver.openOutputStream(uri, "w")
            if (output == null) {
                runCatching { resolver.delete(uri, null, null) }
                logcat(LogPriority.WARN) { "OcrScreenshotBuffer: openOutputStream returned null for $name" }
                null
            } else {
                output.use { it.write(jpeg) }
                uri
            }
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /**
     * Фолбэк для Android 8–9: публичные «Pictures» ещё пишутся файлом.
     *
     * Тут MediaStore ещё не индексирует файл за нас, поэтому после записи
     * просим сканер перечитать каталог — иначе «Галерея» покажет файл только
     * после перезагрузки, а это ровно тот случай, когда читатель «не находит
     * файл НИГДЕ». На Android 10+ MediaStore индексирует вставку сам, там
     * сканер не нужен.
     */
    @Suppress("DEPRECATION")
    private fun writeToPublicPictures(context: Context, jpeg: ByteArray, name: String): Uri? {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            logcat(LogPriority.WARN) {
                "OcrScreenshotBuffer: WRITE_EXTERNAL_STORAGE not granted, skip gallery publish"
            }
            return null
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            GALLERY_DIR_NAME,
        )
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, name)
        file.outputStream().use { it.write(jpeg) }
        runCatching {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        }.onFailure { e ->
            logcat(LogPriority.WARN, e) { "OcrScreenshotBuffer: media scan failed for ${file.name}" }
        }
        return Uri.fromFile(file)
    }

    /**
     * Имя файла для галереи: MediaStore не любит слеши и прочие символы пути,
     * а расширение должно быть ровно `.jpg` — по нему галерея решает, что это
     * именно изображение, и рисует миниатюру.
     */
    private fun galleryFileName(displayName: String): String {
        val safe = displayName.substringBeforeLast('.')
            .map { ch -> if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '_' }
            .joinToString("")
            .take(MAX_GALLERY_NAME_LENGTH)
            .trim('_')
        return "${safe.ifBlank { "screenshot" }}.jpg"
    }

    private fun deleteImageFile(entry: OcrScreenshotEntry) {
        entry.imagePath?.let { path ->
            runCatching { File(path).delete() }.onFailure {
                logcat(LogPriority.WARN, it) { "OcrScreenshotBuffer: delete image failed" }
            }
        }
    }

    /**
     * Полное удаление записи: внутренний файл + копия в галерее.
     *
     * `content://` убираем через ContentResolver (MediaStore), `file://`
     * (Android 8–9) — файлом плюс перескан медиабазы, иначе галерея будет
     * показывать «битую» миниатюру до перезагрузки.
     */
    private fun deleteEverywhere(entry: OcrScreenshotEntry) {
        deleteImageFile(entry)
        val uriString = entry.galleryUri ?: return
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        val resolver = appContext?.contentResolver ?: return
        if (uri.scheme == "content") {
            runCatching { resolver.delete(uri, null, null) }
                .onFailure { logcat(LogPriority.WARN, it) { "OcrScreenshotBuffer: gallery delete failed" } }
        } else {
            runCatching {
                uri.path?.let { File(it).delete() }
                MediaScannerConnection.scanFile(appContext, arrayOf(uri.path), null, null)
            }.onFailure { logcat(LogPriority.WARN, it) { "OcrScreenshotBuffer: gallery file delete failed" } }
        }
    }

    private fun persist() {
        val file = persistFile ?: return
        val entries = _entries.value
        scope.launch {
            try {
                // Временный файл + rename: обрыв процесса не оставит
                // обрезанный JSON, а единственный поток-исполнитель не даст
                // двум записям перемешаться.
                val encoded = json.encodeToString(entries)
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(encoded)
                if (!tmp.renameTo(file)) {
                    file.writeText(encoded)
                    tmp.delete()
                }
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
            version++
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "OcrScreenshotBuffer: load failed" }
        }
    }
}
