package eu.kanade.tachiyomi.ui.bookreader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.BrightnessHigh
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow

/**
 * Экран чтения книг/новелл (текстовый ридер без TTS по умолчанию).
 * Поддерживает: навигацию по главам, настройку размера шрифта и отступов.
 *
 * @param chapterTexts Список пар (название_главы, текст_главы)
 * @param initialChapterIndex Индекс начальной главы
 * @param title Название книги
 */
data class BookReaderScreen(
    val chapterTexts: List<Pair<String, String>>,
    val initialChapterIndex: Int = 0,
    val title: String = "",
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow

        var currentChapterIndex by remember {
            mutableIntStateOf(initialChapterIndex.coerceIn(0, chapterTexts.lastIndex.coerceAtLeast(0)))
        }
        var showChapterList by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        var fontSize by remember { mutableFloatStateOf(18f) }
        var lineSpacing by remember { mutableFloatStateOf(1.4f) }

        if (chapterTexts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Нет текста для чтения", style = MaterialTheme.typography.bodyLarge)
            }
            return
        }

        val currentText = chapterTexts.getOrNull(currentChapterIndex)?.second ?: ""
        val paragraphs = currentText.split(Regex("\n{2,}")).filter { it.isNotBlank() }

        Column(modifier = Modifier.fillMaxSize()) {
            // TopBar
            TopAppBar(
                title = {
                    Column {
                        Text(text = title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = chapterTexts.getOrNull(currentChapterIndex)?.first ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { showChapterList = !showChapterList }) {
                        Icon(Icons.AutoMirrored.Outlined.List, contentDescription = "Главы")
                    }
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Outlined.TextFields, contentDescription = "Настройки")
                    }
                },
            )

            // Список глав
            AnimatedVisibility(visible = showChapterList) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                ) {
                    itemsIndexed(chapterTexts) { index, (name, _) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentChapterIndex = index
                                    showChapterList = false
                                }
                                .background(
                                    if (index == currentChapterIndex)
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.surface,
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${index + 1}.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(32.dp),
                            )
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }

            // Настройки чтения
            AnimatedVisibility(visible = showSettings) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(16.dp),
                ) {
                    Text("Размер шрифта: ${fontSize.toInt()}sp", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = fontSize,
                        onValueChange = { fontSize = it },
                        valueRange = 12f..28f,
                        steps = 7,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Межстрочный: x${String.format("%.1f", lineSpacing)}", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = lineSpacing,
                        onValueChange = { lineSpacing = it },
                        valueRange = 1.0f..2.5f,
                        steps = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Текст главы
            Box(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy((lineSpacing * 8).dp)) {
                    paragraphs.forEach { paragraph ->
                        Text(
                            text = paragraph.trim(),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = fontSize.sp,
                                lineHeight = (fontSize * lineSpacing).sp,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // Навигация по главам (низ)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        if (currentChapterIndex > 0) currentChapterIndex--
                    },
                    enabled = currentChapterIndex > 0,
                ) {
                    Text("◀ Назад", style = MaterialTheme.typography.labelMedium)
                }

                Text(
                    text = "${currentChapterIndex + 1} / ${chapterTexts.size}",
                    style = MaterialTheme.typography.bodySmall,
                )

                IconButton(
                    onClick = {
                        if (currentChapterIndex < chapterTexts.lastIndex) currentChapterIndex++
                    },
                    enabled = currentChapterIndex < chapterTexts.lastIndex,
                ) {
                    Text("Вперёд ▶", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
