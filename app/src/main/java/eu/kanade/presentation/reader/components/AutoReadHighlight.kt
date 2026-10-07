package eu.kanade.presentation.reader.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 *
 * Компонент молчал с v1.9.44: три настройки подсветки и переключатель «показывать
 * номера реплик» ничего не делали, потому что здесь ничего не рисовалось.
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
    val showNumbers = prefs.showSpeechNumbers().get()

    // Рамка приходит в координатах СТРАНИЦЫ (0..1): авточтение возвращает
    // координаты кадра на страницу через pageBox. Без прямоугольника страницы
    // (поля сверху/снизу у манги, по бокам у вебтуна) считаем от всего окна.
    val box = region.box
    val drawn = engine?.frameRegions?.collectAsState()?.value.orEmpty()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val maxW = maxWidth
        val maxH = maxHeight
        val density = LocalDensity.current

        // Пиксели считаем сами: Modifier.offset(x, y) ждёт Dp, а рамка приходит
        // в долях страницы, и лишний перевод в dp путал бы масштаб.
        fun place(left: Float, top: Float, right: Float, bottom: Float): IntArray {
            val z = imageRect
            val zl = z?.left ?: 0f
            val zt = z?.top ?: 0f
            val zw = (z?.right ?: 1f) - zl
            val zh = (z?.bottom ?: 1f) - zt
            val x = zl + left * zw
            val y = zt + top * zh
            val w = ((right - left) * zw).coerceAtLeast(0.004f)
            val h = ((bottom - top) * zh).coerceAtLeast(0.004f)
            return intArrayOf(
                (x * maxW.value).roundToInt(),
                (y * maxH.value).roundToInt(),
                (w * maxW.value).roundToInt(),
                (h * maxH.value).roundToInt(),
            )
        }

        // План чтения и уже озвученные реплики — тусклыми кружками, чтобы видеть
        // «где я» и «что дальше», не перекрывая рисунок.
        drawn.forEach { frame ->
            // v1.9.135: вырожденные рамки (пустой текст, почти нулевая высота
            // на стыке кадров) отрисовывались артефактом «тонкая линия через
            // страницу + бейдж». Такие регионы не рисуем вовсе.
            if (frame.text.isBlank() ||
                frame.box.bottom - frame.box.top < 0.012f ||
                frame.box.right - frame.box.left < 0.02f
            ) return@forEach
            when (frame.state) {
                AutoReadEngine.FrameRegion.State.DONE,
                AutoReadEngine.FrameRegion.State.UPCOMING,
                -> {
                    val p = place(frame.box.left, frame.box.top, frame.box.right, frame.box.bottom)
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(p[0], p[1]) }
                            .width(p[2].dp)
                            .height(p[3].dp)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        accent.copy(alpha = if (frame.state == AutoReadEngine.FrameRegion.State.DONE) 0.04f else 0.02f),
                                        Color.Transparent,
                                    ),
                                ),
                                shape = RoundedCornerShape(percent = 50),
                            ),
                    )
                }

                AutoReadEngine.FrameRegion.State.CURRENT -> Unit
            }
        }

        val cur = place(box.left, box.top, box.right, box.bottom)
        val stroke = strokeWidth.dp
        val label = if (showNumbers) region.index.toString() else region.marks.trim()

        // v1.9.137: «баг с эллипсом». Текущая реплика порой приходила рамкой
        // во весь кадр (целый видимый регион скана): в стиле box/both она
        // рисовалась огранённым 50%-скруглением — ВЕРТИКАЛЬНЫЙ эллипс через
        // пол-экрана поверх арта, где текста нет. Такие гигантские рамки
        // бессмысленны — не рисуем ни контур, ни бейдж.
        val boxTall = box.bottom - box.top
        val boxWide = box.right - box.left
        if (boxTall > 0.6f || boxWide > 0.9f) return@BoxWithConstraints

        when (style) {
            // Мягкое пятно: прямоугольных рамок нет вовсе, только радиальный
            // круг по центру реплики.
            "bubble" -> Box(
                modifier = Modifier
                    .offset { IntOffset(cur[0], cur[1]) }
                    .width(cur[2].dp)
                    .height(cur[3].dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(accent.copy(alpha = 0.22f), Color.Transparent),
                        ),
                        shape = RoundedCornerShape(percent = 50),
                    ),
            )

            "underline" -> Box(
                modifier = Modifier
                    .offset { IntOffset(cur[0], cur[1] + cur[3]) }
                    .width(cur[2].dp)
                    .height(stroke),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(accent.copy(alpha = 0.55f), CircleShape),
                )
            }

            // "box" и "both" — рамка вокруг реплики; "both" добавляет пятно.
            else -> Box(
                modifier = Modifier
                    .offset { IntOffset(cur[0], cur[1]) }
                    .width(cur[2].dp)
                    .height(cur[3].dp)
                    .then(
                        if (style == "both") {
                            Modifier.background(
                                brush = Brush.radialGradient(
                                    colors = listOf(accent.copy(alpha = 0.14f), Color.Transparent),
                                ),
                                shape = RoundedCornerShape(percent = 50),
                            )
                        } else {
                            Modifier
                        },
                    )
                    // v1.9.137: прямоугольник со слегка скруглёнными углами
                    // (раньше RoundedCornerShape(percent = 50) = ЭЛЛИПС на
                    // длинных рамках — он же на скриншоте как «эллипс через
                    // весь экран там, где текста нет»).
                    .border(stroke, accent.copy(alpha = 0.65f), RoundedCornerShape(10.dp)),
            )
        }

        // Номер реплики — служебная метка, в озвучку не идёт, но читателю
        // показывает порядок чтения.
        if (showNumbers && label.isNotBlank()) {
            // Бейдж СЛЕВА-НАД рамкой: раньше он ложился ровно на левый край
            // рамки и перекрывал первую букву текста («2ЛЕНА СЕВЕС…», «2Мир»).
            // Выносим за границу рамки и не даём уйти за экран (кламп к 0).
            val bx = (cur[0] - 26.dp.value).roundToInt().coerceAtLeast(0)
            val by = (cur[1] - 22.dp.value).roundToInt().coerceAtLeast(0)
            val numberOffset = IntOffset(bx, by)
            Box(
                modifier = Modifier
                    .offset { numberOffset }
                    .background(accent.copy(alpha = 0.85f), CircleShape)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = label,
                    color = Color.Black,
                    fontSize = 11.sp,
                )
            }
        }
    }
}