package eu.kanade.tachiyomi.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.current
import eu.kanade.presentation.more.settings.screen.SettingsAdvancedScreen
import eu.kanade.presentation.more.settings.screen.SettingsAiScreen
import eu.kanade.presentation.more.settings.screen.SettingsBrowseScreen
import eu.kanade.presentation.more.settings.screen.SettingsConstructorScreen
import eu.kanade.presentation.more.settings.screen.SettingsMainScreen
import eu.kanade.presentation.more.settings.screen.SettingsOcrPluginsScreen
import eu.kanade.presentation.more.settings.screen.SettingsOcrScreen
import eu.kanade.presentation.reader.OcrBubbleSettingsDialog
import eu.kanade.presentation.reader.TtsSettingsDialog
import eu.kanade.presentation.reader.TtsVoicePickerDialog
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.ai.AiAssistant
import eu.kanade.tachiyomi.data.ai.AiBackends
import eu.kanade.tachiyomi.data.tts.TtsSpeaker
import eu.kanade.tachiyomi.ui.locallibrary.ENGINE_TITLES
import eu.kanade.tachiyomi.ui.overlay.OcrOverlaySettingsScreen
import mihon.data.ocr.OcrContentType
import mihon.data.ocr.OcrLocalMode
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.service.OcrPreferences
import mihon.domain.ocr.service.ScanRegion
import tachiyomi.presentation.core.components.SliderItem
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * НАСТРОЙКИ-ХАБ: одна кнопка в читалке → один экран → разделы с обложками →
 * вкладки внутри раздела.
 *
 * Почему так: настроек стало много, а ходить за ними приходилось в четыре
 * разных места (плавающее меню читалки, «Ещё → Настройки», вкладка
 * «Локальная», экран оверлея), и читатель справедливо жаловался, что они
 * «везде, а толку ноль». Здесь всё, что относится к чтению, сведено в один
 * список разделов, а конкретные наборы настроек — во вкладки. Порядок
 * разделов повторяет приём вкладки «Локальная библиотека»: карточка с
 * обложкой, названием и одной строкой описания, тап открывает содержимое.
 *
 * Обложка — иконка на цветной подложке из темы (primary/secondary/tertiary
 * container), а не картинка из интернета: хаб должен открываться мгновенно и
 * одинаково выглядеть в любой теме.
 *
 * Что переиспользовано, а не продублировано:
 *  • OcrOverlaySettingsScreen  — оверлей целиком (разрешения, список
 *    приложений, служба доступности), в хабе только быстрые переключатели;
 *  • TtsSettingsDialog         — сетка голосов и словари ролей/интонаций;
 *  • TtsVoicePickerDialog      — выбор голоса книги;
 *  • OcrBubbleSettingsDialog   — быстрый набор параметров баблов;
 *  • SettingsOcrScreen / SettingsOcrPluginsScreen / SettingsAiScreen /
 *    SettingsAdvancedScreen / SettingsBrowseScreen / SettingsConstructorScreen
 *    — полные экраны, на которые ведут ссылки из вкладок.
 *
 * Состояние: значения лежат в OcrPreferences, поэтому переключение вкладок и
 * разделов ничего не теряет. Композируется только активная вкладка активного
 * раздела — тяжёлые списки голосов и движков не висят в памяти впустую.
 */
object SettingsHubScreen : Screen() {

    @Composable
    override fun Content() {
        SettingsHubContent()
    }
}

/**
 * Публичная точка входа как обычный composable: тот же экран можно встроить
 * в любое место, где есть [cafe.adriel.voyager.navigator.LocalNavigator]
 * (например, поверх читалки), без Voyager-экрана.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHubContent(modifier: Modifier = Modifier) {
    val navigator = LocalNavigator.current
    val backPress = LocalBackPress.current

    // Открыт ровно один раздел, у него выбрана одна вкладка. Индексы вкладок
    // храним по id раздела, чтобы вернувшись к разделу, получить ту же
    // вкладку, а не первую.
    var openSection by remember { mutableStateOf<String?>(null) }
    var tabIndexes by remember { mutableStateOf(emptyMap<String, Int>()) }

    val goBack: () -> Unit = {
        if (openSection != null) {
            openSection = null
        } else {
            // Хаб открывают и внутри читалки, где Voyager-стека нет: тогда
            // закрывать нечем, и это не ошибка, а обычный случай.
            val nav = navigator
            when {
                backPress != null -> backPress.invoke()
                nav != null && nav.canPop -> nav.pop()
                else -> Unit
            }
        }
    }

    val current = HUB_SECTIONS.firstOrNull { it.id == openSection }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = current?.title ?: "Настройки",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = goBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                }
            },
            actions = {
                if (current != null) {
                    IconButton(onClick = { openSection = null }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Свернуть раздел")
                    }
                }
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            item(key = "hub_intro") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = "Все настройки чтения — здесь",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Выберите раздел, потом вкладку. Настройки применяются сразу, " +
                            "перезапуск не нужен.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HUB_SECTIONS.forEach { section ->
                val isOpen = section.id == openSection
                item(key = "hub_card_${section.id}") {
                    HubSectionCard(
                        section = section,
                        expanded = isOpen,
                        onClick = {
                            // Повторный тап по открытому разделу сворачивает его:
                            // список разделов должен быть под рукой, а не за
                            // двумя экранами вложенности.
                            openSection = if (isOpen) null else section.id
                        },
                    )
                }
                if (isOpen) {
                    val tabIndex = (tabIndexes[section.id] ?: 0).coerceIn(0, section.tabs.lastIndex)
                    item(key = "hub_tabs_${section.id}") {
                        HubSectionTabs(
                            section = section,
                            selected = tabIndex,
                            onSelect = { index -> tabIndexes = tabIndexes + (section.id to index) },
                        )
                    }
                    item(key = "hub_body_${section.id}_$tabIndex") {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            section.tabs[tabIndex].content()
                        }
                    }
                }
            }

            item(key = "hub_footer") {
                Text(
                    text = "Пресеты движков и их названия берутся из общего реестра " +
                        "OCR, поэтому список не может разойтись с тем, что движок " +
                        "действительно умеет.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
        }
    }
}

// ───────────────────────── разделы и вкладки ─────────────────────────

/** Цвет подложки обложки раздела. Берём из темы, а не задаём константой. */
private enum class HubCover { PRIMARY, SECONDARY, TERTIARY }

private class HubTab(
    val title: String,
    val content: @Composable () -> Unit,
)

private class HubSection(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val cover: HubCover,
    val tabs: List<HubTab>,
)

private val HUB_SECTIONS: List<HubSection> = listOf(
    HubSection(
        id = "sound",
        title = "Звук",
        subtitle = "Озвучка, голоса, роли и музыка при авточтении",
        icon = Icons.Outlined.RecordVoiceOver,
        cover = HubCover.PRIMARY,
        tabs = listOf(
            HubTab("Озвучка") { SoundVoiceTab() },
            HubTab("Роли") { SoundRolesTab() },
            HubTab("Авточтение") { SoundAutoReadTab() },
            HubTab("Подсветка") { SoundHighlightTab() },
        ),
    ),
    HubSection(
        id = "ocr",
        title = "OCR",
        subtitle = "Распознавание, баблы, пресеты, порядок чтения и язык",
        icon = Icons.Outlined.DocumentScanner,
        cover = HubCover.SECONDARY,
        tabs = listOf(
            HubTab("Пресеты") { OcrPresetsTab() },
            HubTab("Область") { OcrRegionTab() },
            HubTab("Точные числа") { OcrTuningTab() },
            HubTab("Язык") { OcrLanguageTab() },
        ),
    ),
    HubSection(
        id = "books",
        title = "Книги",
        subtitle = "Формат файла, EPUB/текст/PDF, страница и озвучка книг",
        icon = Icons.Outlined.MenuBook,
        cover = HubCover.TERTIARY,
        tabs = listOf(
            HubTab("Формат") { BooksFormatTab() },
            HubTab("Страница") { BooksPageTab() },
            HubTab("Озвучка") { BooksVoiceTab() },
        ),
    ),
    HubSection(
        id = "other",
        title = "Прочее",
        subtitle = "Оверлей, агенты, экспериментальное, конструктор, источники",
        icon = Icons.Outlined.MoreVert,
        cover = HubCover.PRIMARY,
        tabs = listOf(
            HubTab("Оверлей") { OtherOverlayTab() },
            HubTab("Агенты") { OtherAgentsTab() },
            HubTab("Экспериментальное") { OtherExperimentalTab() },
            HubTab("Конструктор") { OtherConstructorTab() },
            HubTab("Источники") { OtherSourcesTab() },
        ),
    ),
)

// ───────────────────────── ЗВУК ─────────────────────────

/**
 * Озвучка: движок и всё, что нужно именно ему (ключ, адрес, язык голосов),
 * плюс тембр и пресеты голосов по полу.
 */
