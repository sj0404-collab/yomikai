package eu.kanade.tachiyomi.ui.websources

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.websources.WebItem
import eu.kanade.tachiyomi.data.websources.WebSite
import eu.kanade.tachiyomi.data.websources.WebSiteCatalog
import eu.kanade.tachiyomi.data.websources.WebSiteScraper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Выдача сайта из каталога — в родной сетке.
 *
 * Раньше поиск по сайту открывал его страницу в WebView. Теперь есть два пути:
 * «Сетка» разбирает страницу в карточки (обложка, название), а «Сайт» открывает
 * ту же страницу в WebView — на случай, если вёрстка изменилась и разбор
 * перестал попадать в цель.
 */
class WebSiteSearchScreen(
    private val siteId: String,
    private val query: String,
    private val mode: String = "search",
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val prefs = remember { Injekt.get<SourcePreferences>() }
        val scope = rememberCoroutineScope()

        val site = remember(siteId) {
            WebSiteCatalog.all(prefs).firstOrNull { it.id == siteId }
        }

        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var items by remember { mutableStateOf<List<WebItem>>(emptyList()) }
        var attempt by remember { mutableStateOf(0) }

        val targetUrl = remember(site) {
            site?.let { s ->
                if (mode == "latest") {
                    WebSiteCatalog.latestUrl(s)
                } else {
                    WebSiteCatalog.searchUrl(s, query)
                }
            }
        }

        LaunchedEffect(targetUrl, attempt) {
            val url = targetUrl
            if (site == null || url == null) {
                loading = false
                error = "У сайта не задан адрес поиска"
                return@LaunchedEffect
            }
            loading = true
            error = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val client = Injekt.get<eu.kanade.tachiyomi.network.NetworkHelper>().client
                    val request = Request.Builder()
                        .url(url)
                        .header("Accept", "text/html,application/xhtml+xml")
                        .header(
                            "Accept-Language",
                            "ru,en;q=0.8",
                        )
                        .build()
                    val html = client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw java.io.IOException("HTTP ${response.code}")
                        }
                        response.body.string()
                    }
                    WebSiteScraper.parse(html, url, site)
                }
            }
            items = result.getOrDefault(emptyList())
            error = result.exceptionOrNull()?.let { e ->
                when {
                    e.message?.startsWith("HTTP 403") == true ->
                        "Сайт закрыт Cloudflare (403). Откройте во вкладке «Сайт» или включите авто-прокси."
                    e.message?.startsWith("HTTP") == true -> "Сайт ответил: ${e.message}"
                    else -> "Не удалось прочитать страницу: ${e.message ?: "неизвестная ошибка"}"
                }
            }
            loading = false
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = "${site?.name ?: siteId}: ${if (mode == "latest") "новинки" else query}",
                    navigateUp = navigator::pop,
                    actions = {
                        IconButton(onClick = { attempt++ }) {
                            Icon(Icons.Outlined.Refresh, "Обновить")
                        }
                        IconButton(onClick = {
                            targetUrl?.let { url ->
                                navigator.push(
                                    eu.kanade.tachiyomi.ui.webview.WebViewScreen(url, site?.name),
                                )
                            }
                        }) {
                            Icon(Icons.Outlined.OpenInNew, "Открыть страницу сайта")
                        }
                    },
                )
            },
        ) { padding ->
            when {
                loading -> Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            "Читаем выдачу ${site?.name ?: ""}…",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }

                items.isEmpty() -> Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Text(
                            error ?: "Сайт ничего не отдал по этому запросу.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Это не ошибка приложения: страницу разбирает сам сайт. " +
                                "Можно открыть её как есть — кнопка справа сверху.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        TextButton(onClick = { attempt++ }) { Text("Повторить") }
                    }
                }

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 12.dp,
                        bottom = 24.dp + padding.calculateBottomPadding(),
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = padding.calculateTopPadding()),
                ) {
                    items(items, key = { it.url }) { item ->
                        Column(
                            Modifier.clickable {
                                navigator.push(
                                    eu.kanade.tachiyomi.ui.webview.WebViewScreen(item.url, item.title),
                                )
                            },
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.72f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                if (item.cover != null) {
                                    AsyncImage(
                                        model = item.cover,
                                        contentDescription = item.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Text(
                                        item.title.take(1),
                                        style = MaterialTheme.typography.headlineMedium,
                                        modifier = Modifier.align(Alignment.Center),
                                    )
                                }
                            }
                            Text(
                                item.title,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
