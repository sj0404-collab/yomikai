package eu.kanade.tachiyomi.util.source

import eu.kanade.domain.source.service.SourcePreferences
import logcat.LogPriority
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat

/**
 * Менеджер здоровья источников: записывает успех/ошибку запросов,
 * хранит зеркальные домены, позволяет авто-отключать сломанные.
 *
 * Вся информация — в prefs как JSON-строка (без БД).
 */
object SourceHealthManager {

    private const val FAIL_THRESHOLD = 5

    data class Health(
        val successCount: Int = 0,
        val failCount: Int = 0,
        val lastCheck: Long = 0L,
        val activeDomain: String? = null,
    ) {
        val isHealthy: Boolean get() = failCount < FAIL_THRESHOLD
        val failRatio: Float get() {
            val total = successCount + failCount
            return if (total == 0) 0f else failCount.toFloat() / total
        }
    }

    // ── Health tracking ────────────────────────────────────────────

    fun recordSuccess(prefs: SourcePreferences, sourceId: Long) {
        val h = getHealth(prefs, sourceId)
        setHealth(prefs, sourceId, h.copy(
            successCount = h.successCount + 1,
            lastCheck = System.currentTimeMillis(),
        ))
    }

    fun recordFailure(prefs: SourcePreferences, sourceId: Long) {
        val h = getHealth(prefs, sourceId)
        setHealth(prefs, sourceId, h.copy(
            failCount = h.failCount + 1,
            lastCheck = System.currentTimeMillis(),
        ))
    }

    fun getHealth(prefs: SourcePreferences, sourceId: Long): Health = runCatching {
        val root = JSONObject(prefs.sourceHealth.get())
        val obj = root.optJSONObject(sourceId.toString()) ?: return Health()
        Health(
            successCount = obj.optInt("s", 0),
            failCount = obj.optInt("f", 0),
            lastCheck = obj.optLong("t", 0L),
            activeDomain = obj.optString("d", "").takeIf { it.isNotBlank() },
        )
    }.getOrDefault(Health())

    private fun setHealth(prefs: SourcePreferences, sourceId: Long, health: Health) {
        runCatching {
            val root = JSONObject(prefs.sourceHealth.get())
            root.put(sourceId.toString(), JSONObject().apply {
                put("s", health.successCount)
                put("f", health.failCount)
                put("t", health.lastCheck)
                health.activeDomain?.let { put("d", it) }
            })
            prefs.sourceHealth.set(root.toString())
        }.onFailure {
            logcat(LogPriority.WARN, it) { "Failed to save health for source $sourceId" }
        }
    }

    fun resetHealth(prefs: SourcePreferences, sourceId: Long) {
        runCatching {
            val root = JSONObject(prefs.sourceHealth.get())
            root.remove(sourceId.toString())
            prefs.sourceHealth.set(root.toString())
        }
    }

    fun isSourceBroken(prefs: SourcePreferences, sourceId: Long): Boolean =
        getHealth(prefs, sourceId).failCount >= FAIL_THRESHOLD

    // ── Mirror domains ─────────────────────────────────────────────

    fun setMirrors(prefs: SourcePreferences, sourceId: Long, domains: List<String>) {
        runCatching {
            val root = JSONObject(prefs.sourceMirrorDomains.get())
            val arr = org.json.JSONArray()
            domains.forEach { arr.put(it) }
            root.put(sourceId.toString(), arr)
            prefs.sourceMirrorDomains.set(root.toString())
        }.onFailure {
            logcat(LogPriority.WARN, it) { "Failed to save mirrors for source $sourceId" }
        }
    }

    fun getMirrors(prefs: SourcePreferences, sourceId: Long): List<String> = runCatching {
        val root = JSONObject(prefs.sourceMirrorDomains.get())
        val arr = root.optJSONArray(sourceId.toString()) ?: return emptyList()
        (0 until arr.length()).mapNotNull { arr.optString(it) }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    fun setActiveDomain(prefs: SourcePreferences, sourceId: Long, domain: String) {
        val h = getHealth(prefs, sourceId)
        setHealth(prefs, sourceId, h.copy(activeDomain = domain))
    }

    /**
     * Возвращает следующий доступный домен для источника.
     * Сначала проверяет текущий activeDomain, потом зеркала.
     */
    fun getNextDomain(prefs: SourcePreferences, sourceId: Long, primaryDomain: String): String? {
        val mirrors = getMirrors(prefs, sourceId)
        val current = getHealth(prefs, sourceId).activeDomain
        val candidates = buildList {
            if (current != null && current != primaryDomain) add(current)
            add(primaryDomain)
            mirrors.filter { it != current && it != primaryDomain }.forEach { add(it) }
        }
        return candidates.firstOrNull()
    }
}
