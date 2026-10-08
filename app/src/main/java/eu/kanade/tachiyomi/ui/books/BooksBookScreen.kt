package eu.kanade.tachiyomi.ui.books

import android.graphics.BitmapFactory
import android.media.MediaPlayer
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
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import eu.kanade.tachiyomi.data.books.BookParser
import eu.kanade.tachiyomi.data.books.BookTtsScript
import eu.kanade.tachiyomi.data.books.BooksStore
import kotlinx.coroutines.launch
import mihon.domain.ocr.service.OcrPreferences
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Детальный экран книги — как страница манги: обложка, название, автор,
 * прогресс, время чтения и ПОЛНОЕ описание. Плюс секции:
 *  • «Создать аудиокнигу» — с выбором ДИАПАЗОНА (от главы/предложения До
 *    главы/предложения) и опцией склейки всех фрагментов в один файл. В
 *    процессе видно: движок и голос, полный ролевой состав, текущую главу
 *    и предложение, время генерации каждой главы и её аудиохронометраж.
 *  • Список готовых аудиофайлов книги (главы и full-файл склейки) с
 *    встроенным плеером — послушать можно в любой момент, даже когда
 *    генерация ещё идёт (как «живое выступление»).
 *  • «Удалить из приложения» / «Удалить из телефона» с подтверждением.
 */
data class BooksBookScreen(private val bookFileName: String) : Screen {

    private data class ChapterPick(val title: String, val segments: Int)

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

