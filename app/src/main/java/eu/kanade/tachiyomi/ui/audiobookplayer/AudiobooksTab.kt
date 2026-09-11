package eu.kanade.tachiyomi.ui.audiobookplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab

/**
 * Тип-вкладка для контента «Книги»: аудиокниги. Вставьте ссылку на аудио
 * и слушайте. Показывается в нижней навигации только когда выбран тип
 * «Книги» (Аудиокниги).
 */
data object AudiobooksTab : Tab {

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
        var url by rememberSaveable { mutableStateOf("") }
        var title by rememberSaveable { mutableStateOf("") }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Аудиокниги",
                style = MaterialTheme.typography.titleLarge,
            )
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
            Text(
                text = "Список источников и каталога — во вкладке «Обзор».",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}