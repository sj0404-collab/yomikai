package eu.kanade.tachiyomi.ui.koma

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.reader.components.ChapterNavigator
import eu.kanade.presentation.reader.components.ChapterNavigatorType
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode

private val slideAnimSpec = tween<IntOffset>(200)
private val fadeAnimSpec = tween<Float>(150)

@Composable
fun KomaFullUI(
    visible: Boolean,
    mangaTitle: String?,
    chapterTitle: String?,
    currentPage: Int,
    totalPages: Int,
    readingMode: ReadingMode,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
    onPageChange: (Int) -> Unit,
    onOpenManga: () -> Unit,
    onShowSettings: () -> Unit,
) {
    // Верхняя панель
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(fadeAnimSpec) + slideInVertically(slideAnimSpec) { -it },
        exit = fadeOut(fadeAnimSpec) + slideOutVertically(slideAnimSpec) { -it }
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenManga() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(mangaTitle ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(chapterTitle ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                IconButton(onClick = onShowSettings) {
                    Icon(Icons.Outlined.Settings, "Настройки")
                }
            }
        }
    }

    // Нижняя панель
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(fadeAnimSpec) + slideInVertically(slideAnimSpec) { it },
        exit = fadeOut(fadeAnimSpec) + slideOutVertically(slideAnimSpec) { it },
        modifier = Modifier.align(Alignment.BottomCenter)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledIconButton(onClick = onPreviousChapter) {
                        Icon(Icons.Outlined.SkipPrevious, "Предыдущая глава")
                    }
                    Text("Страница ${currentPage + 1} из $totalPages")
                    FilledIconButton(onClick = onNextChapter) {
                        Icon(Icons.Outlined.SkipNext, "Следующая глава")
                    }
                }

                val navType = when (readingMode) {
                    ReadingMode.RIGHT_TO_LEFT -> ChapterNavigatorType.HORIZONTAL_RTL
                    ReadingMode.VERTICAL -> ChapterNavigatorType.VERTICAL_RIGHT
                    else -> ChapterNavigatorType.HORIZONTAL_LTR
                }

                ChapterNavigator(
                    navigatorType = navType,
                    currentPage = currentPage,
                    totalPages = totalPages,
                    onPageChange = onPageChange
                )
            }
        }
    }
}

@Composable
fun KomaFloatingButton(visible: Boolean, onClick: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = Modifier.padding(16.dp).align(Alignment.BottomEnd)
    ) {
        FloatingActionButton(onClick = onClick, containerColor = MaterialTheme.colorScheme.primary) {
            Icon(Icons.Filled.MenuBook, "Вернуться в KOMA")
        }
    }
}
