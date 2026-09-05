package eu.kanade.tachiyomi.ui.webbrowser

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * БИБЛИОТЕКА МИНИ-БРАУЗЕРА: сохранённые HTML-страницы, закладки, история
 * просмотра и вкладки. Один JSON в filesDir + HTML-файлы в
 * <external>/webpages/<источник>/<id>.html — без новых зависимостей и без
 * базы данных; любой сбой чтения = пустой store (crash resilience).
 *
 * Группировка «по источнику и id» — как просил пользователь: страницы
 * разложены по каталогам хостов, у каждой собственный id и запись в индексе.
 */
object WebStore {
    data class Page(val id: String, val host: String, val title: String, val url: String, val file: String, val savedAt: Long)
    data class Mark(val id: String, val host: String, val title: String, val url: String, val savedAt: Long)
    data class Hist(val url: String, val title: String, val at: Long)
    data class TabItem(val id: String, val url: String, val title: String)

    val pages = MutableStateFlow<List<Page>>(emptyList())
    val marks = MutableStateFlow<List<Mark>>(emptyList())
    val history = MutableStateFlow<List<Hist>>(emptyList())
    val tabs = MutableStateFlow<List<TabItem>>(emptyList())

    private var loaded = false

    private fun file(context: Context): File = File(context.filesDir, "webstore.json")

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val f = file(context)
            if (!f.isFile) return
            val root = JSONObject(f.readText())
            pages.value = root.optJSONArray("pages").jsonList { o ->
                Page(o.optString("id"), o.optString("host"), o.optString("title"), o.optString("url"), o.optString("file"), o.optLong("savedAt"))
            }
            marks.value = root.optJSONArray("marks").jsonList { o ->
                Mark(o.optString("id"), o.optString("host"), o.optString("title"), o.optString("url"), o.optLong("savedAt"))
            }
            history.value = root.optJSONArray("history").jsonList { o ->
                Hist(o.optString("url"), o.optString("title"), o.optLong("at"))
            }
            tabs.value = root.optJSONArray("tabs").jsonList { o ->
                TabItem(o.optString("id"), o.optString("url"), o.optString("title"))
            }
        }
    }

    private inline fun <T> JSONArray?.jsonList(map: (JSONObject) -> T?): List<T> {
        val arr = this ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> runCatching { map(arr.getJSONObject(i)) }.getOrNull() }
    }

    private fun save(context: Context) {
        runCatching {
            val root = JSONObject()
            root.put(
                "pages",
                JSONArray().apply {
                    pages.value.forEach { p ->
                        put(JSONObject().apply {
                            put("id", p.id); put("host", p.host); put("title", p.title)
                            put("url", p.url); put("file", p.file); put("savedAt", p.savedAt)
                        })
                    }
                },
            )
            root.put(
                "marks",
                JSONArray().apply {
                    marks.value.forEach { p ->
                        put(JSONObject().apply {
                            put("id", p.id); put("host", p.host); put("title", p.title)
                            put("url", p.url); put("savedAt", p.savedAt)
                        })
                    }
                },
            )
            root.put(
                "history",
                JSONArray().apply {
                    history.value.forEach { p ->
                        put(JSONObject().apply { put("url", p.url); put("title", p.title); put("at", p.at) })
                    }
                },
            )
            root.put(
                "tabs",
                JSONArray().apply {
                    tabs.value.forEach { p ->
                        put(JSONObject().apply { put("id", p.id); put("url", p.url); put("title", p.title) })
                    }
                },
            )
            file(context).writeText(root.toString())
        }
    }

    fun pagesDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "webpages").apply { mkdirs() }

    fun hostOf(url: String): String =
        runCatching { android.net.Uri.parse(url).host ?: url }.getOrDefault(url)

    /** Сохранить страницу как HTML-файл в библиотеку (по источнику и id). */
    fun savePage(context: Context, url: String, title: String, html: String): Page? = runCatching {
        load(context)
        val host = hostOf(url)
        val id = System.currentTimeMillis().toString()
        val dir = File(pagesDir(context), host).apply { mkdirs() }
        val f = File(dir, "$id.html")
        f.writeText(html)
        val p = Page(id, host, title.ifBlank { url }, url, f.absolutePath, System.currentTimeMillis())
        pages.value = (pages.value + p).takeLast(200)
        save(context)
        p
    }.getOrNull()

    fun deletePage(context: Context, p: Page) {
        runCatching { File(p.file).delete() }
        pages.value = pages.value.filterNot { it.id == p.id }
        save(context)
    }

    fun isBookmarked(url: String): Boolean = marks.value.any { it.url == url }

    fun toggleBookmark(context: Context, url: String, title: String): Boolean {
        load(context)
        return if (isBookmarked(url)) {
            marks.value = marks.value.filterNot { it.url == url }
            save(context)
            false
        } else {
            marks.value = (marks.value + Mark(System.currentTimeMillis().toString(), hostOf(url), title.ifBlank { url }, url, System.currentTimeMillis())).takeLast(300)
            save(context)
            true
        }
    }

    fun deleteMark(context: Context, m: Mark) {
        marks.value = marks.value.filterNot { it.id == m.id }
        save(context)
    }

    fun addHistory(context: Context, url: String, title: String) {
        if (url.isBlank()) return
        load(context)
        history.value = (listOf(Hist(url, title.ifBlank { url }, System.currentTimeMillis())) + history.value)
            .distinctBy { it.url }
            .take(300)
        save(context)
    }

    fun clearHistory(context: Context) {
        history.value = emptyList()
        save(context)
    }

    fun addTab(context: Context, url: String, title: String): TabItem {
        load(context)
        val t = TabItem(System.currentTimeMillis().toString(), url, title.ifBlank { url })
        tabs.value = (tabs.value + t).takeLast(20)
        save(context)
        return t
    }

    fun closeTab(context: Context, id: String) {
        tabs.value = tabs.value.filterNot { it.id == id }
        save(context)
    }

    fun cacheSizeBytes(context: Context): Long = runCatching {
        fun rec(f: File): Long = if (f.isFile) f.length() else f.listFiles()?.sumOf { rec(it) } ?: 0L
        rec(context.cacheDir)
    }.getOrDefault(0L)

    fun clearCache(context: Context) {
        runCatching {
            context.cacheDir.listFiles()?.forEach { child ->
                if (child.isDirectory) child.deleteRecursively() else child.delete()
            }
        }
    }
}

/**
 * Универсальный список-диалог мини-браузера: строка = (ключ, заголовок, подпись).
 * Тап по строке — открыть, «Удалить» — убрать из store.
 */
@Composable
fun WebListDialog(
    title: String,
    rows: List<Triple<String, String, String>>,
    onPick: (String) -> Unit,
    onDelete: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (rows.isEmpty()) Text("Пока пусто")
                rows.forEach { r ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { onPick(r.first) }) {
                            Column {
                                Text(r.second, maxLines = 2)
                                if (r.third.isNotBlank()) {
                                    Text(
                                        r.third,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                        if (onDelete != null) {
                            TextButton(onClick = { onDelete(r.first) }) { Text("Удалить") }
                        }
                    }
                }
            }
        },
    )
}
