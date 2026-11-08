package eu.kanade.tachiyomi.ui.koma

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mihon.data.ocr.*

/**
 * Выбор OCR движка для KOMA.
 * 
 * Доступные движки:
 * - KomaOnnxOcrEngine (локальный ONNX)
 * - GlensOcrEngine (Google Lens онлайн)
 * - GoogleAiOcrEngine (Google AI)
 * - OpenRouterOcrEngine (OpenRouter онлайн)
 * - FastOcrEngine (быстрый локальный)
 * - LegacyOcrEngine (устаревший)
 * - MlKitOcrEngine (ML Kit)
 * - и другие
 */
data class OcrEngineOption(
    val id: String,
    val title: String,
    val description: String,
    val online: Boolean,
    val create: (Context) -> OcrEngine
)

val ENGINE_OPTIONS = listOf(
    OcrEngineOption(
        id = "koma_onnx",
        title = "KOMA ONNX",
        description = "Локальный ONNX движок (cyrillic.onnx)",
        online = false,
        create = { ctx -> KomaOnnxOcrEngine(ctx) }
    ),
    OcrEngineOption(
        id = "glens",
        title = "Google Lens",
        description = "Онлайн OCR от Google (требует интернет)",
        online = true,
        create = { ctx -> GlensOcrEngine() }
    ),
    OcrEngineOption(
        id = "google_ai",
        title = "Google AI",
        description = "Google AI OCR (требует API ключ)",
        online = true,
        create = { ctx -> GoogleAiOcrEngine() }
    ),
    OcrEngineOption(
        id = "openrouter",
        title = "OpenRouter",
        description = "OpenRouter OCR (требует API ключ)",
        online = true,
        create = { ctx -> OpenRouterOcrEngine() }
    ),
    OcrEngineOption(
        id = "fast",
        title = "Fast OCR",
        description = "Быстрый локальный движок",
        online = false,
        create = { ctx -> FastOcrEngine() }
    ),
    OcrEngineOption(
        id = "legacy",
        title = "Legacy OCR",
        description = "Устаревший локальный движок",
        online = false,
        create = { ctx -> LegacyOcrEngine() }
    ),
    OcrEngineOption(
        id = "mlkit",
        title = "ML Kit",
        description = "Google ML Kit OCR",
        online = false,
        create = { ctx -> MlKitOcrEngine() }
    ),
    OcrEngineOption(
        id = "cyrillic",
        title = "Cyrillic OCR",
        description = "Специализированный движок для кириллицы",
        online = false,
        create = { ctx -> CyrillicOcrEngine() }
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
                                        androidx.compose.material.icons.Icons.Outlined.Cloud,
                                        "онлайн",
                                        modifier = Modifier.size(16.dp)
                                    )
                                } else {
                                    Icon(
                                        androidx.compose.material.icons.Icons.Outlined.Storage,
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

/**
 * Фабрика для создания OCR движка по ID.
 */
fun createEngine(engineId: String, context: Context): OcrEngine? {
    val option = ENGINE_OPTIONS.find { it.id == engineId } ?: return null
    return option.create(context)
}
