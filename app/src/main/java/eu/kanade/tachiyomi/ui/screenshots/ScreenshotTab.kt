package eu.kanade.tachiyomi.ui.screenshots

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import coil3.compose.AsyncImage
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.data.tts.TtsSpeaker
import eu.kanade.tachiyomi.ui.main.MainActivity
import mihon.data.ocr.OcrScreenshotBuffer
import mihon.data.ocr.OcrScreenshotEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Вкладка «Скриншоты» в нижней навигации.
 *
 * Показывает все скриншоты, сделанные во время авточтения. Каждая карточка —
 * миниатюра настоящего кадра (JPEG, если запись сохранила картинку) + номер
 * страницы + движок OCR, который распознал этот кадр, + время + превью текста.
 *
 * Нажатие на карточку открывает детальный экран с текстовым оверлеем.
 */
data object ScreenshotTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            return TabOptions(
                index = 8u,
                title = "Скриншоты",
                icon = rememberVectorPainter(Icons.Outlined.CameraAlt),
            )
        }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val entries by OcrScreenshotBuffer.entries.collectAsState()
        var showClearDialog = remember { androidx.compose.runtime.mutableStateOf(false) }

        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            TopAppBar(
                title = { Text("Скриншоты") },
                actions = {
                    if (entries.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog.value = true }) {
                            Icon(
                                Icons.Outlined.DeleteSweep,
                                contentDescription = "Очистить",
                            )
                        }
                    }
                },
            )

            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Outlined.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                        Text(
                            "Скриншоты появятся здесь",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Включите авточтение в читалке",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        ScreenshotCard(
                            entry = entry,
                            onClick = {
                                navigator.push(ScreenshotDetailScreen(entry.id))
                            },
                        )
                    }
                }
            }
        }

        if (showClearDialog.value) {
            AlertDialog(
                onDismissRequest = { showClearDialog.value = false },
                title = { Text("Очистить скриншоты?") },
                text = { Text("Все ${entries.size} скриншотов будут удалены.") },
                confirmButton = {
                    TextButton(onClick = {
                        OcrScreenshotBuffer.clear()
                        showClearDialog.value = false
                    }) {
                        Text("Очистить")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog.value = false }) {
                        Text("Отмена")
                    }
                },
            )
        }
    }
}

@Composable
private fun ScreenshotCard(
    entry: OcrScreenshotEntry,
    onClick: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Миниатюра настоящего кадра (если запись сохранила JPEG).
        if (entry.imagePath != null && File(entry.imagePath).exists()) {
            AsyncImage(
                model = File(entry.imagePath),
                contentDescription = null,
                modifier = Modifier
                    .size(width = 72.dp, height = 96.dp)
                    .clip(RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Fit,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Стр. ${entry.pageIndex + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = entry.engineUsed,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = dateFormat.format(Date(entry.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = entry.summaryText.take(120),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = "${entry.regions.size}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
