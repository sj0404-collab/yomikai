package eu.kanade.presentation.reader.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.tachiyomi.data.ai.AiAgent
import eu.kanade.tachiyomi.data.ai.AiBackends
import eu.kanade.tachiyomi.data.ai.AiHistoryManager
import eu.kanade.tachiyomi.data.ai.AiHistoryManager.Msg
import eu.kanade.tachiyomi.data.tts.TtsSpeaker
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.toShareIntent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ReaderAiChatOverlay(
    context: Context,
    mangaId: Long?,
    mangaTitle: String,
    chapterTitle: String?,
    onClose: () -> Unit,
    onVoiceGender: String = "female",
) {
    val history = remember { mutableStateListOf<Msg>() }
    var loading by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }
    var attachedName by remember { mutableStateOf<String?>(null) }
    var attachedBytes by remember { mutableStateOf<ByteArray?>(null) }
    var activity by remember { mutableStateOf("") }
    var elapsed by remember { mutableStateOf(0L) }
    var backendLine by remember { mutableStateOf("") }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val showToast: (String) -> Unit = { message -> context.toast(message) }
    val scrollTo: (Int) -> Unit = { index -> scope.launch { listState.animateScrollToItem(index) } }
    val setActivity: (String) -> Unit = { text ->
        scope.launch {
            activity = text
            elapsed = 0L
        }
    }

    LaunchedEffect(Unit) {
        val prefs = uy.kohesive.injekt.Injekt.get<mihon.domain.ocr.service.OcrPreferences>()
        val backend = eu.kanade.tachiyomi.data.ai.AiBackends.byId(prefs.aiBackend().get())
        val status = eu.kanade.tachiyomi.data.ai.AiBackends.statusOf(
            backend,
            eu.kanade.tachiyomi.data.ai.AiBackends.state(context, prefs),
            prefs.aiProvider().get(),
        )
        backendLine = if (status.available) {
            "${backend.title} · ${status.detail}"
        } else {
            "${backend.title} · недоступно: ${status.missing.joinToString(", ")}"
        }
    }

    LaunchedEffect(loading) {
        if (!loading) {
            elapsed = 0L
            activity = ""
        }
    }

    LaunchedEffect(loading) {
        while (loading) {
            kotlinx.coroutines.delay(1000)
            elapsed += 1
        }
    }

    val send: (String, String?, ByteArray?) -> Unit = { text, fileName, fileBytes ->
        if (text.isNotBlank() && !loading) {
            sendMessage(
                context = context,
                scope = scope,
                mangaId = mangaId,
                mangaTitle = mangaTitle,
                chapterTitle = chapterTitle,
                input = text,
                attachmentsName = fileName,
                attachmentsBytes = fileBytes,
                history = history,
                loading = { loading = it },
                onScroll = scrollTo,
                onActivity = setActivity,
            )
            attachedName = null
            attachedBytes = null
        }
    }

    LaunchedEffect(mangaId) {
        history.clear()
        history.addAll(AiHistoryManager.load(context, mangaId))
        runCatching { listState.scrollToItem((history.size - 1).coerceAtLeast(0)) }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val (name, bytes) = readUriFile(context, uri) ?: return@rememberLauncherForActivityResult
        attachedName = name
        attachedBytes = bytes
    }

    Dialog(
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
        onDismissRequest = onClose,
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.SmartToy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "AI-чат · $mangaTitle",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                        Text(
                            chapterTitle ?: "читалка",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        if (backendLine.isNotBlank()) {
                            Text(
                                backendLine,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = "Скрыть AI-чат")
                    }
                }
                if (loading || activity.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            buildString {
                                append(if (loading) "AI думает" else "Готово")
                                if (loading && elapsed > 0) append(" · ").append(elapsed).append(" с")
                                if (activity.isNotBlank()) append(" · ").append(activity)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
                HorizontalDivider()

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (history.isEmpty() && !loading) {
                        item {
                            Text(
                                "Спросите что угодно о книге «$mangaTitle»: " +
                                    "сюжет, персонажи, порядок чтения, перевод реплик, " +
                                    "«нарисуй лого», «сделай заметку»… AI умеет создавать " +
                                    "файлы в workspace, а результат можно скачать.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                            )
                        }
                    }

                    itemsIndexed(history) { index, msg ->
                        ChatBubble(
                            context = context,
                            msg = msg,
                            isMine = msg.role == "user",
                            canPlay = msg.role == "ai",
                            onCopy = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText("AI-чат", msg.text))
                                showToast("Скопировано")
                            },
                            onChoice = { choice ->
                                if (!loading) send(choice, null, null)
                            },
                            onRetry = {
                                if (!loading) {
                                    history.remove(msg)
                                    send(msg.text, null, null)
                                }
                            },
                            onDelete = {
                                history.remove(msg)
                                AiHistoryManager.save(context, history, mangaId)
                            },
                            onSpeak = {
                                TtsSpeaker.speak(context, msg.text)
                            },
                        )
                    }
                }

                AnimatedVisibility(visible = attachedName != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            attachedName ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            attachedName = null
                            attachedBytes = null
                        }) {
                            Text("Убрать")
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OutlinedIconButton(
                        onClick = { fileLauncher.launch(arrayOf("*/*")) },
                        enabled = !loading,
                    ) {
                        Icon(Icons.Outlined.AttachFile, contentDescription = "Прикрепить файл")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Сообщение AI…") },
                        maxLines = 4,
                        enabled = !loading,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                send(input.trim(), attachedName, attachedBytes)
                            },
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedIconButton(
                        onClick = { send(input.trim(), attachedName, attachedBytes) },
                        enabled = input.isNotBlank() && !loading,
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Отправить")
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { showClearConfirm = true }) {
                        Text("Очистить историю")
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Очистить историю чата?") },
            text = { Text("История этой книги будет удалена навсегда. Это действие нельзя отменить.") },
            confirmButton = {
                Button(onClick = {
                    history.clear()
                    AiHistoryManager.save(context, history, mangaId)
                    showClearConfirm = false
                    showToast("История очищена")
                }) {
                    Text("Очистить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Отмена") }
            },
        )
    }
}

