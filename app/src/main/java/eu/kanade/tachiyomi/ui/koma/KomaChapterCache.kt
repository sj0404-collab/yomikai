package eu.kanade.tachiyomi.ui.koma

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import eu.kanade.tachiyomi.data.ocr.OcrPageInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Страницы главы на диске для читалки KOMA.
 *
 * Что было не так (жалоба «глава открывается долго, хотя файлы уже в
 * телефоне»):
 *  • кэш лежал в `cacheDir`, который Android вправе вычистить в любой момент —
 *    глава материализовалась заново;
 *  • КАЖДАЯ страница декодировалась и пережималась в JPEG 90, последовательно,
 *    ДО открытия читалки. Для манхвы 1600×9000 × 30 страниц это десятки секунд,
 *    плюс двойное сжатие портило картинку и распознавание;
 *  • «готово» означало «в папке есть хоть один файл» — оборванная запись
 *    засчитывалась как полная глава.
 *
 * Теперь:
 *  • папка в `filesDir` (`koma-pages/<id>`), ничего не пережимается — поток
 *    исходника копируется БАЙТ В БАЙТ (если источник отдаёт поток), поэтому
 *    копирование в разы дешевле декодирования;
 *  • материализация идёт в фоне, 3 страницы параллельно, первая — первой;
 *  • запись атомарная: `.tmp` → `rename`, недописанный файл никогда не виден;
 *  • читалка открывается сразу, а страница, которую ещё копируют, ждёт своего
 *    файла в [awaitFile] — вместо ошибки «Не удалось открыть».
 */
object KomaChapterCache {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Пути, которые прямо сейчас копируются: их ждут экран и распознавание. */
    private val pending = ConcurrentHashMap<String, Long>()

    /** Сколько страниц копируем одновременно: диск и источник не любят больше. */
    private const val PARALLEL = 3

    /** Имя страницы фиксировано: путь нужен раньше, чем файл готов. */
    fun fileName(index: Int): String = "%04d.img".format(index)

    fun dir(context: Context, bookId: String): File =
        File(context.filesDir, "koma-pages/${bookId.replace(Regex("[^\\w.-]"), "_")}")

    /** Пути всех страниц главы — их можно отдать читалке до копирования. */
    fun paths(context: Context, bookId: String, count: Int): List<String> {
        val dir = dir(context, bookId).apply { mkdirs() }
        return (0 until count).map { File(dir, fileName(it)).absolutePath }
    }

    /** Уже лежит на диске (и файл не пустой). */
    fun ready(path: String): Boolean {
        val f = File(path)
        return f.isFile && f.length() > 0L && !path.endsWith(".tmp")
    }

    fun isPending(path: String): Boolean = pending.containsKey(path)

    /**
     * Ставит в фон копирование отсутствующих страниц.
     *
     * Возвращает сразу: экран не ждёт диск. Индексы открываются по порядку —
     * первая страница (и та, что сейчас на экране) готова раньше остальных.
     */
    fun prefetch(
        context: Context,
        bookId: String,
        pages: List<OcrPageInput>,
        paths: List<String>,
        priority: List<Int> = emptyList(),
    ): Job? {
        val app = context.applicationContext
        val missing = pages.indices.filter { paths.getOrNull(it)?.let(::ready) != true }
        if (missing.isEmpty()) return null
        val order = (priority.filter { it in missing } + missing).distinct()
        val gate = Semaphore(PARALLEL)
        val jobs = order.mapNotNull { index ->
            val path = paths.getOrNull(index) ?: return@mapNotNull null
            if (ready(path) || pending.putIfAbsent(path, SystemClock.elapsedRealtime()) != null) {
                return@mapNotNull null
            }
            scope.launch {
                try {
                    gate.withPermit { copyOne(app, pages[index], path) }
                } finally {
                    pending.remove(path)
                }
            }
        }
        if (jobs.isEmpty()) return null
        // Одна агрегирующая задача: по ней вызывающий понимает, когда источник
        // (архив, PDF-дескриптор) уже можно закрывать.
        return scope.launch { jobs.joinAll() }
    }

    private suspend fun copyOne(context: Context, input: OcrPageInput, path: String) {
        val target = File(path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        val ok = runCatching {
            withContext(Dispatchers.IO) {
                if (tmp.exists()) tmp.delete()
                val stream = input.openStream?.invoke()
                if (stream != null) {
                    // Главный путь: копия исходника без перекодирования.
                    stream.use { src -> tmp.outputStream().use { out -> src.copyTo(out, 1 shl 16) } }
                } else {
                    // PDF и прочие источники без потока: только там нужен растр.
                    val bitmap = input.openBitmap() ?: error("страница недоступна")
                    try {
                        tmp.outputStream().use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
                tmp.length() > 0L
            }
        }.getOrDefault(false)
        if (ok && tmp.length() > 0L) {
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                runCatching { tmp.copyTo(target, overwrite = true) }
                tmp.delete()
            }
        } else {
            tmp.delete()
        }
    }

    /**
     * Ждёт появления файла страницы, если его прямо сейчас копируют.
     *
     * Вызывается из потока ввода-вывода перед декодированием: пользователь
     * листает главу быстрее, чем копируется диск, но получает страницу, а не
     * ошибку.
     */
    fun awaitFile(path: String, timeoutMs: Long = 20_000L): Boolean {
        if (ready(path)) return true
        val started = pending[path] ?: return waitAppear(path, timeoutMs)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ready(path)) return true
            if (!pending.containsKey(path)) return waitAppear(path, 1_500L)
            SystemClock.sleep(50)
        }
        return ready(path)
    }

    private fun waitAppear(path: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ready(path)) return true
            SystemClock.sleep(50)
        }
        return ready(path)
    }

    /** Сколько места занято кэшем глав (для настроек). */
    fun sizeBytes(context: Context): Long {
        val root = File(context.filesDir, "koma-pages")
        return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, "koma-pages").deleteRecursively() }
    }

    /** Подстраховка для параллельного прогрева нескольких глав. */
    suspend fun awaitAllStarted(paths: List<String>) = withContext(Dispatchers.IO) {
        paths.map { async { awaitFile(it) } }.awaitAll()
    }

    /** Запускает прогрев главы и не ждёт его — удобно из UI-событий. */
    fun warmUp(context: Context, bookId: String, pages: List<OcrPageInput>, paths: List<String>) {
        runCatching { prefetch(context, bookId, pages, paths) }
    }
}

/** Запуск фоновой задачи кэша глав (для вызывающих вне объекта). */
internal fun komaCacheScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Хелпер, чтобы не тянуть корутинный скоуп в вызывающий код. */
internal fun KomaChapterCache.startBackground(block: suspend () -> Unit) {
    komaCacheScope().launch { block() }
}
