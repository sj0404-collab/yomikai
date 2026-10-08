package eu.kanade.tachiyomi.data.books

import android.content.Context
import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import com.hippo.unifile.UniFile
import java.io.File
import java.io.FileOutputStream
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Хранилище локальных электронных книг.
 *
 * Книги лежат в подкаталоге `books/` основного хранилища приложения.
 * Поддерживаются: PDF, EPUB, FB2, DOCX, HTML, TXT и другие текстовые форматы.
 *
 * Прогресс чтения хранится в SharedPreferences, метаданные и обложки — в кэше.
 */
object BooksStore {

    data class Snapshot(
        val chapter: Int = 0,
        val sentence: Int = 0,
        val percent: Int = 0,
    )

    /**
     * Результат добавления книги.
     */
    sealed class ImportResult {
        data class Success(val book: UniFile) : ImportResult()
        data class Failure(val reason: String) : ImportResult()
    }

    private const val PROGRESS_PREFS = "yomikai_books_progress"
    private const val BOOKMARKS_PREFS = "book_bookmarks"
    private const val HISTORY_PREFS = "book_history"
    private const val LASTREAD_PREFS = "book_lastread"
    private const val READTIME_PREFS = "book_readtime"
    private const val ADDEDAT_PREFS = "book_addedat"
    private const val METADATA_PREFS = "yomikai_books_metadata"
    private const val COVER_DIR = "book_covers"

    private fun storageManager(): tachiyomi.domain.storage.service.StorageManager =
        Injekt.get()

    fun booksDirectory(context: Context): UniFile? {
        val primary = storageManager().getBooksDirectory()
        if (primary != null && primary.exists()) return primary
        val folder = File(context.filesDir, "books")
        if (!folder.exists()) folder.mkdirs()
        return UniFile.fromFile(folder)
    }

    /** Список книг (исключая скрытые файлы), отсортированные по имени. */
    fun listBooks(context: Context): List<UniFile> {
        return booksDirectory(context)?.listFiles()
            ?.filter { !it.name.orEmpty().startsWith('.') }
            ?.sortedBy { it.name.orEmpty().lowercase() }
            .orEmpty()
            .toList()
    }

    /**
     * Копирует выбранный пользователем файл (SAF URI) в каталог книг.
     */
    fun importBook(context: Context, uri: android.net.Uri): ImportResult {
        val dir = booksDirectory(context) ?: return ImportResult.Failure("Нет каталога для книг")
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            return ImportResult.Failure("Не удалось открыть файл: ${e.message}")
        } ?: return ImportResult.Failure("Файл открыт, но пуст или недоступен")

