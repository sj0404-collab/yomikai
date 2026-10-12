package eu.kanade.presentation.reader.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.data.ai.AiConsole
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Живой журнал работы ИИ: раунды агента, запросы к модели, каждый инструмент
 * с временем и итогом, стадии распознавания и озвучки.
 *
 * Существует потому, что смотреть на это было негде: журнал запросов к модели
 * жил в настройках озвучки и появлялся только при включённом `aiGenderVoices`,
 * ход агента писался внутрь ответа чата и прокручивался вместе с диалогом, а
 * история OCR и озвучки была отдельным экраном. Здесь всё это в одном списке,
 * который обновляется на каждое событие.
 *
 * Одна кнопка «Остановить» на всё: агент, чтение главы, озвучка и пересказ
 * «Сюжета». Отдельные кнопки у каждого подсистемы не нужны — читателю важно,
 * чтобы всё замолчало сразу, а не по частям.
 */
@Composable
fun AiConsoleDialog(
    onClose: () -> Unit,
    /** Полная остановка: агент, авточтение, озвучка, «Сюжет». */
    onStopEverything: () -> Unit,
) {
    val entries by AiConsole.entries.collectAsState()
    var kindFilter by remember { mutableStateOf<AiConsole.Kind?>(null) }

    // Фильтр по роду события: ход агента и запросы к модели — разные вещи, и
    // вперемешку они нечитаемы. Пусто = все.
    val shown = remember(entries, kindFilter) {
        if (kindFilter == null) entries else entries.filter { it.kind == kindFilter }
    }

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = { AiConsole.clear() }) { Text("Очистить") }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("Закрыть") }
        },
        title = {
            Column {
                Text("Консоль ИИ")
                Text(
                    text = "событий: ${entries.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = kindFilter == null,
                        onClick = { kindFilter = null },
                        label = { Text("все") },
                        modifier = Modifier.height(30.dp),
                    )
                    AiConsole.Kind.entries.forEach { kind ->
                        FilterChip(
                            selected = kindFilter == kind,
                            onClick = { kindFilter = kind },
                            label = { Text(kind.title()) },
                            modifier = Modifier.height(30.dp),
                        )
                    }
                }
                if (shown.isEmpty()) {
                    Text(
                        text = "Пока пусто. Журнал наполняется сам: ход агента, " +
                            "запросы к модели, инструменты и стадии чтения.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(shown, key = { it.id }) { entry ->
                            ConsoleRow(entry)
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ConsoleRow(entry: AiConsole.Entry) {
    val stamp = remember(entry.timeMs) { timeFormat.format(Date(entry.timeMs)) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = entryColor(entry),
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stamp,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = " ${entry.kind.marker()} ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entry.ms?.let {
                Text(
                    text = humanMs(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = entry.title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        entry.detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Строка ошибки/сбоя заметнее обычной: иначе в списке она теряется. */
@Composable
private fun entryColor(entry: AiConsole.Entry): Color = when (entry.level) {
    AiConsole.Level.ERROR -> MaterialTheme.colorScheme.errorContainer
    AiConsole.Level.WARN -> MaterialTheme.colorScheme.tertiaryContainer
    AiConsole.Level.OK -> MaterialTheme.colorScheme.secondaryContainer
    AiConsole.Level.INFO -> MaterialTheme.colorScheme.surfaceContainerHigh
}

private fun AiConsole.Kind.title(): String = when (this) {
    AiConsole.Kind.ROUND -> "раунды"
    AiConsole.Kind.MODEL -> "модель"
    AiConsole.Kind.TOOL -> "инструменты"
    AiConsole.Kind.USER -> "действия"
    AiConsole.Kind.OCR -> "чтение"
    AiConsole.Kind.NOTE -> "заметки"
}

/** Метка вида строки: чтобы в потоке читалось, что это за событие. */
private fun AiConsole.Kind.marker(): String = when (this) {
    AiConsole.Kind.ROUND -> "РАУНД"
    AiConsole.Kind.MODEL -> "МОДЕЛЬ"
    AiConsole.Kind.TOOL -> "ИНСТР"
    AiConsole.Kind.USER -> "ДЕЙСТ"
    AiConsole.Kind.OCR -> "ЧТЕНИЕ"
    AiConsole.Kind.NOTE -> "ЗАМЕТ"
}

private fun humanMs(ms: Long): String = when {
    ms < 1000 -> "$ms мс"
    ms < 60_000 -> "${ms / 1000}.${(ms % 1000) / 100} с"
    else -> "${ms / 60_000} мин ${(ms % 60_000) / 1000} с"
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())