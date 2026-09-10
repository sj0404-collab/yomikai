package eu.kanade.tachiyomi.ui.ranobe

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.MutableStateFlow
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.domain.source.service.SourceManager

data class RanobeReaderScreen(
    private val mangaUrl: String,
    private val mangaTitle: String,
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val sourceManager = remember { Injekt.get<SourceManager>() }
        var chapters by remember { mutableStateOf<List<Triple<Long, String, String>>>(emptyList()) }
        var selectedChapter by remember { mutableStateOf<Pair<Long, String>?>(null) }
        var chapterContent by remember { mutableStateOf("") }
        var isLoading by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(mangaTitle) },
                )
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                if (selectedChapter != null) {
                    Text(
                        text = selectedChapter?.second ?: "",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )

                    if (chapterContent.isNotBlank()) {
                        Text(
                            text = chapterContent,
                            style = MaterialTheme.typography.bodyLarge,
                            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
                        )
                    } else if (isLoading) {
                        Text(
                            text = "Загрузка...",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    Text(
                        text = "Выберите главу для чтения",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )

                    chapters.forEach { (id, number, title) ->
                        Text(
                            text = "Глава $number: $title",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
