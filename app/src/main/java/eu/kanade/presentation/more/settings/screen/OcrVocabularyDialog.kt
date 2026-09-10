package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import mihon.data.ocr.OcrVocabulary

/**
 * Редактор пользовательского словаря OCR. Слова вводятся вручную или
 * переносятся из «＋ Словарь» на карточке результата распознавания. Список
 * хранится в файле приложения и сразу используется детектором границ слов.
 */
@Composable
fun OcrVocabularyDialog(onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    val words = remember { mutableStateListOf<String>().apply { addAll(OcrVocabulary.words()) } }

    fun reload() {
        words.clear()
        words.addAll(OcrVocabulary.words())
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Словарь OCR — ${words.size} слов",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Словами помечаются границы разбиения слипшегося текста и\n" +
                        "голосуется выбор кандидатов v3/v5. Сам текст не заменяется.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("Слово") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = {
                            val added = OcrVocabulary.addWord(draft)
                            if (added) {
                                draft = ""
                                reload()
                            }
                        },
                    ) {
                        Text("Добавить")
                    }
                }
                if (words.isEmpty()) {
                    Text(
                        text = "Пусто: добавляйте сюда редкие имена и термины из манги,\n" +
                            "которых нет во встроенном словаре.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        items(words, key = { it }) { word ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(word, modifier = Modifier.weight(1f))
                                TextButton(
                                    onClick = {
                                        OcrVocabulary.removeWord(word)
                                        reload()
                                    },
                                ) {
                                    Text("Удалить")
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        onClick = {
                            OcrVocabulary.clear()
                            reload()
                        },
                    ) {
                        Text("Очистить все")
                    }
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                }
            }
        }
    }
}