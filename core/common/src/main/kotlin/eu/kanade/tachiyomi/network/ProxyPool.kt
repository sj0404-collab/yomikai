package eu.kanade.tachiyomi.network

import android.content.Context
import eu.kanade.tachiyomi.network.NetworkPreferences
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/**
 * Авто-выбор прокси из списка «host:port».
 *
 * Ограничение Android/OkHttp: прокси задаётся на весь клиент сразу, а не на
 * отдельный запрос, поэтому «включить прокси только для одного сайта» нельзя.
 * Зато можно при создании клиента выбрать первый **живой** прокси из списка —
 * именно это и делает [pick]: проверяет TCP-соединение с коротким таймаутом и
 * возвращает первый рабочий. Если ни один не ответил — приложение работает
 * напрямую, а не сидит в тишине с мёртвым прокси.
 *
 * Смена прокси применяется при следующем запуске: клиент OkHttp создаётся один
 * раз на процесс (так же, как у штатных настроек прокси).
 */
object ProxyPool {

    data class Entry(val host: String, val port: Int)

    /** Разбор строки списка: пустые строки и комментарии `#` пропускаются. */
    fun parse(raw: String): List<Entry> = raw.lineSequence()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val h = line.substringBefore(':').trim()
            val p = line.substringAfter(':', "").trim().toIntOrNull()
            if (h.isNotEmpty() && p != null && p in 1..65535) Entry(h, p) else null
        }
        .toList()

    /**
     * Первый прокси, который принимает TCP-соединение.
     * @param timeoutMs сколько ждать каждый адрес (по умолчанию 900 мс)
     */
    fun pick(entries: List<Entry>, timeoutMs: Int = 900): Entry? = entries.firstOrNull { entry ->
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(entry.host, entry.port), timeoutMs)
                true
            }
        }.getOrDefault(false)
    }

    fun proxyOf(entry: Entry, socks: Boolean): Proxy = Proxy(
        if (socks) Proxy.Type.SOCKS else Proxy.Type.HTTP,
        InetSocketAddress(entry.host, entry.port),
    )

    /** Публичный помощник для настроек/диагностики. */
    fun describe(context: Context, prefs: NetworkPreferences): String {
        val raw = prefs.proxyPool.get()
        val entries = parse(raw)
        if (entries.isEmpty()) return "Список прокси пуст"
        return entries.joinToString(", ") { "${it.host}:${it.port}" }
    }
}
