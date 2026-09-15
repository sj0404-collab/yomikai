package eu.kanade.tachiyomi.ui.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.presentation.util.Screen as YomikaiScreen
import mihon.data.ocr.OcrScreenshotBuffer
import mihon.data.ocr.OcrScreenshotEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Детальный экран одного скриншота.
 *
 * Показывает:
 *  - все распознанные регионы с текстом и координатами,
 *  - номер страницы, движок OCR, время захвата.
 *
 * В будущем (Commit 5) будет добавлен визуальный оверлей текста
 * поверх изображения страницы и функция diff между двумя скринами.
 */
class ScreenshotDetailScreen(private val entryId: Long) : YomikaiScreen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val entry = OcrScreenshotBuffer.entries.collectAsState().value
            .firstOrNull { it.id == entryId }
        val dateFormat = SimpleDateFormat("HH:mm:ss, dd.MM", Locale.getDefault())

        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(
                        if (entry != null) "Стр. ${entry.pageIndex + 1} — ${dateFormat.format(Date(entry.timestamp))}"
                        else "Скриншот"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        cafe.adriel.voyager.navigator.LocalNavigator.currentOrThrow.pop()
                    }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                    }
                },
            )

            if (entry == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Скриншот не найден", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Content
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Движок: ${entry.engineUsed}", style = MaterialTheme.typography.labelMedium)
                    Text("Регионов: ${entry.regions.size}", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "Размер: ${entry.imageWidth}×${entry.imageHeight}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }

                Text(
                    "Полный текст:",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    entry.summaryText,
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text(
                    "Регионы:",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                entry.regions.forEach { region ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "#${region.order}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "[${String.format("%.2f", region.left)},${String.format("%.2f", region.top)}," +
                                "${String.format("%.2f", region.right)},${String.format("%.2f", region.bottom)}]",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            region.text,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
