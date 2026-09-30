package eu.kanade.tachiyomi.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

object ReaderAiActions {

    private const val TIMEOUT_MS = 120_000L

    private val actions = LinkedHashMap<String, suspend (JSONObject) -> String>()

    fun register(id: String, block: suspend (JSONObject) -> String) {
        synchronized(actions) { actions[id] = block }
    }

    fun unregister(id: String) {
        synchronized(actions) { actions.remove(id) }
    }

    fun unregisterAll(prefix: String) {
        synchronized(actions) {
            actions.keys.filter { it.startsWith(prefix) }.forEach { actions.remove(it) }
        }
    }

    fun ids(): List<String> = synchronized(actions) { actions.keys.toList() }

    fun describe(): String {
        val list = ids().map { it.removePrefix("reader.") }
        return if (list.isEmpty()) {
            "Читалка не зарегистрировала действия (открой книгу в читалке)."
        } else {
            "Доступные действия читалки: " + list.joinToString(", ")
        }
    }

    suspend fun run(id: String, args: JSONObject): String = withContext(Dispatchers.IO) {
        val block = synchronized(actions) { actions[id] ?: actions["reader.$id"] }
        if (block == null) {
            return@withContext "ОШИБКА: действия «$id» нет. ${describe()}"
        }
        val result = withTimeoutOrNull(TIMEOUT_MS) { runCatching { block(args) } }
        when {
            result == null -> "ДЕЙСТВИЕ «$id» не ответило за ${TIMEOUT_MS / 1000}с"
            result.isFailure -> "ОШИБКА действия «$id»: ${result.exceptionOrNull()?.message?.take(160)}"
            else -> result.getOrDefault("").ifBlank { "ДЕЙСТВИЕ «$id»: пустой результат" }
        }
    }
}
