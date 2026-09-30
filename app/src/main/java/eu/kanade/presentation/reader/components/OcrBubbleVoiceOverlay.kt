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
 * Значок 🔊 над репликами (баблами) читалки.
 *
 * Один-единственный бейдж: пока TTS озвучивает реплику ([isSpeaking] — true),
 * показывается ровно ОДИН значок у текущего бабла (справа сверху от его рамки),
 * а не россыпь значков по странице. Когда озвучка не идёт, значков на данных
 * нет — остаётся только перетаскиваемый значок для ручной озвучки реплики.
 *
 * Координаты баблов — нормализованные 0..1 относительно СТРАНИЦЫ, а страница
 * занимает лишь часть окна (поля сверху/снизу у манги, по бокам у вебтуна).
 * Поэтому [imageRect] — прямоугольник страницы в долях окна (его отдаёт
 * `Viewer.displayedPageRect()`): раньше координаты умножались на размер всего
 * окна, и значок уезжал с реплики. Без [imageRect] (например в браузере)
 * поведение прежнее — вся область.
 *
 * Если текущую реплику найти не удалось, значок НЕ рисуется: жёсткая привязка
 * к углу окна выглядела для читателя как «значок прыгает по странице».
 */
@Composable
fun OcrBubbleVoiceOverlay(
    regions: List<AutoReadEngine.FrameRegion>,
    onSpeakRegion: (text: String, index: Int) -> Unit,
    modifier: Modifier = Modifier,
    perBubble: Boolean = true,
    draggable: Boolean = true,
    iconColor: Color = Color(0xFF00E5FF),
    isSpeaking: Boolean = false,
    imageRect: android.graphics.RectF? = null,
) {
    if (regions.isEmpty()) return
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().semantics { contentDescription = "Бейдж озвучки реплик" },
    ) {
        val maxW = maxWidth
        val maxH = maxHeight
        val iconSize = 30.dp

        if (isSpeaking) {
            // ЕДИНЫЙ бейдж: один индикатор у реплики, которую TTS озвучивает
            // сейчас. Нет реплики или нет её рамки — не рисуем ничего.
            val current = regions.firstOrNull { it.state == AutoReadEngine.FrameRegion.State.CURRENT }
            val currentIndex = current?.let { regions.indexOf(it) } ?: -1
            val anchor = remember(current, imageRect, maxW, maxH) {
                current
                    ?.takeIf { it.text.isNotBlank() && it.box.isValidForIcon() }
                    ?.box
                    ?.iconAnchor(imageRect, maxW, maxH, iconSize)
            }
            if (current != null && currentIndex >= 0 && anchor != null) {
                Box(
                    modifier = Modifier
                        .offset(x = anchor.x, y = anchor.y)
                        .size(iconSize)
                        .pointerInput(currentIndex, current.text, onSpeakRegion) {
                            detectTapGestures { onSpeakRegion(current.text, currentIndex) }
                        }
                        .semantics {
                            contentDescription =
                                "Озвучить реплику ${current.index}: ${current.text.take(30)}"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    SpeakerBadge(color = iconColor, size = iconSize)
                }
            }
        } else if (draggable) {
            // Не звучит — только перетаскиваемый значок для ручной озвучки.
            DraggableSpeakIcon(
                regions = regions,
                onSpeakRegion = onSpeakRegion,
                accent = iconColor,
                imageRect = imageRect,
            )
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
    imageRect: android.graphics.RectF?,
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
                .pointerInput(pxW, pxH, regions, onSpeakRegion, imageRect) {
                    detectDragGestures(
                        onDragStart = { placed = true },
                        onDrag = { change, drag ->
                            change.consume()
                            posX = (posX + drag.x).coerceIn(0f, pxW)
                            posY = (posY + drag.y).coerceIn(0f, pxH)
                        },
                        onDragEnd = {
                            val target = regions
                                .filter { it.text.isNotBlank() && it.box.isValidForIcon() }
                                .minByOrNull { r ->
                                    // Расстояние до центра бабла считаем в тех же
                                    // координатах, где лежит значок, иначе он
                                    // притягивается не к тому тексту.
                                    val dx = r.box.pageCenterX(imageRect) * pxW - (posX + 18f)
                                    val dy = r.box.pageCenterY(imageRect) * pxH - (posY + 18f)
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

/** Положение левого верхнего угла значка относительно области композиции. */
private data class IconAnchor(val x: Dp, val y: Dp)

/**
 * Куда поставить значок для бабла.
 *
 * [imageRect] — прямоугольник страницы в долях (0..1) области: рамка реплики
 * откладывается внутри страницы, а не от краёв окна.
 */
private fun OcrBoundingBox.iconAnchor(
    imageRect: android.graphics.RectF?,
    maxWidth: Dp,
    maxHeight: Dp,
    iconSize: Dp,
): IconAnchor {
    val pageLeft = imageRect?.left ?: 0f
    val pageTop = imageRect?.top ?: 0f
    val pageWidth = pageWidthFraction(imageRect)
    val pageHeight = pageHeightFraction(imageRect)
    val leftDp = (pageLeft + left * pageWidth) * maxWidth.value
    val topDp = (pageTop + top * pageHeight) * maxHeight.value
    val widthDp = (right - left) * pageWidth * maxWidth.value
    return IconAnchor(
        x = leftDp.dp + widthDp.dp - iconSize,
        y = topDp.dp - iconSize / 3f,
    )
}

/** Центр бокса по X в долях области (0..1) — с учётом прямоугольника страницы. */
private fun OcrBoundingBox.pageCenterX(imageRect: android.graphics.RectF?): Float =
    (imageRect?.left ?: 0f) + centerX() * pageWidthFraction(imageRect)

/** Центр бокса по Y в долях области (0..1) — с учётом прямоугольника страницы. */
private fun OcrBoundingBox.pageCenterY(imageRect: android.graphics.RectF?): Float =
    (imageRect?.top ?: 0f) + centerY() * pageHeightFraction(imageRect)

private fun pageWidthFraction(imageRect: android.graphics.RectF?): Float =
    (imageRect?.width() ?: 1f).coerceAtLeast(0.0001f)

private fun pageHeightFraction(imageRect: android.graphics.RectF?): Float =
    (imageRect?.height() ?: 1f).coerceAtLeast(0.0001f)

/** Центр нормализованного бокса (0..1). */
private fun OcrBoundingBox.centerX(): Float = (left + right) / 2f
private fun OcrBoundingBox.centerY(): Float = (top + bottom) / 2f

/** Размер рамки достаточно велик и лежит в пределах изображения. */
private fun OcrBoundingBox.isValidForIcon(): Boolean =
    left >= -0.001f && top >= -0.001f && right <= 1.001f && bottom <= 1.001f &&
        (right - left) > 0.02f && (bottom - top) > 0.01f