@Composable
private fun SoundVoiceTab() {
    val prefs = remember { Injekt.get<OcrPreferences>() }
    val navigator = LocalNavigator.current
    val engine by prefs.voiceEngine().changes().collectAsState(initial = prefs.voiceEngine().get())
    val rate by prefs.speechRate().changes().collectAsState(initial = prefs.speechRate().get())
    val pitch by prefs.speechPitch().changes().collectAsState(initial = prefs.speechPitch().get())
    val phoneOnly by prefs.voicePhoneOnly().changes()
        .collectAsState(initial = prefs.voicePhoneOnly().get())
    val webLang by prefs.ttsWebLanguage().changes()
        .collectAsState(initial = prefs.ttsWebLanguage().get())
    val edgeLang by prefs.edgeLanguage().changes()
        .collectAsState(initial = prefs.edgeLanguage().get())
    var showEditor by remember { mutableStateOf(false) }

    HubOptions(
        title = "Движок озвучки",
        options = listOf(
            TtsSpeaker.ENGINE_SYSTEM to "📱 Системный TTS — голоса телефона, офлайн",
            TtsSpeaker.ENGINE_REMOTE to "🖥 TTS-сервер — нейроголоса на ПК или ранере",
            TtsSpeaker.ENGINE_GOOGLE_WEB to "☁ Google Web — онлайн, без ключа",
            TtsSpeaker.ENGINE_EDGE_TTS to "☁ Edge TTS — онлайн, без ключа",
            TtsSpeaker.ENGINE_ELEVENLABS to "☁ ElevenLabs — онлайн, по ключу",
        ),
        selected = engine,
        onSelect = { prefs.voiceEngine().set(it) },
    )

    // Показываем только то, что относится к выбранному движку: ключ от
    // ElevenLabs в списке системного движка — шум, который читатель искал
    // именно в этих настройках.
    when (engine) {
        TtsSpeaker.ENGINE_REMOTE -> HubTextField(
            label = "Адрес TTS-сервера",
            value = prefs.remoteTtsUrl().get(),
            onChange = { prefs.remoteTtsUrl().set(it) },
            helper = "Запустите tools/remote_tts_server.py на ПК и укажите адрес, " +
                "например http://192.168.1.10:8788",
        )

        TtsSpeaker.ENGINE_ELEVENLABS -> {
            HubTextField(
                label = "Ключ ElevenLabs",
                value = prefs.elevenApiKey().get(),
                onChange = { prefs.elevenApiKey().set(it) },
            )
            HubTextField(
                label = "Голос ElevenLabs",
                value = prefs.elevenVoiceId().get(),
                onChange = { prefs.elevenVoiceId().set(it) },
                helper = "Пусто — голос по умолчанию аккаунта",
            )
        }

        TtsSpeaker.ENGINE_GOOGLE_WEB -> HubOptions(
            title = "Язык веб-озвучки",
            options = listOf(
                "ru" to "Русский",
                "en" to "Английский",
                "uk" to "Украинский",
                "de" to "Немецкий",
                "fr" to "Французский",
                "ja" to "Японский",
            ),
            selected = webLang,
            onSelect = { prefs.ttsWebLanguage().set(it) },
        )

        TtsSpeaker.ENGINE_EDGE_TTS -> {
            HubTextField(
                label = "Голос Edge TTS",
                value = prefs.edgeVoice().get(),
                onChange = { prefs.edgeVoice().set(it) },
                helper = "Например ru-RU-SvetlanaNeural или en-US-EmmaMultilingualNeural",
            )
            HubOptions(
                title = "Фильтр языка в списке голосов",
                options = listOf(
                    "auto" to "Как у выбранного голоса",
                    "" to "Все языки",
                    "🌐" to "🌐 Только мультиязычные",
                    "ru" to "Русский",
                    "en" to "Английский",
                    "de" to "Немецкий",
                    "ja" to "Японский",
                ),
                selected = edgeLang,
                onSelect = { prefs.edgeLanguage().set(it) },
            )
        }
    }

    HubHeader("Тембр")
    HubPercentSlider(
        label = "Скорость речи",
        value = rate,
        percentRange = 50..200,
        text = { "×%.2f".format(it / 100f) },
    ) { prefs.speechRate().set(it / 100f) }
    HubPercentSlider(
        label = "Высота голоса",
        value = pitch,
        percentRange = 50..200,
        text = { "×%.2f".format(it / 100f) },
    ) { prefs.speechPitch().set(it / 100f) }

    HubSwitchRow(
        title = "Только голос телефона",
        subtitle = "Игнорировать сетевые голоса: список и озвучка всегда совпадают",
        checked = phoneOnly,
    ) { prefs.voicePhoneOnly().set(it) }

    HubHeader("Голоса по полу говорящего")
    HubTextField(
        label = "Основной голос",
        value = prefs.voiceName().get(),
        onChange = { prefs.voiceName().set(it) },
        helper = "Пусто — голос по умолчанию движка",
    )
    HubTextField(
        label = "Женский голос",
        value = prefs.voiceFemale().get(),
        onChange = { prefs.voiceFemale().set(it) },
    )
    HubTextField(
        label = "Мужской голос",
        value = prefs.voiceMale().get(),
        onChange = { prefs.voiceMale().set(it) },
    )
    HubTextField(
        label = "Пакет системного TTS-движка",
        value = prefs.systemTtsEngine().get(),
        onChange = { prefs.systemTtsEngine().set(it) },
        helper = "Пусто — движок по умолчанию. Пример: com.google.android.tts",
    )

    HubLinkRow(
        icon = Icons.Outlined.GraphicEq,
        title = "Голоса, роли и интонации",
        subtitle = "Полный редактор: сетка голосов, словари ролей и интонаций",
        onClick = { showEditor = true },
    )

    // Словари ролей и интонаций уже написаны один раз в TtsSettingsDialog.
    // Дублировать их ради «ещё одной вкладки» нельзя: правки в двух местах
    // разъезжаются, и читатель не понимает, где править.
    if (showEditor) {
        TtsSettingsDialog(
            onDismissRequest = { showEditor = false },
            onOpenFullSettings = {
                showEditor = false
                navigator?.push(SettingsMainScreen)
            },
        )
    }
}

/** Роли: как раздаются голоса между рассказчиком и персонажами. */
@Composable
private fun SoundRolesTab() {
    val prefs = remember { Injekt.get<OcrPreferences>() }
    val navigator = LocalNavigator.current
    val mode by prefs.voiceMode().changes().collectAsState(initial = prefs.voiceMode().get())
    val narrator by prefs.narratorGender().changes()
        .collectAsState(initial = prefs.narratorGender().get())
    val perSpeaker by prefs.perSpeakerVoices().changes()
        .collectAsState(initial = prefs.perSpeakerVoices().get())
    val manual by prefs.manualVoiceMode().changes()
        .collectAsState(initial = prefs.manualVoiceMode().get())
    val manualGender by prefs.manualVoiceGender().changes()
        .collectAsState(initial = prefs.manualVoiceGender().get())
    val aiGender by prefs.aiGenderVoices().changes()
        .collectAsState(initial = prefs.aiGenderVoices().get())
    val presetGender by prefs.voicePresetGender().changes()
        .collectAsState(initial = prefs.voicePresetGender().get())
    val presetAge by prefs.voicePresetAge().changes()
        .collectAsState(initial = prefs.voicePresetAge().get())
    val maleEngine by prefs.voiceMaleEngine().changes()
        .collectAsState(initial = prefs.voiceMaleEngine().get())
    val femaleEngine by prefs.voiceFemaleEngine().changes()
        .collectAsState(initial = prefs.voiceFemaleEngine().get())
    val narratorEngine by prefs.voiceNarratorEngine().changes()
        .collectAsState(initial = prefs.voiceNarratorEngine().get())
    var showEditor by remember { mutableStateOf(false) }

    HubOptions(
        title = "Сколько голосов в сцене",
        options = listOf(
            "single" to "Один голос рассказчика",
            "dual" to "Два голоса: рассказчик и персонажи",
            "triple" to "Три голоса: рассказчик, женский, мужской",
        ),
        selected = mode,
        onSelect = { prefs.voiceMode().set(it) },
    )
    HubOptions(
        title = "Пол рассказчика",
        options = listOf("female" to "♀ Женский", "male" to "♂ Мужской", "auto" to "Авто"),
        selected = narrator,
        onSelect = { prefs.narratorGender().set(it) },
    )

    HubSwitchRow(
        title = "Разные голоса внутри одной сцены",
        subtitle = "Персонажи одного пола могут говорить разными голосами",
        checked = perSpeaker,
    ) { prefs.perSpeakerVoices().set(it) }

    HubSwitchRow(
        title = "Голос выбираю сам",
        subtitle = "Выключено — пол реплики определяется автоматически",
        checked = manual,
    ) { prefs.manualVoiceMode().set(it) }
    if (manual) {
        HubOptions(
            title = "Голос в ручном режиме",
            options = listOf("female" to "♀ Женский", "male" to "♂ Мужской"),
            selected = manualGender,
            onSelect = { prefs.manualVoiceGender().set(it) },
        )
    }

    HubSwitchRow(
        title = "AI определяет пол говорящего",
        subtitle = "Нужен ключ Gemini и сеть; выключено — пол берётся из текста",
        checked = aiGender,
    ) { prefs.aiGenderVoices().set(it) }

    HubOptions(
        title = "Пресет голоса",
        options = listOf(
            "auto" to "Пол — автоматически",
            "female" to "♀ Женский",
            "male" to "♂ Мужской",
            "neutral" to "⚥ Нейтральный",
        ),
        selected = presetGender,
        onSelect = { prefs.voicePresetGender().set(it) },
    )
    HubOptions(
        title = "Возраст голоса",
        options = listOf(
            "infant" to "Младенец",
            "child" to "Ребёнок",
            "teen" to "Подросток",
            "adult" to "Взрослый",
            "elderly" to "Пожилой",
        ),
        selected = presetAge,
        onSelect = { prefs.voicePresetAge().set(it) },
    )

    HubOptions(
        title = "Движок мужского голоса",
        options = hubEngineOptions(),
        selected = maleEngine,
        onSelect = { prefs.voiceMaleEngine().set(it) },
    )
    HubOptions(
        title = "Движок женского голоса",
        options = hubEngineOptions(),
        selected = femaleEngine,
        onSelect = { prefs.voiceFemaleEngine().set(it) },
    )
    HubOptions(
        title = "Движок голоса рассказчика",
        options = hubEngineOptions(),
        selected = narratorEngine,
        onSelect = { prefs.voiceNarratorEngine().set(it) },
    )

    HubLinkRow(
        icon = Icons.Outlined.GraphicEq,
        title = "Словари ролей и интонаций",
        subtitle = "Персонаж → голос, узор фразы → пауза и тон",
        onClick = { showEditor = true },
    )

    if (showEditor) {
        TtsSettingsDialog(
            onDismissRequest = { showEditor = false },
            onOpenFullSettings = {
                showEditor = false
                navigator?.push(SettingsMainScreen)
            },
        )
    }
}

