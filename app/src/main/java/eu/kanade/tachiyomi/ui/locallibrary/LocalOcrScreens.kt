package eu.kanade.tachiyomi.ui.locallibrary

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.tachiyomi.data.tts.TtsSpeaker
import eu.kanade.tachiyomi.ui.overlay.OcrOverlayService
import eu.kanade.tachiyomi.util.system.toast
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.service.OcrPreferences
import mihon.domain.ocr.service.ScanRegion
import tachiyomi.presentation.core.components.SliderItem
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Выделенные экраны настроек локальной библиотеки: у каждого пункта свой
 * экран со своими слайдерами и цифрами (без дублей).
 */

// ---------- Каркас ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubSettingsScaffold(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    val navigator = LocalNavigator.currentOrThrow
    val backPress = LocalBackPress.current
    val goBack: () -> Unit = { backPress?.invoke() ?: navigator.pop() }
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = goBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                }
            },
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { content() }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun OptionsBlock(
    title: String,
    options: Map<String, String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    SectionTitle(title)
    options.forEach { (key, label) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(key) }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = key == selected, onClick = { onSelect(key) })
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

internal val ENGINE_TITLES = mapOf(
    OcrModel.CYRILLIC to "Кириллица (офлайн, точно)",
    OcrModel.MLKIT to "Google ML Kit (офлайн, встроена в APK)",
    OcrModel.FAST to "Быстрый (офлайн, для ARM)",
    OcrModel.LEGACY to "Старый (медленно)",
    OcrModel.TESSERACT to "Tesseract (полный офлайн)",
    OcrModel.GLENS to "Google Lens (онлайн)",
    OcrModel.ZEN_FREE to "Space Bunny Free (OpenCode Zen, онлайн)",
    OcrModel.GOOGLE to "Gemini (онлайн, по ключу)",
    OcrModel.OPENROUTER to "OpenRouter (онлайн, по ключу)",
    OcrModel.OWOCR to "OwOCR (свой сервер)",
)

@Composable
private fun EngineOptions(
    selected: OcrModel,
    onSelect: (OcrModel) -> Unit,
) {
    SectionTitle("Технология распознавания")
    OcrModel.entries.forEach { model ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(model) }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = model == selected, onClick = { onSelect(model) })
            Text(
                text = ENGINE_TITLES[model] ?: model.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

object LocalGameSttScreen : Screen {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val prefs = rememberPrefs()
        val sttOn by prefs.sttEnabled().changes()
            .collectAsState(initial = prefs.sttEnabled().get())
        val sourceLang by prefs.sttSourceLang().changes()
            .collectAsState(initial = prefs.sttSourceLang().get())
        val translate by prefs.sttTranslate().changes()
            .collectAsState(initial = prefs.sttTranslate().get())
        val gender by prefs.gameVoiceGender().changes()
            .collectAsState(initial = prefs.gameVoiceGender().get())
        val gameEngine by prefs.gameOcrEngine().changes()
            .collectAsState(initial = prefs.gameOcrEngine().get())
        val audioGranted = OcrOverlayService.hasRecordAudio(context)

        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            context.toast(if (granted) "Микрофон разрешён" else "Без микрофона STT не работает")
        }

        SubSettingsScaffold(
            title = "Игры и STT",
            subtitle = "Английская речь игры → текст → русские голоса, в реальном времени.",
        ) {
            if (!audioGranted) {
                TextButton(
                    onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text("Дать доступ к микрофону")
                }
            }
            SwitchRow(
                title = "Слушать микрофон (STT)",
                subtitle = if (audioGranted) "Распознавание включается кнопкой 🎙 STT на плавающей кнопке" else "Сначала дайте доступ к микрофону",
                checked = sttOn && audioGranted,
                onChange = { prefs.sttEnabled().set(it) },
            )
            OptionsBlock(
                title = "Язык речи в игре",
                options = mapOf(
                    "en-US" to "Английский (обычно в играх)",
                    "ru-RU" to "Русский",
                    "ja-JP" to "Японский",
                    "de-DE" to "Немецкий",
                    "fr-FR" to "Французский",
                    "zh-CN" to "Китайский",
                    "ko-KR" to "Корейский",
                ),
                selected = sourceLang,
                onSelect = { prefs.sttSourceLang().set(it) },
            )
            SwitchRow(
                title = "Переводить в русский",
                subtitle = "Иначе озвучивается распознанный оригинал",
                checked = translate,
                onChange = { prefs.sttTranslate().set(it) },
            )
            OptionsBlock(
                title = "Голос игровых реплик",
                options = mapOf(
                    "auto" to "Авто",
                    "female" to "♀ Женский",
                    "male" to "♂ Мужской",
                    "narrator" to "🎙 Диктор",
                ),
                selected = gender,
                onSelect = { prefs.gameVoiceGender().set(it) },
            )
            EngineOptions(
                selected = gameEngine,
                onSelect = { prefs.gameOcrEngine().set(it) },
            )
        }
    }
}

@Composable
private fun rememberPrefs(): OcrPreferences {
    return androidx.compose.runtime.remember { Injekt.get<OcrPreferences>() }
}
