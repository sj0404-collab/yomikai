package eu.kanade.presentation.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min

@Composable
fun OcrSelectionOverlay(
    onCancel: () -> Unit,
    instructionText: AnnotatedString,
    startPoint: Offset?,
    endPoint: Offset?,
    shape: mihon.data.ocr.ScanShape = mihon.data.ocr.ScanShape.RECT,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onCancel() },
                )
            },
    ) {
        // Draw the selection rectangle
        if (startPoint != null && endPoint != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val left = min(startPoint.x, endPoint.x)
                val top = min(startPoint.y, endPoint.y)
                val width = abs(endPoint.x - startPoint.x)
                val height = abs(endPoint.y - startPoint.y)

                // Рамка/фигура БЕЗ заливки и с тонкими краями (запрос пользователя):
                // страница остаётся читаемой, виден только контур выбранной области.
                if (shape == mihon.data.ocr.ScanShape.RECT) {
                    drawRect(
                        color = Color.Transparent,
                        topLeft = Offset(left, top),
                        size = Size(width, height),
                    )
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(left, top),
                        size = Size(width, height),
                        style = Stroke(width = 2f),
                    )
                } else {
                    // Фигурная рамка (круг, ромб, …): путь вписан в выделенные границы.
                    val shapePath = shape
                        .buildPath(android.graphics.RectF(left, top, left + width, top + height))
                        .asComposePath()
                    drawPath(path = shapePath, color = Color.Transparent)
                    drawPath(path = shapePath, color = Color.White, style = Stroke(width = 2f))
                }
            }
        }

        // Instruction text
        if (startPoint == null) {
            Text(
                text = instructionText,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 32.dp),
            )
        }
    }
}