private fun sendMessage(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    mangaId: Long?,
    mangaTitle: String,
    chapterTitle: String?,
    input: String,
    attachmentsName: String? = null,
    attachmentsBytes: ByteArray? = null,
    history: SnapshotStateList<Msg>,
    loading: (Boolean) -> Unit,
    onScroll: (Int) -> Unit,
    onActivity: (String) -> Unit = {},
) {
    AiHistoryManager.append(context, history, Msg(role = "user", text = input), mangaId)
    loading(true)
    onActivity("Запрос к модели…")
    scope.launch {
        val reply = runCatching {
            chatOnce(
                context = context,
                mangaId = mangaId,
                mangaTitle = mangaTitle,
                chapterTitle = chapterTitle,
                input = input,
                attachmentsName = attachmentsName,
                attachmentsBytes = attachmentsBytes,
                history = history,
                onProgress = onActivity,
            )
        }.getOrElse { error ->
            onActivity("Ошибка запроса")
            AiAgent.AgentReply(
                text = "Сбой запроса к AI: ${error.message ?: error::class.java.simpleName}",
                toolResults = emptyList(),
                images = emptyList(),
            )
        }
        val files = (reply.toolResults.mapNotNull { it.fileProduced } + reply.images)
            .distinct()
            .mapNotNull { eu.kanade.tachiyomi.data.ai.AiWorkspace.relPathOrNull(context, it) }
        AiHistoryManager.append(
            context = context,
            history = history,
            msg = Msg(
                role = "ai",
                text = reply.text,
                time = System.currentTimeMillis(),
                tokens = reply.tokens,
                model = reply.model,
                reasoning = reply.reasoning.orEmpty(),
                choices = reply.choices,
                tools = reply.toolResults.map { tr ->
                    buildString {
                        append(tr.name)
                        if (tr.status == "error") append(" — ошибка")
                        if (tr.tookMs > 0) append(" · ").append(tr.tookMs / 1000).append(" с")
                        val produced = tr.fileProduced
                        if (produced != null) {
                            append(" · ").append(produced.name).append(' ')
                            append(produced.length() / 1024).append(" КБ")
                        }
                    }
                },
                files = files,
            ),
            mangaId = mangaId,
        )
        loading(false)
        onActivity("Готово")
        onScroll(history.size - 1)
    }
}

