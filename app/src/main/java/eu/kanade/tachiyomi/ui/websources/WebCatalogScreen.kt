package eu.kanade.tachiyomi.ui.websources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.websources.WebSite
import eu.kanade.tachiyomi.data.websources.WebSiteCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Каталог сайтов-источников.
 *
 * Зачем отдельный экран: расширения для RU-сайтов живут недолго, а сами сайты —
 * живут (иногда переезжая на новый домен). Каталог показывает сайты по типам
 * контента, даёт поиск по каждому прямо в приложении, открывает страницу
 * новинок, проверяет доступность и позволяет один раз поправить домен или
 * шаблон поиска — без сборки расширения.
 */
class WebCatalogScreen(
    private val initialType: String = "MANGA",
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val prefs = remember { Injekt.get<SourcePreferences>() }
        val scope = rememberCoroutineScope()

        var type by remember { mutableStateOf(initialType) }
        var query by remember { mutableStateOf("") }
        var revision by remember { mutableStateOf(0) }
        var editing by remember { mutableStateOf<WebSite?>(null) }
        val status = remember { mutableStateMapOf<String, String>() }
        val sites = remember(type, revision) { WebSiteCatalog.ofType(prefs, type) }

        Scaffold(
            topBar = {
                AppBar(
                    title = "Сайты: поиск и новинки",
                    navigateUp = navigator::pop,
                    actions = {
                        IconButton(onClick = {
                            scope.launch {
                                status.clear()
                                withContext(Dispatchers.IO) {
                                    coroutineScope {
                                        sites.map { site ->
                                            async {
                                                val code = probe("https://${site.hosts.first()}/")
                                                status[site.id] = when {
                                                    code == null -> "нет ответа"
                                                    code in 200..399 -> "доступен"
                                                    code == 403 || code == 503 -> "Cloudflare · $code"
                                                    else -> "код $code"
                                                }
                                            }
                                        }.awaitAll()
                                    }
                                }
                            }
                        }) {
                            Icon(Icons.Outlined.Refresh, "Проверить доступность")
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Что искать на сайтах") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )

                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(
                        listOf(
                            "MANGA" to "Манга",
                            "ANIME" to "Аниме",
                            "RANOBE" to "Ранобэ",
                            "BOOKS" to "Книги",
                            "DRAMAS" to "Дорамы",
                        ),
                    ) { (id, title) ->
                        AssistChip(
                            onClick = { type = id },
                            label = { Text(title) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (type == id) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            ),
                        )
                    }
                }

                Text(
                    "Поиск открывается на самом сайте — поэтому редизайн сайта ничего не ломает. " +
                        "Если домен сменился, поправьте его карандашом: приложение запомнит новый адрес.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )

                LazyColumn(Modifier.fillMaxWidth()) {
                    items(sites, key = { it.id }) { site ->
                        val enabled = WebSiteCatalog.isEnabled(prefs, site.id)
                        Surface(color = MaterialTheme.colorScheme.surface) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(site.name, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            buildString {
                                                append(site.hosts.first())
                                                status[site.id]?.let { append(" · ").append(it) }
                                                if (site.hosts.size > 1) append(" · зеркал: ${site.hosts.size - 1}")
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Switch(
                                        checked = enabled,
                                        onCheckedChange = {
                                            WebSiteCatalog.setEnabled(prefs, site.id, it)
                                            revision++
                                        },
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    // «Сетка» — разбор выдачи в родные карточки,
                                    // «Сайт» — та же страница как есть, на случай
                                    // если вёрстка изменилась.
                                    TextButton(
                                        onClick = {
                                            if (query.isBlank()) {
                                                navigator.push(
                                                    eu.kanade.tachiyomi.ui.webview.WebViewScreen(
                                                        WebSiteCatalog.homeUrl(site), site.name,
                                                    ),
                                                )
                                            } else {
                                                navigator.push(WebSiteSearchScreen(site.id, query))
                                            }
                                        },
                                        enabled = enabled,
                                    ) { Text(if (query.isBlank()) "Открыть" else "Сетка") }
                                    TextButton(
                                        onClick = {
                                            navigator.push(WebSiteSearchScreen(site.id, "", mode = "latest"))
                                        },
                                        enabled = enabled,
                                    ) { Text("Новинки") }
                                    TextButton(
                                        onClick = {
                                            val url = WebSiteCatalog.searchUrl(site, query)
                                                ?: WebSiteCatalog.homeUrl(site)
                                            navigator.push(
                                                eu.kanade.tachiyomi.ui.webview.WebViewScreen(url, site.name),
                                            )
                                        },
                                        enabled = enabled,
                                    ) { Text("Сайт") }
                                    IconButton(onClick = { editing = site }) {
                                        Icon(Icons.Outlined.Edit, "Домен и шаблон поиска")
                                    }
                                    IconButton(onClick = { navigator.push(
                                        eu.kanade.tachiyomi.ui.webview.WebViewScreen(
                                            WebSiteCatalog.homeUrl(site), site.name,
                                        ),
                                    ) }) {
                                        Icon(Icons.Outlined.OpenInNew, "Открыть главную")
                                    }
                                }
                                if (site.note != null) {
                                    Text(
                                        site.note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }

        editing?.let { site ->
            EditSiteDialog(
                site = site,
                onDismiss = { editing = null },
                onSave = { host, searchTpl, itemSel, titleSel, coverSel ->
                    WebSiteCatalog.setHost(prefs, site.id, host)
                    WebSiteCatalog.setTemplate(prefs, site.id, searchTpl, null)
                    WebSiteCatalog.setSelectors(prefs, site.id, itemSel, titleSel, coverSel)
                    revision++
                    editing = null
                },
                onReset = {
                    WebSiteCatalog.clearHost(prefs, site.id)
                    WebSiteCatalog.clearTemplate(prefs, site.id)
                    WebSiteCatalog.clearSelectors(prefs, site.id)
                    revision++
                    editing = null
                },
            )
        }
    }

    private suspend fun probe(url: String): Int? = runCatching {
        withContext(Dispatchers.IO) {
            val client = Injekt.get<eu.kanade.tachiyomi.network.NetworkHelper>().client
            val request = okhttp3.Request.Builder()
                .url(url)
                .head()
                .build()
            client.newCall(request).execute().use { it.code }
        }
    }.getOrNull()
}

@Composable
private fun EditSiteDialog(
    site: WebSite,
    onDismiss: () -> Unit,
    onSave: (host: String, search: String, item: String, title: String, cover: String) -> Unit,
    onReset: () -> Unit,
) {
    var host by remember { mutableStateOf(site.hosts.first()) }
    var search by remember { mutableStateOf(site.search.orEmpty()) }
    var itemSelector by remember { mutableStateOf(site.itemSelector.orEmpty()) }
    var titleSelector by remember { mutableStateOf(site.titleSelector.orEmpty()) }
    var coverSelector by remember { mutableStateOf(site.coverSelector.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(site.name) },
        text = {
            Column {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Домен (например mangalib.org)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Шаблон поиска") },
                    supportingText = {
                        Text("{host} — домен, {q} — запрос. Пусто — открывать главную.")
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    "Разбор выдачи (CSS-селекторы). Пусто — работает эвристика " +
                        "по ссылкам и картинкам.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                OutlinedTextField(
                    value = itemSelector,
                    onValueChange = { itemSelector = it },
                    label = { Text("Карточка") },
                    placeholder = { Text(".media-manga, .media") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = titleSelector,
                    onValueChange = { titleSelector = it },
                    label = { Text("Название внутри карточки") },
                    placeholder = { Text("h4.media-heading a[href]") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = coverSelector,
                    onValueChange = { coverSelector = it },
                    label = { Text("Обложка внутри карточки") },
                    placeholder = { Text("img.manga-thumb") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (site.hosts.size > 1) {
                    Text(
                        "Зеркала: ${site.hosts.drop(1).joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(host, search, itemSelector, titleSelector, coverSelector)
            }) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text("Сбросить") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}
