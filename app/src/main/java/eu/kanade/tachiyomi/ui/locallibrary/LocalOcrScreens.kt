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

private val ENGINE_TITLES = mapOf(
    OcrModel.CYRILLIC to "Кириллица (офлайн, точно)",
    OcrModel.FAST to "Быстрый (офлайн, для ARM)",
    OcrModel.LEGACY to "Старый (медленно)",
    OcrModel.TESSERACT to "Tesseract (полный офлайн)",
    OcrModel.GLENS to "Google Lens (онлайн)",
    OcrModel.ZEN_FREE to "Zen Free (онлайн, без ключа)",
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

// ---------- 1. Точная настройка (числа со слайдерами) ----------

object LocalOcrTuningScreen : Screen {

    @Composable
    override fun Content() {
        val prefs = rememberPrefs()
        SubSettingsScaffold(
            title = "Точная настройка",
            subtitle = "Переопределения пресетов детектора. «Сброс» возвращает значение пресету.",
        ) {
            TuningFloatRow(
                label = "Порог детектора",
                prefText = prefs.detectorThresholdOverride().get(),
                default = 0.20f,
                range = 1..95,
                format = { "%.2f".format(it / 100f) },
                onSet = { prefs.detectorThresholdOverride().set(it) },
            )
            TuningIntRow(
                label = "Мин. площадь области",
                prefText = prefs.minComponentAreaOverride().get(),
                default = 16,
                range = 4..512,
                onSet = { prefs.minComponentAreaOverride().set(it) },
            )
            TuningIntRow(
                label = "Макс. боксов со страницы",
                prefText = prefs.maxTextBoxesOverride().get(),
                default = 96,
                range = 8..512,
                onSet = { prefs.maxTextBoxesOverride().set(it) },
            )
            TuningFloatRow(
                label = "Фактор зазора слов",
                prefText = prefs.wordGapFactorOverride().get(),
                default = 1.7f,
                range = 100..300,
                format = { "%.2f".format(it / 100f) },
                onSet = { prefs.wordGapFactorOverride().set(it) },
            )
            TuningFloatRow(
                label = "Мин. уверенность",
                prefText = prefs.minAcceptConfidenceOverride().get(),
                default = 0.25f,
                range = 5..95,
                format = { "%.2f".format(it / 100f) },
                onSet = { prefs.minAcceptConfidenceOverride().set(it) },
            )
            TuningFloatRow(
                label = "Уверенность коротких реплик",
                prefText = prefs.shortTextConfidenceOverride().get(),
                default = 0.12f,
                range = 1..60,
                format = { "%.2f".format(it / 100f) },
                onSet = { prefs.shortTextConfidenceOverride().set(it) },
            )
            TuningFloatRow(
                label = "Мин. покрытие",
                prefText = prefs.minCoverageOverride().get(),
                default = 0.12f,
                range = 1..60,
                format = { "%.2f".format(it / 100f) },
                onSet = { prefs.minCoverageOverride().set(it) },
            )
            TuningIntRow(
                label = "Строк rescue-эшелона",
                prefText = prefs.rescueMaxLinesOverride().get(),
                default = 6,
                range = 0..32,
                onSet = { prefs.rescueMaxLinesOverride().set(it) },
            )
        }
    }

    @Composable
    private fun TuningFloatRow(
        label: String,
        prefText: String,
        default: Float,
        range: IntRange,
        format: (Int) -> String,
        onSet: (String) -> Unit,
    ) {
        val current = prefText.toFloatOrNull() ?: default
        val intValue = (current * 100).toInt().coerceIn(range.first, range.last)
        val isPreset = prefText.isBlank()
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            SliderItem(
                value = intValue,
                valueRange = range.first..range.last,
                label = label,
                valueString = (if (isPreset) "пресет " else "") + format(intValue),
                onChange = { onSet("%.2f".format(it / 100f)) },
            )
            if (!isPreset) {
                TextButton(onClick = { onSet("") }, modifier = Modifier.align(Alignment.End)) {
                    Text("Сброс")
                }
            }
        }
    }

    @Composable
    private fun TuningIntRow(
        label: String,
        prefText: String,
        default: Int,
        range: IntRange,
        onSet: (String) -> Unit,
    ) {
        val intValue = prefText.toIntOrNull() ?: default
        val isPreset = prefText.isBlank()
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            SliderItem(
                value = intValue.coerceIn(range.first, range.last),
                valueRange = range.first..range.last,
                label = label,
                valueString = (if (isPreset) "пресет " else "") + intValue.toString(),
                onChange = { onSet(it.toString()) },
            )
            if (!isPreset) {
                TextButton(onClick = { onSet("") }, modifier = Modifier.align(Alignment.End)) {
                    Text("Сброс")
                }
            }
        }
    }
}