private suspend fun chatOnce(
    context: Context,
    mangaId: Long?,
    mangaTitle: String,
    chapterTitle: String?,
    input: String,
    attachmentsName: String?,
    attachmentsBytes: ByteArray?,
    history: List<Msg>,
    onProgress: ((String) -> Unit)? = null,
): AiAgent.AgentReply =
    withContext(Dispatchers.IO) {
        val prefs = uy.kohesive.injekt.Injekt.get<mihon.domain.ocr.service.OcrPreferences>()
        val resolution = AiBackends.resolve(context, prefs.aiBackend().get())
        val chat = resolution.chat
        if (chat == null) {
            return@withContext AiAgent.AgentReply(
                text = resolution.message ?: "AI-бэкенд недоступен",
                toolResults = emptyList(),
                images = emptyList(),
            )
        }
        val attachedText = if (attachmentsName != null && attachmentsBytes != null) {
            val saved = eu.kanade.tachiyomi.data.ai.AiWorkspace.importAttachment(context, attachmentsName, attachmentsBytes)
            if (saved != null) {
                "Прикреплён файл: ${eu.kanade.tachiyomi.data.ai.AiWorkspace.relPath(context, saved)}"
            } else {
                "Прикреплён файл: $attachmentsName (не удалось сохранить)"
            }
        } else {
            null
        }
        val priorTurns = history
            .dropLast(1)
            .map { it.role to it.text }
            .takeLast(6)
        val knowledge = eu.kanade.tachiyomi.data.ai.BookKnowledge.render(context, mangaId)
            .ifBlank { "ЗНАНИЕ О КНИГЕ: пока ничего не проверено — при необходимости выясни через web_search и сохрани через book_remember." }
        val prompt = buildString {
            append("Книга: ").append(mangaTitle)
            if (!chapterTitle.isNullOrBlank()) append("\nГлава: ").append(chapterTitle)
            append("\nВопрос: ").append(input)
        }
        AiAgent.run(
            context = context,
            userText = prompt,
            attachmentsInfo = attachedText,
            history = priorTurns,
            chatFn = chat,
            mangaId = mangaId,
            bookContext = knowledge,
            onProgress = onProgress,
        )
    }

private fun readUriFile(context: Context, uri: Uri): Pair<String, ByteArray>? =
    runCatching {
        val name = context.contentResolver.query(
            uri,
            null,
            null,
            null,
            null,
        )?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0) c.getString(i) else null
        } ?: "file"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@runCatching null
        name to bytes
    }.getOrNull()

@Composable
private fun ChatBubble(
    context: Context,
    msg: Msg,
    isMine: Boolean,
    canPlay: Boolean,
    onCopy: () -> Unit,
    onChoice: (String) -> Unit = {},
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onSpeak: () -> Unit,
) {
    val bubbleColor = if (isMine) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val radius = RoundedCornerShape(
        topStart = 14.dp,
        topEnd = 14.dp,
        bottomStart = if (isMine) 14.dp else 4.dp,
        bottomEnd = if (isMine) 4.dp else 14.dp,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
    ) {
        Surface(
            color = bubbleColor,
            shape = radius,
            modifier = Modifier.fillMaxWidth(if (isMine) 0.85f else 0.95f),
        ) {
            Column(Modifier.padding(10.dp)) {
                if (msg.reasoning.isNotBlank()) {
                    var showReasoning by remember { mutableStateOf(false) }
                    Text(
                        if (showReasoning) "Размышления ▾" else "Размышления ▸",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { showReasoning = !showReasoning },
                    )
                    if (showReasoning) {
                        Text(
                            msg.reasoning.take(1200),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                SelectionContainer {
                    Text(
                        msg.text,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (!isMine && msg.choices.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        msg.choices.forEach { choice ->
                            OutlinedButton(
                                onClick = { onChoice(choice) },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    choice,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                }
                if (msg.tools.isNotEmpty()) {
                    var showTools by remember { mutableStateOf(false) }
                    Text(
                        if (showTools) "Инструменты (${msg.tools.size}) ▾" else "Инструменты (${msg.tools.size}) ▸",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { showTools = !showTools }.padding(top = 4.dp),
                    )
                    if (showTools) {
                        msg.tools.forEach { line ->
                            Text(
                                "• $line",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                msg.files.forEach { rel ->
                    val file = java.io.File(eu.kanade.tachiyomi.data.ai.AiWorkspace.root(context), rel)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                file.name,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                            Text(
                                rel + " · " + (file.length() / 1024) + " КБ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        TextButton(
                            onClick = {
                                if (!file.exists()) {
                                    context.toast("Файл не найден")
                                } else {
                                    runCatching {
                                        context.startActivity(
                                            file.getUriCompat(context).toShareIntent(
                                                context = context,
                                                type = "application/octet-stream",
                                                message = rel,
                                            ),
                                        )
                                    }.onFailure { context.toast("Не удалось открыть файл") }
                                }
                            },
                            enabled = file.exists(),
                        ) {
                            Text("Отправить")
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.time)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onCopy, modifier = Modifier.size(26.dp)) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Копировать", modifier = Modifier.size(14.dp))
                    }
                    IconButton(onClick = onRetry, modifier = Modifier.size(26.dp)) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Повторить", modifier = Modifier.size(14.dp))
                    }
                    if (canPlay) {
                        IconButton(onClick = onSpeak, modifier = Modifier.size(26.dp)) {
                            Icon(Icons.Outlined.GraphicEq, contentDescription = "Озвучить", modifier = Modifier.size(14.dp))
                        }
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(26.dp)) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Удалить", modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}
