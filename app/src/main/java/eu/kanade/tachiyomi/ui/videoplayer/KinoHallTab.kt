package eu.kanade.tachiyomi.ui.videoplayer

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.LiveTv
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
 * Тип-вкладка для контента «Аниме» и «Дорамы» (Кинозал): справочник ссылок
 * на реальные сайты/API, открывающиеся во встроенной вкладке-браузере, плюс
 * прямой ввод ссылки на видео в плеер. «Браузер» и «AI» тип не затрагивает.
 */
data object KinoHallTab : Tab {

    private data class Site(val name: String, val desc: String, val url: String)

    private val animeSites = listOf(
        Site("Anilibria", "Аниме онлайн + официальное API", "https://anilibria.tv"),
        Site("Shikimori", "База аниме/манги, поиск, расписание", "https://shikimori.one"),
        Site("Kodik", "Плеер и API для встраивания видео", "https://kodik.info"),
    )

    private val doramaSites = listOf(
        Site("DoramaLive", "Дорамы с субтитрами (онлайн)", "https://dorama.live"),
        Site("Dorama.kim", "Дорамы на русском", "https://dorama.kim"),
        Site("Kodik", "Плеер и API для видео", "https://kodik.info"),
    )

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 6u,
            title = "Кинозал",
            icon = rememberVectorPainter(Icons.Outlined.LiveTv),
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
                    text = "Кинозал: аниме и дорамы",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            item { Text("Аниме", style = MaterialTheme.typography.titleMedium) }
            items(animeSites, key = { it.url }) { site ->
                SiteCard(site = site, onClick = { openSite(site) })
            }
            item { Text("Дорамы", style = MaterialTheme.typography.titleMedium) }
            items(doramaSites, key = { it.url }) { site ->
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
                        label = { Text("Ссылка на видео") },
                        placeholder = { Text("https://…/video.m3u8 или .mp4") },
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
                            if (link.isNotEmpty()) {
                                navigator.push(VideoPlayerScreen(videoUrl = link, title = title.trim()))
                            }
                        },
                    ) {
                        Text("▶ Смотреть")
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