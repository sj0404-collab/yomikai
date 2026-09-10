package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.network.interceptor.DomainFallbackInterceptor
import okhttp3.Request

/**
 * Добавляет к запросу тег [DomainFallbackInterceptor.DomainFallbackConfig] для
 * автоматического переключения на зеркальный домен при ошибке подключения.
 *
 * Использование в HttpSource:
 * ```
 * override fun popularMangaRequest(page: Int): Request {
 *     return GET("$baseUrl/popular?page=$page", headers)
 *         .withDomainFallback("mangalib.me", listOf("mangalib.to", "mangalib.ru"))
 * }
 * ```
 *
 * @param primary основной домен (тот, что в baseUrl)
 * @param mirrors список зеркальных доменов
 * @param onDomainSwitched callback при успешном переключении (для сохранения нового домена)
 */
fun Request.withDomainFallback(
    primary: String,
    mirrors: List<String>,
    onDomainSwitched: ((oldDomain: String, newDomain: String) -> Unit)? = null,
): Request {
    return this.newBuilder()
        .tag(
            DomainFallbackInterceptor.DomainFallbackConfig(
                primaryDomain = primary,
                mirrorDomains = mirrors,
                onDomainSwitched = onDomainSwitched,
            ),
        )
        .build()
}