        return input.use { stream ->
            try {
                val name = displayName(context, uri)
                    ?.takeIf { it.isNotBlank() }
                    ?: "book_${System.currentTimeMillis()}"
                val safeName = name.replace(Regex("[/\\\\:<>|?*\"]"), "_")

                var candidate = safeName
                var attempt = 0
                while (dir.findFile(candidate)?.exists() == true) {
                    attempt++
                    val dot = safeName.lastIndexOf('.')
                    candidate = if (dot > 0) {
                        safeName.substring(0, dot) + "($attempt)" + safeName.substring(dot)
                    } else {
                        "$safeName($attempt)"
                    }
                }
                val target = dir.createFile(candidate)
                    ?: return@use ImportResult.Failure(
                        "Не удалось создать файл «$candidate» в хранилище",
                    )
                val out = try {
                    target.openOutputStream()
                } catch (e: Exception) {
                    target.delete()
                    return@use ImportResult.Failure("Нет прав на запись: ${e.message}")
                } ?: run {
                    target.delete()
                    return@use ImportResult.Failure("Не удалось открыть файл для записи")
                }
                out.use { targetOut ->
                    stream.copyTo(targetOut, 64 * 1024)
                }
                val finalFile = dir.findFile(candidate)
                if (finalFile?.exists() == true) {
                    context.getSharedPreferences(ADDEDAT_PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putLong(finalFile.uri.toString(), System.currentTimeMillis())
                        .apply()
                    ImportResult.Success(finalFile)
                } else {
                    ImportResult.Failure("Файл не появился после добавления — проверьте хранилище")
                }
            } catch (e: Exception) {
                ImportResult.Failure("Ошибка добавления: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun deleteBook(book: UniFile): Boolean {
        return book.delete()
    }

    /** Добавляет книгу из готового массива байт (например, скачанной по URL). */
    fun importBytes(context: Context, fileName: String, content: ByteArray): ImportResult {
        val dir = booksDirectory(context) ?: return ImportResult.Failure("Нет каталога для книг")
        try {
            val safeName = fileName
                .substringAfterLast('/')
                .replace(Regex("[/\\\\:<>|?*\"]"), "_")
                .ifBlank { "book_${System.currentTimeMillis()}" }
            var candidate = safeName
            var attempt = 0
            while (dir.findFile(candidate)?.exists() == true) {
                attempt++
                val dot = candidate.lastIndexOf('.')
                candidate = if (dot > 0) {
                    candidate.substring(0, dot) + "($attempt)" + candidate.substring(dot)
                } else {
                    "$candidate($attempt)"
                }
            }
            val target = dir.createFile(candidate)
                ?: return ImportResult.Failure("Не удалось создать файл «$candidate»")
            val out = target.openOutputStream()
                ?: return ImportResult.Failure("Не удалось открыть файл для записи")
            out.use { it.write(content) }
            context.getSharedPreferences(ADDEDAT_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(target.uri.toString(), System.currentTimeMillis())
                .apply()
            return ImportResult.Success(target)
        } catch (e: Exception) {
            return ImportResult.Failure("Ошибка добавления: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---------- Прогресс чтения ----------

    fun save(context: Context, book: UniFile, snapshot: Snapshot) {
        context.getSharedPreferences(PROGRESS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(book.uri.toString(), "${snapshot.chapter}|${snapshot.sentence}|${snapshot.percent}")
            .apply()
    }

    fun clear(context: Context, book: UniFile) {
        val key = book.uri.toString()
        context.getSharedPreferences(PROGRESS_PREFS, Context.MODE_PRIVATE)
            .edit().remove(key).apply()
        context.getSharedPreferences(BOOKMARKS_PREFS, Context.MODE_PRIVATE)
            .edit().remove(key).apply()
        context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
            .edit().remove(key).apply()
        context.getSharedPreferences(LASTREAD_PREFS, Context.MODE_PRIVATE)
            .edit().remove(key).apply()
        context.getSharedPreferences(READTIME_PREFS, Context.MODE_PRIVATE)
            .edit().remove(key).apply()
        context.getSharedPreferences(ADDEDAT_PREFS, Context.MODE_PRIVATE)
            .edit().remove(key).apply()
        // Также очищаем кэш метаданных и обложки
        clearMetadata(context, book)
        clearCover(context, book)
    }

    fun load(context: Context, book: UniFile): Snapshot {
        val raw = context.getSharedPreferences(PROGRESS_PREFS, Context.MODE_PRIVATE)
            .getString(book.uri.toString(), null)
            ?: return Snapshot()
        val parts = raw.split('|')
        if (parts.size != 3) return Snapshot()
        return Snapshot(
            chapter = parts[0].toIntOrNull()?.coerceAtLeast(0) ?: 0,
            sentence = parts[1].toIntOrNull()?.coerceAtLeast(0) ?: 0,
            percent = parts[2].toIntOrNull()?.coerceIn(0, 100) ?: 0,
        )
    }

    // ---------- «Продолжить чтение»: последнее открытие, время чтения ----------

    /** Книга была открыта в читалке (для сортировки «Недавние» и карточки). */
    fun markOpened(context: Context, book: UniFile) {
        context.getSharedPreferences(LASTREAD_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(book.uri.toString(), System.currentTimeMillis())
            .apply()
    }

    fun lastOpened(context: Context, book: UniFile): Long =
        context.getSharedPreferences(LASTREAD_PREFS, Context.MODE_PRIVATE)
            .getLong(book.uri.toString(), 0L)

    /** Накопление времени чтения голосом (секунды суммируются). */
    fun addReadSeconds(context: Context, book: UniFile, seconds: Int) {
        val prefs = context.getSharedPreferences(READTIME_PREFS, Context.MODE_PRIVATE)
        val key = book.uri.toString()
        prefs.edit()
            .putLong(key, (prefs.getLong(key, 0L) + seconds).coerceAtLeast(0))
            .apply()
    }

    fun readSeconds(context: Context, book: UniFile): Long =
        context.getSharedPreferences(READTIME_PREFS, Context.MODE_PRIVATE)
            .getLong(book.uri.toString(), 0L)

    /** Когда книга попала в библиотеку (для сортировки «Новые»). */
    fun addedAt(context: Context, book: UniFile): Long {
        val prefs = context.getSharedPreferences(ADDEDAT_PREFS, Context.MODE_PRIVATE)
        val key = book.uri.toString()
        val saved = prefs.getLong(key, 0L)
        if (saved > 0) return saved
        val fallback = runCatching { book.lastModified() }.getOrDefault(0L)
        if (fallback > 0) {
            prefs.edit().putLong(key, fallback).apply()
        }
        return fallback
    }

    // ---------- Закладки и история чтения ----------

    /** Закладка: место в книге, к которому читатель хочет вернуться. */
    data class Bookmark(
        val chapter: Int,
        val sentence: Int,
        val label: String,
        val timestamp: Long,
    )

    /**
     * Хранение — SharedPreferences, ключ = URI книги.
     * Строки разделяются «^^^», поля внутри — «|».
     */
    fun loadBookmarks(context: Context, book: UniFile): List<Bookmark> {
        val raw = context.getSharedPreferences(BOOKMARKS_PREFS, Context.MODE_PRIVATE)
            .getString(book.uri.toString(), null) ?: return emptyList()
        return raw.split("^^^").mapNotNull { row ->
            val parts = row.split('|')
            if (parts.size < 4) return@mapNotNull null
            Bookmark(
                chapter = parts[0].toIntOrNull() ?: return@mapNotNull null,
                sentence = parts[1].toIntOrNull() ?: 0,
                label = parts[2],
                timestamp = parts[3].toLongOrNull() ?: 0L,
            )
        }.sortedByDescending { it.timestamp }
    }

    fun addBookmark(context: Context, book: UniFile, bookmark: Bookmark) {
        val list = loadBookmarks(context, book).filterNot {
            it.chapter == bookmark.chapter && it.sentence == bookmark.sentence
        } + bookmark
        saveBookmarks(context, book, list)
    }

    fun removeBookmark(context: Context, book: UniFile, bookmark: Bookmark) {
        saveBookmarks(
            context, book,
            loadBookmarks(context, book).filterNot { it.timestamp == bookmark.timestamp },
        )
    }

    private fun saveBookmarks(context: Context, book: UniFile, list: List<Bookmark>) {
        val value = list.joinToString("^^^") {
            "${it.chapter}|${it.sentence}|${it.label.replace("^^^", " ").replace("|", " ")}|${it.timestamp}"
        }
        context.getSharedPreferences(BOOKMARKS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(book.uri.toString(), value)
            .apply()
    }

    /**
     * Контрольная точка истории: куда читал(а) и когда. Храним последние 30
     * позиций — список «вернуться назад по следам чтения».
     */
    data class HistoryPoint(
        val timestamp: Long,
        val chapter: Int,
        val sentence: Int,
        val chapterTitle: String,
    )

    fun loadHistory(context: Context, book: UniFile): List<HistoryPoint> {
        val raw = context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
            .getString(book.uri.toString(), null) ?: return emptyList()
        return raw.split("^^^").mapNotNull { row ->
            val parts = row.split('|')
            if (parts.size < 4) return@mapNotNull null
            HistoryPoint(
                timestamp = parts[0].toLongOrNull() ?: 0L,
                chapter = parts[1].toIntOrNull() ?: return@mapNotNull null,
                sentence = parts[2].toIntOrNull() ?: 0,
                chapterTitle = parts[3],
            )
        }
    }

    /** Кладёт точку истории; подряд идущие дубли той же главы заменяются. */
    fun pushHistory(context: Context, book: UniFile, point: HistoryPoint) {
        val list = loadHistory(context, book).toMutableList()
        val last = list.lastOrNull()
        if (last != null &&
            last.chapter == point.chapter &&
            last.sentence == point.sentence
        ) {
            return
        }
        list += point
        while (list.size > 30) list.removeAt(0)
        val value = list.joinToString("^^^") {
            "${it.timestamp}|${it.chapter}|${it.sentence}|" +
                it.chapterTitle.replace("^^^", " ").replace("|", " ")
        }
        context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(book.uri.toString(), value)
            .apply()
    }

    // ---------- Метаданные ----------

    /**
     * Сохраняет метаданные книги в SharedPreferences.
     * Ключ = URI книги, значение = JSON строка "title|author|description|language|publisher|year".
     */
    fun saveMetadata(context: Context, book: UniFile, metadata: BookParser.BookMetadata) {
        val key = book.uri.toString()
        val value = listOf(
            metadata.title,
            metadata.author.orEmpty(),
            metadata.description.orEmpty(),
            metadata.language.orEmpty(),
            metadata.publisher.orEmpty(),
            metadata.year?.toString().orEmpty(),
            metadata.genre?.joinToString(",").orEmpty(),
        ).joinToString("|||")
        context.getSharedPreferences(METADATA_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key, value)
            .apply()
    }

    /**
     * Загружает сохранённые метаданные книги. Если нет — извлекает из файла.
     */
    fun loadMetadata(context: Context, book: UniFile): BookParser.BookMetadata {
        val key = book.uri.toString()
        val raw = context.getSharedPreferences(METADATA_PREFS, Context.MODE_PRIVATE)
            .getString(key, null)
        if (raw != null) {
            val parts = raw.split("|||")
            if (parts.size >= 7) {
                return BookParser.BookMetadata(
                    title = parts[0].ifBlank { book.name.orEmpty().substringBeforeLast('.') },
                    author = parts[1].ifBlank { null },
                    description = parts[2].ifBlank { null },
                    language = parts[3].ifBlank { null },
                    publisher = parts[4].ifBlank { null },
                    year = parts[5].toIntOrNull(),
                    genre = parts[6].ifBlank { null }?.split(","),
                )
            }
        }
        // Извлекаем из файла и кэшируем
        return try {
            val metadata = BookParser.extractMetadata(book)
            saveMetadata(context, book, metadata)
            metadata
        } catch (e: Exception) {
            BookParser.BookMetadata(title = book.name.orEmpty().substringBeforeLast('.'))
        }
    }

    private fun clearMetadata(context: Context, book: UniFile) {
        context.getSharedPreferences(METADATA_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(book.uri.toString())
            .apply()
    }

    // ---------- Обложки ----------

    private fun coversDir(context: Context): File {
        val dir = File(context.filesDir, COVER_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun coverFileName(book: UniFile): String {
        return book.uri.toString()
            .replace(Regex("[^a-zA-Z0-9]"), "_")
            .take(128) + ".png"
    }

    /**
     * Загружает обложку книги. Если нет в кэше — извлекает из файла.
     */
    fun loadCover(context: Context, book: UniFile): android.graphics.Bitmap? {
        val cacheFile = File(coversDir(context), coverFileName(book))
        if (cacheFile.exists()) {
            val bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
            if (bitmap != null) return bitmap
            cacheFile.delete()
        }
        // Извлекаем и кэшируем
        return try {
            val coverBytes = BookParser.extractCover(context, book) ?: return null
            val bitmap = BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size) ?: return null
            FileOutputStream(cacheFile).use { it.write(coverBytes) }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Возвращает путь к обложке (или null).
     *
     * Библиотека читает только этот путь, поэтому обложка обязана появиться
     * здесь. Раньше метод лишь смотрел в кэш: пока кто-то не вызвал
     * [loadCover], каждая книга показывалась заведомо «без обложки», хотя
     * извлечение было бы успешным. Теперь при отсутствии кэша обложка
     * извлекается и сохраняется на лету.
     */
    fun coverPath(context: Context, book: UniFile): String? {
        val cacheFile = File(coversDir(context), coverFileName(book))
        if (cacheFile.exists()) return cacheFile.absolutePath
        return try {
            val coverBytes = BookParser.extractCover(context, book) ?: return null
            FileOutputStream(cacheFile).use { it.write(coverBytes) }
            cacheFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun clearCover(context: Context, book: UniFile) {
        File(coversDir(context), coverFileName(book)).delete()
    }

    /** Сохраняет обложку (PNG/JPEG байты) в кэш, чтобы её можно было отдать по пути. */
    fun saveCover(context: Context, book: UniFile, bytes: ByteArray) {
        try {
            FileOutputStream(File(coversDir(context), coverFileName(book))).use { it.write(bytes) }
        } catch (e: Exception) {
            // кэш не критичен
        }
    }

    private fun displayName(context: Context, uri: android.net.Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment
    }
}
