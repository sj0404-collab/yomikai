package eu.kanade.tachiyomi.ui.screenshots

import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.util.Screen as YomikaiScreen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import mihon.data.ocr.OcrScreenshotBuffer
import mihon.data.ocr.OcrScreenshotEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Детальный экран одного скриншота.
 *
 * Два режима просмотра:
 *  1) Оверлей — Canvas рисует распознанные регионы в их координатах
 *     (нормализованные 0..1) на «странице»-заглушке. Текст виден
 *     в тех же позициях, что и на оригинальном изображении.
 *  2) Список — как было: текст + координаты + номера регионов.
 *
 * Кнопка «Diff» переключает на экран сравнения двух скриншотов.
 */
class ScreenshotDetailScreen(
    private val entryId: Long,
    private val compareWithId: Long? = null,
) : YomikaiScreen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val entries by OcrScreenshotBuffer.entries.collectAsState()
        val entry = entries.firstOrNull { it.id == entryId }
        val compareEntry = compareWithId?.let { id -> entries.firstOrNull { it.id == id } }
        val dateFormat = remember { SimpleDateFormat("HH:mm:ss, dd.MM", Locale.getDefault()) }
        var showDiff by remember { mutableStateOf(compareEntry != null) }

        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(
                        if (entry != null) "Стр. ${entry.pageIndex + 1} — ${dateFormat.format(Date(entry.timestamp))}"
                        else "Скриншот",
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        LocalNavigator.currentOrThrow.pop()
                    }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (compareEntry != null) {
                        IconButton(onClick = { showDiff = !showDiff }) {
                            Icon(Icons.Outlined.SwapHoriz, contentDescription = "Diff")
                        }
                    }
                },
            )

            if (entry == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Скриншот не найден", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Content
            }

            if (showDiff && compareEntry != null) {
                DiffView(
                    entryA = compareEntry,
                    entryB = entry,
                )
            } else {
                OverlayView(entry)
            }
        }
    }

    @Composable
    private fun OverlayView(entry: OcrScreenshotEntry) {
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

            // ===== Canvas-оверлей: рисуем регионы в координатах OCR =====
            Text(
                "Визуальный оверлей:",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            val regionColors = listOf(
                Color(0x8800E5FF), Color(0x88FF6D00), Color(0x88AA00FF),
                Color(0x8800E676), Color(0x88FFD600), Color(0x88FF1744),
            )

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            ) {
                val canvasW = size.width
                val canvasH = size.height

                // Сетка-подложка (лёгкая)
                for (i in 1..4) {
                    drawLine(
                        color = Color.LightGray.copy(alpha = 0.3f),
                        start = Offset(0f, canvasH * i / 5),
                        end = Offset(canvasW, canvasH * i / 5),
                        strokeWidth = 1f,
                    )
                    drawLine(
                        color = Color.LightGray.copy(alpha = 0.3f),
                        start = Offset(canvasW * i / 5, 0f),
                        end = Offset(canvasW * i / 5, canvasH),
                        strokeWidth = 1f,
                    )
                }

                val paint = android.graphics.Paint().apply {
                    textSize = 11.sp.toPx()
                    isAntiAlias = true
                    color = android.graphics.Color.DKGRAY
                }

                entry.regions.forEachIndexed { idx, region ->
                    val color = regionColors[idx % regionColors.size]
                    val x1 = region.left * canvasW
                    val y1 = region.top * canvasH
                    val w = (region.right - region.left) * canvasW
                    val h = (region.bottom - region.top) * canvasH

                    // Фон региона
                    drawRect(
                        color = color.copy(alpha = 0.25f),
                        topLeft = Offset(x1, y1),
                        size = Size(w, h),
                    )
                    // Рамка
                    drawRect(
                        color = color.copy(alpha = 0.7f),
                        topLeft = Offset(x1, y1),
                        size = Size(w, h),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f),
                    )
                    // Номер + текст
                    paint.color = android.graphics.Color.WHITE
                    drawContext.canvas.nativeCanvas.drawText(
                        "#${idx + 1}",
                        x1 + 3f,
                        y1 + 12f,
                        paint,
                    )
                    val textLines = region.text.chunked((w / 5f).toInt().coerceAtLeast(8))
                    paint.color = android.graphics.Color.DKGRAY
                    textLines.take(3).forEachIndexed { li, line ->
                        drawContext.canvas.nativeCanvas.drawText(
                            line,
                            x1 + 3f,
                            y1 + 24f + li * 13f,
                            paint,
                        )
                    }
                }
            }

            // ===== Текстовый список =====
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

    /**
     * Diff-вью: два скриншота бок о бок. Слева — A (старый), справа — B (новый).
     * Регионы, которых нет в A — подсвечены зелёным, в A, но нет в B — красным.
     * Совпадающие — серым.
     */
    @Composable
    private fun DiffView(entryA: OcrScreenshotEntry, entryB: OcrScreenshotEntry) {
        val regionColorsA = Color(0x88FF1744)  // красный — удалено
        val regionColorsB = Color(0x8800E676)  // зелёный — добавлено
        val regionColorsSame = Color(0x889E9E9E) // серый — совпадает

        val textsA = entryA.regions.map { it.text }.toSet()
        val textsB = entryB.regions.map { it.text }.toSet()

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
                Text("A: стр. ${entryA.pageIndex + 1}", style = MaterialTheme.typography.labelMedium, color = Color(0xFFFF1744))
                Text("B: стр. ${entryB.pageIndex + 1}", style = MaterialTheme.typography.labelMedium, color = Color(0xFF00E676))
                Text("Разница: ${textsB.size - textsA.size} регионов", style = MaterialTheme.typography.labelMedium)
            }

            Text(
                "Разница (A → B):",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            // Тексты, удалённые из A
            val removed = textsA - textsB
            if (removed.isNotEmpty()) {
                Text("Удалено (${removed.size}):", style = MaterialTheme.typography.labelMedium, color = Color(0xFFFF1744))
                removed.forEach { t ->
                    Text("− $t", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF1744))
                }
            }

            // Тексты, добавленные в B
            val added = textsB - textsA
            if (added.isNotEmpty()) {
                Text("Добавлено (${added.size}):", style = MaterialTheme.typography.labelMedium, color = Color(0xFF00E676))
                added.forEach { t ->
                    Text("+ $t", style = MaterialTheme.typography.bodySmall, color = Color(0xFF00E676))
                }
            }

            // Canvas-оверлей B с подсветкой diff
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            ) {
                val canvasW = size.width
                val canvasH = size.height

                entryB.regions.forEach { region ->
                    val color = if (region.text in textsA) regionColorsSame else regionColorsB
                    val x1 = region.left * canvasW
                    val y1 = region.top * canvasH
                    val w = (region.right - region.left) * canvasW
                    val h = (region.bottom - region.top) * canvasH

                    drawRect(
                        color = color.copy(alpha = 0.25f),
                        topLeft = Offset(x1, y1),
                        size = Size(w, h),
                    )
                    drawRect(
                        color = color.copy(alpha = 0.7f),
                        topLeft = Offset(x1, y1),
                        size = Size(w, h),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f),
                    )

                    val paint = android.graphics.Paint().apply {
                        textSize = 10.sp.toPx()
                        isAntiAlias = true
                    }
                    paint.color = android.graphics.Color.DKGRAY
                    val textLines = region.text.chunked((w / 5f).toInt().coerceAtLeast(8))
                    textLines.take(3).forEachIndexed { li, line ->
                        drawContext.canvas.nativeCanvas.drawText(
                            line,
                            x1 + 3f,
                            y1 + 14f + li * 12f,
                            paint,
                        )
                    }
                }
            }
        }
    }
}
