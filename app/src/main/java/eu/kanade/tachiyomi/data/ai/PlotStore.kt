package eu.kanade.tachiyomi.data.ai

import java.io.File

/**
 * Пересказ книги на диске, по файлу на книгу.
 *
 * Хранится рядом с другими рабочими файлами приложения и переживает закрытие
 * читалки: пересказ пишется запросом к модели, и терять его при повороте
 * экрана было бы обидно. Ограничение размера — файл всё-таки хранилище, а не
 * журнал: длинный пересказ обрезается, а не разрастается.
 */
interface PlotStore {
    fun read(bookId: Long): String
    fun write(bookId: Long, text: String)
}

class FilePlotStore(dir: File) : PlotStore {

    private val root = File(dir, "plot").apply { mkdirs() }

    override fun read(bookId: Long): String {
        if (bookId <= 0L) return ""
        return runCatching { File(root, "$bookId.txt").takeIf { it.isFile }?.readText().orEmpty() }
            .getOrDefault("")
    }

    override fun write(bookId: Long, text: String) {
        if (bookId <= 0L) return
        val safe = text.take(MAX_CHARS)
        runCatching { File(root, "$bookId.txt").writeText(safe) }
    }

    companion object {
        private const val MAX_CHARS = 40_000
    }
}

/** Хранилище в памяти — для тестов и для мест, где диск недоступен. */
class MemoryPlotStore : PlotStore {
    private val map = mutableMapOf<Long, String>()
    override fun read(bookId: Long): String = map[bookId].orEmpty()
    override fun write(bookId: Long, text: String) {
        map[bookId] = text
    }
}