/** Авточтение: музыка, автолистание, язык и перевод реплик. */
@Composable
private fun SoundAutoReadTab() {
    val prefs = remember { Injekt.get<OcrPreferences>() }
    val music by prefs.autoReadMusicEnabled().changes()
        .collectAsState(initial = prefs.autoReadMusicEnabled().get())
    val musicVolume by prefs.autoReadMusicVolume().changes()
        .collectAsState(initial = prefs.autoReadMusicVolume().get())
    val speed by prefs.autoReadWebtoonSpeed().changes()
        .collectAsState(initial = prefs.autoReadWebtoonSpeed().get())
    val autoStart by prefs.autoReadAutoStart().changes()
        .collectAsState(initial = prefs.autoReadAutoStart().get())
    val advance by prefs.autoReadAutoAdvance().changes()
        .collectAsState(initial = prefs.autoReadAutoAdvance().get())
    val icons by prefs.voiceIcons().changes().collectAsState(initial = prefs.voiceIcons().get())
    val autoScan by prefs.autoScanAndSpeak().changes()
        .collectAsState(initial = prefs.autoScanAndSpeak().get())
    val language by prefs.autoReadLanguage().changes()
        .collectAsState(initial = prefs.autoReadLanguage().get())
    val translate by prefs.autoReadTranslate().changes()
        .collectAsState(initial = prefs.autoReadTranslate().get())
    val target by prefs.translateTarget().changes()
        .collectAsState(initial = prefs.translateTarget().get())

    HubSwitchRow(
        title = "Фоновая музыка",
        subtitle = "Процедурные петли под настроение сцены; на время речи приглушаются",
        checked = music,
    ) { prefs.autoReadMusicEnabled().set(it) }
    if (music) {
        HubPercentSlider(
            label = "Громкость музыки",
            value = musicVolume,
            percentRange = 4..80,
            text = { "%.0f%%".format(it) },
        ) { prefs.autoReadMusicVolume().set(it / 100f) }
    }

    HubOptions(
        title = "Скорость автолистания",
        options = listOf(
            "phrase" to "Плавно пофразно",
            "slow" to "Медленно",
            "normal" to "Обычно",
            "fast" to "Быстрее",
            "max" to "Максимум",
        ),
        selected = speed,
        onSelect = { prefs.autoReadWebtoonSpeed().set(it) },
    )
    HubNote(
        when (speed) {
            "phrase" -> "После каждой реплики экран опускается ровно на её высоту — " +
                "следующая реплика уже внизу, границы кадров не перечитываются."
            "slow" -> "Шаг ~15% экрана, большое перекрытие кадров."
            "fast" -> "Шаг ~55% экрана, меньше перекрытия."
            "max" -> "Шаг ~80% экрана, почти без перекрытия."
            else -> "Шаг ~35% экрана с перекрытием — реплики на границе не пропускаются."
        },
    )

    HubSwitchRow(
        title = "Начинать чтение при открытии главы",
        subtitle = "Иначе чтение стартует только по кнопке",
        checked = autoStart,
    ) { prefs.autoReadAutoStart().set(it) }
    HubSwitchRow(
        title = "Листать после последней реплики кадра",
        subtitle = "В вебтуне — автоскролл на следующий кадр",
        checked = advance,
    ) { prefs.autoReadAutoAdvance().set(it) }
    HubSwitchRow(
        title = "Значки 🔊 на репликах",
        subtitle = "Показывать, что реплика уже озвучена",
        checked = icons,
    ) { prefs.voiceIcons().set(it) }
    HubSwitchRow(
        title = "Сканировать и озвучивать страницу целиком",
        subtitle = "Один снимок экрана и озвучка всей страницы без ожидания реплик",
        checked = autoScan,
    ) { prefs.autoScanAndSpeak().set(it) }

    HubOptions(
        title = "Язык реплик",
        options = listOf(
            "ru" to "Русский",
            "en" to "Английский",
            "ja" to "Японский",
            "ko" to "Корейский",
            "zh" to "Китайский",
            "any" to "Любой — не отбрасывать",
        ),
        selected = language,
        onSelect = { prefs.autoReadLanguage().set(it) },
    )
    HubSwitchRow(
        title = "Переводить реплики перед озвучкой",
        subtitle = "Нужен интернет; оригинал остаётся в тексте",
        checked = translate,
    ) { prefs.autoReadTranslate().set(it) }
    if (translate) {
        HubOptions(
            title = "Язык перевода",
            options = listOf(
                "ru" to "Русский",
                "en" to "Английский",
                "uk" to "Украинский",
                "de" to "Немецкий",
            ),
            selected = target,
            onSelect = { prefs.translateTarget().set(it) },
        )
    }
}

/** Подсветка реплик и скриншоты авточтения. */
@Composable
private fun SoundHighlightTab() {
    val prefs = remember { Injekt.get<OcrPreferences>() }
    val style by prefs.highlightStyle().changes()
        .collectAsState(initial = prefs.highlightStyle().get())
    val width by prefs.highlightWidth().changes()
        .collectAsState(initial = prefs.highlightWidth().get())
    val numbers by prefs.showSpeechNumbers().changes()
        .collectAsState(initial = prefs.showSpeechNumbers().get())
    val color by prefs.highlightColor().changes()
        .collectAsState(initial = prefs.highlightColor().get())
    val shots by prefs.autoScreenshotEnabled().changes()
        .collectAsState(initial = prefs.autoScreenshotEnabled().get())
    val shotIndicator by prefs.screenshotIndicatorEnabled().changes()
        .collectAsState(initial = prefs.screenshotIndicatorEnabled().get())
    val shotText by prefs.screenshotShowTextOverlay().changes()
        .collectAsState(initial = prefs.screenshotShowTextOverlay().get())
    val buffer by prefs.screenshotBufferSize().changes()
        .collectAsState(initial = prefs.screenshotBufferSize().get())

    HubOptions(
        title = "Вид подсветки",
        options = listOf(
            "bubble" to "Мягкое пятно",
            "box" to "Рамка",
            "underline" to "Подчёркивание",
            "both" to "Рамка и подчёркивание",
        ),
        selected = style,
        onSelect = { prefs.highlightStyle().set(it) },
    )
    HubIntSlider(
        label = "Толщина подсветки",
        value = width.toInt(),
        range = 1..10,
        text = { "$it dp" },
    ) { prefs.highlightWidth().set(it.toFloat()) }
    HubOptions(
        title = "Цвет подсветки",
        options = listOf(
            0xFF00E5FFL to "Бирюзовый",
            0xFFFF5252L to "Красный",
            0xFF69F0AEL to "Зелёный",
            0xFFFFD54FL to "Жёлтый",
            0xFFFFFFFFL to "Белый",
        ),
        selected = color,
        onSelect = { prefs.highlightColor().set(it) },
    )
    HubSwitchRow(
        title = "Номера реплик на странице",
        subtitle = "Видны глазами, но TTS их не произносит",
        checked = numbers,
    ) { prefs.showSpeechNumbers().set(it) }

    HubHeader("Скриншоты авточтения")
    HubSwitchRow(
        title = "Снимать кадр на каждом шаге",
        subtitle = "Скриншоты складываются в буфер на вкладке «Скриншоты»",
        checked = shots,
    ) { prefs.autoScreenshotEnabled().set(it) }
    HubSwitchRow(
        title = "Индикатор съёмки в углу",
        subtitle = "Пульсирующая точка и число областей",
        checked = shotIndicator,
    ) { prefs.screenshotIndicatorEnabled().set(it) }
    HubSwitchRow(
        title = "Текст поверх скриншота",
        subtitle = "Выключено — только изображение без рамок",
        checked = shotText,
    ) { prefs.screenshotShowTextOverlay().set(it) }
    if (shots) {
        HubIntSlider(
            label = "Размер буфера скриншотов",
            value = buffer,
            range = 10..200,
            text = { "${it} шт" },
        ) { prefs.screenshotBufferSize().set(it) }
    }
}

