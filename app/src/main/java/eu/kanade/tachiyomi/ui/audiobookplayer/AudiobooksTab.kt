package eu.kanade.tachiyomi.ui.audiobookplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.home.HomeScreen
import kotlinx.coroutines.launch

/**
 * Тип-вкладка для контента «Книги» (Аудиокниги): справочник ссылок на реальные
 * сайты аудиокниг (открытие во встроенном браузере) + прямая ссылка на аудио.
 */
data object AudiobooksTab : Tab {

    private data class Site(val name: String, val desc: String, val url: String)

    private val audiobookSites = listOf(
        Site("ЛитРес", "Легальные аудиокниги: каталог", "https://www.litres.ru"),
        Site("АКнига", "Клуб аудиокниг онлайн", "https://akniga.org"),
        Site("Audioteka", "Аудиокниги на русском", "https://audioteka.ru"),
        Site("LibriVox", "Свободные аудиокниги (EN)", "https://librivox.org"),
    )

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 8u,
            title = "Аудиокниги",
            icon = rememberVectorPainter(Icons.Outlined.Headphones),
        )

    override suspend fun onReselect(navigator: Navigator) = Unit

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        var url by rememberSaveable { mutableStateOf("") }
        var title by rememberSaveable { mutableStateOf("") }
        val openSite: (Site) -> Unit = { site ->
            scope.launch { HomeScreen.openTab(HomeScreen.Tab.Browser(url = site.url, title = site.name)) }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    text = "Аудиокниги",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            items(audiobookSites, key = { it.url }) { site ->
                SiteCard(site = site, onClick = { openSite(site) })
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider()
                    Text("Открыть по прямой ссылке (плеер)", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Ссылка на аудио") },
                        placeholder = { Text("https://…/audio.mp3") },
                    )
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Название (необязательно)") },
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val link = url.trim()
                            val name = title.trim()
                            if (link.isNotEmpty()) {
                                navigator.push(
                                    AudiobookPlayerScreen(
                                        tracks = listOf(name to link),
                                        initialTrackIndex = 0,
                                        title = name,
                                    ),
                                )
                            }
                        },
                    ) {
                        Text("▶ Слушать")
                    }
                }
            }
        }
    }

    @Composable
    private fun SiteCard(site: Site, onClick: () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = onClick,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(site.name, style = MaterialTheme.typography.titleSmall)
                    Text(site.desc, style = MaterialTheme.typography.bodySmall)
                }
                Icon(
                    Icons.Outlined.OpenInNew,
                    contentDescription = "Открыть в браузере",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}