package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.ui.UiActionRegistry
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.data.ui.UiActionSpec
import mihon.data.ui.UiPlacement

/**
 * Компактная панель действий над распознанным текстом.
 *
 * Раньше те же действия были двумя рядами подписей в карточке плюс тремя
 * крупными плавающими кнопками у нижнего края — вместе они закрывали почти
 * половину экрана, а закрыть результат было нечем. Здесь постоянные действия
 * (озвучка, копирование, словарь, скрытие подсветки) — компактные иконки в
 * одну строку, крестик закрытия стоит в шапке карточки, а редкие действия
 * (выбор голоса, роли чтения, пользовательские действия реестра) спрятаны
 * под «⋮».
 */
@Composable
fun OcrResultActionBar(
    onSpeak: () -> Unit,
    onCopyText: () -> Unit,
    onChooseVoice: () -> Unit,
    onSpeakRole: (String) -> Unit,
    onAddToDictionary: () -> Unit,
    onHidePageOverlay: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // Пользовательские действия реестра UiActions: раньше они висели
    // отдельным прокручиваемым рядом плавающих кнопок, теперь — в меню.
    val context = LocalContext.current
    var userActions by remember { mutableStateOf<List<UiActionSpec>>(emptyList()) }
    LaunchedEffect(Unit) {
        userActions = withContext(Dispatchers.IO) {
            UiActionRegistry.list(context).filter { it.placement == UiPlacement.OCR_CARD }
        }
    }

    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        FilledTonalIconButton(onClick = onSpeak) {
            Icon(Icons.Outlined.RecordVoiceOver, contentDescription = "Озвучить")
        }
        IconButton(onClick = onCopyText) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = "Копировать")
        }
        IconButton(onClick = onAddToDictionary) {
            Icon(Icons.Outlined.BookmarkAdd, contentDescription = "Добавить в словарь")
        }
        if (onHidePageOverlay != null) {
            IconButton(onClick = onHidePageOverlay) {
                Icon(Icons.Outlined.VisibilityOff, contentDescription = "Скрыть подсветку")
            }
        }

        Spacer(Modifier.width(2.dp))

        // Box нужен, чтобы меню раскрывалось от самой кнопки «⋮», а не от
        // левого верхнего угла экрана — иначе связь с кнопкой не читается.
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "Ещё")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("Выбрать голос") },
                    onClick = {
                        menuExpanded = false
                        onChooseVoice()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Женский голос") },
                    onClick = {
                        menuExpanded = false
                        onSpeakRole("female")
                    },
                )
                DropdownMenuItem(
                    text = { Text("Мужской голос") },
                    onClick = {
                        menuExpanded = false
                        onSpeakRole("male")
                    },
                )
                DropdownMenuItem(
                    text = { Text("Голос рассказчика") },
                    onClick = {
                        menuExpanded = false
                        onSpeakRole("narrator")
                    },
                )
                if (userActions.isNotEmpty()) {
                    HorizontalDivider()
                    userActions.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(action.title) },
                            onClick = {
                                menuExpanded = false
                                context.toast(UiActionRegistry.apply(context, action))
                            },
                        )
                    }
                }
            }
        }
    }
}
