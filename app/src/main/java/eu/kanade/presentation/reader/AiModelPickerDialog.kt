package eu.kanade.presentation.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import eu.kanade.tachiyomi.util.system.toast
import mihon.domain.ocr.service.OcrPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Быстрая смена AI-модели прямо в читалке (по требованию пользователя).
 *
 * Раньше AI-кнопка в читалке открывала только диалог озвучки, где выбор модели
 * был зарыт среди голосов; сменить модель, не уходя из читалки и не раскрывая
 * TTS-настройки, было нельзя. Этот диалог даёт только то, что просили:
 * провайдер + модель (или API-ключ для OpenRouter). Голоса остаются в
 * отдельном диалоге озвучки — сюда он открывается одной кнопкой.
 *
 * Пишет в те же преференсы, что и настройки AI-чата и озвучки
 * (`pref_ai_provider`, `pref_zen_model`, `pref_openrouter_free_model`,
 * `pref_openrouter_api_key`), поэтому второй источник истины не возникает.
 */
@Composable
fun AiModelPickerDialog(
    onDismissRequest: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { Injekt.get<OcrPreferences>() }

    var aiProvider by remember { mutableStateOf(prefs.aiProvider().get()) }
    var zenModel by remember { mutableStateOf(prefs.zenModel().get()) }
    var orFreeModel by remember { mutableStateOf(prefs.openrouterFreeModel().get()) }
    var orKey by remember { mutableStateOf(prefs.openrouterApiKey().get()) }
    var orModels by remember { mutableStateOf<List<String>>(emptyList()) }

    val userProviders = remember(context) {
        eu.kanade.tachiyomi.data.ai.AiProviders.list(context)
    }

    // Живой список :free моделей OpenRouter (фолбэк при оффлайне — в fallback).
    LaunchedEffect(aiProvider) {
        if (aiProvider == eu.kanade.tachiyomi.data.ai.AiAssistant.PROVIDER_OPENROUTER && orModels.isEmpty()) {
            orModels = runCatching {
                eu.kanade.tachiyomi.data.ai.AiAssistant.fetchOpenRouterFreeModels()
            }.getOrElse { emptyList() }
        }
    }

    fun save() {
        prefs.aiProvider().set(aiProvider)
        prefs.zenModel().set(zenModel)
        prefs.openrouterFreeModel().set(orFreeModel)
        prefs.openrouterApiKey().set(orKey.trim())
        context.toast("AI-модель: ${currentModelLabel(aiProvider, zenModel, orFreeModel, userProviders)}")
        onDismissRequest()
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Сменить AI-модель") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "Провайдер:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(modifier = Modifier.padding(top = 4.dp)) {
                    FilterChip(
                        selected = aiProvider == eu.kanade.tachiyomi.data.ai.AiAssistant.PROVIDER_ZEN,
                        onClick = { aiProvider = eu.kanade.tachiyomi.data.ai.AiAssistant.PROVIDER_ZEN },
                        label = { Text("Zen (без ключа)") },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    FilterChip(
                        selected = aiProvider == eu.kanade.tachiyomi.data.ai.AiAssistant.PROVIDER_OPENROUTER,
                        onClick = { aiProvider = eu.kanade.tachiyomi.data.ai.AiAssistant.PROVIDER_OPENROUTER },
                        label = { Text("OpenRouter") },
                    )
                }
                // Свои провайдеры пользователя (Ollama, LM Studio, прокси).
                if (userProviders.isNotEmpty()) {
                    Text(
                        text = "Свои:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    userProviders.forEach { spec ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { aiProvider = spec.id },
                        ) {
                            RadioButton(selected = aiProvider == spec.id, onClick = { aiProvider = spec.id })
                            Text(spec.title.ifBlank { spec.id }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                if (aiProvider == eu.kanade.tachiyomi.data.ai.AiAssistant.PROVIDER_ZEN) {
                    Text(
                        text = "Модель Zen (бесплатно, без регистрации):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Column(modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                        eu.kanade.tachiyomi.data.ai.AiAssistant.ZEN_MODELS.forEach { m ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { zenModel = m },
                            ) {
                                RadioButton(selected = zenModel == m, onClick = { zenModel = m })
                                Text(m, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                } else if (aiProvider == eu.kanade.tachiyomi.data.ai.AiAssistant.PROVIDER_OPENROUTER) {
                    OutlinedTextField(
                        value = orKey,
                        onValueChange = { orKey = it },
                        label = { Text("OpenRouter API-ключ") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                    Text(
                        text = if (orModels.isEmpty()) "Загрузка списка :free моделей…" else "Бесплатные модели (:free):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Column(modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                        val list = orModels.ifEmpty { eu.kanade.tachiyomi.data.ai.AiAssistant.OPENROUTER_FREE_FALLBACK }
                        list.forEach { m ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { orFreeModel = m },
                            ) {
                                RadioButton(selected = orFreeModel == m, onClick = { orFreeModel = m })
                                Text(m, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                        }
                    }
                } else {
                    // Пользовательский провайдер; его модель и адрес фиксированы
                    // в объявлении, поэтому показываем их как справку.
                    val spec = userProviders.find { it.id == aiProvider }
                    if (spec != null) {
                        Text(
                            text = "Модель: ${spec.model}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(
                            text = "Адрес: ${spec.baseUrl}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Модель и ключ заданы в файле провайдера (workspace/providers) — менять их можно оттуда.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }) { Text("✓ Применить") }
        },
        dismissButton = {
            TextButton(onClick = onOpenVoiceSettings) { Text("Голос и озвучка…") }
        },
    )
}

/** Текст «модель» для тоста после применения, чтобы было видно, что сменилось. */
private fun currentModelLabel(
    provider: String,
    zenModel: String,
    orFreeModel: String,
    userProviders: List<eu.kanade.tachiyomi.data.ai.AiProviders.Spec>,
): String = when {
    provider == eu.kanade.tachiyomi.data.ai.AiAssistant.PROVIDER_ZEN -> "Zen • $zenModel"
    provider == eu.kanade.tachiyomi.data.ai.AiAssistant.PROVIDER_OPENROUTER ->
        "OpenRouter • ${orFreeModel.ifBlank { eu.kanade.tachiyomi.data.ai.AiAssistant.OPENROUTER_FREE_FALLBACK.first() }}"
    else -> userProviders.find { it.id == provider }?.title?.ifBlank { provider } ?: provider
}
