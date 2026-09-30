package eu.kanade.presentation.more.settings.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import mihon.data.ocr.OcrEvalRunner

/**
 * Прогоняет локальный OCR по эталонам и показывает отчёт: сколько стоит
 * загрузка моделей, сколько занимает детект и чтение, что ожидалось и что
 * движок реально прочитал (CER в процентах).
 *
 * Отчёт — обычный текст без разметки, его можно выделить и скопировать, а
 * продублирован в logcat по тегу [TAG]: на телефоне удобнее смотреть вывод
 * команды, чем разглядывать окно.
 */
private const val TAG = "OcrEval"

@Composable
fun OcrEvalDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf("Подготовка…") }
    var report by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Проверка тянет модели и гоняет шесть страниц: это секунды, а не минуты,
    // но держать диалог открытым всё равно нужно до конца.
    LaunchedEffect(Unit) {
        scope.launch {
            runCatching {
                OcrEvalRunner(context).run(onProgress = { progress = it })
            }.onSuccess { result ->
                val text = result.format()
                Log.i(TAG, text)
                report = text
            }.onFailure { cause ->
                error = cause.message ?: cause::class.java.simpleName
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            report?.let { text ->
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("OCR eval", text))
                            }
                        },
                        enabled = report != null,
                    ) { Text("Копировать") }
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                }
                HorizontalDivider()
                val text = report
                when {
                    text != null -> Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                    )

                    error != null -> Text(
                        text = "Проверка не удалась: $error",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    )

                    else -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Text(text = progress, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