// ───────────────────────── OCR ─────────────────────────

/** Пресеты распознавания: тип контента, точность и движок. */
@Composable
private fun OcrPresetsTab() {
    val prefs = remember { Injekt.get<OcrPreferences>() }
    val navigator = LocalNavigator.current
    val contentType by prefs.contentType().changes().collectAsState(initial = prefs.contentType().get())
    val autoPreset by prefs.autoPreset().changes().collectAsState(initial = prefs.autoPreset().get())
    val localMode by prefs.localMode().changes().collectAsState(initial = prefs.localMode().get())
    val ruStress by prefs.ruStress().changes().collectAsState(initial = prefs.ruStress().get())
    val model by prefs.ocrModel().changes().collectAsState(initial = prefs.ocrModel().get())
    val fallbacks by prefs.useFallbackModels().changes()
        .collectAsState(initial = prefs.useFallbackModels().get())
    val fallbackPreset by prefs.fallbackPreset().changes()
        .collectAsState(initial = prefs.fallbackPreset().get())
    var showBubbles by remember { mutableStateOf(false) }

    val type = OcrContentType.fromId(contentType)
    HubOptions(
        title = "Тип контента",
        options = OcrContentType.entries.map { it.id to "${it.title} — ${it.hint}" },
        selected = contentType,
        onSelect = { prefs.contentType().set(it) },
    )
    HubNote("Выбрано: ${type.title}. ${type.hint}")

    HubOptions(
        title = "Автоподбор пресета",
        options = listOf(
            "on" to "Включён — по геометрии страницы",
            "off" to "Выключен — всегда выбранный пресет",
        ),
        selected = autoPreset,
        onSelect = { prefs.autoPreset().set(it) },
    )

    HubOptions(
        title = "Насколько дорого распознавать",
        options = OcrLocalMode.entries.map { it.id to "${it.title} — ${it.hint}" },
        selected = localMode,
        onSelect = { prefs.localMode().set(it) },
    )

    HubOptions(
        title = "Ударения в репликах",
        options = listOf(
            "on" to "Ставить ударения — нужно для RHVoice",
            "off" to "Не ставить",
        ),
        selected = ruStress,
        onSelect = { prefs.ruStress().set(it) },
    )

    HubOptions(
        title = "Движок распознавания",
        options = OcrModel.entries.map { it to (ENGINE_TITLES[it] ?: it.name) },
        selected = model,
        onSelect = { prefs.ocrModel().set(it) },
    )

    HubSwitchRow(
        title = "Пробовать другие движки при неудаче",
        subtitle = "Движок не прочитал — берём следующий по цепочке",
        checked = fallbacks,
    ) { prefs.useFallbackModels().set(it) }
    if (fallbacks) {
        HubOptions(
            title = "Порядок фолбэков",
            options = listOf(
                "auto" to "Умный: онлайн при сети, локальные без сети",
                "online" to "Только онлайн",
                "offline" to "Только локальные",
                "single" to "Без фолбэков",
            ),
            selected = fallbackPreset,
            onSelect = { prefs.fallbackPreset().set(it) },
        )
    }

    HubLinkRow(
        icon = Icons.Outlined.Tune,
        title = "Быстрые параметры баблов",
        subtitle = "Тип контента, область и порядок — коротким списком",
        onClick = { showBubbles = true },
    )
    HubLinkRow(
        icon = Icons.Outlined.ArrowForward,
        title = "Полные настройки распознавания",
        subtitle = "Весь список параметров движка",
        onClick = { navigator?.push(SettingsOcrScreen) },
    )
    HubLinkRow(
        icon = Icons.Outlined.AutoMode,
        title = "Плагины и цепочки движков",
        subtitle = "Установленные плагины, доступность по сети",
        onClick = { navigator?.push(SettingsOcrPluginsScreen) },
    )

    if (showBubbles) {
        OcrBubbleSettingsDialog(
            onDismissRequest = { showBubbles = false },
            onOpenFullSettings = {
                showBubbles = false
                navigator?.push(SettingsOcrScreen)
            },
        )
    }
}

/** Область страницы и порядок чтения. */
@Composable
private fun OcrRegionTab() {
    val prefs = remember { Injekt.get<OcrPreferences>() }
    val presetRegion by prefs.presetScanRegion().changes()
        .collectAsState(initial = prefs.presetScanRegion().get())
    val region by prefs.scanRegion().changes().collectAsState(initial = prefs.scanRegion().get())
    val order by prefs.scanReadingOrder().changes()
        .collectAsState(initial = prefs.scanReadingOrder().get())
    val shape by prefs.scanShape().changes().collectAsState(initial = prefs.scanShape().get())
    val remembered by prefs.rememberedScanRegion().changes()
        .collectAsState(initial = prefs.rememberedScanRegion().get())
    val navigator = LocalNavigator.current
    var showBubbles by remember { mutableStateOf(false) }

    HubOptions(
        title = "Область по умолчанию",
        options = listOf(
            "full" to "Вся страница",
            "top" to "Верхняя половина",
            "bottom" to "Нижняя половина",
        ),
        selected = presetRegion,
        onSelect = { prefs.presetScanRegion().set(it) },
    )
    HubOptions(
        title = "Порядок чтения",
        options = listOf(
            "rtl" to "Справа налево (манга)",
            "ltr" to "Слева направо (комиксы)",
            "vertical" to "Сверху вниз (вебтун)",
        ),
        selected = order,
        onSelect = { prefs.scanReadingOrder().set(it) },
    )
    HubOptions(
        title = "Область одной главы",
        subtitle = "Переопределение пресета, если у страницы своя разметка",
        options = listOf(
            ScanRegion.FULL_PAGE.name to "Вся страница",
            ScanRegion.TOP_HALF.name to "Верх 50%",
            ScanRegion.BOTTOM_HALF.name to "Низ 50%",
        ),
        selected = region.name,
        onSelect = { prefs.scanRegion().set(ScanRegion.valueOf(it)) },
    )
    HubOptions(
        title = "Форма рамки сканирования",
        options = listOf(
            "rect" to "Прямоугольник",
            "circle" to "Круг",
            "diamond" to "Ромб",
            "hexagon" to "Шестиугольник",
            "octagon" to "Восьмиугольник",
            "figure8" to "Восьмёрка",
            "free" to "Произвольная",
        ),
        selected = shape,
        onSelect = { prefs.scanShape().set(it) },
    )
    HubNote(
        if (remembered.isBlank()) {
            "Запомненная область не задана: удержание пальцем на странице выбирает " +
                "область один раз, дальше она переиспользуется."
        } else {
            "Запомненная область: $remembered. Сбросить — удержанием выберите новую."
        },
    )

    HubLinkRow(
        icon = Icons.Outlined.Tune,
        title = "Быстрые параметры баблов",
        subtitle = "Область и порядок в компактном виде",
        onClick = { showBubbles = true },
    )
    HubLinkRow(
        icon = Icons.Outlined.ArrowForward,
        title = "Полные настройки распознавания",
        subtitle = "Все параметры движка одним списком",
        onClick = { navigator?.push(SettingsOcrScreen) },
    )

    if (showBubbles) {
        OcrBubbleSettingsDialog(
            onDismissRequest = { showBubbles = false },
            onOpenFullSettings = {
                showBubbles = false
                navigator?.push(SettingsOcrScreen)
            },
        )
    }
}

/**
 * Точные числа детектора: переопределения пресетов.
 *
 * Пустое значение = «как в пресете», поэтому у каждого ползунка есть «Сброс».
 */
