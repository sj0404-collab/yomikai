package eu.kanade.presentation.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import eu.kanade.tachiyomi.data.tts.EdgeTts
import eu.kanade.tachiyomi.data.tts.TtsSpeaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.ocr.service.OcrPreferences
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Быстрый выбор голоса для распознанного текста (кнопка «Выбрать» на карточке
 * текста / плавающих кнопках озвучки). Один список совмещает ОНЛАЙН голоса
 * Edge TTS (🌐) и ОФФЛАЙН системные голоса (📱), чтобы для каждого отмеченного
 * текста можно было нажать имя конкретного голоса, и зазвучал именно он.
 */
@Composable
fun TtsVoicePickerDialog(
    onDismissRequest: () -> Unit,
    onPickSystem: (String) -> Unit,
    onPickEdge: (String) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { Injekt.get<OcrPreferences>() }
    var edgeVoices by remember { mutableStateOf<List<EdgeTts.EdgeVoice>>(emptyList()) }
    var systemVoices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val edgePref = prefs.edgeLanguage().get()
        val currentEdge = prefs.edgeVoice().get()
        val sys = withContext(Dispatchers.IO) { TtsSpeaker.systemVoiceSpecs(context) }
        systemVoices = sys
        val all = try {
            withContext(Dispatchers.IO) { EdgeTts.fetchVoices() }
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "fetchVoices for picker failed" }
            emptyList()
        }
        val selectedLang = all.firstOrNull { it.shortName == currentEdge }?.language ?: "ru"
        edgeVoices = when (edgePref.trim()) {
            "" -> all
            "auto" -> all.filter { it.language == selectedLang }
            "🌐" -> EdgeTts.multilingualVoices(all)
            else -> all.filter { it.language == edgePref.trim().lowercase() }
        }
        loading = false
    }

    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 18.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "🎙 Выберите голос",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismissRequest) {
                        Text("Отмена")
                    }
                }
                Text(
                    text = "Нажмите имя голоса — текст озвучится именно им. " +
                        "🌐 Edge TTS нужен интернет, 📱 системные голоса работают оффлайн.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                HorizontalDivider()
                when {
                    loading -> {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier.fillMaxWidth().padding(48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    edgeVoices.isEmpty() && systemVoices.isEmpty() -> {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier.fillMaxSize().padding(48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Список голосов пуст (нет сети?" +
                                " )", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        ) {
                            if (edgeVoices.isNotEmpty()) {
                                item {
                                    SectionHeader(label = "🌐 Edge TTS (онлайн)")
                                }
                                items(count = edgeVoices.size, key = { edgeVoices[it].shortName }) { i ->
                                    VoiceRow(label = edgeVoices[i].label) {
                                        onPickEdge(edgeVoices[i].shortName)
                                    }
                                }
                            }
                            if (systemVoices.isNotEmpty()) {
                                item {
                                    SectionHeader(label = "📱 Системные голоса (оффлайн)")
                                }
                                items(count = systemVoices.size, key = { systemVoices[it].second }) { i ->
                                    VoiceRow(label = systemVoices[i].first) {
                                        onPickSystem(systemVoices[i].second)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun VoiceRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}