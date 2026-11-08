package eu.kanade.tachiyomi.ui.koma

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Выбор OCR движка для KOMA.
 * 
 * Доступные движки (ID):
 * - koma_onnx (локальный ONNX)
 * - glens (Google Lens онлайн)
 * - google_ai (Google AI)
 * - openrouter (OpenRouter онлайн)
 * - fast (быстрый локальный)
 * - legacy (устаревший)
 * - mlkit (ML Kit)
 * - cyrillic (кириллица)
 */
data class OcrEngineOption(
    val id: String,
    val title: String,
    val description: String,
    val online: Boolean
)

val ENGINE_OPTIONS = listOf(
    OcrEngineOption(
        id = "koma_onnx",
        title = "KOMA ONNX",
        description = "Локальный ONNX движок (cyrillic.onnx)",
        online = false
    ),
    OcrEngineOption(
        id = "glens",
        title = "Google Lens",
        description = "Онлайн OCR от Google (требует интернет)",
        online = true
    ),
    OcrEngineOption(
        id = "google_ai",
        title = "Google AI",
        description = "Google AI OCR (требует API ключ)",
        online = true
    ),
    OcrEngineOption(
        id = "openrouter",
        title = "OpenRouter",
        description = "OpenRouter OCR (требует API ключ)",
        online = true
    ),
    OcrEngineOption(
        id = "fast",
        title = "Fast OCR",
        description = "Быстрый локальный движок",
        online = false
    ),
    OcrEngineOption(
        id = "legacy",
        title = "Legacy OCR",
        description = "Устаревший локальный движок",
        online = false
    ),
    OcrEngineOption(
        id = "mlkit",
        title = "ML Kit",
        description = "Google ML Kit OCR",
        online = false
    ),
    OcrEngineOption(
        id = "cyrillic",
        title = "Cyrillic OCR",
        description = "Специализированный движок для кириллицы",
        online = false
    ),
)

@Composable
fun KomaEngineSelector(
    selectedEngineId: String,
    onEngineSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выбор OCR движка") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ENGINE_OPTIONS.forEach { option ->
                    OutlinedButton(
                        onClick = {
                            onEngineSelected(option.id)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(option.title, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.width(8.dp))
                                if (option.online) {
                                    Icon(
                                        Icons.Outlined.Cloud,
                                        "онлайн",
                                        modifier = Modifier.size(16.dp)
                                    )
                                } else {
                                    Icon(
                                        Icons.Outlined.Storage,
                                        "локально",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Text(
                                option.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )
}
