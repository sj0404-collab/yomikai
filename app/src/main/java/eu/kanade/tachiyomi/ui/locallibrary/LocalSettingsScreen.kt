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
import androidx.compose.material.icons.outlined.SportsEsports
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.screen.OcrHistoryDialog
import eu.kanade.presentation.more.settings.screen.OcrVocabularyDialog
import eu.kanade.presentation.more.settings.screen.SettingsDictionaryScreen
import eu.kanade.presentation.more.settings.screen.SettingsOcrPluginsScreen
import eu.kanade.presentation.more.settings.screen.SettingsOcrScreen
import eu.kanade.presentation.reader.OcrBubbleSettingsDialog

/**
 * Раздел «Настройки» внутри локальной библиотеки: у каждого пункта свой
 * экран со своими слайдерами и цифрами, без дублей.
 *
 *  • Распознавание: баблы (диалог), область и порядок, точная настройка,
 *    движки, полные настройки;
 *  • Голоса: общие TTS и отдельно озвучка книг;
 *  • Словари: словарь OCR, история, настройки словарей;
 *  • Игры: STT (речь → текст → русские голоса);
 *  • Приложения: плавающая кнопка-оверлей, область, своя технология.
 */
object LocalSettingsScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        var showBubbleSettings by remember { mutableStateOf(false) }
        var showVocabulary by remember { mutableStateOf(false) }
        var showHistory by remember { mutableStateOf(false) }

        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(title = { Text("Настройки") })

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
            ) {
                item { SettingsHeader("Распознавание OCR") }
                item {
                    SettingsItem(
                        icon = Icons.Outlined.Tune,
                        title = "Настройки OCR (баблы)",
                        subtitle = "Тип контента, область, порядок, язык",
                    ) { showBubbleSettings = true }
                }
                item {
                    SettingsItem(
                        icon = Icons.Outlined.DocumentScanner,
                        title = "Область и порядок",
                        subtitle = "Часть страницы и направление чтения",
                    ) { navigator.push(LocalOcrRegionScreen) }
                }
                item {
                    SettingsItem(
                        icon = Icons.Outlined.Tune,
                        title = "Точная настройка",
                        subtitle = "Пороги и лимиты детектора — слайдеры с цифрами",
                    ) { navigator.push(LocalOcrTuningScreen) }
                }
                item {
                    SettingsItem(
                        icon = Icons.Outlined.GTranslate,
                        title = "Движки распознавания",
                        subtitle = "Плагины, цепочки, языки Glens",
                    ) { navigator.push(SettingsOcrPluginsScreen) }
                }
                item {
                    SettingsItem(
                        icon = Icons.Outlined.DocumentScanner,
                        title = "Полные настройки OCR",
                        subtitle = "Всё остальное одним списком",
                    ) { navigator.push(SettingsOcrScreen) }
                }

                item { SettingsHeader("Озвучка и голоса") }
                item {
                    SettingsItem(
                        icon = Icons.Outlined.RecordVoiceOver,
                        title = "Голоса и озвучка",
                        subtitle = "Движок TTS, скорость и высота — слайдеры",
                    ) { navigator.push(LocalVoiceScreen) }
                }
                item {
                    SettingsItem(
                        icon = Icons.Outlined.RecordVoiceOver,
                        title = "Озвучка книг",
                        subtitle = "Свои скорость, высота и технология для книг",
                    ) { navigator.push(LocalBookVoiceScreen) }
                }

                item { SettingsHeader("Словари") }
                item {
                    SettingsItem(
                        icon = Icons.Outlined.Translate,
                        title = "Словарь OCR",
                        subtitle = "Свои слова для разбиения и кандидатов",
                    ) { showVocabulary = true }
                }
                item {
                    SettingsItem(
                        icon = Icons.Outlined.DocumentScanner,
                        title = "История",
                        subtitle = "Авточтение и сканирование: успехи и сбои",
                    ) { showHistory = true }
                }
                item {
                    SettingsItem(
                        icon = Icons.Outlined.Translate,
                        title = "Настройки словарей",
                        subtitle = "Словари перевода и показ результата",
                    ) { navigator.push(SettingsDictionaryScreen) }
                }

                item { SettingsHeader("Игры") }
                item {
                    SettingsItem(
                        icon = Icons.Outlined.SportsEsports,
                        title = "Игры и STT",
                        subtitle = "Речь → текст → русские голоса, своя технология",
                    ) { navigator.push(LocalGameSttScreen) }
                }

                item { SettingsHeader("Приложения") }
                item {
                    SettingsItem(
                        icon = Icons.Outlined.RecordVoiceOver,
                        title = "Оверлей приложений",
                        subtitle = "Плавающая кнопка, область, своя технология",
                    ) { navigator.push(LocalAppOverlayScreen) }
                }
            }
        }

        if (showBubbleSettings) {
            OcrBubbleSettingsDialog(
                onDismissRequest = { showBubbleSettings = false },
                onOpenFullSettings = {
                    showBubbleSettings = false
                    navigator.push(SettingsOcrScreen)
                },
                onReadingOrderChange = {},
            )
        }
        if (showVocabulary) {
            OcrVocabularyDialog(onDismiss = { showVocabulary = false })
        }
        if (showHistory) {
            OcrHistoryDialog(onDismiss = { showHistory = false })
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
        icon: ImageVector,
        title: String,
        subtitle: String? = null,
        onClick: () -> Unit,
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
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
