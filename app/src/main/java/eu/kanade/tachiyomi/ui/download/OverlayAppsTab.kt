package eu.kanade.tachiyomi.ui.download

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.overlay.InstalledApps

/**
 * Вкладка запуска оверлея поверх приложений телефона.
 *
 * Задумана не только для манги: оверлей нужен там, где текст есть, а читать
 * его нечем или нужен другой язык — игра на чужом языке, русский текст без
 * озвучки, субтитры к видео в реальном времени. Список поэтому общий, а не
 * «только манга».
 */
@Composable
fun OverlayAppsTab() {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<InstalledApps.AppsState>(InstalledApps.AppsState.Loading) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        apps = InstalledApps.loadState(context)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Выберите приложение — оверлей появится поверх него",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        OutlinedButton(
            onClick = {
                eu.kanade.tachiyomi.ui.overlay.OcrOverlayService.start(context)
            },
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Text("Запустить оверлей без приложения")
        }
        Spacer(Modifier.width(8.dp))

        when (val state = apps) {
            is InstalledApps.AppsState.Loading ->
                Text("Читаю список приложений…", modifier = Modifier.padding(16.dp))

            is InstalledApps.AppsState.Failed ->
                Text(
                    text = "Не удалось прочитать список: ${state.reason}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )

            is InstalledApps.AppsState.Ready -> {
                if (state.entries.isEmpty()) {
                    Text("Запускаемых приложений не найдено", modifier = Modifier.padding(16.dp))
                }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.entries, key = { it.packageName }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    // Сначала открываем приложение: оверлей
                                    // ложится поверх того, что видно, иначе он
                                    // повиснет над нашими же настройками.
                                    InstalledApps.launch(context, entry.packageName)
                                    eu.kanade.tachiyomi.ui.overlay.OcrOverlayService.start(context)
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            entry.icon?.let { icon ->
                                AppIcon(icon)
                                Spacer(Modifier.width(12.dp))
                            }
                            Text(
                                text = entry.label,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/**
 * Иконка приложения.
 *
 * `Icon()` умеет только [androidx.compose.ui.graphics.vector.ImageVector] и
 * Painter, а PackageManager отдаёт Drawable. Конвертируем в картинку сами:
 * тянуть accompanist ради одного значка незачем.
 */
@Composable
private fun AppIcon(drawable: android.graphics.drawable.Drawable) {
    val size = 36
    val bitmap = remember(drawable) {
        runCatching {
            if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return@runCatching null
            android.graphics.Bitmap
                .createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, android.graphics.Bitmap.Config.ARGB_8888)
                .also { bmp ->
                    val canvas = android.graphics.Canvas(bmp)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                }
        }.getOrNull()
    }
    if (bitmap == null) {
        Spacer(Modifier.size(36.dp))
        return
    }
    androidx.compose.foundation.Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.size(36.dp),
    )
}
