package eu.kanade.tachiyomi.ui.webbrowser

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * «Расширения и скрипты» мини-браузера.
 *
 * Встроенные расширения — тумблеры (AdBlock). Пользовательские скрипты —
 * userscript-менеджер: список, вкл/выкл, редактирование, удаление и добавление.
 * [onReinject] вызывается после изменения, чтобы применить правки к уже
 * открытой странице без перезагрузки.
 */
@Composable
fun WebScriptsDialog(
    context: Context,
    onReinject: () -> Unit,
    onDismiss: () -> Unit,
) {
    WebScriptsStore.load(context)
    val scripts by WebScriptsStore.scripts.collectAsState()
    var adBlockOn by remember { mutableStateOf(BrowserAdBlock.isEnabled(context)) }
    var editing by remember { mutableStateOf<WebScriptsStore.UserScript?>(null) }
    var isNew by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        title = { Text("Расширения и скрипты") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Встроенные расширения", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.padding(end = 8.dp)) {
                        Text("AdBlock", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Режет рекламные сети и промо-плашки",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = adBlockOn,
                        onCheckedChange = { on ->
                            adBlockOn = on
                            BrowserAdBlock.setEnabled(context, on)
                            onReinject()
                        },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                Text(
                    "Пользовательские скрипты (${scripts.size})",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (scripts.isEmpty()) {
                    Text(
                        "Пока пусто. Добавьте JS-скрипт и укажите, на каких сайтах его выполнять.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                scripts.forEach { s ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    s.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    (if (s.runAt == "start") "до загрузки · " else "после загрузки · ") + s.match,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Switch(
                                checked = s.enabled,
                                onCheckedChange = { on ->
                                    WebScriptsStore.setEnabled(context, s.id, on)
                                    onReinject()
                                },
                            )
                        }
                        Row {
                            TextButton(onClick = { editing = s; isNew = false }) { Text("Изменить") }
                            TextButton(onClick = {
                                WebScriptsStore.remove(context, s.id)
                                onReinject()
                            }) { Text("Удалить") }
                        }
                        HorizontalDivider()
                    }
                }
                TextButton(onClick = {
                    editing = WebScriptsStore.UserScript("", "", "*", "", true, "end")
                    isNew = true
                }) { Text("＋ Добавить скрипт") }
            }
        },
    )

    editing?.let { script ->
        ScriptEditorDialog(
            context = context,
            initial = script,
            isNew = isNew,
            onSaved = {
                editing = null
                onReinject()
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun ScriptEditorDialog(
    context: Context,
    initial: WebScriptsStore.UserScript,
    isNew: Boolean,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var match by remember { mutableStateOf(initial.match) }
    var code by remember { mutableStateOf(initial.code) }
    var runAt by remember { mutableStateOf(if (initial.runAt == "start") "start" else "end") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank() && code.isBlank()) {
                    onDismiss()
                    return@TextButton
                }
                if (isNew) {
                    WebScriptsStore.add(context, name, match, code, runAt)
                } else {
                    WebScriptsStore.update(
                        context,
                        initial.copy(name = name, match = match, code = code, runAt = runAt),
                    )
                }
                onSaved()
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        title = { Text(if (isNew) "Новый скрипт" else "Скрипт") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = match,
                    onValueChange = { match = it },
                    label = { Text("URL-шаблон (* = любые символы)") },
                    placeholder = { Text("*mangabuff.ru*") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("JavaScript") },
                    placeholder = { Text("document.querySelector('.ads')?.remove()") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = runAt == "start",
                        onClick = { runAt = "start" },
                        label = { Text("До загрузки") },
                    )
                    FilterChip(
                        selected = runAt == "end",
                        onClick = { runAt = "end" },
                        label = { Text("После загрузки") },
                    )
                }
                Text(
                    "Пример: заблокировать элемент — document.querySelector('#banner')?.remove()",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
