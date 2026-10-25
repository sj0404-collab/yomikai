package eu.kanade.tachiyomi.ui.webbrowser

/**
 * Отображение и сравнение адресов браузера.
 *
 * Вынесено отдельным объектом без Compose и Android: это чистые функции, их
 * проверяют юнит-тесты. В `WebStore` они только вызываются.
 */
object UrlDisplay {

    /**
     * Сравнение адресов без схемы, «www.», хвостового слэша и регистра.
     *
     * Регистр снимается ПЕРВЫМ: иначе `HTTPS://Mangabuff.RU/...` и
     * `https://mangabuff.ru/...` оставались разными адресами и снова давали
     * две вкладки на одну страницу.
     */
    fun normalize(url: String?): String = url.orEmpty()
        .trim()
        .lowercase()
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .trimEnd('/')

    /**
     * Короткий вид для строки навигации: без схемы и «www.», длинный хвост
     * обрезается по последнему слэшу.
     *
     * Повод: на устройстве в адресной строке было видно «https://n» — поле
     * обрезалось по ширине, потому что туда подставлялся полный URL.
     */
    fun pretty(url: String?, maxLength: Int = 46): String {
        val trimmed = url.orEmpty().trim()
        val noScheme = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed.substring(8)
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed.substring(7)
            else -> trimmed
        }
        val host = noScheme.removePrefix("www.").trimEnd('/')
        if (host.isBlank()) return ""
        if (host.length <= maxLength) return host
        val cut = host.take(maxLength)
        val slash = cut.lastIndexOf('/')
        return (if (slash > 12) cut.take(slash) else cut).trimEnd('/') + "…"
    }
}