// ---------- 2. Область и порядок ----------

object LocalOcrRegionScreen : Screen {

    @Composable
    override fun Content() {
        val prefs = rememberPrefs()
        val presetRegion by prefs.presetScanRegion().changes()
            .collectAsState(initial = prefs.presetScanRegion().get())
        val readingOrder by prefs.scanReadingOrder().changes()
            .collectAsState(initial = prefs.scanReadingOrder().get())
        val scanRegion by prefs.scanRegion().changes()
            .collectAsState(initial = prefs.scanRegion().get())
        SubSettingsScaffold(
            title = "Область и порядок",
            subtitle = "Какая часть страницы сканируется и в каком порядке читаются баблы.",
        ) {
            OptionsBlock(
                title = "Область по умолчанию",
                options = mapOf(
                    "full" to "Вся страница",
                    "top" to "Верхняя половина",
                    "bottom" to "Нижняя половина",
                ),
                selected = presetRegion,
                onSelect = { prefs.presetScanRegion().set(it) },
            )
            OptionsBlock(
                title = "Порядок чтения",
                options = mapOf(
                    "rtl" to "Справа налево (манга)",
                    "ltr" to "Слева направо (комиксы)",
                    "vertical" to "Сверху вниз (вебтун)",
                ),
                selected = readingOrder,
                onSelect = { prefs.scanReadingOrder().set(it) },
            )
            OptionsBlock(
                title = "Переопределение области",
                options = mapOf(
                    ScanRegion.FULL_PAGE.name to "Вся страница",
                    ScanRegion.TOP_HALF.name to "Верх 50%",
                    ScanRegion.BOTTOM_HALF.name to "Низ 50%",
                ),
                selected = scanRegion.name,
                onSelect = { prefs.scanRegion().set(ScanRegion.valueOf(it)) },
            )
        }
    }
}

// ---------- 3. Голоса и озвучка (общие) ----------

object LocalVoiceScreen : Screen {

    @Composable
    override fun Content() {
        val prefs = rememberPrefs()
        val engine by prefs.voiceEngine().changes()
            .collectAsState(initial = prefs.voiceEngine().get())
        val rate by prefs.speechRate().changes()
            .collectAsState(initial = prefs.speechRate().get())
        val pitch by prefs.speechPitch().changes()
            .collectAsState(initial = prefs.speechPitch().get())
        SubSettingsScaffold(
            title = "Голоса и озвучка",
            subtitle = "Общий движок и тембр для читалки манги и очереди OCR.",
        ) {
            OptionsBlock(
                title = "Движок TTS",
                options = mapOf(
                    TtsSpeaker.ENGINE_SYSTEM to "📱 Системный TTS (офлайн + онлайн)",
                    TtsSpeaker.ENGINE_REMOTE to "🖥 TTS-сервер (нейроголоса на ПК)",
                    TtsSpeaker.ENGINE_GOOGLE_WEB to "☁ Google Web (онлайн, без ключа)",
                    TtsSpeaker.ENGINE_EDGE_TTS to "☁ Edge TTS (онлайн, без ключа)",
                    TtsSpeaker.ENGINE_ELEVENLABS to "☁ ElevenLabs (онлайн, по ключу)",
                ),
                selected = engine,
                onSelect = { prefs.voiceEngine().set(it) },
            )
            SectionTitle("Тембр")
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                SliderItem(
                    value = (rate * 100).toInt().coerceIn(50, 200),
                    valueRange = 50..200,
                    label = "Скорость речи",
                    valueString = "×%.2f".format(rate),
                    onChange = { prefs.speechRate().set(it / 100f) },
                )
                Spacer(modifier = Modifier.height(8.dp))
                SliderItem(
                    value = (pitch * 100).toInt().coerceIn(50, 200),
                    valueRange = 50..200,
                    label = "Высота голоса",
                    valueString = "×%.2f".format(pitch),
                    onChange = { prefs.speechPitch().set(it / 100f) },
                )
            }
        }
    }
}

// ---------- 4. Озвучка книг (свои настройки) ----------

object LocalBookVoiceScreen : Screen {

