package eu.kanade.presentation.reader.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.tts.AutoReadEngine
import mihon.domain.ocr.service.OcrPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.roundToInt

/**
 * «Линейка чтения»: подсветка текущей озвучиваемой реплики.
 *
 * Вид настраивается (Настройки → Озвучка):
 * - [OcrPreferences.highlightColor] — цвет пятна/рамки/линии;
 * - [OcrPreferences.highlightStyle] — `bubble` (мягкие еле заметные кружки,
 *   по умолчанию), `box` (рамка), `underline` (подчёркивание) или `both`;
 * - [OcrPreferences.highlightWidth] — толщина в dp для box/underline.
 *
 * В режиме `bubble` никакие прямоугольники не рисуются вообще: текущая
 * реплика — радиальное пятно-круг, сходящее к нулю на краях, а история и
 * план чтения — совсем тусклые кружки. Промах бокса при таком пятне не
 * режет глаз, в отличие от жёсткой рамки.
 *
 * Номер реплики показывается рядом с рамкой: он нужен глазами, чтобы видеть
 * порядок чтения, но в озвучку не попадает (снимается SpeechMarkup.strip).
 */
@Composable
fun AutoReadHighlight(
    region: AutoReadEngine.SpokenRegion,
    modifier: Modifier = Modifier,
    engine: AutoReadEngine? = null,
    /** The actual displayed image rect within the parent (0..1 normalized).
     *  When null, falls back to the full composable area (may be wrong
     *  with letterboxed images). Set this from ReaderPageImageView.displayedImageLocalRect(). */
    imageRect: android.graphics.RectF? = null,
) {
    val prefs = remember { Injekt.get<OcrPreferences>() }
    // Не кэшируем навсегда: пользователь меняет цвет в настройках — рамки
    // перекрашиваются со следующей реплики, без перезапуска читалки
    val accent = Color(prefs.highlightColor().get().toULong().toLong())
    val style = prefs.highlightStyle().get()
    val strokeWidth = prefs.highlightWidth().get().coerceIn(1f, 12f)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val density = LocalDensity.current

        // Карта кадра: прочитанные (тускло), текущая (ярко), будущие (пунктир).
        // Видно и историю, и предстоящий план чтения.
        val frameRegions: List<AutoReadEngine.FrameRegion> = if (engine != null) {
            engine.frameRegions.collectAsState().value
        } else {
            emptyList()
        }
        val imgFr = imageRect
        val iwFr = if (imgFr != null) (imgFr.right - imgFr.left) * w else w
        val ihFr = if (imgFr != null) (imgFr.bottom - imgFr.top) * h else h
        val ixFr = if (imgFr != null) imgFr.left * w else 0f
        val iyFr = if (imgFr != null) imgFr.top * h else 0f
        // v1.9.43: БЕЗ ЗАЛИВКИ и без текста поверх страницы: история и план
        // чтения не рисуют ничего (реплики и так видны на странице), текущая
        // реплика — только контур рамки (ниже).
        for (fr in frameRegions) {
            if (fr.state != AutoReadEngine.FrameRegion.State.CURRENT) continue
        }

        val img = imageRect
        val iw = if (img != null) (img.right - img.left) * w else w
        val ih = if (img != null) (img.bottom - img.top) * h else h
        val ix = if (img != null) img.left * w else 0f
        val iy = if (img != null) img.top * h else 0f
        val mapped = engine?.mapToViewport(region.box) ?: region.box
        // Санитария: вырожденный бокс (узкая полоса во всю высоту) — мусор
        // маппинга, а не реплика: схлопываем в точку, рисовать нечего.
        val mappedSafe = if ((mapped.right - mapped.left) < 0.12f || (mapped.bottom - mapped.top) > 0.92f) {
            mihon.domain.ocr.model.OcrBoundingBox(
                left = mapped.left,
                top = mapped.top,
                right = mapped.left,
                bottom = mapped.top,
            )
        } else {
            mapped
        }
        val boxWidth = with(density) { ((mappedSafe.right - mappedSafe.left) * iw).toDp() }
        val boxHeight = with(density) { ((mappedSafe.bottom - mappedSafe.top) * ih).toDp() }
        val offsetModifier = Modifier.offset {
            IntOffset(
                (ix + mappedSafe.left * iw).roundToInt(),
                (iy + mappedSafe.top * ih).roundToInt(),
            )
        }

        // v1.9.43: текущая реплика = ТОЛЬКО контур рамки акцентным цветом
        // (без заливки, без текста поверх оригинала — текст уже на странице).
        if (boxWidth > 6.dp && boxHeight > 3.dp) {
            Box(
                modifier = offsetModifier
                    .width(boxWidth)
                    .height(boxHeight)
                    .border(
                        width = strokeWidth.dp,
                        color = accent,
                        shape = RoundedCornerShape(6.dp),
                    ),
            )
        }
    }
}
