package eu.kanade.presentation.reader.components

import android.media.AudioManager
import android.media.ToneGenerator
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import eu.kanade.tachiyomi.util.system.toast
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import mihon.domain.ocr.service.ScanRegion

/**
 * SAO-стиль: единственная перемещаемая плавающая кнопка. Тап (со звуком)
 * раскрывает вертикальное меню со всеми действиями: OCR (с выбором области),
 * автопрокрутка со скоростью, настройки озвучки. Кнопку можно перетащить
 * в любое место экрана — позиция сохраняется, пока открыта читалка.
 */
@Composable
fun ReaderFloatingControls(
    visible: Boolean,
    onTriggerOcr: () -> Unit,
    onOpenOcrSettings: () -> Unit,
    /** Отдельная кнопка полной настройки «как читать баблы» (OCR). */
    onOpenFullOcrSettings: () -> Unit = {},
    /**
     * Единый хаб настроек: звук, распознавание, книги, прочее.
     *
     * Отдельные пункты «Озвучка» и «Настройки OCR» из меню убраны — читатель
     * жаловался, что настроек «везде, а толку ноль». Всё это теперь в одном
     * месте, а меню отвечает только за действия.
     */
    onOpenSettingsHub: () -> Unit = {},
    onOpenAiChat: () -> Unit = {},
    /**
     * Консоль ИИ: живой журнал раундов, запросов к модели и чтения.
     *
     * Отдельный вход из меню читалки, а не только из AI-чата: половина журнала
     * — это распознавание и озвучка, которые идут без открытого чата, и смотреть
     * на них было негде.
     */
    onOpenAiConsole: () -> Unit = {},
    onScanRegionChange: (ScanRegion) -> Unit,
    onAutoscrollToggle: (Boolean, Float) -> Unit,
    onAutoSpeakPage: () -> Unit = {},
    /**
     * Авточтение всей главы: страница за страницей до конца, с листанием.
     *
     * Раньше в меню была только кнопка «Прочитать страницу» — она озвучивала
     * текущий кадр и останавливалась, а запустить чтение главы можно было
     * только из плавающей кнопки на экране манги (с предварительным сканом).
     */
    onAutoReadChapter: () -> Unit = {},
    /** Идёт ли чтение главы — для подписи и цвета кнопки. */
    chapterReadActive: Boolean = false,
    onStopSpeak: () -> Unit = {},
    /** Мгновенный скриншот текущего кадра (Glens/любой движок): OCR сейчас,
     *  не дожидаясь авточтения, и запись в буфер «Скриншоты». */
    onInstantScreenshot: () -> Unit = {},
    onReadingOrderChange: (String) -> Unit = {},
    readingOrder: String = "rtl",
    /** Пресет типа контента (id из OcrContentType): задаёт и порядок чтения. */
    contentPreset: String = "balanced",
    onContentPresetChange: (String) -> Unit = {},
    /** true — голос выбирает читатель, false — определяется автоматически. */
    manualVoiceMode: Boolean = false,
    /** Голос в ручном режиме: "female" | "male". */
    manualVoiceGender: String = "female",
    onVoiceModeChange: (Boolean) -> Unit = {},
    onVoiceGenderChange: (String) -> Unit = {},
    /** Значки 🔊 на каждой реплике (переключатель). */
    voiceIconsEnabled: Boolean = false,
    /** Включить/выключить значки озвучки реплик. */
    onVoiceIconsToggle: (Boolean) -> Unit = {},
    /** Идёт ли чтение (для состояний кнопки «Стоп чтения»: покой / работа). */
    readingActive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var manualVoice by remember(manualVoiceMode) { mutableStateOf(manualVoiceMode) }
    var voiceGender by remember(manualVoiceGender) { mutableStateOf(manualVoiceGender) }
    var menuOpen by remember { mutableStateOf(false) }
    val ctorContext = androidx.compose.ui.platform.LocalContext.current
    val ctorVersion by eu.kanade.tachiyomi.data.ui.UiConstructorStore.version.collectAsState()
    val ctorUiPrefs = ctorContext.getSharedPreferences("yomikai_ctor_ui", android.content.Context.MODE_PRIVATE)
    var ctorExpanded by remember { mutableStateOf(ctorUiPrefs.getBoolean("menu_ctor_expanded", true)) }
    val userActs = remember(ctorVersion) { eu.kanade.tachiyomi.data.ui.UiActionRegistry.forPlacement(ctorContext, mihon.data.ui.UiPlacement.FLOATING_MENU) }
    val hiddenM = remember(ctorVersion) { eu.kanade.tachiyomi.data.ui.UiConstructorStore.moduleHidden(ctorContext) }
    var isAutoscrollActive by remember { mutableStateOf(false) }
    var autoscrollSpeed by remember { mutableFloatStateOf(2f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Короткий SAO-подобный "бип" на открытие/закрытие меню и действия
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_SYSTEM, 55) }.getOrNull() }
    DisposableEffect(Unit) {
        onDispose { runCatching { tone?.release() } }
    }
    fun beepOpen() = runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 60) }
    fun beepAction() = runCatching { tone?.startTone(ToneGenerator.TONE_PROP_ACK, 70) }

    // Скрытые в конструкторе модули отсеиваем при сборке списка: сами строки
    // каждый раз заново создаются, но это просто data-классы, так что дёшево.
    fun rowUnlessHidden(module: String, row: OcrMenuRow): OcrMenuRow? =
        if (hiddenM.contains(module)) null else row

    // Пресет типа контента задаёт и порядок чтения — подпись строки меню.
    val presetTitle = when (contentPreset) {
        "manga" -> "Манга"
        "manhwa" -> "Манхва"
        "manhua" -> "Маньхуа"
        "comic" -> "Комикс"
        else -> "Сбаланс."
    }
    val presetOrder = when (contentPreset) {
        "manga" -> "← Справа налево"
        "manhwa", "manhua" -> "↓ Сверху вниз"
        "comic" -> "→ Слева направо"
        else -> when (readingOrder) {
            "ltr" -> "→ Слева направо"
            "vertical" -> "↓ Сверху вниз"
            else -> "← Справа налево"
        }
    }

    // Пункты меню как данные: вид и раскладку рисует общий
    // OcrControlMenuCard — здесь только подписи, действия и состояние.
    //
    // Слайдер скорости виден только при активной автопрокрутке и вставлен
    // сразу под свою строку: в общем хвосте карточки до него пришлось бы
    // доезжать скроллом.
    val speedBlock: (@Composable ColumnScope.() -> Unit)? = if (isAutoscrollActive) {
        {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Speed, contentDescription = null)
                Slider(
                    value = autoscrollSpeed,
                    onValueChange = {
                        autoscrollSpeed = it
                        onAutoscrollToggle(true, autoscrollSpeed)
                    },
                    valueRange = 1f..10f,
                    modifier = Modifier.width(140.dp),
                )
                Text("×${autoscrollSpeed.roundToInt()}")
            }
        }
    } else {
        null
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomEnd,
    ) {
    // Статус скана — БЕЗ громоздких центральных оверлеев и заметок
    // «Текст готов»: в углу экрана маленькая песочная анимация (часики
    // переворачиваются верх-вниз), а подробности уходят уведомлением в шторку.
    val ocrStage by mihon.data.ocr.OcrStageBus.event.collectAsState()
    val scanActive = ocrStage.stage == mihon.data.ocr.OcrStageBus.Stage.DETECTING ||
        ocrStage.stage == mihon.data.ocr.OcrStageBus.Stage.RECOGNIZING
    if (scanActive) {
        // Часики изолированы в ОТДЕЛЬНУЮ самодельную компосабл: анимация
        // переворота рекомпозирует только крошечную иконку, а НЕ всё плавающее
        // меню дважды в секунду (что на слабых устройствах отъедало UI-поток
        // и замедляло захват кадра рядом с OCR).
        ScanHourglassStateIcon(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp),
        )
    }
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                    // Держим кнопку НАД нижней панелью читалки, чтобы не
                    // перекрывать шестерёнку настроек и прочие кнопки меню.
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 120.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Раскрывающееся меню (SAO): столбец пунктов над кнопкой.
                // Вид и раскладку рисует общий OcrControlMenuCard — здесь
                // только данные: подписи, действия и состояние.
                AnimatedVisibility(
                    visible = menuOpen,
                    enter = fadeIn() + scaleIn(initialScale = 0.8f),
                    exit = fadeOut() + scaleOut(targetScale = 0.8f),
                ) {
                    OcrControlMenuCard(
                        rows = listOfNotNull(
                            rowUnlessHidden(
                                "r_scan",
                                OcrMenuRow(
                                    label = "OCR скан",
                                    action = OcrMenuAction(
                                        icon = Icons.Outlined.DocumentScanner,
                                        onClick = {
                                            beepAction()
                                            // Сразу режим выделения области: промежуточные
                                            // кнопки «100%/верх/низ» убраны как лишние — область
                                            // пользователь выбирает перетаскиванием.
                                            onTriggerOcr()
                                        },
                                        contentDescription = "OCR",
                                    ),
                                ),
                            ),
                            rowUnlessHidden(
                                "r_autoscroll",
                                OcrMenuRow(
                                    label = if (isAutoscrollActive) "Стоп прокрутки" else "Автопрокрутка",
                                    action = OcrMenuAction(
                                        icon = if (isAutoscrollActive) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                        onClick = {
                                            beepAction()
                                            isAutoscrollActive = !isAutoscrollActive
                                            onAutoscrollToggle(isAutoscrollActive, autoscrollSpeed)
                                        },
                                        // Подсветки фона тут нет, меняется только иконка —
                                        // так владелец сразу видит, что автопрокрутка идёт.
                                        contentColor = if (isAutoscrollActive) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        contentDescription = "Автопрокрутка",
                                    ),
                                    // Слайдер идёт сразу за своей строкой, а не
                                    // в хвосте extraContent.
                                    below = speedBlock,
                                ),
                            ),
                            rowUnlessHidden(
                                "r_autoread",
                                OcrMenuRow(
                                    label = if (readingActive) "Чтение: идёт…" else "Прочитать страницу",
                                    action = OcrMenuAction(
                                        icon = if (readingActive) Icons.Outlined.GraphicEq else Icons.Outlined.PlayArrow,
                                        onClick = {
                                            beepAction()
                                            menuOpen = false
                                            onAutoSpeakPage()
                                        },
                                        containerColor = if (readingActive) {
                                            MaterialTheme.colorScheme.tertiaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        },
                                        contentColor = if (readingActive) {
                                            MaterialTheme.colorScheme.onTertiaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        },
                                        contentDescription = "Прочитать страницу",
                                    ),
                                ),
                            ),
                            rowUnlessHidden(
                                "r_autoread_chapter",
                                OcrMenuRow(
                                    label = if (chapterReadActive) "Глава: читаю…" else "Читать главу",
                                    action = OcrMenuAction(
                                        icon = if (chapterReadActive) {
                                            Icons.Outlined.GraphicEq
                                        } else {
                                            Icons.Outlined.AutoStories
                                        },
                                        onClick = {
                                            beepAction()
                                            menuOpen = false
                                            onAutoReadChapter()
                                        },
                                        containerColor = if (chapterReadActive) {
                                            MaterialTheme.colorScheme.tertiaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.primaryContainer
                                        },
                                        contentColor = if (chapterReadActive) {
                                            MaterialTheme.colorScheme.onTertiaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        },
                                        contentDescription = "Читать главу",
                                    ),
                                ),
                            ),
                            rowUnlessHidden(
                                "r_aiconsole",
                                OcrMenuRow(
                                    label = "Консоль ИИ",
                                    action = OcrMenuAction(
                                        icon = Icons.Outlined.Terminal,
                                        onClick = {
                                            beepAction()
                                            menuOpen = false
                                            onOpenAiConsole()
                                        },
                                        contentDescription = "Консоль ИИ",
                                    ),
                                ),
                            ),
                            OcrMenuRow(
                                label = "Стоп чтения",
                                action = OcrMenuAction(
                                    icon = if (readingActive) Icons.Outlined.StopCircle else Icons.Outlined.Pause,
                                    onClick = {
                                        beepAction()
                                        onStopSpeak()
                                    },
                                    containerColor = if (readingActive) {
                                        MaterialTheme.colorScheme.errorContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                    contentColor = if (readingActive) {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    contentDescription = "Стоп чтения",
                                ),
                            ),
                            rowUnlessHidden(
                                "r_instant_sc",
                                OcrMenuRow(
                                    label = "Скриншот сейчас",
                                    action = OcrMenuAction(
                                        icon = Icons.Outlined.CameraAlt,
                                        onClick = {
                                            beepAction()
                                            onInstantScreenshot()
                                        },
                                        contentDescription = "Скриншот сейчас",
                                    ),
                                ),
                            ),
                            rowUnlessHidden(
                                "r_voiceicons",
                                OcrMenuRow(
                                    label = if (voiceIconsEnabled) "Значки озвучки: вкл" else "Значки озвучки: выкл",
                                    action = OcrMenuAction(
                                        icon = Icons.Outlined.RecordVoiceOver,
                                        onClick = {
                                            beepAction()
                                            onVoiceIconsToggle(!voiceIconsEnabled)
                                        },
                                        contentColor = if (voiceIconsEnabled) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        contentDescription = "Значки озвучки",
                                    ),
                                ),
                            ),
                            rowUnlessHidden(
                                "r_order",
                                OcrMenuRow(
                                    label = "$presetTitle · $presetOrder",
                                    action = OcrMenuAction(
                                        icon = Icons.Outlined.DocumentScanner,
                                        onClick = {
                                            beepAction()
                                            onContentPresetChange(
                                                when (contentPreset) {
                                                    "manga" -> "manhwa"
                                                    "manhwa" -> "manhua"
                                                    "manhua" -> "comic"
                                                    "comic" -> "manga"
                                                    else -> "manga"
                                                },
                                            )
                                        },
                                        contentDescription = "Режим чтения: $presetTitle",
                                    ),
                                ),
                            ),
                            OcrMenuRow(
                                label = "AI-чат",
                                action = OcrMenuAction(
                                    icon = Icons.Outlined.SmartToy,
                                    onClick = {
                                        beepAction()
                                        menuOpen = false
                                        onOpenAiChat()
                                    },
                                    contentDescription = "AI-чат",
                                ),
                            ),
                            // Голос: режим (авто/ручной) и, в ручном, выбор пола. Две кнопки
                            // рядом — чтобы не уходить в настройки посреди главы.
                            OcrMenuRow(
                                label = if (manualVoice) "Голос: вручную" else "Голос: авто",
                                action = OcrMenuAction(
                                    icon = if (manualVoice) Icons.Outlined.TouchApp else Icons.Outlined.AutoMode,
                                    onClick = {
                                        beepAction()
                                        manualVoice = !manualVoice
                                        onVoiceModeChange(manualVoice)
                                    },
                                    contentDescription = "Режим выбора голоса",
                                ),
                                secondary = OcrMenuAction(
                                    glyph = if (voiceGender == "male") "♂" else "♀",
                                    onClick = {
                                        if (!manualVoice) return@OcrMenuAction
                                        beepAction()
                                        voiceGender = if (voiceGender == "male") "female" else "male"
                                        onVoiceGenderChange(voiceGender)
                                    },
                                    // В ручном режиме кнопка подсвечена: в авто пол выбирать
                                    // нечем, и тап остаётся вхолостую (см. onClick выше).
                                    active = manualVoice,
                                    contentDescription = "Голос: ${if (voiceGender == "male") "мужской" else "женский"}",
                                ),
                            ),
                            OcrMenuRow(
                                label = "Все настройки",
                                action = OcrMenuAction(
                                    icon = Icons.Outlined.Tune,
                                    onClick = {
                                        beepAction()
                                        menuOpen = false
                                        onOpenSettingsHub()
                                    },
                                    contentDescription = "Все настройки: звук, распознавание, книги",
                                ),
                            ),
                            OcrMenuRow(
                                label = "Мужской голос",
                                action = OcrMenuAction(
                                    icon = Icons.Outlined.RecordVoiceOver,
                                    onClick = {
                                        Injekt.get<mihon.domain.ocr.service.OcrPreferences>().voicePresetGender().set("male")
                                        ctorContext.toast("Мужской голос по умолчанию")
                                    },
                                    contentDescription = "Мужской голос",
                                ),
                            ),
                            OcrMenuRow(
                                label = "Женский голос",
                                action = OcrMenuAction(
                                    icon = Icons.Outlined.RecordVoiceOver,
                                    onClick = {
                                        Injekt.get<mihon.domain.ocr.service.OcrPreferences>().voicePresetGender().set("female")
                                        ctorContext.toast("Женский голос по умолчанию")
                                    },
                                    contentDescription = "Женский голос",
                                ),
                            ),
                        ),
                        maxHeight = 480.dp,
                        footer = "yomikai " + eu.kanade.tachiyomi.AppInfo.getVersionName(),
                        // Кнопки конструктора: список пользовательских действий
                        // известен только в рантайме, в модель строк он не ложится.
                        extraContent = {
                            if (userActs.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Кнопки конструктора",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    IconButton(onClick = {
                                        ctorExpanded = !ctorExpanded
                                        ctorUiPrefs.edit().putBoolean("menu_ctor_expanded", ctorExpanded).apply()
                                    }) {
                                        Icon(
                                            if (ctorExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                                            contentDescription = "Скрыть или показать кнопки конструктора",
                                        )
                                    }
                                }
                                if (ctorExpanded) {
                                    userActs.filterNot { it.title.startsWith("Пресет") }.forEach { act ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = act.title,
                                                modifier = Modifier.weight(1f),
                                                style = MaterialTheme.typography.labelMedium,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            OcrMenuActionButton(
                                                OcrMenuAction(
                                                    icon = Icons.Outlined.Tune,
                                                    onClick = {
                                                        ctorContext.toast(eu.kanade.tachiyomi.data.ui.UiActionRegistry.apply(ctorContext, act))
                                                    },
                                                    contentDescription = act.title,
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        },
                    )
                }

                // Главная кнопка: тап — меню; перетаскивание разведено
                // отдельной обёрткой Box внутри компонента, чтобы клик и drag
                // не конфликтовали. Позицию держит вызывающий.
                OcrControlFab(
                    expanded = menuOpen,
                    onToggle = {
                        beepOpen()
                        menuOpen = !menuOpen
                    },
                    onDrag = { delta ->
                        offsetX = (offsetX + delta.x).coerceIn(-4000f, 0f)
                        offsetY = (offsetY + delta.y).coerceIn(-4000f, 400f)
                    },
                    contentDescription = if (menuOpen) "Закрыть меню" else "Меню читалки",
                )
            }
        }
    }
}

/**
 * Маленькие «часики» статуса OCR, изолированные от родителя: переворот
 * иконки дважды в секунду рекомпозирует ТОЛЬКО эту иконку, а не всё
 * плавающее меню, поэтому onUi-поток не проседает рядом с OCR.
 */
@Composable
private fun ScanHourglassStateIcon(
    modifier: Modifier = Modifier,
) {
    var flip by androidx.compose.runtime.remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            flip = !flip
            kotlinx.coroutines.delay(700)
        }
    }
    Icon(
        imageVector = if (flip) Icons.Outlined.HourglassTop else Icons.Outlined.HourglassBottom,
        contentDescription = "Сканирование",
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}