    @Composable
    override fun Content() {
        val prefs = rememberPrefs()
        val rate by prefs.bookSpeechRate().changes()
            .collectAsState(initial = prefs.bookSpeechRate().get())
        val pitch by prefs.bookSpeechPitch().changes()
            .collectAsState(initial = prefs.bookSpeechPitch().get())
        val bookEngine by prefs.bookOcrEngine().changes()
            .collectAsState(initial = prefs.bookOcrEngine().get())
        SubSettingsScaffold(
            title = "Озвучка книг",
            subtitle = "Свои скорость, высота и технология для библиотеки книг.",
        ) {
            SectionTitle("Голос книг")
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                SliderItem(
                    value = (rate * 100).toInt().coerceIn(50, 300),
                    valueRange = 50..300,
                    label = "Скорость чтения книг",
                    valueString = "×%.2f".format(rate),
                    onChange = { prefs.bookSpeechRate().set(it / 100f) },
                )
                Spacer(modifier = Modifier.height(8.dp))
                SliderItem(
                    value = (pitch * 100).toInt().coerceIn(50, 200),
                    valueRange = 50..200,
                    label = "Высота голоса книг",
                    valueString = "×%.2f".format(pitch),
                    onChange = { prefs.bookSpeechPitch().set(it / 100f) },
                )
            }
            Text(
                text = "Конкретный голос выбирается в читалке книги (кнопка настроек).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            EngineOptions(
                selected = bookEngine,
                onSelect = { prefs.bookOcrEngine().set(it) },
            )
        }
    }
}

// ---------- 5. Игры и STT ----------

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

// ---------- 6. Оверлей приложений ----------

object LocalAppOverlayScreen : Screen {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val prefs = rememberPrefs()
        val regionMode by prefs.overlayRegionMode().changes()
            .collectAsState(initial = prefs.overlayRegionMode().get())
        val fixedRegion by prefs.overlayFixedRegion().changes()
            .collectAsState(initial = prefs.overlayFixedRegion().get())
        val showFrame by prefs.overlayShowFrame().changes()
            .collectAsState(initial = prefs.overlayShowFrame().get())
        val watchClip by prefs.overlayWatchClipboard().changes()
            .collectAsState(initial = prefs.overlayWatchClipboard().get())
        val appEngine by prefs.appOcrEngine().changes()
            .collectAsState(initial = prefs.appOcrEngine().get())

        SubSettingsScaffold(
            title = "Оверлей приложений",
            subtitle = "Плавающая кнопка поверх других APK: свои область и технология.",
        ) {
            SectionTitle("Питание")
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                TextButton(onClick = {
                    if (!OcrOverlayService.canDrawOverlays(context)) {
                        OcrOverlayService.requestPermission(context)
                    } else {
                        OcrOverlayService.start(context)
                    }
                }) { Text("Запустить") }
                TextButton(onClick = { OcrOverlayService.stop(context) }) { Text("Остановить") }
            }
            OptionsBlock(
                title = "Область",
                options = mapOf(
                    "auto" to "Авто — весь экран",
                    "manual" to "Вручную — выделить пальцем",
                    "fixed" to "Фикс — реплики игр, область не двигается",
                ),
                selected = regionMode,
                onSelect = {
                    prefs.overlayRegionMode().set(it)
                    OcrOverlayService.refresh(context)
                },
            )
            Text(
                text = if (fixedRegion.isBlank()) {
                    "Фиксированная область не задана — кнопка ✏ на панели оверлея."
                } else {
                    "Фикс: $fixedRegion"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            TextButton(
                onClick = {
                    if (!OcrOverlayService.canDrawOverlays(context)) {
                        OcrOverlayService.requestPermission(context)
                    } else {
                        OcrOverlayService.start(context)
                        OcrOverlayService.selectRegion(context)
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            ) { Text("Задать область пальцем") }
            SwitchRow(
                title = "Показывать рамку области",
                subtitle = "Тонкая рамка зафиксированной области поверх приложений",
                checked = showFrame,
                onChange = {
                    prefs.overlayShowFrame().set(it)
                    OcrOverlayService.refresh(context)
                },
            )
            SwitchRow(
                title = "Следить за буфером обмена",
                subtitle = "Новое скопированное озвучивается само",
                checked = watchClip,
                onChange = {
                    prefs.overlayWatchClipboard().set(it)
                    OcrOverlayService.refresh(context)
                },
            )
            EngineOptions(
                selected = appEngine,
                onSelect = { prefs.appOcrEngine().set(it) },
            )
        }
    }
}

@Composable
private fun rememberPrefs(): OcrPreferences {
    return androidx.compose.runtime.remember { Injekt.get<OcrPreferences>() }
}