@Composable
private fun OcrTuningTab() {
    val prefs = remember { Injekt.get<OcrPreferences>() }

    HubTuningFloatSlider(
        label = "Порог детектора",
        prefText = prefs.detectorThresholdOverride().get(),
        default = 0.20f,
        range = 1..95,
        text = { "%.2f".format(it / 100f) },
    ) { prefs.detectorThresholdOverride().set(it) }

    HubTuningIntSlider(
        label = "Мин. площадь области",
        prefText = prefs.minComponentAreaOverride().get(),
        default = 16,
        range = 4..512,
    ) { prefs.minComponentAreaOverride().set(it) }

    HubTuningIntSlider(
        label = "Макс. боксов со страницы",
        prefText = prefs.maxTextBoxesOverride().get(),
        default = 96,
        range = 8..512,
    ) { prefs.maxTextBoxesOverride().set(it) }

    HubTuningFloatSlider(
        label = "Фактор зазора слов",
        prefText = prefs.wordGapFactorOverride().get(),
        default = 1.7f,
        range = 100..300,
        text = { "%.2f".format(it / 100f) },
    ) { prefs.wordGapFactorOverride().set(it) }

    HubTuningFloatSlider(
        label = "Мин. уверенность",
        prefText = prefs.minAcceptConfidenceOverride().get(),
        default = 0.25f,
        range = 5..95,
        text = { "%.2f".format(it / 100f) },
    ) { prefs.minAcceptConfidenceOverride().set(it) }

    HubTuningFloatSlider(
        label = "Уверенность коротких реплик",
        prefText = prefs.shortTextConfidenceOverride().get(),
        default = 0.12f,
        range = 1..60,
        text = { "%.2f".format(it / 100f) },
    ) { prefs.shortTextConfidenceOverride().set(it) }

    HubTuningFloatSlider(
        label = "Мин. покрытие",
        prefText = prefs.minCoverageOverride().get(),
        default = 0.12f,
        range = 1..60,
        text = { "%.2f".format(it / 100f) },
    ) { prefs.minCoverageOverride().set(it) }

    HubTuningIntSlider(
        label = "Строк rescue-эшелона",
        prefText = prefs.rescueMaxLinesOverride().get(),
        default = 6,
        range = 0..32,
    ) { prefs.rescueMaxLinesOverride().set(it) }
}

/** Языки распознавания, Tesseract и адреса онлайн-движков. */
@Composable
private fun OcrLanguageTab() {
    val prefs = remember { Injekt.get<OcrPreferences>() }
    val glensLang by prefs.glensLanguage().changes()
        .collectAsState(initial = prefs.glensLanguage().get())
    val tessLangs by prefs.tessLangs().changes().collectAsState(initial = prefs.tessLangs().get())
    val psm by prefs.tessPsm().changes().collectAsState(initial = prefs.tessPsm().get())
    val upscale by prefs.tessUpscaleMinSide().changes()
        .collectAsState(initial = prefs.tessUpscaleMinSide().get())
    val preprocess by prefs.tessPreprocess().changes()
        .collectAsState(initial = prefs.tessPreprocess().get())
    val keepPacks by prefs.keepOfflinePacks().changes()
        .collectAsState(initial = prefs.keepOfflinePacks().get())
    val zenFree by prefs.zenFreeEnabled().changes()
        .collectAsState(initial = prefs.zenFreeEnabled().get())
    val navigator = LocalNavigator.current

    HubOptions(
        title = "Язык Google Lens",
        options = listOf(
            "ja" to "Японский",
            "en" to "Английский",
            "ru" to "Русский",
            "ko" to "Корейский",
            "zh" to "Китайский",
            "fr" to "Французский",
            "de" to "Немецкий",
        ),
        selected = glensLang,
        onSelect = { prefs.glensLanguage().set(it) },
    )
    HubTextField(
        label = "Регион Google Lens",
        value = prefs.glensRegion().get(),
        onChange = { prefs.glensRegion().set(it) },
        helper = "IANA-зона, например Asia/Tokyo. Пусто — определяется по региону устройства",
    )

    HubOptions(
        title = "Языки Tesseract",
        options = listOf(
            "eng+rus" to "Английский + русский",
            "rus" to "Только русский",
            "eng" to "Только английский",
        ),
        selected = tessLangs,
        onSelect = { prefs.tessLangs().set(it) },
    )
    HubOptions(
        title = "Сегментация страницы",
        options = listOf(
            "single_block" to "Баллон целиком",
            "auto" to "Автоматически",
            "sparse" to "Разреженный текст",
            "single_line" to "По одной строке",
        ),
        selected = psm,
        onSelect = { prefs.tessPsm().set(it) },
    )
    HubIntSlider(
        label = "Апскейл мелких кропов",
        value = upscale,
        range = 0..640,
        text = { if (it == 0) "выкл" else "$it px" },
    ) { prefs.tessUpscaleMinSide().set(it) }
    HubSwitchRow(
        title = "Предобработка перед распознаванием",
        subtitle = "Ч/б и усиление контраста: меньше ошибок на выцветшей печати",
        checked = preprocess,
    ) { prefs.tessPreprocess().set(it) }
    HubSwitchRow(
        title = "Держать офлайн-модели распакованными",
        subtitle = "Быстрее первый запуск, но ~8 МБ постоянно на диске",
        checked = keepPacks,
    ) { prefs.keepOfflinePacks().set(it) }

    HubHeader("Онлайн-движки")
    HubSwitchRow(
        title = "Space Bunny Free (Zen)",
        subtitle = "Бесплатный онлайн-движок без ключа",
        checked = zenFree,
    ) { prefs.zenFreeEnabled().set(it) }
    HubTextField(
        label = "Ключ Gemini",
        value = prefs.googleApiKey().get(),
        onChange = { prefs.googleApiKey().set(it) },
    )
    HubTextField(
        label = "Модель Gemini",
        value = prefs.googleModel().get(),
        onChange = { prefs.googleModel().set(it) },
    )
    HubTextField(
        label = "Ключ OpenRouter",
        value = prefs.openrouterApiKey().get(),
        onChange = { prefs.openrouterApiKey().set(it) },
    )
    HubTextField(
        label = "Модель OpenRouter",
        value = prefs.openrouterModel().get(),
        onChange = { prefs.openrouterModel().set(it) },
    )
    HubTextField(
        label = "Адрес своего сервера распознавания (OwOCR)",
        value = prefs.owocrAddress().get(),
        onChange = { prefs.owocrAddress().set(it) },
    )

    HubLinkRow(
        icon = Icons.Outlined.AutoMode,
        title = "Плагины и цепочки движков",
        subtitle = "Требования каждого движка и их доступность",
        onClick = { navigator?.push(SettingsOcrPluginsScreen) },
    )
}

// ───────────────────────── КНИГИ ─────────────────────────

/**
 * Формат книги.
 *
 * Отдельной настройки формата нет и быть не должно: BookParser определяет
 * формат по сигнатуре файла, а не по расширению, — бинарный файл с именем
 * «.pdf» обязан читаться как то, что он есть. Поэтому вкладка объясняет
 * поддерживаемые форматы, а не предлагает выбрать несуществующий параметр.
 */
@Composable
private fun BooksFormatTab() {
    HubNote(
        "Формат определяется по содержимому файла, а не по расширению: переименованный " +
            "или бинарный файл не сломает разбор.",
    )
    HubFormatRow("PDF", "Страницы-картинки. Текст распознаётся OCR — включите «Страница».")
    HubFormatRow("EPUB", "Главы из spine, свой текст. Озвучивается без распознавания.")
    HubFormatRow("DOCX", "Абзацы документа, главы по заголовкам.")
    HubFormatRow("FB2", "FictionBook: главы и иллюстрации из тела книги.")
    HubFormatRow("HTML", "Заголовки и абзацы страницы.")
    HubFormatRow("RTF", "Форматированный текст.")
    HubFormatRow("TXT, MD и подобное", "Плоский текст, главы по пустым строкам.")
    HubNote(
        "Файл больше 40 МБ не открывается: разбор держится в памяти целиком, " +
            "поэтому очень большие книги лучше разбить.",
    )
}

