package eu.kanade.presentation.reader.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.tts.AutoReadEngine
import mihon.domain.ocr.model.OcrBoundingBox
import kotlin.math.roundToInt

/**
 * Значки 🔊 поверх реплик (баблов) читалки.
 *
 * Показываются по переключателю «значки озвучки» (иначе на странице тишина,
 * чтобы не мешать чтению). Дают два способа озвучить реплику:
 *  - **Значок на каждом бабле** ([perBubble]): маленькая кнопка 🔊 в правом
 *    верхнем углу рамки; тап — озвучить именно этот текст.
 *  - **Перетаскиваемый значок** ([draggable]): один значок перетаскивается,
 *    а на отпускании привязывается к ближайшему баблу и озвучивает его.
 *
 * Координаты баблов — нормализованные 0..1 относительно изображения. Здесь они
 * маппятся на размер компоновки (как у [AutoReadHighlight] по умолчанию). Если
 * страница letterbox-ится и значки смещаются, это известное допущение; точный
 * [imageRect] при желании можно прокинуть через параметр в будущем.
 */
@Composable
fun OcrBubbleVoiceOverlay(
    regions: List<AutoReadEngine.FrameRegion>,
    onSpeakRegion: (text: String, index: Int) -> Unit,
    modifier: Modifier = Modifier,
    perBubble: Boolean = true,
    draggable: Boolean = true,
    iconColor: Color = Color(0xFF00E5FF),
) {
    if (regions.isEmpty()) return
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().semantics { contentDescription = "Значки озвучки реплик" },
    ) {
        val maxW = maxWidth
        val maxH = maxHeight
        val iconSize = 30.dp

        if (perBubble) {
            regions.forEachIndexed { index, region ->
                val box = region.boundingBox
                if (region.text.isBlank() || !box.isValidForIcon()) return@forEachIndexed
                // Позиция правого верхнего угла рамки в dp внутри компоновки.
                val xDp = (box.left * maxW.value).dp
                val yDp = (box.top * maxH.value).dp
                val wDp = ((box.right - box.left) * maxW.value).dp
                Box(
                    modifier = Modifier
                        .offset(x = xDp + wDp - iconSize, y = yDp - iconSize / 3)
                        .size(iconSize)
                        .pointerInput(index, region.text, onSpeakRegion) {
                            detectTapGestures { onSpeakRegion(region.text, index) }
                        }
                        .semantics { contentDescription = "Озвучить реплику ${index + 1}: ${region.text.take(30)}" },
                    contentAlignment = Alignment.Center,
                ) {
                    SpeakerBadge(color = iconColor, size = iconSize)
                }
            }
        }

        if (draggable) {
            DraggableSpeakIcon(regions = regions, onSpeakRegion = onSpeakRegion, accent = iconColor)
        }
    }
}

/** Круглая кнопка-значок озвучки. */
@Composable
private fun SpeakerBadge(color: Color, size: Dp) {
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.85f),
        contentColor = Color.Black,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
        modifier = Modifier.size(size),
    ) {
        Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Outlined.RecordVoiceOver,
                contentDescription = null,
                modifier = Modifier.padding(size / 4f),
            )
        }
    }
}

/** Один перетаскиваемый значок: при отпускании привязывается к ближайшему баблу. */
@Composable
private fun DraggableSpeakIcon(
    regions: List<AutoReadEngine.FrameRegion>,
    onSpeakRegion: (String, Int) -> Unit,
    accent: Color,
) {
    var posX by remember { mutableStateOf(0f) }
    var posY by remember { mutableStateOf(0f) }
    var placed by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val pxW = with(density) { maxWidth.toPx() }
        val pxH = with(density) { maxHeight.toPx() }

        Box(
            modifier = Modifier
                .offset { if (!placed) IntOffset.Zero else IntOffset(posX.roundToInt(), posY.roundToInt()) }
                .size(36.dp)
                .pointerInput(pxW, pxH, regions, onSpeakRegion) {
                    detectDragGestures(
                        onDragStart = { placed = true },
                        onDrag = { change, drag ->
                            change.consume()
                            posX = (posX + drag.x).coerceIn(0f, pxW)
                            posY = (posY + drag.y).coerceIn(0f, pxH)
                        },
                        onDragEnd = {
                            val target = regions
                                .filter { it.text.isNotBlank() && it.boundingBox.isValidForIcon() }
                                .minByOrNull { r ->
                                    val cx = r.boundingBox.centerX() * pxW
                                    val cy = r.boundingBox.centerY() * pxH
                                    val dx = cx - (posX + 18f)
                                    val dy = cy - (posY + 18f)
                                    dx * dx + dy * dy
                                }
                            if (target != null) {
                                val idx = regions.indexOf(target)
                                onSpeakRegion(target.text, idx)
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            SpeakerBadge(color = accent, size = 36.dp)
        }
    }
}

/** Центр нормализованного бокса (0..1). */
private fun OcrBoundingBox.centerX(): Float = (left + right) / 2f
private fun OcrBoundingBox.centerY(): Float = (top + bottom) / 2f

/** Размер рамки достаточно велик и лежит в пределах изображения. */
private fun OcrBoundingBox.isValidForIcon(): Boolean =
    left >= -0.001f && top >= -0.001f && right <= 1.001f && bottom <= 1.001f &&
        (right - left) > 0.02f && (bottom - top) > 0.01f
