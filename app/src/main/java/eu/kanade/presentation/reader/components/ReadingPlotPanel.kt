package eu.kanade.presentation.reader.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.ai.ReadingPlotAgentHolder

/**
 * Вкладка «Сюжет»: пересказ услышанного при чтении, написанный моделью.
 *
 * Показывает ровно то, что вернула модель. Если пересказа нет — видна причина
 * («нет ключа», «пока нечего пересказывать», «модель не ответила»), а не
 * текст-рыба: иначе читатель принял бы выдумку приложения за пересказ книги.
 */
@Composable
fun ReadingPlotPanel(
    mangaId: Long?,
    showToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val agent = ReadingPlotAgentHolder.agent
    val state by agent.state.collectAsState()

    // Возврат к книге показывает уже написанный пересказ, а не пустую вкладку
    // до первого запроса к модели.
    androidx.compose.runtime.LaunchedEffect(mangaId) {
        agent.loadFor(mangaId)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Услышано реплик: ${state.heard}",
                style = MaterialTheme.typography.labelMedium,
            )
            TextButton(onClick = { agent.refresh(mangaId) }) { Text("Обновить") }
        }

        when {
            state.thinking -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp))
                Text("  Модель пишет пересказ…", style = MaterialTheme.typography.bodySmall)
            }

            state.text.isNotBlank() -> {
                Text(state.text, style = MaterialTheme.typography.bodyMedium)
                if (state.model.isNotBlank()) {
                    Text(
                        "модель: ${state.model}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                // Размышления модели: раньше они приходили и молча терялись.
                // Свёрнуты по умолчанию — они длинные, а читателю нужен итог,
                // но возможность подсмотреть «почему так» должна быть.
                if (state.reasoning.isNotBlank()) {
                    var reasoningOpen by androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(false)
                    }
                    TextButton(onClick = { reasoningOpen = !reasoningOpen }) {
                        Text(if (reasoningOpen) "Размышления ▾" else "Размышления ▸")
                    }
                    if (reasoningOpen) {
                        Text(
                            text = state.reasoning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            else -> Text(
                // Честное пустое состояние: причина вместо правдоподобного текста.
                state.reason ?: "Пересказ появится, как только прозвучат реплики.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}