/** Страница книги: распознавание сканов и движок для них. */
@Composable
private fun BooksPageTab() {
    val prefs = remember { Injekt.get<OcrPreferences>() }
    val navigator = LocalNavigator.current
    val pageOcr by prefs.bookPageOcr().changes().collectAsState(initial = prefs.bookPageOcr().get())
    val fullscreen by prefs.bookFullscreen().changes()
        .collectAsState(initial = prefs.bookFullscreen().get())
    val bookEngine by prefs.bookOcrEngine().changes()
        .collectAsState(initial = prefs.bookOcrEngine().get())
    val history by prefs.persistOcrHistory().changes()
        .collectAsState(initial = prefs.persistOcrHistory().get())

    HubSwitchRow(
        title = "Распознавать страницы (OCR)",
        subtitle = "Нужно для PDF и EPUB со сканами: без него показывается картинка",
        checked = pageOcr,
    ) { prefs.bookPageOcr().set(it) }
    HubOptions(
        title = "Движок для книг",
        options = OcrModel.entries.map { it to (ENGINE_TITLES[it] ?: it.name) },
        selected = bookEngine,
        onSelect = { prefs.bookOcrEngine().set(it) },
    )
    HubNote(
        "Свой движок для книг, а не общий читалки: у книги другая вёрстка и " +
            "другой шрифт, и один движок на всё хуже читает сканы.",
    )
    HubSwitchRow(
        title = "Читалка во весь экран",
        subtitle = "Прятать панели и системные панели",
        checked = fullscreen,
    ) { prefs.bookFullscreen().set(it) }
    HubSwitchRow(
        title = "Хранить историю распознавания",
        subtitle = "Видеть, какие страницы уже прочитаны и что не получилось",
        checked = history,
    ) { prefs.persistOcrHistory().set(it) }

    HubLinkRow(
        icon = Icons.Outlined.ArrowForward,
        title = "Полные настройки распознавания",
        subtitle = "Пороги детектора и цепочки движков",
        onClick = { navigator?.push(SettingsOcrScreen) },
    )
}

/** Озвучка книги: движок, голос, тембр, роли и музыка. */
@Composable
private fun BooksVoiceTab() {
    val prefs = remember { Injekt.get<OcrPreferences>() }
    val navigator = LocalNavigator.current
    val engine by prefs.bookTtsEngine().changes().collectAsState(initial = prefs.bookTtsEngine().get())
    val rate by prefs.bookSpeechRate().changes()
        .collectAsState(initial = prefs.bookSpeechRate().get())
    val pitch by prefs.bookSpeechPitch().changes()
        .collectAsState(initial = prefs.bookSpeechPitch().get())
    val roleVoices by prefs.bookRoleVoices().changes()
        .collectAsState(initial = prefs.bookRoleVoices().get())
    val music by prefs.bookMusicEnabled().changes()
        .collectAsState(initial = prefs.bookMusicEnabled().get())
    val musicVolume by prefs.bookMusicVolume().changes()
        .collectAsState(initial = prefs.bookMusicVolume().get())
    val voiceLabel by prefs.bookVoiceLabel().changes()
        .collectAsState(initial = prefs.bookVoiceLabel().get())
    var showPicker by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }

    HubOptions(
        title = "Движок озвучки книги",
        options = listOf(
            "system" to "📱 Системный TTS — офлайн",
            "edge" to "☁ Edge TTS — онлайн, без ключа",
        ),
        selected = engine,
        onSelect = { prefs.bookTtsEngine().set(it) },
    )

    // Список голосов собирает TtsVoicePickerDialog — свой список означал бы
    // вторую правду о том, какие голоса есть на устройстве.
    HubLinkRow(
        icon = Icons.Outlined.GraphicEq,
        title = "Голос книги",
        subtitle = voiceLabel.ifBlank { "Не выбран — голос по умолчанию" },
        onClick = { showPicker = true },
    )

    HubPercentSlider(
        label = "Скорость чтения",
        value = rate,
        percentRange = 50..300,
        text = { "×%.2f".format(it / 100f) },
    ) { prefs.bookSpeechRate().set(it / 100f) }
    HubPercentSlider(
        label = "Высота голоса",
        value = pitch,
        percentRange = 50..200,
        text = { "×%.2f".format(it / 100f) },
    ) { prefs.bookSpeechPitch().set(it / 100f) }

    HubSwitchRow(
        title = "Голоса по ролям",
        subtitle = "Нарратив своим голосом, персонажи — отдельными",
        checked = roleVoices,
    ) { prefs.bookRoleVoices().set(it) }

    HubSwitchRow(
        title = "Фоновая музыка",
        subtitle = "Процедурные петли под настроение книги",
        checked = music,
    ) { prefs.bookMusicEnabled().set(it) }
    if (music) {
        HubPercentSlider(
            label = "Громкость музыки",
            value = musicVolume,
            percentRange = 4..80,
            text = { "%.0f%%".format(it) },
        ) { prefs.bookMusicVolume().set(it / 100f) }
    }

    HubLinkRow(
        icon = Icons.Outlined.GraphicEq,
        title = "Общие голоса и словари ролей",
        subtitle = "Сетка голосов устройства, роли и интонации",
        onClick = { showEditor = true },
    )

    if (showPicker) {
        TtsVoicePickerDialog(
            onDismissRequest = { showPicker = false },
            onPickSystem = { spec ->
                prefs.bookTtsEngine().set("system")
                prefs.bookVoiceSpec().set(spec)
                prefs.bookVoiceLabel().set(spec.substringAfterLast("::"))
                showPicker = false
            },
            onPickEdge = { shortName ->
                prefs.bookTtsEngine().set("edge")
                prefs.bookVoiceSpec().set(shortName)
                prefs.bookVoiceLabel().set(shortName)
                showPicker = false
            },
        )
    }

    if (showEditor) {
        TtsSettingsDialog(
            onDismissRequest = { showEditor = false },
            onOpenFullSettings = {
                showEditor = false
                navigator?.push(SettingsMainScreen)
            },
        )
    }
}

// ───────────────────────── ПРОЧЕЕ ─────────────────────────

/**
 * Оверлей поверх чужих приложений.
 *
 * Здесь только то, что нужно менять часто и без перезапуска оверлея. Всё
 * остальное (разрешения, список приложений, служба доступности, запуск и
 * остановка) живёт в [OcrOverlaySettingsScreen] — второй экран с теми же
 * настройками означал бы две правды об оверлее.
 */
@Composable
private fun OtherOverlayTab() {
    val prefs = remember { Injekt.get<OcrPreferences>() }
    val navigator = LocalNavigator.current
    val mode by prefs.overlayRegionMode().changes()
        .collectAsState(initial = prefs.overlayRegionMode().get())
    val showFrame by prefs.overlayShowFrame().changes()
        .collectAsState(initial = prefs.overlayShowFrame().get())
    val watchClip by prefs.overlayWatchClipboard().changes()
        .collectAsState(initial = prefs.overlayWatchClipboard().get())
    val ownEngine by prefs.overlayOwnEngine().changes()
        .collectAsState(initial = prefs.overlayOwnEngine().get())
    val appEngine by prefs.appOcrEngine().changes()
        .collectAsState(initial = prefs.appOcrEngine().get())
    val pause by prefs.overlayReadPause().changes()
        .collectAsState(initial = prefs.overlayReadPause().get())
    val settle by prefs.overlayScrollSettle().changes()
        .collectAsState(initial = prefs.overlayScrollSettle().get())
    val step by prefs.overlayScrollStep().changes()
        .collectAsState(initial = prefs.overlayScrollStep().get())

    HubOptions(
        title = "Область распознавания",
        options = listOf(
            "auto" to "Авто — весь экран",
            "manual" to "Вручную — выделить пальцем",
            "fixed" to "Фикс — реплики игр, область не двигается",
        ),
        selected = mode,
        onSelect = { prefs.overlayRegionMode().set(it) },
    )
    HubSwitchRow(
        title = "Показывать рамку области",
        subtitle = "Тонкая рамка зафиксированной области поверх приложения",
        checked = showFrame,
    ) { prefs.overlayShowFrame().set(it) }
    HubSwitchRow(
        title = "Следить за буфером обмена",
        subtitle = "Новое скопированное озвучивается само",
        checked = watchClip,
    ) { prefs.overlayWatchClipboard().set(it) }
    HubSwitchRow(
        title = "Свой движок для оверлея",
        subtitle = "Выключено — читает тем же движком, что и читалка",
        checked = ownEngine,
    ) { prefs.overlayOwnEngine().set(it) }
    if (ownEngine) {
        HubOptions(
            title = "Движок оверлея",
            options = OcrModel.entries.map { it to (ENGINE_TITLES[it] ?: it.name) },
            selected = appEngine,
            onSelect = { prefs.appOcrEngine().set(it) },
        )
    }

    HubHeader("Чтение рамки")
    HubIntSlider(
        label = "Пауза после реплики",
        value = pause,
        range = 0..4000,
        text = { "$it мс" },
    ) { prefs.overlayReadPause().set(it) }
    HubIntSlider(
        label = "Ожидание перерисовки",
        value = settle,
        range = 0..5000,
        text = { "$it мс" },
    ) { prefs.overlayScrollSettle().set(it) }
    HubIntSlider(
        label = "Длина свайпа",
        value = step,
        range = 10..100,
        text = { "$it% высоты области" },
    ) { prefs.overlayScrollStep().set(it) }

    HubLinkRow(
        icon = Icons.Outlined.Layers,
        title = "Настройки оверлея",
        subtitle = "Запуск, разрешения, список приложений, доступность",
        onClick = { navigator?.push(OcrOverlaySettingsScreen) },
    )
}

