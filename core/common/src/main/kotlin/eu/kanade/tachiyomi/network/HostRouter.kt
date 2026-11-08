package eu.kanade.tachiyomi.network

/**
 * Реестр «правильных» доменов для сетевого слоя.
 *
 * Расширение источника жёстко хранит свой `baseUrl` (например
 * `https://mangalib.me`). Когда сайт переезжает, правка расширения не поможет:
 * его код у нас, а расширение — сторонний APK. Поэтому адрес переписывается
 * **на выходе**: интерцептор [eu.kanade.tachiyomi.network.interceptor.HostRoutingInterceptor]
 * заменяет домен запроса на рабочий и, если тот не ответил, пробует зеркала.
 *
 * Реестр намеренно статический и простой (пара volatile-полей): его заполняет
 * приложение из prefs (`SourcePreferences.hostOverrides`), а читает сетевой слой
 * в любом потоке, без DI и без корутин.
 */
object HostRouter {

    /** `старый домен → рабочий домен`. */
    @Volatile
    var overrides: Map<String, String> = emptyMap()

    /** `домен → зеркала` в порядке попытки. */
    @Volatile
    var mirrors: Map<String, List<String>> = emptyMap()

    /** Глобальный выключатель (настройка «переключать домены автоматически»). */
    @Volatile
    var enabled: Boolean = true

    /** Уведомление о состоявшемся переключении — приложение сохраняет его в prefs. */
    @Volatile
    var onSwitched: ((from: String, to: String) -> Unit)? = null

    /** Рабочий домен для запроса: сначала переопределение, иначе исходный. */
    fun resolve(host: String): String = overrides[host]?.takeIf { it.isNotBlank() } ?: host

    /** Кандидаты в порядке попытки: рабочий домен, затем зеркала. */
    fun candidates(host: String): List<String> {
        val primary = resolve(host)
        val extra = (mirrors[host].orEmpty() + mirrors[primary].orEmpty())
            .filter { it != primary && it.isNotBlank() }
        return (listOf(primary) + extra).distinct()
    }

    fun report(from: String, to: String) {
        onSwitched?.invoke(from, to)
    }
}
