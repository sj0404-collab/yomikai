package eu.kanade.tachiyomi.ui.books

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.books.AudiobookMaker
import eu.kanade.tachiyomi.data.books.BooksStore
import tachiyomi.core.common.util.lang.withIOContext

/**
 * Детальный экран книги — как страница манги у манги: обложка, название,
 * автор, прогресс, время чтения и ПОЛНОЕ описание, а ниже действия:
 *  • «Продолжить» — открыть читалку с сохранённого места;
 *  • «Создать аудиокнигу» — прогнать все главы выбранным движком TTS с
 *    ролевыми голосами и сложить mp3/wav-файлы в audiobooks/<Название>;
 *  • «Удалить из приложения» — книга пропадает из библиотеки (файл остаётся);
 *  • «Удалить из телефона» — файл стирается физически.
 */
data class BooksBookScreen(private val bookFileName: String) : Screen {

    private fun formatReadTime(seconds: Long): String {
        if (seconds < 60) return "<1 мин"
        val minutes = seconds / 60
        val hours = minutes / 60
        return if (hours > 0) {
            val rest = minutes % 60
            if (rest > 0) "$hours ч $rest мин" else "$hours ч"
        } else {
            "$minutes мин"
        }
    }

    private fun formatAgo(ts: Long): String {
        if (ts <= 0) return "ещё не открывали"
        val diff = System.currentTimeMillis() - ts
        val minutes = diff / 60_000
        return when {
            minutes < 1 -> "только что"
            minutes < 60 -> "$minutes мин назад"
            minutes < 60 * 24 -> "${minutes / 60} ч назад"
            else -> "${minutes / (60 * 24)} дн. назад"
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var file by remember { mutableStateOf<UniFile?>(null) }
        var title by remember { mutableStateOf(bookFileName.substringBeforeLast('.')) }
        var author by remember { mutableStateOf<String?>(null) }
        var description by remember { mutableStateOf<String?>(null) }
        var coverPath by remember { mutableStateOf<String?>(null) }
        var progress by remember { mutableIntStateOf(0) }
        var savedChapter by remember { mutableIntStateOf(0) }
        var readSeconds by remember { mutableStateOf(0L) }
        var lastOpened by remember { mutableStateOf(0L) }
        var sizeBytes by remember { mutableStateOf(0L) }
        var ext by remember { mutableStateOf("") }
        var showAudiobookDialog by remember { mutableStateOf(false) }
        var showHideDialog by remember { mutableStateOf(false) }
        var showDeleteDialog by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            withIOContext {
                val found = BooksStore.booksDirectory(context)?.findFile(bookFileName)
                if (found != null) {
                    val meta = BooksStore.loadMetadata(context, found)
                    val snap = BooksStore.load(context, found)
                    title = meta.title.ifBlank { bookFileName.substringBeforeLast('.') }
                    author = meta.author
                    description = meta.description
                    coverPath = BooksStore.coverPath(context, found)
                    progress = snap.percent
                    savedChapter = snap.chapter
                    readSeconds = BooksStore.readSeconds(context, found)
                    lastOpened = BooksStore.lastOpened(context, found)
                    sizeBytes = runCatching { found.length() }.getOrDefault(0L)
                    ext = bookFileName.substringAfterLast('.', "").uppercase()
                }
                file = found
            }
        }

        val coverBitmap = remember(coverPath) {
            coverPath?.let {
                runCatching {
                    BitmapFactory.decodeFile(it, BitmapFactory.Options().apply { inSampleSize = 2 })
                }.getOrNull()
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Назад",
                        )
                    }
                },
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Row {
                    // Обложка крупно, как у манги.
                    if (coverBitmap != null) {
                        Image(
                            bitmap = coverBitmap.asImageBitmap(),
                            contentDescription = "Обложка",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(128.dp, 176.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(128.dp, 176.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                ext.ifBlank { "📚" },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        author?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Формат: ${ext.ifBlank { "—" }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (sizeBytes > 0) {
                            val sizeText = when {
                                sizeBytes >= 1048576 -> "%.1f МБ".format(sizeBytes / 1048576f)
                                sizeBytes >= 1024 -> "%.1f КБ".format(sizeBytes / 1024f)
                                else -> "$sizeBytes Б"
                            }
                            Text(
                                "Размер: $sizeText",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (progress > 0) {
                            Text(
                                "Прочитано: $progress% (глава ${savedChapter + 1})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (readSeconds > 0) {
                            Text(
                                "Время чтения: ${formatReadTime(readSeconds)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "Открывали: ${formatAgo(lastOpened)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (progress > 0) {
                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Действия.
                FilledTonalButton(
                    onClick = { navigator.push(BooksReaderScreen(bookFileName, title)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (progress > 0) "Продолжить чтение" else "Читать")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        // Обновляем состояние и начинаем генерацию ЕСЛИ другая не идёт.
                        AudiobookMaker.state.let { st ->
                            if (!st.running) {
                                file?.let { AudiobookMaker.start(context, it, title) }
                            }
                        }
                        showAudiobookDialog = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Headphones, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (AudiobookMaker.state.running) {
                            "Аудиокнига создаётся…"
                        } else {
                            "Создать аудиокнигу"
                        },
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showHideDialog = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.VisibilityOff, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Из приложения", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Из телефона",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                // Полное описание — как у манги.
                description?.takeIf { it.isNotBlank() }?.let { desc ->
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Описание", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---- Диалоги ----

        if (showAudiobookDialog) {
            val st = AudiobookMaker.state
            AlertDialog(
                onDismissRequest = {
                    if (!st.running) showAudiobookDialog = false
                },
                title = { Text(if (st.running) "Создаю аудиокнигу…" else "Аудиокнига") },
                text = {
                    Column {
                        Text(
                            "«${st.bookTitle.ifBlank { title }}»",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        when {
                            st.running -> {
                                LinearProgressIndicator(
                                    progress = {
                                        if (st.chaptersTotal > 0) {
                                            st.chapter.toFloat() / st.chaptersTotal
                                        } else {
                                            0f
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(6.dp),
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Глава ${st.chapter} из ${st.chaptersTotal}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Голоса и движок берутся из настроек ⚙ читалки. " +
                                        "Не закрывайте диалога — идёт запись в " +
                                        "${st.outDirLabel}…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            st.error != null -> {
                                Text(
                                    "Ошибка: ${st.error}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            st.cancelled -> {
                                Text(
                                    "Генерация отменена. Готовые главы остались в папке.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            st.done -> {
                                Text(
                                    "Готово! Файлы глав лежат в:\n" +
                                        "audiobooks/${st.outDirLabel.trimStart('/')}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            else -> {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                },
                confirmButton = {
                    if (!st.running) {
                        TextButton(onClick = { showAudiobookDialog = false }) { Text("Закрыть") }
                    } else {
                        TextButton(onClick = { AudiobookMaker.cancel() }) { Text("Отмена") }
                    }
                },
            )
        }

        if (showHideDialog) {
            AlertDialog(
                onDismissRequest = { showHideDialog = false },
                title = { Text("Удалить из приложения?") },
                text = {
                    Text(
                        "«$title» исчезнет из библиотеки, но файл останется на телефоне " +
                            "(его можно будет добавить заново).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        file?.let { BooksStore.hideFromLibrary(context, it) }
                        showHideDialog = false
                        navigator.pop()
                    }) { Text("Удалить из приложения") }
                },
                dismissButton = {
                    TextButton(onClick = { showHideDialog = false }) { Text("Отмена") }
                },
            )
        }

        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("Удалить из телефона?") },
                text = {
                    Text(
                        "Файл «$bookFileName» будет удалён с устройства БЕЗ ВОЗВРАТА.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteDialog = false
                        val f = file
                        if (f != null) {
                            scope.launch {
                                withIOContext {
                                    runCatching {
                                        BooksStore.clear(context, f)
                                        BooksStore.deleteBook(f)
                                    }
                                }
                            }
                        }
                        navigator.pop()
                    }) {
                        Text(
                            "Удалить из телефона",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) { Text("Отмена") }
                },
            )
        }
    }
}