/** Агенты и онлайн-модели: чем читающий помощник думает и куда ходит. */
@Composable
private fun OtherAgentsTab() {
    val prefs = remember { Injekt.get<OcrPreferences>() }
    val navigator = LocalNavigator.current
    val backend by prefs.aiBackend().changes().collectAsState(initial = prefs.aiBackend().get())
    val provider by prefs.aiProvider().changes().collectAsState(initial = prefs.aiProvider().get())
    val orchestrator by prefs.aiOrchestratorBackend().changes()
        .collectAsState(initial = prefs.aiOrchestratorBackend().get())
    val allowRunner by prefs.aiAllowRunner().changes()
        .collectAsState(initial = prefs.aiAllowRunner().get())
    val allowGithub by prefs.aiAllowGithub().changes()
        .collectAsState(initial = prefs.aiAllowGithub().get())
    val tabVisible by prefs.aiTabVisible().changes()
        .collectAsState(initial = prefs.aiTabVisible().get())
    val httpServer by prefs.aiHttpServer().changes()
        .collectAsState(initial = prefs.aiHttpServer().get())

    HubOptions(
        title = "Бэкенд чата",
        options = listOf(
            AiBackends.BACKEND_ONLINE to "Онлайн (Zen / OpenRouter)",
            AiBackends.BACKEND_LOCAL to "Локальная модель на телефоне",
            AiBackends.BACKEND_RUNNER to "Гитхаб-раннер (полу-онлайн LLM)",
        ),
        selected = backend,
        onSelect = { prefs.aiBackend().set(it) },
    )
    HubOptions(
        title = "Онлайн-провайдер",
        options = listOf(
            AiAssistant.PROVIDER_ZEN to "Zen — бесплатно, без ключа",
            AiAssistant.PROVIDER_OPENROUTER to "OpenRouter — по ключу",
        ),
        selected = provider,
        onSelect = { prefs.aiProvider().set(it) },
    )
    HubTextField(
        label = "Модель Zen",
        value = prefs.zenModel().get(),
        onChange = { prefs.zenModel().set(it) },
    )
    HubTextField(
        label = "Ключ OpenRouter",
        value = prefs.openrouterApiKey().get(),
        onChange = { prefs.openrouterApiKey().set(it) },
    )
    HubTextField(
        label = "Бесплатная модель OpenRouter (:free)",
        value = prefs.openrouterFreeModel().get(),
        onChange = { prefs.openrouterFreeModel().set(it) },
    )
    HubTextField(
        label = "Локальная модель на телефоне",
        value = prefs.localLlmModel().get(),
        onChange = { prefs.localLlmModel().set(it) },
        helper = "Пусто — модель не выбрана; сама модель не входит в APK",
    )

    HubHeader("Агент читалки")
    HubOptions(
        title = "Модель оркестратора",
        subtitle = "Агент вызывает инструменты и выводит правила книги",
        options = listOf(
            "" to "Как у чата",
            AiBackends.BACKEND_ONLINE to "Онлайн",
            AiBackends.BACKEND_LOCAL to "Локальная модель",
            AiBackends.BACKEND_RUNNER to "Раннер",
        ),
        selected = orchestrator,
        onSelect = { prefs.aiOrchestratorBackend().set(it) },
    )
    HubTextField(
        label = "Модель оркестратора",
        value = prefs.aiOrchestratorModel().get(),
        onChange = { prefs.aiOrchestratorModel().set(it) },
        helper = "Пусто — как у чата",
    )
    HubSwitchRow(
        title = "Разрешить агенту ранер",
        subtitle = "Запускать LLM-сессии в гитхаб-раннере",
        checked = allowRunner,
    ) { prefs.aiAllowRunner().set(it) }
    HubSwitchRow(
        title = "Разрешить агенту GitHub API",
        subtitle = "Список воркфлоу, статусы, диспатч по привязанному токену",
        checked = allowGithub,
    ) { prefs.aiAllowGithub().set(it) }
    HubTextField(
        label = "GitHub PAT",
        value = prefs.githubPat().get(),
        onChange = { prefs.githubPat().set(it) },
        helper = "Хранится только на устройстве и уходит в токен доступа",
    )
    HubSwitchRow(
        title = "Вкладка AI в нижней панели",
        subtitle = "Выключено — агент доступен только из внешнего браузера",
        checked = tabVisible,
    ) { prefs.aiTabVisible().set(it) }
    HubSwitchRow(
        title = "Встроенный HTTP-сервер агента",
        subtitle = "http://127.0.0.1:8765 и по Wi-Fi, доступ по секрету",
        checked = httpServer,
    ) { prefs.aiHttpServer().set(it) }

    HubLinkRow(
        icon = Icons.Outlined.Psychology,
        title = "Настройки AI",
        subtitle = "Бэкенды, модели, история чата и отчёт о готовности",
        onClick = { navigator?.push(SettingsAiScreen) },
    )
}

/** Экспериментальное: то, что может изменить поведение неожиданно. */
@Composable
private fun OtherExperimentalTab() {
    val prefs = remember { Injekt.get<OcrPreferences>() }
    val navigator = LocalNavigator.current
    val textOverlay by prefs.ocrPageTextOverlay().changes()
        .collectAsState(initial = prefs.ocrPageTextOverlay().get())
    val toNotification by prefs.ocrToNotification().changes()
        .collectAsState(initial = prefs.ocrToNotification().get())
    val streaming by prefs.ocrStreamingHighlight().changes()
        .collectAsState(initial = prefs.ocrStreamingHighlight().get())
    val compactOverlay by prefs.ocrOverlayCompact().changes()
        .collectAsState(initial = prefs.ocrOverlayCompact().get())
    val autoOcr by prefs.autoOcrOnDownload().changes()
        .collectAsState(initial = prefs.autoOcrOnDownload().get())
    val perSite by prefs.webSavePerSiteFolder().changes()
        .collectAsState(initial = prefs.webSavePerSiteFolder().get())
    val availability by prefs.aiShowAvailability().changes()
        .collectAsState(initial = prefs.aiShowAvailability().get())
    val reasoning by prefs.aiShowReasoning().changes()
        .collectAsState(initial = prefs.aiShowReasoning().get())
    val autoRotate by prefs.aiAutoRotate().changes()
        .collectAsState(initial = prefs.aiAutoRotate().get())
    val historyLimit by prefs.aiHistoryLimit().changes()
        .collectAsState(initial = prefs.aiHistoryLimit().get())

    HubSwitchRow(
        title = "Текст поверх страницы в читалке",
        subtitle = "Выключите, если белый текст мешает на светлых страницах",
        checked = textOverlay,
    ) { prefs.ocrPageTextOverlay().set(it) }
    HubSwitchRow(
        title = "Дублировать текст в шторку уведомлений",
        checked = toNotification,
    ) { prefs.ocrToNotification().set(it) }
    HubSwitchRow(
        title = "Стриминг подсветки",
        subtitle = "Подсвечивать слово сразу по мере распознавания",
        checked = streaming,
    ) { prefs.ocrStreamingHighlight().set(it) }
    HubSwitchRow(
        title = "Компактный оверлей",
        subtitle = "Уменьшенная панель вместо полноэкранного затемнения",
        checked = compactOverlay,
    ) { prefs.ocrOverlayCompact().set(it) }
    HubSwitchRow(
        title = "Распознавать при скачивании",
        subtitle = "Глава разбирается сразу, без ожидания первого открытия",
        checked = autoOcr,
    ) { prefs.autoOcrOnDownload().set(it) }
    HubSwitchRow(
        title = "Веб-страницы — в отдельные папки по сайту",
        checked = perSite,
    ) { prefs.webSavePerSiteFolder().set(it) }
    HubSwitchRow(
        title = "Показывать, что доступно и что невозможно",
        subtitle = "Отчёт о готовности бэкендов и движков",
        checked = availability,
    ) { prefs.aiShowAvailability().set(it) }
    HubSwitchRow(
        title = "Показывать рассуждения reasoning-моделей",
        checked = reasoning,
    ) { prefs.aiShowReasoning().set(it) }
    HubSwitchRow(
        title = "Автосмена модели при лимите",
        subtitle = "Следующая модель списка вместо отказа",
        checked = autoRotate,
    ) { prefs.aiAutoRotate().set(it) }
    HubIntSlider(
        label = "Сообщений в истории чата",
        value = historyLimit,
        range = 4..50,
        text = { "${it} шт" },
    ) { prefs.aiHistoryLimit().set(it) }
    HubTextField(
        label = "Лимит токенов на ответ",
        value = prefs.aiTokenBudget().get().toString(),
        onChange = { text -> text.toIntOrNull()?.let { prefs.aiTokenBudget().set(it) } },
        helper = "Меньше — ответ короче и дешевле",
    )

    HubLinkRow(
        icon = Icons.Outlined.Tune,
        title = "Продвинутые настройки",
        subtitle = "Полный список экспериментального и внутреннего",
        onClick = { navigator?.push(SettingsAdvancedScreen) },
    )
}

