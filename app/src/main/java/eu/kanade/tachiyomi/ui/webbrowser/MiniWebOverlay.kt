package eu.kanade.tachiyomi.ui.webbrowser

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Плавающий мини-плеер внутри приложения (v1.9.52).
 *
 * Вопреки системному PiP (который выносил ВСЮ активность целиком), здесь
 * выносится только живая web-вкладка: [BrowserTab.attachActiveWebView]
 * переносит WebView активной вкладки в это окно, а само приложение остаётся
 * полностью рабочим — можно листать вкладки и продолжать пользоваться другими
 * экранами. Окно можно перетаскивать, масштабировать (кнопки ±) и делать
 * полупрозрачным (слайдер). Кнопка ✕ возвращает вкладку в полноэкранный браузер.
 *
 * Рендерится в [HomeScreen] поверх любого таба.
 */
@Composable
fun MiniWebOverlay() {
    val open = WebStore.miniWebOpen.value
    if (!open) return
    var scale by remember { mutableStateOf(WebStore.miniWebScale.value) }
    var alpha by remember { mutableStateOf(WebStore.miniWebAlpha.value) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val baseWidth = 210
    val baseHeight = 330

    // Сохраняем масштаб/прозрачность (переживают пересоздание композиции).
    LaunchedEffect(scale, alpha) {
        WebStore.miniWebScale.value = scale
        WebStore.miniWebAlpha.value = alpha
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                .padding(end = 14.dp, bottom = 88.dp)
                .width((baseWidth * scale).dp)
                .height((baseHeight * scale).dp)
                .graphicsLayer { this.alpha = alpha.coerceIn(0.2f, 1f) }
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        offset += drag
                    }
                },
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Шапка: масштаб ±, прозрачность (слайдер), закрыть в полный браузер.
                Row(
                    modifier = Modifier.height(34.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    IconButton(
                        modifier = Modifier.size(28.dp),
                        onClick = { scale = (scale - 0.2f).coerceIn(0.5f, 2.5f) },
                    ) {
                        Icon(Icons.Outlined.ZoomOut, contentDescription = "Уменьшить", modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        modifier = Modifier.size(28.dp),
                        onClick = { scale = (scale + 0.2f).coerceIn(0.5f, 2.5f) },
                    ) {
                        Icon(Icons.Outlined.ZoomIn, contentDescription = "Увеличить", modifier = Modifier.size(18.dp))
                    }
                    Slider(
                        value = alpha,
                        onValueChange = { alpha = it },
                        valueRange = 0.3f..1f,
                        modifier = Modifier.weight(1f).height(28.dp),
                    )
                    IconButton(
                        modifier = Modifier.size(28.dp),
                        onClick = {
                            WebStore.miniWebOpen.value = false
                            WebStore.pipMode.value = false
                        },
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "Развернуть в полный браузер",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                // Живая web-вкладка.
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    MirrorWebViewHost(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** Хост, который переносит WebView активной вкладки в свой FrameLayout. */
@Composable
fun MirrorWebViewHost(modifier: Modifier = Modifier) {
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { ctx -> android.widget.FrameLayout(ctx) },
        update = { fl -> BrowserTab.attachActiveWebView(fl) },
        onRelease = { fl -> (fl as? android.widget.FrameLayout)?.removeAllViews() },
    )
}
