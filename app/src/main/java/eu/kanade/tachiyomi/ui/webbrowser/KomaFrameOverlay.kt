package eu.kanade.tachiyomi.ui.webbrowser

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import eu.kanade.tachiyomi.data.tts.AutoReadEngine

/**
 * Прозрачный оверлей распознанных реплик в браузере — тот же вид, что в
 * KOMA-читалке: тонкая рамка вокруг каждой реплики и номер рядом.
 *
 * Раньше здесь рисовалась «линейка чтения» из прежней читалки — широкие
 * скруглённые пятна без привязки к тексту: на скриншотах они лежали поверх
 * рисунка, а строка «ВЫ ЗНАЛИ, ЧТО НАША ЛЮБОВЬ…» оставалась без рамки вообще.
 *
 * Координаты реплик приходят нормализованными к КАДРУ (обрезанной зоне книги),
 * поэтому оверлей обязан занимать ровно область WebView и умножать на зону:
 * без этого рамки уезжают.
 *
 * Рисуется только контур и номер — заливки нет, страница видна полностью.
 */
@Composable
fun KomaFrameOverlay(
    regions: List<AutoReadEngine.FrameRegion>,
    currentText: String?,
    zone: android.graphics.RectF?,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFFFF8A3D),
    idle: Color = Color(0xFF4DD0E1),
) {
    if (regions.isEmpty()) return
    val zl = zone?.left ?: 0f
    val zt = zone?.top ?: 0f
    val zw = (zone?.right ?: 1f) - zl
    val zh = (zone?.bottom ?: 1f) - zt

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        val numberPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f * density
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            color = android.graphics.Color.WHITE
        }
        val badgePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        Canvas(modifier = Modifier.fillMaxSize()) {
            regions.forEach { region ->
                val box = region.box
                val w = (box.right - box.left) * zw
                val h = (box.bottom - box.top) * zh
                // Мусорные рамки (щель между кадрами, штрих) не рисуем: от них
                // на экране оставалась линия через всю страницу и бейдж.
                if (w < 0.02f || h < 0.012f) return@forEach
                val left = (zl + box.left * zw) * width
                val top = (zt + box.top * zh) * height
                val size = Size(w * width, h * height)
                val isCurrent = currentText != null && region.text == currentText
                val color = if (isCurrent) accent else idle
                drawRect(
                    color = color.copy(alpha = if (isCurrent) 0.95f else 0.55f),
                    topLeft = Offset(left, top),
                    size = size,
                    style = Stroke(width = if (isCurrent) 5f else 2.5f),
                )
                // Номер реплики — маленький бейдж в левом верхнем углу рамки.
                val label = region.index.toString()
                val textWidth = numberPaint.measureText(label)
                val badgeSide = textWidth + 10f
                badgePaint.color = android.graphics.Color.argb(
                    if (isCurrent) 240 else 170, 20, 20, 20,
                )
                drawContext.canvas.nativeCanvas.apply {
                    drawRect(left, top, left + badgeSide, top + 16f, badgePaint)
                    drawText(label, left + 5f, top + 12f, numberPaint)
                }
            }
        }
    }
}
