package eu.kanade.tachiyomi.ui.locallibrary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.GTranslate
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.screen.SettingsDictionaryScreen
import eu.kanade.presentation.more.settings.screen.SettingsOcrScreen
import eu.kanade.presentation.more.settings.screen.SettingsOcrPluginsScreen
import eu.kanade.presentation.more.settings.screen.SettingsVoicePluginsScreen
import eu.kanade.presentation.reader.OcrBubbleSettingsDialog
import eu.kanade.tachiyomi.ui.overlay.OcrOverlayService

/**
 * Раздел «Настройки» внутри локальной библиотеки — объединяет настройки
 * распознавания, голоса и словарей в одном месте. Делегирует полные
 * экраны настроек уже существующим [SettingsOcrScreen] и др.
 */
object LocalSettingsScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        var showBubbleSettings by remember { mutableStateOf(false) }

        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Настройки") },
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
            ) {
                // ── Распознавание OCR ──────────────────────────────────
                item { SettingsHeader("Распознавание OCR") }
                item {
                    SettingsItem(
                        icon = Icons.Outlined.Tune,
                        title = "Настройки OCR (баблы)",
                        subtitle = "Тип контента, область сканирования, порядок чтения",
                    ) { showBubbleSettings = true }
                }
                item {
                    SettingsItem(
                        icon = Icons.Outlined.DocumentScanner,
                        title = "Полные настройки OCR",
                        subtitle = "Движки, языки Glens, история",
                    ) { navigator.push(SettingsOcrScreen) }
                }
                item {
                    SettingsItem(
                        icon = Icons.Outlined.Tune,
                        title = "Плагины распознавания",
                    ) { navigator.push(SettingsOcrPluginsScreen) }
                }

                // ── Озвучка и голоса ───────────────────────────────────
                item { SettingsHeader("Озвучка и голоса") }
                item {
                    SettingsItem(
                        icon = Icons.Outlined.RecordVoiceOver,
                        title = "Настройки голосов",
                        subtitle = "Движок TTS, голос, громкость",
                    ) { navigator.push(SettingsVoicePluginsScreen) }
                }

                // ── Словари ────────────────────────────────────────────
                item { SettingsHeader("Словари") }
                item {
                    SettingsItem(
                        icon = Icons.Outlined.DocumentScanner,
                        title = "Настройки словарей",
                        subtitle = "Предпочтительный словарь, способ показа",
                    ) { navigator.push(SettingsDictionaryScreen) }
                }
                item {
                    SettingsItem(
                        icon = Icons.Outlined.Tune,
                        title = "Правила распознавания (регион)",
                        subtitle = "Область по умолчанию и порядок чтения для сканера",
                    ) { showBubbleSettings = true }
                }
                item {
                    SettingsItem(
                        icon = Icons.Outlined.DocumentScanner,
                        title = "История авточтения и сканирования",
                    ) { navigator.push(SettingsOcrScreen) }
                }

                // ── Управление приложением ──────────────────────────────
                item { SettingsHeader("Управление") }
                item {
                    SettingsItem(
                        icon = Icons.Outlined.Tune,
                        title = "Настройки распознавания из сканера",
                        subtitle = "Сканер запускается в ридере — полные настройки OCR выше.",
                    ) { navigator.push(SettingsOcrScreen) }
                }
                // ── Оверлей для других приложений ────────────────────────
                item { SettingsHeader("Оверлей поверх приложений") }
                item {
                    SettingsItem(
                        icon = Icons.Outlined.RecordVoiceOver,
                        title = "Запустить OCR-оверлей",
                        subtitle = "Бабл над любым APK: текст из буфера обмена + озвучка",
                    ) {
                        if (!OcrOverlayService.canDrawOverlays(context)) {
                            OcrOverlayService.requestPermission(context)
                        } else {
                            OcrOverlayService.start(context)
                        }
                    }
                }
                item {
                    SettingsItem(
                        icon = Icons.Outlined.Tune,
                        title = "Остановить OCR-оверлей",
                    ) { OcrOverlayService.stop(context) }
                }
            }
        }

        // Диалог быстрых настроек OCR-баблов (содержание визуально
        // идентично OcrBubbleSettingsDialog из ридера, но без привязки
        // к MangaPageController — настройки применяются глобально).
        if (showBubbleSettings) {
            OcrBubbleSettingsDialog(
                onDismissRequest = { showBubbleSettings = false },
                onOpenFullSettings = {
                    showBubbleSettings = false
                    navigator.push(SettingsOcrScreen)
                },
                onReadingOrderChange = {}, // без привязки к ридеру — настройка пишется в OcrPreferences
            )
        }
    }

    @Composable
    private fun SettingsHeader(text: String) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
    }

    @Composable
    private fun SettingsItem(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        title: String,
        subtitle: String? = null,
        onClick: () -> Unit,
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 16.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
