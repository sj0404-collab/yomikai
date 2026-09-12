package eu.kanade.presentation.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.util.system.toast
import mihon.data.ocr.ContentAutoPreset
import mihon.data.ocr.OcrContentType
import mihon.data.ocr.OcrRegionRules
import mihon.data.ocr.ReaderContextBus
import mihon.domain.ocr.service.OcrPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Полная настройка «как читать баблы» прямо в читалке, одной кнопкой.
 *
 * Диалог редактирует ТЕ ЖЕ настройки, что и раздел OCR в главном меню, но не
 * уводит из читалки: применяется сразу, следующее авточтение (и скан) использует
 * новый порядок/область/язык без перезапуска.
 *
 * Порядок чтения здесь — источник истины для авточтения. Пресет типа контента
 * (манга/манхва/комикс) задаёт свой порядок; выбор порядка вручную переводит
 * пресет в «Сбалансированный», чтобы выбранный порядок действительно применился
 * (иначе непрозрачно «управляется пресетом»).
 */
@Composable
fun OcrBubbleSettingsDialog(
    onDismissRequest: () -> Unit,
    onOpenFullSettings: () -> Unit,
    /** Проброс выбранного порядка в состояние читалки (цикл-кнопка плавающего меню). */
    onReadingOrderChange: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember { Injekt.get<OcrPreferences>() }
    val readerPrefs = remember { Injekt.get<ReaderPreferences>() }

    var contentType by remember { mutableStateOf(OcrContentType.fromId(prefs.contentType().get())) }
    var presetRegion by remember { mutableStateOf(prefs.presetScanRegion().get()) }
    var contentOrder by remember { mutableStateOf(prefs.contentType().get()) }
    val effectiveOrder = remember(contentOrder) { OcrRegionRules.readingOrderFor(prefs) }
    var manualOrder by remember { mutableStateOf(prefs.scanReadingOrder().get()) }
    var language by remember { mutableStateOf(prefs.autoReadLanguage().get()) }
    var translate by remember { mutableStateOf(prefs.autoReadTranslate().get()) }
    var voiceIcons by remember { mutableStateOf(prefs.voiceIcons().get()) }
    var showNumbers by remember { mutableStateOf(prefs.showSpeechNumbers().get()) }
    var aiGender by remember { mutableStateOf(prefs.aiGenderVoices().get()) }

    // Пресет типа контента меняет число (максимум) — плюс, как в настройках,
    // правит режим чтения вьюера и запоминается как ручной выбор для манги.
    fun applyContentType(type: OcrContentType) {
        contentType = type
        contentOrder = type.id
        prefs.contentType().set(type.id)
        val mode = ReadingMode.fromOcrHint(type.viewer)
        if (mode != null) readerPrefs.defaultReadingMode.set(mode.flagValue)
        ContentAutoPreset.rememberManual(ReaderContextBus.current.value?.mangaId, type.id, prefs)
        if (type.id != "balanced") {
            context.toast("Пресет «${type.title}»: порядок чтения определяет пресет")
        }
    }

    fun applyRegion(id: String) {
        prefs.presetScanRegion().set(id)
        presetRegion = id
    }

    fun applyOrder(order: String) {
        // Ручной порядок работает только у «Сбалансированного» пресета (увлечён
        // правилами OcrRegionRules.readingOrderFor). Иначе молча игнорируется.
        if (contentType.id != "balanced") {
            contentType = OcrContentType.BALANCED
            contentOrder = "balanced"
            prefs.contentType().set("balanced")
            context.toast("Пресет переведён в «Сбалансированный» — порядок чтения применяется")
        }
        manualOrder = order
        prefs.scanReadingOrder().set(order)
        onReadingOrderChange(order)
        context.toast(
            when (order) {
                "ltr" -> "Порядок чтения: слева направо (комиксы)"
                "vertical" -> "Порядок чтения: сверху вниз (вебтуны)"
                else -> "Порядок чтения: справа налево (манга)"
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
        title = { Text("Настройки OCR: чтение баблов") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Тип контента (пресет распознавания)", style = MaterialTheme.typography.titleSmall)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    OcrContentType.entries.forEach { type ->
                        FilterChip(
                            selected = contentType == type,
                            onClick = { applyContentType(type) },
                            label = { Text(type.title) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
                Text(
                    contentType.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Text(
                    "Область страницы",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("full" to "Вся страница", "top" to "Верхняя половина", "bottom" to "Нижняя половина")
                        .forEach { (id, label) ->
                            FilterChip(
                                selected = presetRegion == id,
                                onClick = { applyRegion(id) },
                                label = { Text(label) },
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                }

                Text(
                    "Порядок чтения баблов",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(
                        "rtl" to "← Справа налево (манга)",
                        "ltr" to "→ Слева направо (комиксы)",
                        "vertical" to "↓ Сверху вниз (вебтуны)",
                    ).forEach { (id, label) ->
                        FilterChip(
                            selected = effectiveOrder == id,
                            onClick = { applyOrder(id) },
                            label = { Text(label) },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
                Text(
                    if (contentType.id == "balanced") {
                        "Порядок выбирается тут и применяется к следующей странице «сверху вниз, колонками»."
                    } else {
                        "Порядок задаёт пресет «${contentType.title}»: ${OcrRegionRules.orderTitle(effectiveOrder)}. " +
                            "Нажмите на порядок, чтобы выбрать его вручную."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Text(
                    "Язык чтения",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("ru", "en", "ja", "ko", "zh", "any")
                        .forEach { lang ->
                            FilterChip(
                                selected = language == lang,
                                onClick = {
                                    language = lang
                                    prefs.autoReadLanguage().set(lang)
                                },
                                label = {
                                    Text(
                                        when (lang) {
                                            "ru" -> "🇷🇺 Русский"
                                            "en" -> "🇬🇧 English"
                                            "ja" -> "🇯🇵 日本語"
                                            "ko" -> "🇰🇷 한국어"
                                            "zh" -> "🇨🇳 中文"
                                            else -> "🌍 Любой"
                                        },
                                    )
                                },
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        }
                }
                Text(
                    "Читаются только реплики этого языка; остальной текст кадра игнорируется.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            translate = !translate
                            prefs.autoReadTranslate().set(translate)
                        },
                ) {
                    Checkbox(
                        checked = translate,
                        onCheckedChange = {
                            translate = it
                            prefs.autoReadTranslate().set(it)
                        },
                    )
                    Column {
                        Text("Переводить реплики на русский", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            voiceIcons = !voiceIcons
                            prefs.voiceIcons().set(voiceIcons)
                        },
                ) {
                    Checkbox(checked = voiceIcons, onCheckedChange = { voiceIcons = it; prefs.voiceIcons().set(it) })
                    Column {
                        Text("Значки 🔊 на репликах", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showNumbers = !showNumbers
                            prefs.showSpeechNumbers().set(showNumbers)
                        },
                ) {
                    Checkbox(
                        checked = showNumbers,
                        onCheckedChange = {
                            showNumbers = it
                            prefs.showSpeechNumbers().set(it)
                        },
                    )
                    Column {
                        Text("Номера реплик (порядок чтения)", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            aiGender = !aiGender
                            prefs.aiGenderVoices().set(aiGender)
                        },
                ) {
                    Checkbox(checked = aiGender, onCheckedChange = { aiGender = it; prefs.aiGenderVoices().set(it) })
                    Column {
                        Text("AI-голоса по полу говорящего", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Встроенная морфология + онлайн-ассистент (Zen — без ключа) определяют, " +
                                "кто говорит: реплики озвучиваются голосом ♀/♂.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismissRequest()
                    onOpenFullSettings()
                },
            ) { Text("Все настройки OCR…") }
        },
        dismissButton = { TextButton(onClick = onDismissRequest) { Text("Готово") } },
    )
}