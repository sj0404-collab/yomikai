package eu.kanade.tachiyomi.ui.webbrowser

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ПОЛЬЗОВАТЕЛЬСКИЕ СКРИПТЫ («расширения») мини-браузера.
 *
 * Аналог userscript-менеджера (Tampermonkey/Greasemonkey): пользователь
 * добавляет JS, задаёт URL-шаблон и момент выполнения, скрипт вживляется в
 * WebView на каждой подходящей странице. Настоящие Chrome-расширения (.crx)
 * в WebView невозможны — у него нет Extension API, поэтому «расширения»
 * здесь = встроенные тумблеры (AdBlock и т.п.) + пользовательские скрипты.
 *
 * Хранение — один JSON в filesDir, без БД; любой сбой чтения = пустой список.
 */
object WebScriptsStore {

    /** [runAt]: "start" — до загрузки DOM, "end" — после загрузки страницы. */
    data class UserScript(
        val id: String,
        val name: String,
        val match: String,
        val code: String,
        val enabled: Boolean,
        val runAt: String,
    )

    val scripts = MutableStateFlow<List<UserScript>>(emptyList())

    private var loaded = false

    private fun file(context: Context): File = File(context.filesDir, "webscripts.json")

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val f = file(context)
            if (!f.isFile) return@runCatching
            val arr = JSONArray(f.readText())
            scripts.value = (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val o = arr.getJSONObject(i)
                    UserScript(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        match = o.optString("match", "*"),
                        code = o.optString("code"),
                        enabled = o.optBoolean("enabled", true),
                        runAt = o.optString("runAt", "end"),
                    )
                }.getOrNull()
            }
        }
    }

    private fun save(context: Context) {
        runCatching {
            val arr = JSONArray()
            scripts.value.forEach { s ->
                arr.put(
                    JSONObject().apply {
                        put("id", s.id); put("name", s.name); put("match", s.match)
                        put("code", s.code); put("enabled", s.enabled); put("runAt", s.runAt)
                    },
                )
            }
            file(context).writeText(arr.toString())
        }
    }

    fun add(context: Context, name: String, match: String, code: String, runAt: String): UserScript {
        load(context)
        val s = UserScript(
            id = System.currentTimeMillis().toString(),
            name = name.ifBlank { "Скрипт" },
            match = match.ifBlank { "*" },
            code = code,
            enabled = true,
            runAt = if (runAt == "start") "start" else "end",
        )
        scripts.value = scripts.value + s
        save(context)
        return s
    }

    fun update(context: Context, script: UserScript) {
        load(context)
        scripts.value = scripts.value.map { if (it.id == script.id) script else it }
        save(context)
    }

    fun remove(context: Context, id: String) {
        load(context)
        scripts.value = scripts.value.filterNot { it.id == id }
        save(context)
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        load(context)
        scripts.value = scripts.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        save(context)
    }

    /**
     * Совпадает ли url с шаблоном [match]. Шаблон можно дробить переносами или
     * запятыми; `*` = любая последовательность. Пусто или `*` = все страницы.
     */
    fun matches(match: String, url: String): Boolean {
        val patterns = match.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
        if (patterns.isEmpty()) return true
        val u = url.lowercase()
        return patterns.any { p ->
            val lp = p.lowercase()
            if (lp == "*" || lp == "all") {
                true
            } else {
                val re = lp.split('*').joinToString(".*") { Regex.escape(it) }
                runCatching { Regex("^$re$").containsMatchIn(u) }.getOrDefault(false)
            }
        }
    }

    /** Вживить подходящие скрипты выбранного момента ([runAt] = "start"/"end"). */
    fun inject(context: Context, view: WebView?, url: String?, runAt: String) {
        val wv = view ?: return
        val u = url?.takeIf { it.isNotBlank() } ?: return
        load(context)
        val at = if (runAt == "start") "start" else "end"
        scripts.value
            .filter { it.enabled && it.runAt == at && matches(it.match, u) }
            .forEach { s ->
                val wrapped = "try{\n" + s.code + "\n}catch(e){console.warn('yk-script: '+e);}"
                runCatching { wv.evaluateJavascript(wrapped, null) }
            }
    }

    /** Переинжектить все подходящие скрипты в уже открытую страницу. */
    fun injectAll(context: Context, view: WebView?, url: String?) {
        inject(context, view, url, "start")
        inject(context, view, url, "end")
    }
}
