package eu.kanade.presentation.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.ai.AiAssistant
import kotlinx.coroutines.launch

/**
 * Модели OpenRouter: живой список :free, свои модели (ручной ввод) и кнопка
 * «Тест» у каждой. Общий для диалога читалки и настроек озвучки, чтобы список
 * и проверка работали одинаково.
 *
 * Тест — один короткий запрос именно к этой модели, без автосмены на Zen,
 * поэтому результат относится к выбранной модели, а не к ротации.
 */
@Composable
fun OpenRouterModelsSection(
    apiKey: String,
    selected: String,
    onSelect: (String) -> Unit,
    liveModels: List<String>,
    customModels: List<String>,
    onAddCustom: (String) -> Unit,
    onRemoveCustom: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val results = remember { mutableStateMapOf<String, String>() }
    var newId by remember { mutableStateOf("") }
    val all = (customModels + liveModels).distinct()

    OutlinedTextField(
        value = newId,
        onValueChange = { newId = it },
        label = { Text("Своя модель (id, например vendor/name:free)") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    )
    TextButton(
        onClick = {
            val id = newId.trim()
            if (id.isNotBlank()) {
                onAddCustom(id)
                onSelect(id)
                newId = ""
            }
        },
    ) { Text("＋ Добавить модель") }

    Text(
        text = "Модели OpenRouter (нажмите «Тест», чтобы проверить):",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
    Column(modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
        all.forEach { m ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(m) },
            ) {
                RadioButton(selected = selected == m, onClick = { onSelect(m) })
                Column(modifier = Modifier.weight(1f)) {
                    Text(m, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    results[m]?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(
                    onClick = {
                        results[m] = "…проверяю"
                        scope.launch { results[m] = AiAssistant.testOpenRouterModel(m, apiKey) }
                    },
                ) { Text("Тест") }
                if (m in customModels) {
                    TextButton(onClick = { onRemoveCustom(m) }) { Text("✕") }
                }
            }
        }
    }
}
