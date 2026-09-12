package eu.kanade.tachiyomi.data.books

import android.content.Context
import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import com.hippo.unifile.UniFile
import java.io.File
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
                    return@use ImportResult.Failure("Нет прав на запись: ${e.message}")
                } ?: return@use ImportResult.Failure("Не удалось открыть файл для записи")
                out.use { targetOut ->
                    stream.copyTo(targetOut, 64 * 1024)
                }
                val finalFile = dir.findFile(candidate)
                if (finalFile?.exists() == true) {
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
        context.getSharedPreferences(PROGRESS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(book.uri.toString())
            .apply()
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
     */
    fun coverPath(context: Context, book: UniFile): String? {
        val cacheFile = File(coversDir(context), coverFileName(book))
        return if (cacheFile.exists()) cacheFile.absolutePath else null
    }

    private fun clearCover(context: Context, book: UniFile) {
        File(coversDir(context), coverFileName(book)).delete()
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
