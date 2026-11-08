package eu.kanade.tachiyomi.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Кэш «Ленты обновлений» на диске.
 *
 * Раньше лента каждый раз начиналась с нуля: пользователь заходил — пустой экран
 * и полоска загрузки, пока не отработают все источники по очереди (до 15 секунд
 * на каждый). Теперь последний снимок лежит в файле и показывается сразу, а
 * свежие данные подмешиваются поверх.
 *
 * Формат намеренно простой (JSON): это кэш, а не база — его можно потерять.
 */
object FeedCache {

    private const val FILE = "koma_feed.json"
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    data class Row(
        val sourceId: Long,
        val sourceName: String,
        val lang: String,
        val titles: List<String>,
        val error: String?,
        val at: Long,
    )

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun load(context: Context): List<Row> = runCatching {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val root = JSONObject(f.readText())
        val rows = root.optJSONArray("rows") ?: JSONArray()
        val now = System.currentTimeMillis()
        (0 until rows.length()).mapNotNull { i ->
            val o = rows.optJSONObject(i) ?: return@mapNotNull null
            val at = o.optLong("at", 0L)
            if (now - at > MAX_AGE_MS) return@mapNotNull null
            val titles = o.optJSONArray("titles") ?: JSONArray()
            Row(
                sourceId = o.optLong("id"),
                sourceName = o.optString("name"),
                lang = o.optString("lang"),
                titles = (0 until titles.length()).map { titles.optString(it) },
                error = o.optString("error").takeIf { it.isNotBlank() },
                at = at,
            )
        }
    }.getOrDefault(emptyList())

    /** Сохранить снимок; ошибки сети тоже сохраняем — их видно пользователю. */
    fun save(context: Context, rows: List<Row>) = runCatching {
        val arr = JSONArray()
        rows.forEach { row ->
            arr.put(
                JSONObject().apply {
                    put("id", row.sourceId)
                    put("name", row.sourceName)
                    put("lang", row.lang)
                    put("error", row.error.orEmpty())
                    put("at", row.at)
                    put("titles", JSONArray(row.titles))
                },
            )
        }
        file(context).writeText(JSONObject().put("rows", arr).toString())
        Unit
    }.let { }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
