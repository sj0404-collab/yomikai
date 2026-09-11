package eu.kanade.tachiyomi.ui.ranobe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MenuBook
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
 * Тип-вкладка для контента «Ранобэ»: вставьте текст главы и читайте.
 * Показывается в нижней навигации только когда выбран тип «Ранобэ».
 */
data object RanobeTab : Tab {

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
        var title by rememberSaveable { mutableStateOf("") }
        var text by rememberSaveable { mutableStateOf("") }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Ранобэ: начните читать",
                style = MaterialTheme.typography.titleLarge,
            )
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
            Text(
                text = "Список источников и каталога — во вкладке «Обзор».",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}