package eu.kanade.tachiyomi.network.interceptor

import logcat.LogPriority
import okhttp3.Interceptor
import okhttp3.Response
import tachiyomi.core.common.util.system.logcat
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Интерцептор для автоматического переключения домена при сетевых ошибках.
 *
 * Использует тег запроса [DOMAIN_FALLBACK_TAG] с объектом [DomainFallbackConfig],
 * содержащим основной домен и список зеркал. При ошибке подключения (IOException,
 * SocketTimeoutException) заменяет домен в URL на следующий из списка и повторяет запрос.
 */
class DomainFallbackInterceptor : Interceptor {

    data class DomainFallbackConfig(
        val primaryDomain: String,
        val mirrorDomains: List<String>,
        val onDomainSwitched: ((oldDomain: String, newDomain: String) -> Unit)? = null,
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val config = request.tag(DomainFallbackConfig::class.java) ?: return chain.proceed(request)

        val candidates = buildList {
            add(config.primaryDomain)
            config.mirrorDomains.filter { it != config.primaryDomain }.forEach { add(it) }
        }

        var lastException: Exception? = null

        for ((index, domain) in candidates.withIndex()) {
            val url = request.url.newBuilder()
                .scheme("https")
                .host(domain)
                .build()
            val newRequest = request.newBuilder().url(url).build()

            try {
                val response = chain.proceed(newRequest)
                if (response.isSuccessful || index == candidates.lastIndex) {
                    if (index > 0) {
                        config.onDomainSwitched?.invoke(candidates[0], domain)
                        logcat(LogPriority.INFO) {
                            "Domain fallback: switched from ${candidates[0]} to $domain"
                        }
                    }
                    return response
                }
                // Если не 2xx, но есть ещё кандидаты — пробуем следующий
                response.close()
            } catch (e: IOException) {
                lastException = e
                logcat(LogPriority.WARN) {
                    "Domain fallback: $domain failed (${e.message}), trying next..."
                }
                continue
            } catch (e: SocketException) {
                lastException = e
                logcat(LogPriority.WARN) {
                    "Domain fallback: $domain socket error (${e.message}), trying next..."
                }
                continue
            } catch (e: SocketTimeoutException) {
                lastException = e
                logcat(LogPriority.WARN) {
                    "Domain fallback: $domain timeout, trying next..."
                }
                continue
            }
        }

        throw lastException ?: IOException("All domain candidates failed")
    }

    companion object {
        const val DOMAIN_FALLBACK_TAG = "domain_fallback"
    }
}
