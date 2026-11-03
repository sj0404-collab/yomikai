package eu.kanade.tachiyomi.ui.koma

import android.content.Context
import android.net.ConnectivityManager

/**
 * Готовность движков распознавания — для списка в читалке.
 *
 * Ответ `null` означает «не проверяли»: список тогда не врёт, что движка нет
 * (см. [KomaEngineChoice.options]). Проверка сама ничего не ломает: без сети,
 * без пакета моделей и без LiteRT она честно вернёт меньший набор.
 */
object KomaEngineAvailability {

    @Suppress("DEPRECATION")
    fun availableIds(context: Context): Set<String>? = runCatching {
        val online = (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
            ?.activeNetworkInfo?.isConnected == true
        mihon.data.ocr.OcrPluginAvailability.availableIds(
            context = context.applicationContext,
            networkAvailable = online,
        )
    }.getOrNull()
}
