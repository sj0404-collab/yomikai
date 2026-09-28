package eu.kanade.presentation.reader

import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.domain.dictionary.OcrResultPresentation
import eu.kanade.tachiyomi.ui.dictionary.DictionarySearchScreenModel
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.ocr.model.OcrTextSource
import mihon.feature.ocr.selectedOcrModel
import mihon.feature.ocr.titleRes
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data class OcrResultPopupSettings(
    val widthDp: Int,
    val heightDp: Int,
    val contentScale: Float,
)

@Composable
fun OcrResultOverlay(
    onDismissRequest: () -> Unit,
    presentation: OcrResultPresentation,
    popupSettings: OcrResultPopupSettings,
    dimBackground: Boolean,
    queryText: String,
    initialSearchText: String = queryText,
    /**
     * Кто распознал: при ручном выборе областей качество сильно зависит от
     * движка, и без подписи непонятно, почему один и тот же фрагмент читается
     * то лучше, то хуже. null — источник неизвестен, подписи нет.
     */
    source: OcrTextSource? = null,
    anchorRect: RectF?,
    onCopyText: () -> Unit,
    searchState: DictionarySearchScreenModel.State,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onTermGroupClick: (List<DictionaryTerm>) -> Unit,
    onPlayAudioClick: (List<DictionaryTerm>) -> Unit,
    onSpeak: () -> Unit = {},
    onChooseVoice: () -> Unit = {},
    onSpeakRole: (String) -> Unit = {},
    onAddToDictionary: () -> Unit = {},
    /**
     * Убрать распознанный текст со страницы, не закрывая карточку. Раньше
     * оверлей нельзя было убрать, не закрыв результат, — а на светлой
     * странице он всё равно мешал.
     */
    onHidePageOverlay: (() -> Unit)? = null,
) {
    BackHandler(onBack = onDismissRequest)
    // Словарей нет — не дёргаем поиск и не показываем «No Dictionaries
    // Enabled»: пользователю нужен сам распознанный текст.
    val noDictionaries = searchState.dictionaries.isEmpty()
    LaunchedEffect(queryText, initialSearchText, noDictionaries) {
        if (queryText.isNotBlank() && !noDictionaries) {
            onQueryChange(queryText)
            onSearch(initialSearchText)
        }
    }

    when {
        noDictionaries -> {
            OcrPlainTextCard(
                text = queryText,
                source = source,
                dimBackground = dimBackground,
                onDismissRequest = onDismissRequest,
                onCopyText = onCopyText,
                onSpeak = onSpeak,
                onChooseVoice = onChooseVoice,
                onSpeakRole = onSpeakRole,
                onAddToDictionary = onAddToDictionary,
                onHidePageOverlay = onHidePageOverlay,
            )
        }
        presentation == OcrResultPresentation.POPUP && anchorRect != null -> {
            OcrResultPopup(
                onDismissRequest = onDismissRequest,
                anchorRect = anchorRect,
                settings = popupSettings,
                onCopyText = onCopyText,
                searchState = searchState,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                onTermGroupClick = onTermGroupClick,
                onPlayAudioClick = onPlayAudioClick,
                onSpeak = onSpeak,
                onChooseVoice = onChooseVoice,
                onSpeakRole = onSpeakRole,
                onAddToDictionary = onAddToDictionary,
                onHidePageOverlay = onHidePageOverlay,
            )
        }
        else -> {
            OcrResultBottomSheet(
                onDismissRequest = onDismissRequest,
                onCopyText = onCopyText,
                searchState = searchState,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                onTermGroupClick = onTermGroupClick,
                onPlayAudioClick = onPlayAudioClick,
                actions = {
                    OcrResultActionBar(
                        onSpeak = onSpeak,
                        onCopyText = onCopyText,
                        onChooseVoice = onChooseVoice,
                        onSpeakRole = onSpeakRole,
                        onAddToDictionary = onAddToDictionary,
                        onHidePageOverlay = onHidePageOverlay,
                    )
                },
            )
        }
    }
}

/**
 * Затемнение, тап по которому закрывает результат.
 *
 * Раньше блок был написан, но завёрнут в `if (false)`: карточка висела
 * поверх страницы, и закрыть её можно было только кнопкой в самой карточке
 * или аппаратной «назад». Теперь тап мимо карточки и по затемнению гасит
 * результат — то самое, чего не хватало.
 */
@Composable
private fun OcrDismissScrim(
    dim: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (dim) {
                    Modifier.background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                } else {
                    Modifier
                },
            )
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismissRequest,
            ),
    )
}

/**
 * Карточка распознанного текста без словарной части: текст можно скопировать
 * и озвучить, лишних сообщений «словари не найдены» нет.
 *
 * Карточка намеренно небольшая: текст ограничен по высоте и прокручивается
 * внутри, а действия собраны в одну строку иконок под ним. Раньше здесь были
 * два ряда подписей и отдельные плавающие кнопки — вместе они закрывали
 * половину экрана.
 */
@Composable
private fun OcrPlainTextCard(
    text: String,
    source: OcrTextSource?,
    dimBackground: Boolean,
    onDismissRequest: () -> Unit,
    onCopyText: () -> Unit,
    onSpeak: () -> Unit = {},
    onChooseVoice: () -> Unit = {},
    onSpeakRole: (String) -> Unit = {},
    onAddToDictionary: () -> Unit = {},
    onHidePageOverlay: (() -> Unit)? = null,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Не больше трети экрана: карточка не должна закрывать рисунок.
        val maxTextHeight = (maxHeight * 0.3f).coerceAtLeast(96.dp)

        OcrDismissScrim(dim = dimBackground, onDismissRequest = onDismissRequest)

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 24.dp)
                // Гасим тап мимо текста, иначе тап по пустому месту карточки
                // закрывал бы результат вместо выделения текста.
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
        ) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
                        OcrEngineCaption(source)
                    }
                    IconButton(onClick = onDismissRequest) {
                        Icon(Icons.Outlined.Close, contentDescription = "Закрыть")
                    }
                }

                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxTextHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(end = 8.dp),
                )

                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(4.dp))

                OcrResultActionBar(
                    onSpeak = onSpeak,
                    onCopyText = onCopyText,
                    onChooseVoice = onChooseVoice,
                    onSpeakRole = onSpeakRole,
                    onAddToDictionary = onAddToDictionary,
                    onHidePageOverlay = onHidePageOverlay,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Подпись «кто распознал» над распознанным текстом.
 *
 * Если текст прочитал не тот движок, который выбран в настройках (без сети
 * онлайн пропускается и читает локальный), это сказано прямо: иначе
 * читатель сравнивает качество двух разных движков и делает вывод, что они
 * одинаковые.
 */
@Composable
private fun OcrEngineCaption(source: OcrTextSource?) {
    if (source == null) return
    val engineName = stringResource(source.engine.titleRes)
    val text = if (source.usedInsteadOfSelected) {
        val selectedName = stringResource(selectedOcrModel().titleRes)
        stringResource(MR.strings.ocr_recognized_by_fallback, engineName, selectedName)
    } else {
        stringResource(MR.strings.ocr_recognized_by, engineName)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
