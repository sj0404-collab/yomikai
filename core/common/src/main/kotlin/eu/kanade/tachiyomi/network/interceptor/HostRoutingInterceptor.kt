package eu.kanade.tachiyomi.network.interceptor

import eu.kanade.tachiyomi.network.HostRouter
import logcat.LogPriority
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import tachiyomi.core.common.util.system.logcat
import java.io.IOException

/**
 * Переписывает домен запроса на рабочий и, если он не ответил, пробует зеркала.
 *
 * Проблема, которую это решает: у расширения домен зашит в `baseUrl`, и когда
 * сайт переезжает (`mangalib.me` → `mangalib.org`), источник навсегда «играет в
 * молчанку» — запросы уходят на мёртвый адрес. Готовый
 * [DomainFallbackInterceptor] этого не решает: он работает только если расширение
 * само пометило запрос тегом `withDomainFallback`, а сторонние расширения так не
 * делают.
 *
 * Здесь домен берётся из [HostRouter] — то есть из настроек приложения, — и
 * применяется ко ВСЕМ запросам без участия расширения. Пробуем не больше
 * [MAX_ATTEMPTS] адресов, чтобы мёртвый сайт не тормозил экран на минуты.
 */
class HostRoutingInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host

        if (!HostRouter.enabled) return chain.proceed(request)

        val candidates = HostRouter.candidates(host)
        if (candidates.size == 1 && candidates.first() == host) {
            return chain.proceed(request)
        }

        var lastError: IOException? = null
        var index = 0

        for (domain in candidates.take(MAX_ATTEMPTS)) {
            val attempt = if (domain == host) {
                request
            } else {
                request.newBuilder()
                    .url(request.url.newBuilder().host(domain).build())
                    .build()
            }

            try {
                val response = chain.proceed(attempt)
                // Успех: запоминаем новый домен, чтобы следующий запуск шёл сразу
                // на него и не тратил попытки.
                if (response.isSuccessful || index == candidates.lastIndex || !retryable(response.code)) {
                    if (domain != host && response.isSuccessful) {
                        HostRouter.report(host, domain)
                        logcat(LogPriority.INFO) { "Host routing: $host → $domain" }
                    }
                    return response
                }
                logcat(LogPriority.WARN) { "Host routing: $domain ответил ${response.code}" }
                response.close()
            } catch (e: IOException) {
                lastError = e
                logcat(LogPriority.WARN) { "Host routing: $domain не открылся (${e.message})" }
            }
            index++
        }

        throw lastError ?: IOException("Ни один домен не ответил: $candidates")
    }

    private fun retryable(code: Int): Boolean =
        code == 403 || code == 404 || code == 502 || code == 503 || code == 520 || code == 522

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