/** Конструктор интерфейса. */
@Composable
private fun OtherConstructorTab() {
    val navigator = LocalNavigator.current
    HubNote(
        "Конструктор собирает вид приложения: вкладки нижней панели, модули " +
            "панели читалки и браузера, собственные кнопки действий. Значения " +
            "хранятся декларативно, исполняемый код добавить нельзя.",
    )
    HubLinkRow(
        icon = Icons.Outlined.MoreVert,
        title = "Конструктор интерфейса",
        subtitle = "Вкладки, модули панелей и свои кнопки действий",
        onClick = { navigator?.push(SettingsConstructorScreen) },
    )
    HubLinkRow(
        icon = Icons.Outlined.Settings,
        title = "Полные настройки приложения",
        subtitle = "Вид, данные, загрузки, безопасность",
        onClick = { navigator?.push(SettingsMainScreen) },
    )
}

/** Источники и расширения. */
@Composable
private fun OtherSourcesTab() {
    val navigator = LocalNavigator.current
    HubNote(
        "Источники и расширения настраиваются в своём разделе: там же обновление " +
            "и удаление репозиториев. В читалке их настройки не дублируются.",
    )
    HubLinkRow(
        icon = Icons.Outlined.Public,
        title = "Источники и расширения",
        subtitle = "Репозитории, обновление, установка",
        onClick = { navigator?.push(SettingsBrowseScreen) },
    )
}

// ───────────────────────── каркас и мелкие блоки ─────────────────────────

/**
 * Карточка раздела с обложкой — приём взят из списка книг: обложка
 * фиксированного размера со скруглением, слева подпись в две строки, справа
 * стрелка состояния. Иконка на цветной подложке вместо картинки: хаб
 * открывается мгновенно и одинаково в любой теме.
 */
@Composable
private fun HubSectionCard(
    section: HubSection,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val coverColor = when (section.cover) {
        HubCover.PRIMARY -> MaterialTheme.colorScheme.primaryContainer
        HubCover.SECONDARY -> MaterialTheme.colorScheme.secondaryContainer
        HubCover.TERTIARY -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val coverContentColor = when (section.cover) {
        HubCover.PRIMARY -> MaterialTheme.colorScheme.onPrimaryContainer
        HubCover.SECONDARY -> MaterialTheme.colorScheme.onSecondaryContainer
        HubCover.TERTIARY -> MaterialTheme.colorScheme.onTertiaryContainer
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (expanded) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp, 74.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(coverColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = section.icon,
                contentDescription = null,
                tint = coverContentColor,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = section.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = section.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${section.tabs.size} ${hubTabsWord(section.tabs.size)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Вкладки раздела. Длинные названия не сжимаются — ряд прокручивается. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HubSectionTabs(
    section: HubSection,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    ScrollableTabRow(selectedTabIndex = selected) {
        section.tabs.forEachIndexed { index, tab ->
            Tab(
                selected = selected == index,
                onClick = { onSelect(index) },
                text = { Text(tab.title, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun HubHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun HubNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun HubSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Радио-варианты. Список, а не Map: порядок вариантов значим (частый выбор
 * сверху), а Map здесь тащил бы за собой отдельный список ключей.
 */
@Composable
private fun <T> HubOptions(
    title: String? = null,
    subtitle: String? = null,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    if (title != null) HubHeader(title)
    if (subtitle != null) HubNote(subtitle)
    options.forEach { (key, label) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(key) }
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = key == selected, onClick = { onSelect(key) })
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Ползунок в процентах.
 *
 * Настройки хранятся как доля (0.5…2.0 и т. п.), а ползунок работает по
 * целым процентам: так значение не «плывёт» сотыми при каждом касании и
 * остаётся ровным в настройках.
 */
@Composable
private fun HubPercentSlider(
    label: String,
    value: Float,
    percentRange: IntRange,
    text: (Int) -> String,
    onChange: (Int) -> Unit,
) {
    val percent = (value * 100).toInt().coerceIn(percentRange.first, percentRange.last)
    SliderItem(
        value = percent,
        valueRange = percentRange,
        label = label,
        valueString = text(percent),
        onChange = onChange,
    )
}

/**
 * Ползунок для настроек, которые хранятся целым числом (миллисекунды, px,
 * размер буфера). Здесь НЕ умножаем на 100: значение уже «как есть», иначе
 * 320 px превратились бы в 32000 и улетели за предел диапазона.
 */
@Composable
private fun HubIntSlider(
    label: String,
    value: Int,
    range: IntRange,
    text: (Int) -> String,
    onChange: (Int) -> Unit,
) {
    SliderItem(
        value = value.coerceIn(range.first, range.last),
        valueRange = range,
        label = label,
        valueString = text(value.coerceIn(range.first, range.last)),
        onChange = onChange,
    )
}

/**
 * Ползунок точной настройки детектора для дробных параметров (пороги
 * уверенности, покрытие).
 *
 * Пустое значение в настройке = «как в пресете», поэтому показываем пресетное
 * значение и предлагаем «Сброс» — иначе переопределение нельзя отличить от
 * совпадения с пресетом. Ползунок целочисленный, а настройка дробная, отсюда
 * ×100 при чтении и обратно при записи.
 */
@Composable
private fun HubTuningFloatSlider(
    label: String,
    prefText: String,
    default: Float,
    range: IntRange,
    text: (Int) -> String,
    onSet: (String) -> Unit,
) {
    val current = prefText.toFloatOrNull() ?: default
    val isPreset = prefText.isBlank()
    val intValue = (current * 100).toInt().coerceIn(range.first, range.last)
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        SliderItem(
            value = intValue,
            valueRange = range,
            label = label,
            valueString = (if (isPreset) "пресет " else "") + text(intValue),
            onChange = { onSet("%.2f".format(it / 100f)) },
        )
        if (!isPreset) {
            HubResetButton(onReset = { onSet("") })
        }
    }
}

/**
 * Ползунок точной настройки для целых параметров (площадь, число боксов,
 * строки rescue). Здесь НЕ масштабируем на 100: «мин. площадь области = 16»
 * это 16, а не 1600.
 */
@Composable
private fun HubTuningIntSlider(
    label: String,
    prefText: String,
    default: Int,
    range: IntRange,
    onSet: (String) -> Unit,
) {
    val intValue = (prefText.toIntOrNull() ?: default).coerceIn(range.first, range.last)
    val isPreset = prefText.isBlank()
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        SliderItem(
            value = intValue,
            valueRange = range,
            label = label,
            valueString = (if (isPreset) "пресет " else "") + intValue.toString(),
            onChange = { onSet(it.toString()) },
        )
        if (!isPreset) {
            HubResetButton(onReset = { onSet("") })
        }
    }
}

/**
 * Кнопка «вернуть пресетное значение» у переопределения детектора.
 *
 * Расширение ColumnScope, а не обычная функция: `Modifier.align` существует
 * только внутри Column, иначе кнопка не прижалась бы вправо.
 */
@Composable
private fun ColumnScope.HubResetButton(onReset: () -> Unit) {
    TextButton(
        onClick = onReset,
        modifier = Modifier.align(Alignment.End),
    ) {
        Text("Сброс")
    }
}

/** Текстовое поле поверх строковых настроек: пишем сразу, без кнопки «Сохранить». */
@Composable
private fun HubTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    helper: String? = null,
) {
    var text by remember { mutableStateOf(value) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onChange(it)
            },
            label = { Text(text = label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (helper != null) {
            Text(
                text = helper,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Ссылка на уже готовый экран: логика остаётся там, где её написали один раз. */
@Composable
private fun HubLinkRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Строка-справка о поддерживаемом формате книги. */
@Composable
private fun HubFormatRow(title: String, note: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Движки озвучки как варианты выбора: те же значения, что и в редакторе голосов. */
private fun hubEngineOptions(): List<Pair<String, String>> = listOf(
    TtsSpeaker.ENGINE_SYSTEM to "📱 Системный TTS",
    TtsSpeaker.ENGINE_GOOGLE_WEB to "☁ Google Web",
    TtsSpeaker.ENGINE_EDGE_TTS to "☁ Edge TTS",
    TtsSpeaker.ENGINE_ELEVENLABS to "☁ ElevenLabs",
    TtsSpeaker.ENGINE_REMOTE to "🖥 TTS-сервер",
)

private fun hubTabsWord(count: Int): String = when (count) {
    1 -> "вкладка"
    2, 3, 4 -> "вкладки"
    else -> "вкладок"
}
