package eu.kanade.tachiyomi.ui.koma

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Очередь распознавания «наперёд».
 *
 * Жалоба: «страницы идут по очереди, одни долго, другие мгновенно ещё до своей
 * очереди». Причина была в коде экрана: префетч — ровно одна следующая страница,
 * и запускался он только ПОСЛЕ возврата распознавания текущей.
 *
 * Теперь два уровня:
 *  • [urgent] — то, что на экране прямо сейчас (полоса текущей страницы);
 *  • [ahead]  — полосы следующих страниц, они греются, пока читается текущая.
 *
 * Воркер всегда сначала берёт urgent, поэтому листание никогда не ждёт
 * «дальнюю» страницу. Задача одна за раз: модель ONNX внутри движка серийная
 * (mutex), параллельные вызовы только мешали бы друг другу и памяти.
 */
class KomaOcrQueue(
    scope: CoroutineScope,
    private val worker: suspend (page: Int, band: KomaBand) -> Unit,
) {
    private val urgent = Channel<Key>(Channel.UNLIMITED)
    private val ahead = Channel<Key>(Channel.UNLIMITED)
    private val queued = ConcurrentHashMap.newKeySet<String>()

    private data class Key(val page: Int, val band: KomaBand)

    private val job: Job = scope.launch(Dispatchers.Default) {
        while (isActive) {
            val next = urgent.tryReceive().getOrNull() ?: ahead.tryReceive().getOrNull()
            if (next == null) {
                delay(40)
                continue
            }
            val id = id(next.page, next.band)
            queued.remove(id)
            runCatching { worker(next.page, next.band) }
        }
    }

    private fun id(page: Int, band: KomaBand): String =
        "$page:${if (band.whole) 0 else band.index + 1}"

    /** Ставит задачу: [urgent] — на экране, иначе в очередь «наперёд». */
    fun enqueue(page: Int, band: KomaBand, urgent: Boolean = false) {
        if (queued.add(id(page, band))) {
            val channel: Channel<Key> = if (urgent) this.urgent else ahead
            if (channel.trySend(Key(page, band)).isFailure) queued.remove(id(page, band))
        }
    }

    /** Греет страницы впереди текущей — полосами, если это вебтун. */
    fun warmAhead(from: Int, last: Int, bandsFor: (Int) -> List<KomaBand>) {
        // Две страницы вперёд — это уже секунд на 20 работы; больше ставить
        // бессмысленно, листание всё равно приоритетнее.
        for (index in (from + 1)..minOf(last, from + 2)) {
            bandsFor(index).take(2).forEach { enqueue(index, it, urgent = false) }
        }
    }

    fun cancel() {
        runCatching { job.cancel() }
        urgent.close()
        ahead.close()
        queued.clear()
    }
}
