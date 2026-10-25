package eu.kanade.tachiyomi.ui.webbrowser

/**
 * Отображение и сравнение адресов браузера.
 *
 * Вынесено отдельным объектом без Compose и Android: это чистые функции, их
 * проверяют юнит-тесты. В `WebStore` они только вызываются.
 */
object UrlDisplay {

    /** Сравнение адресов без схемы, «www.», хвостового слэша и регистра. */
    fun normalize(url: String?): String = url.orEmpty()
        .trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .trimEnd('/')
        .lowercase()

    /**
     * Короткий вид для строки навигации: без схемы и «www.», длинный хвост
     * обрезается по последнему слэшу.
     *
     * Повод: на устройстве в адресной строке было видно «https://n» — поле
     * обрезалось по ширине, потому что туда подставлялся полный URL.
     */
    fun pretty(url: String?, maxLength: Int = 46): String {
        val host = url.orEmpty()
            .trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .trimEnd('/')
        if (host.isBlank()) return ""
        if (host.length <= maxLength) return host
        val cut = host.take(maxLength)
        val slash = cut.lastIndexOf('/')
        return (if (slash > 12) cut.take(slash) else cut).trimEnd('/') + "…"
    }
}
