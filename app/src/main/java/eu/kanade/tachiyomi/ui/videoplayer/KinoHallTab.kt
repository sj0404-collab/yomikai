package eu.kanade.tachiyomi.ui.videoplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LiveTv
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
 * Тип-вкладка для контента «Аниме» и «Дорамы»: точка входа в видеоплеер.
 * Показывается в нижней навигации только когда выбран соответствующий тип
 * контента; «Браузер» и «AI» тип не затрагивает.
 */
data object KinoHallTab : Tab {

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
        var url by rememberSaveable { mutableStateOf("") }
        var title by rememberSaveable { mutableStateOf("") }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Кинозал: аниме и дорамы",
                style = MaterialTheme.typography.titleLarge,
            )
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
            Text(
                text = "Список источников и каталога — во вкладке «Обзор».",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}