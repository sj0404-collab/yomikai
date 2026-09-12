package eu.kanade.tachiyomi.ui.ranobe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MenuBook
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
 * Тип-вкладка для контента «Ранобэ»: справочник ссылок на реальные сайты
 * ранобэ (открытие во встроенном браузере) + вставка текста главы для чтения.
 */
data object RanobeTab : Tab {

    private data class Site(val name: String, val desc: String, val url: String)

    private val ranobeSites = listOf(
        Site("RanobeLib", "Ранобэ на русском: каталог и чтение", "https://ranobelib.me"),
        Site("LibRead", "Книги и ранобэ на русском", "https://libread.me"),
        Site("Novel Updates", "Индекс новелл и ранобэ (EN)", "https://www.novelupdates.com"),
        Site("ReadNovelFull", "Сборник новелл (EN)", "https://readnovelfull.com"),
    )

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 7u,
            title = "Ранобэ",
            icon = rememberVectorPainter(Icons.Outlined.MenuBook),
        )

    override suspend fun onReselect(navigator: Navigator) = Unit

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        var title by rememberSaveable { mutableStateOf("") }
        var text by rememberSaveable { mutableStateOf("") }
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
                    text = "Ранобэ",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            items(ranobeSites, key = { it.url }) { site ->
                SiteCard(site = site, onClick = { openSite(site) })
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider()
                    Text("Вставить текст главы (чтение)", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Название (необязательно)") },
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp),
                        label = { Text("Текст главы") },
                        placeholder = { Text("Вставьте текст главы…") },
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val body = text.trim()
                            val name = title.trim()
                            if (body.isNotEmpty()) {
                                navigator.push(
                                    RanobeReaderScreen(
                                        chapterTexts = listOf(name to body),
                                        initialChapterIndex = 0,
                                        title = name,
                                    ),
                                )
                            }
                        },
                    ) {
                        Text("Читать")
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