    private fun fmtHms(s: Long): String {
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
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

        // Аудиокнига: диалоги и выбор диапазона.
        var showSetupDialog by remember { mutableStateOf(false) }
        var showProgressDialog by remember { mutableStateOf(false) }
        var chapterPicks by remember { mutableStateOf<List<ChapterPick>>(emptyList()) }
        var fromChapter by remember { mutableIntStateOf(1) }
        var fromSentence by remember { mutableIntStateOf(1) }
        var toChapter by remember { mutableIntStateOf(0) }
        var toSentence by remember { mutableIntStateOf(0) }

        // Готовые аудиофайлы книги + встроенный плеер.
        var audioFiles by remember { mutableStateOf<List<UniFile>>(emptyList()) }
        var audioDurations by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
        var player by remember { mutableStateOf<MediaPlayer?>(null) }
        var playingName by remember { mutableStateOf<String?>(null) }

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

        // Обновляем список готовых глав и их хронометраж после записи
        // каждой главы и по концу генерации.
        val makerState = AudiobookMaker.state
        LaunchedEffect(title, makerState.chapter, makerState.done, makerState.cancelled) {
            if (title.isBlank()) return@LaunchedEffect
            val files = withIOContext { AudiobookMaker.listFiles(context, title) }.orEmpty()
            audioFiles = files
            audioDurations = withIOContext {
                files.associate { f ->
                    (f.name ?: "") to AudiobookMaker.audioSecondsOf(context, f)
                }
            }
        }

        // Останавливаем плеер при уходе с экрана.
        DisposableEffect(Unit) {
            onDispose {
                player?.let { p ->
                    runCatching { if (p.isPlaying) p.stop() }
                    p.release()
                }
            }
        }

        fun playFile(f: UniFile) {
            val name = f.name ?: return
            if (playingName == name) {
                player?.let { p ->
                    runCatching { if (p.isPlaying) p.stop() }
                    p.release()
                }
                player = null
                playingName = null
                return
            }
            player?.let { p ->
                runCatching { if (p.isPlaying) p.stop() }
                p.release()
            }
            scope.launch {
                runCatching {
                    val mp = withIOContext {
                        MediaPlayer().apply {
                            setDataSource(context, f.uri)
                            prepare()
                        }
                    }
                    player = mp
                    playingName = name
                    mp.setOnCompletionListener {
                        playingName = null
                    }
                    mp.start()
                }
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
                        if (makerState.running) {
                            showProgressDialog = true
                        } else {
                            // Подготовим главы для выбора диапазона.
                            scope.launch {
                                if (chapterPicks.isEmpty()) {
                                    chapterPicks = withIOContext {
                                        val f = file ?: return@withIOContext emptyList()
                                        runCatching {
                                            BookParser.parse(f, f.uri.toString(), context)
                                        }.getOrNull()?.chapters.orEmpty()
                                            .filter { !it.isPageBased && it.resolvedText.isNotBlank() }
                                            .map { ch ->
                                                ChapterPick(
                                                    title = ch.displayTitle.take(60),
                                                    segments = BookTtsScript.split(ch.resolvedText).size,
                                                )
                                            }
                                    }
                                    toChapter = chapterPicks.size
                                    toSentence = chapterPicks.lastOrNull()?.segments ?: 0
                                }
                                showSetupDialog = true
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Headphones, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (makerState.running) {
                            "Аудиокнига создаётся…"
                        } else {
                            "Создать аудиокнигу…"
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

                // Готовые аудиофайлы книги — можно проиграть в любой момент,
                // пока идёт или завершилась генерация (эффект «спектакля»).
                if (audioFiles.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Аудиокнига · ${audioFiles.size} файлов",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    audioFiles.forEach { f ->
                        val name = f.name.orEmpty()
                        val dur = audioDurations[name] ?: 0L
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                        ) {
                            IconButton(onClick = { playFile(f) }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    if (playingName == name) {
                                        Icons.Outlined.Stop
                                    } else {
                                        Icons.Outlined.PlayArrow
                                    },
                                    contentDescription = if (playingName == name) "Стоп" else "Играть",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(
                                name,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                                color = if (playingName == name) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            if (dur > 0) {
                                Text(
                                    fmtHms(dur),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

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

        // 1) Настройка генерации: диапазон + склейка + кто озвучивает.
        if (showSetupDialog) {
            val prefs = runCatching { Injekt.get<OcrPreferences>() }.getOrNull()
            val isEdge = prefs?.bookTtsEngine()?.get() == "edge"
            val voiceLbl = prefs?.bookVoiceLabel()?.get().orEmpty().ifBlank { "по умолчанию" }
            val roles = prefs?.bookRoleVoices()?.get() ?: true
            var mergeOne by remember { mutableStateOf(true) }

            @Composable
            fun stepperRow(
                label: String,
                value: Int,
                max: Int,
                onChange: (Int) -> Unit,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(90.dp),
                    )
                    TextButton(onClick = { if (value > 1) onChange(value - 1) }) { Text("−") }
                    Text(
                        "$value / $max",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { if (value < max) onChange(value + 1) }) { Text("+") }
                }
            }

            AlertDialog(
                onDismissRequest = { showSetupDialog = false },
                title = { Text("Создать аудиокнигу") },
                text = {
                    Column {
                        Text(
                            "«$title»",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        // КТО озвучивает — видно заранее.
                        Text(
                            "Движок: " + if (isEdge) "Edge TTS (онлайн)" else "Системный TTS",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Голос: $voiceLbl" + if (roles) " · роли: вкл" else " · роли: выкл",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        if (chapterPicks.isEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Считаю главы и предложения…",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        } else {
                            // Диапазон глав/предложений (можно пропустить оглавление).
                            stepperRow("С главы", fromChapter, chapterPicks.size) {
                                fromChapter = it
                                if (toChapter < it) toChapter = it
                            }
                            stepperRow(
                                "  с предложения",
                                fromSentence,
                                chapterPicks.getOrNull(fromChapter - 1)?.segments?.coerceAtLeast(1) ?: 1,
                            ) { fromSentence = it }
                            stepperRow("До главы", toChapter.coerceIn(1, chapterPicks.size), chapterPicks.size) {
                                toChapter = it
                            }
                            stepperRow(
                                "  до предложения",
                                toSentence,
                                chapterPicks.getOrNull(toChapter - 1)?.segments?.coerceAtLeast(1) ?: 1,
                            ) { toSentence = it }
                            Text(
                                chapterPicks.getOrNull(fromChapter - 1)?.title.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = mergeOne, onCheckedChange = { mergeOne = it })
                                Text(
                                    "Склеить в один файл full.mp3/wav",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showSetupDialog = false
                            file?.let { f ->
                                AudiobookMaker.start(
                                    context = context,
                                    book = f,
                                    title = title,
                                    fromChapter = if (fromChapter <= 1 && fromSentence <= 1) -1 else fromChapter,
                                    fromSentence = if (fromSentence <= 1) -1 else fromSentence,
                                    toChapter = if (toChapter <= 0 || toChapter >= chapterPicks.size) {
                                        if (toChapter >= chapterPicks.size &&
                                            toSentence >= (chapterPicks.lastOrNull()?.segments ?: 0)
                                        ) {
                                            -1
                                        } else {
                                            toChapter
                                        }
                                    } else {
                                        toChapter
                                    },
                                    toSentence = if (toSentence <= 0 ||
                                        toSentence >= (
                                            chapterPicks.getOrNull(toChapter - 1)?.segments ?: Int.MAX_VALUE
                                            )
                                    ) {
                                        -1
                                    } else {
                                        toSentence
                                    },
                                    mergeOneFile = mergeOne,
                                )
                            }
                            showProgressDialog = true
                        },
                        enabled = chapterPicks.isNotEmpty(),
                    ) { Text("Начать") }
                },
                dismissButton = {
                    TextButton(onClick = { showSetupDialog = false }) { Text("Отмена") }
                },
            )
        }

        // 2) Прогресс генерации: кто, этап, цикл, время глав.
        if (showProgressDialog) {
            val st = AudiobookMaker.state
            AlertDialog(
                onDismissRequest = {
                    if (!st.running) showProgressDialog = false
                },
                title = { Text(if (st.running) "Создаю аудиокнигу…" else "Аудиокнига") },
                text = {
                    Column {
                        Text(
                            "«${st.bookTitle.ifBlank { title }}»",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${st.engineLabel} · ${st.voiceLabel}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (st.rolesLine.isNotBlank()) {
                            Text(
                                st.rolesLine,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (st.rangeLabel.isNotBlank()) {
                            Text(
                                "Диапазон: ${st.rangeLabel}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "Глава ${st.chapter} из ${st.chaptersTotal}" +
                                        if (st.segmentsInChapter > 0) {
                                            " · предложение ${st.segmentInChapter}/${st.segmentsInChapter}"
                                        } else {
                                            ""
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "Прошло: ${fmtHms(st.elapsedSeconds)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (st.logLines.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    HorizontalDivider()
                                    Spacer(modifier = Modifier.height(4.dp))
                                    st.logLines.takeLast(3).forEach { line ->
                                        Text(
                                            line,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Можно не ждать: готовые главы уже послушать " +
                                        "на этой странице ниже. Запись в ${st.outDirLabel}…",
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
                                    "Отменено. Готовые главы остались в папке.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            st.done -> {
                                Text(
                                    "Готово за ${fmtHms(st.elapsedSeconds)}!" +
                                        (st.mergedFileName?.let { "\nОбщий файл: $it" }.orEmpty()) +
                                        "\nПапка: ${st.outDirLabel}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                st.logLines.takeLast(4).forEach { line ->
                                    Text(
                                        line,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            else -> {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                },
                confirmButton = {
                    if (!st.running) {
                        TextButton(onClick = { showProgressDialog = false }) { Text("Закрыть") }
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
