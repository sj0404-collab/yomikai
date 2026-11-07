package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import kotlin.math.roundToInt

/**
 * Ползунок страниц в стиле Koma: тонкая полоса внизу, по которой можно
 * перетащить бегунок и сразу прыгнуть на нужную страницу, не открывая меню
 * читалки.
 *
 * Переход выполняется по отпусканию ([onSeek]), а не на каждый пиксель
 * перетаскивания: иначе читалка дёргала бы загрузку страниц на каждом
 * движении пальца.
 */
@Composable
fun ReaderPageSlider(
    currentPage: Int,
    totalPages: Int,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (totalPages <= 1) return

    // Локальное значение, пока пользователь тащит бегунок, чтобы он не
    // «прыгал» обратно на текущую страницу во время перетаскивания.
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember(currentPage) { mutableStateOf(currentPage.toFloat()) }
    val shown = if (dragging) dragValue else currentPage.toFloat()

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxWidth(),
    ) {
        Slider(
            value = shown.coerceIn(1f, totalPages.toFloat()),
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                dragging = false
                onSeek(dragValue.roundToInt().coerceIn(1, totalPages))
            },
            valueRange = 1f..totalPages.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = Color(235, 235, 235),
                activeTrackColor = Color(235, 235, 235, 200),
                inactiveTrackColor = Color(235, 235, 235, 60),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )

        // Подпись с номером страницы, пока бегунок в руке.
        if (dragging) {
            Text(
                text = "${dragValue.roundToInt().coerceIn(1, totalPages)} / $totalPages",
                color = Color(235, 235, 235),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 28.dp),
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun ReaderPageSliderPreview() {
    TachiyomiPreviewTheme {
        ReaderPageSlider(currentPage = 10, totalPages = 69, onSeek = {})
    }
}
