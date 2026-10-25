package eu.kanade.presentation.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.util.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import androidx.compose.ui.platform.LocalContext
import eu.kanade.tachiyomi.data.FeedCache
import eu.kanade.tachiyomi.data.websources.WebSiteCatalog
import eu.kanade.domain.source.service.SourcePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class FeedScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        FeedScreenContent(
            onNavigateUp = { navigator.pop() },
            onOpenSource = { sourceId, query -> navigator.push(BrowseSourceScreen(sourceId, query)) },
            onOpenUrl = { url, title ->
                navigator.push(eu.kanade.tachiyomi.ui.webview.WebViewScreen(url, title))
            },
            onOpenSiteGrid = { siteId, query, mode ->
                navigator.push(eu.kanade.tachiyomi.ui.websources.WebSiteSearchScreen(siteId, query, mode))
            },
        )
    }
}

private data class FeedEntry(
    val sourceId: Long,
    val sourceName: String,
    val lang: String,
    val mangas: List<SManga>,
    val error: String? = null,
)

@Composable
private fun FeedScreenContent(
    onNavigateUp: () -> Unit,
    onOpenSource: (Long, String) -> Unit,
    onOpenUrl: (String, String?) -> Unit,
    onOpenSiteGrid: (String, String, String) -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var entries by remember { mutableStateOf<List<FeedEntry>>(emptyList()) }
    var failedSources by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var selectedLang by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var retryKey by remember { mutableIntStateOf(0) }

    val sourceManager = remember { Injekt.get<SourceManager>() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val catalogPrefs = remember { Injekt.get<SourcePreferences>() }
    val webSites = remember {
        WebSiteCatalog.all(catalogPrefs).filter { WebSiteCatalog.isEnabled(catalogPrefs, it.id) }
    }
    var siteFilter by remember { mutableStateOf<String?>(null) }

    // Сколько источников тянем одновременно. Раньше лента шла строго по одному
    // (до 15 секунд на источник) — при 20 источниках это минуты ожидания и
    // пустой экран, из-за чего лента выглядела «мёртвой».
    val parallel = 6

    LaunchedEffect(retryKey) {
        // Сразу показываем прошлый снимок — даже без сети лента не пустая.
        if (retryKey == 0) {
            val cached = withContext(Dispatchers.IO) { FeedCache.load(context) }
            if (cached.isNotEmpty()) {
                entries = cached.map { row ->
                    FeedEntry(
                        sourceId = row.sourceId,
                        sourceName = row.sourceName,
                        lang = row.lang,
                        mangas = row.titles.map { cachedManga(it) },
                        error = row.error,
                    )
                }.sortedByDescending { it.mangas.size }
                loading = false
            }
        }
        loading = entries.isEmpty()
        failedSources = emptyList()

        withContext(Dispatchers.IO) {
            val sources = sourceManager.getOnlineSources()
                .filterIsInstance<CatalogueSource>()
                .filter { it.supportsLatest }
            val gate = Semaphore(parallel)
            val failures = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
            val done = java.util.Collections.synchronizedList(mutableListOf<FeedCache.Row>())

            coroutineScope {
                sources.map { source ->
                    async {
                        gate.withPermit {
                            val page = runCatching {
                                withTimeout(20_000L) { source.getLatestUpdates(1) }
                            }
                            val mangas = page.getOrNull()?.mangas?.take(30).orEmpty()
                            val error = page.exceptionOrNull()?.let { it.message ?: "Неизвестная ошибка" }
                            if (error != null) failures += source.name to error
                            val entry = FeedEntry(source.id, source.name, source.lang, mangas, error)
                            // Публикуем сразу: пользователь видит источники по мере
                            // готовности, а не ждёт самый медленный.
                            if (mangas.isNotEmpty() || error != null) {
                                entries = (entries.filterNot { it.sourceId == source.id } + entry)
                                    .sortedByDescending { it.mangas.size }
                            }
                            done += FeedCache.Row(
                                sourceId = source.id,
                                sourceName = source.name,
                                lang = source.lang,
                                titles = mangas.map { it.title },
                                error = error,
                                at = System.currentTimeMillis(),
                            )
                        }
                    }
                }.awaitAll()
            }
            failedSources = failures.toList()
            FeedCache.save(context, done.toList())
        }
        loading = false
    }

    val availableLangs = remember(entries) {
        entries.map { it.lang }.distinct().sorted()
    }

    // Поиск по ленте: раньше сравнивалось ТОЛЬКО название источника, поэтому
    // «Берсерк» не находил ничего, хотя тайтл был на экране.
    val filteredEntries = remember(entries, selectedLang, searchQuery) {
        val q = searchQuery.trim()
        entries.mapNotNull { entry ->
            val bySource = q.isBlank() || entry.sourceName.contains(q, ignoreCase = true)
            val titles = if (q.isBlank() || bySource) {
                entry.mangas
            } else {
                entry.mangas.filter { it.title.contains(q, ignoreCase = true) }
            }
            when {
                bySource || titles.isNotEmpty() -> entry.copy(mangas = titles)
                else -> null
            }
        }.filter { selectedLang == null || it.lang == selectedLang }
    }

    val filteredSites = remember(webSites, siteFilter, searchQuery) {
        webSites.filter { site ->
            (siteFilter == null || site.type == siteFilter) &&
                (searchQuery.isBlank() || site.name.contains(searchQuery.trim(), ignoreCase = true))
        }
    }

    Scaffold(
        topBar = {
            AppBar(
                title = "Лента обновлений",
                navigateUp = onNavigateUp,
                actions = {
                    if (failedSources.isNotEmpty()) {
                        IconButton(onClick = { retryKey++ }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_refresh_24dp),
                                contentDescription = "Повторить",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                },
            )
        },
    ) { contentPadding ->
        when {
            // Полоска загрузки на весь экран — только пока показывать нечего.
            loading && entries.isEmpty() && filteredSites.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .padding(contentPadding)
                        .fillMaxWidth(),
                ) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "Загрузка ленты...",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            entries.isEmpty() -> {
                Text(
                    text = if (failedSources.isNotEmpty()) {
                        "Все источники вернули ошибки. Проверьте сеть и расширения."
                    } else {
                        "Нет источников с лентой обновлений. Установите расширения."
                    },
                    modifier = Modifier
                        .padding(contentPadding)
                        .padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            else -> {
                LazyColumn(contentPadding = contentPadding) {
                    // Поиск
                    item(key = "search") {
                        androidx.compose.material3.OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text("Поиск источника") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }

                    // ── Сайты (каталог) ──────────────────────────────────
                    // Расширения для RU-сайтов часто мертвы; здесь те же сайты
                    // открываются напрямую — поиск и новинки на самом сайте.
                    if (filteredSites.isNotEmpty()) {
                        item(key = "sites_header") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Сайты", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${filteredSites.size} сайтов · поиск и новинки открываются в приложении",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        item(key = "sites_row") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                item {
                                    AssistChip(
                                        onClick = { siteFilter = null },
                                        label = { Text("Все") },
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = if (siteFilter == null)
                                                MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceVariant,
                                        ),
                                    )
                                }
                                items(listOf("MANGA", "ANIME", "RANOBE", "BOOKS")) { type ->
                                    AssistChip(
                                        onClick = { siteFilter = if (siteFilter == type) null else type },
                                        label = {
                                            Text(
                                                when (type) {
                                                    "MANGA" -> "Манга"
                                                    "ANIME" -> "Аниме"
                                                    "RANOBE" -> "Ранобэ"
                                                    else -> "Книги"
                                                },
                                            )
                                        },
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = if (siteFilter == type)
                                                MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceVariant,
                                        ),
                                    )
                                }
                            }
                        }
                        items(filteredSites, key = { "site_${it.id}" }) { site ->
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(site.name, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            site.hosts.first(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(
                                        onClick = {
                                            if (searchQuery.isBlank()) {
                                                onOpenUrl(WebSiteCatalog.homeUrl(site), site.name)
                                            } else {
                                                onOpenSiteGrid(site.id, searchQuery, "search")
                                            }
                                        },
                                    ) { Text("Поиск") }
                                    TextButton(
                                        onClick = { onOpenSiteGrid(site.id, "", "latest") },
                                    ) { Text("Новинки") }
                                }
                                if (site.note != null) {
                                    Text(
                                        site.note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }

                    // Фильтр по языку
                    if (availableLangs.size > 1) {
                        item(key = "lang_filter") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                item {
                                    AssistChip(
                                        onClick = { selectedLang = null },
                                        label = { Text("Все") },
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = if (selectedLang == null)
                                                MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceVariant,
                                        ),
                                    )
                                }
                                items(availableLangs) { lang ->
                                    AssistChip(
                                        onClick = {
                                            selectedLang = if (selectedLang == lang) null else lang
                                        },
                                        label = { Text(lang.uppercase()) },
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = if (selectedLang == lang)
                                                MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceVariant,
                                        ),
                                    )
                                }
                            }
                        }
                    }

                    // Список источников
                    filteredEntries.forEach { entry ->
                        item(key = "header_${entry.sourceId}") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenSource(entry.sourceId, "") }
                                    .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.sourceName,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    if (entry.error != null) {
                                        Text(
                                            text = "Ошибка: ${entry.error.take(60)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    } else {
                                        Text(
                                            text = "${entry.mangas.size} обновлений · ${entry.lang.uppercase()}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (entry.error != null) {
                                    TextButton(onClick = {
                                        scope.launch { retryKey++ }
                                    }) {
                                        Text("Повтор")
                                    }
                                }
                            }
                        }
                        items(items = entry.mangas) { manga ->
                            Text(
                                text = manga.title,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenSource(entry.sourceId, manga.title) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }

                    // Футер
                    item {
                        val working = entries.count { it.error == null && it.mangas.isNotEmpty() }
                        val broken = entries.count { it.error != null }
                        Text(
                            text = buildString {
                                append("Работает: $working")
                                if (broken > 0) append(" · Ошибок: $broken")
                                append(" · Фильтр: ${filteredEntries.size}/${entries.size}")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(PaddingValues(16.dp)),
                        )
                    }
                }
            }
        }
    }
}

/** Тайтл из кэша ленты: ссылка не нужна, клик ищет по названию в источнике. */
private fun cachedManga(title: String): SManga = SManga.create().apply {
    this.title = title
    this.url = ""
}
