package eu.kanade.tachiyomi.data.books

import android.content.Context
import android.provider.OpenableColumns
import com.hippo.unifile.UniFile
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Хранилище локальных электронных книг.
 *
 * Книги лежат в подкаталоге `books/` основного хранилища приложения
 * (см. [tachiyomi.domain.storage.service.StorageManager.getBooksDirectory]),
 * рядом с мангой локального источника. Добавляются ЛЮБЫЕ файлы (любое
 * расширение и MIME) — парсер честно пытается извлечь текст при открытии.
 *
 * Прогресс чтения (последнее место) хранится в отдельном SharedPreferences
 * по ключу = идентификатор файла; библиотека рисует его полоской прогресса,
 * читалка восстанавливает при открытии.
 */
object BooksStore {

    data class Snapshot(
        val chapter: Int = 0,
        val sentence: Int = 0,
        val percent: Int = 0,
    )

    private const val PROGRESS_PREFS = "yomikai_books_progress"

    private fun storageManager(): tachiyomi.domain.storage.service.StorageManager =
        Injekt.get()

    fun booksDirectory(context: Context): UniFile? {
        return storageManager().getBooksDirectory()
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
     * При совпадении имени добавляет суффикс (2), (3)…
     * Возвращает скопированный файл или null.
     */
    fun importBook(context: Context, uri: android.net.Uri): UniFile? {
        val dir = booksDirectory(context) ?: return null
        val name = displayName(context, uri)
            ?.takeIf { it.isNotBlank() }
            ?: "book_${System.currentTimeMillis()}"
        val safeName = name.replace(Regex("[/\\\\:<>|?*\"]"), "_")

        val input = context.contentResolver.openInputStream(uri) ?: return null
        return input.use { stream ->
            var candidate = safeName
            var attempt = 0
            while (dir.findFile(candidate) != null) {
                attempt++
                val dot = safeName.lastIndexOf('.')
                candidate = if (dot > 0) {
                    safeName.substring(0, dot) + "($attempt)" + safeName.substring(dot)
                } else {
                    "$safeName($attempt)"
                }
            }
            val target = dir.createFile(candidate) ?: return@use null
            val out = target.openOutputStream() ?: return@use null
            out.use { stream.copyTo(it, 64 * 1024) }
            target
        }
    }

    fun deleteBook(book: UniFile): Boolean {
        return book.delete()
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