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
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class FeedScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        FeedScreenContent(
            onNavigateUp = { navigator.pop() },
            onOpenSource = { sourceId, query -> navigator.push(BrowseSourceScreen(sourceId, query)) },
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
) {
    var loading by remember { mutableStateOf(true) }
    var entries by remember { mutableStateOf<List<FeedEntry>>(emptyList()) }
    var failedSources by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var selectedLang by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var retryKey by remember { mutableIntStateOf(0) }

    val sourceManager = remember { Injekt.get<SourceManager>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(retryKey) {
        loading = true
        failedSources = emptyList()
        withContext(Dispatchers.IO) {
            val sources = sourceManager.getOnlineSources()
                .filterIsInstance<CatalogueSource>()
                .filter { it.supportsLatest }
            val result = mutableListOf<FeedEntry>()
            val failures = mutableListOf<Pair<String, String>>()

            for (source in sources) {
                val page = runCatching {
                    withTimeout(15_000L) { source.getLatestUpdates(1) }
                }
                val lang = source.lang
                if (page.isSuccess) {
                    val mangas = page.getOrNull()?.mangas?.take(30) ?: emptyList()
                    if (mangas.isNotEmpty()) {
                        result += FeedEntry(source.id, source.name, lang, mangas)
                    }
                } else {
                    val err = page.exceptionOrNull()?.message ?: "Неизвестная ошибка"
                    failures += source.name to err
                    result += FeedEntry(source.id, source.name, lang, emptyList(), err)
                }
            }
            entries = result.sortedByDescending { it.mangas.size }
            failedSources = failures
            loading = false
        }
    }

    val availableLangs = remember(entries) {
        entries.map { it.lang }.distinct().sorted()
    }

    val filteredEntries = remember(entries, selectedLang, searchQuery) {
        entries.filter { entry ->
            (selectedLang == null || entry.lang == selectedLang) &&
                (searchQuery.isBlank() || entry.sourceName.contains(searchQuery, ignoreCase = true))
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
            loading -> {
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
