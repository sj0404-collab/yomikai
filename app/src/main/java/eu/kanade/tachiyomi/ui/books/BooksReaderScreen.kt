package eu.kanade.tachiyomi.ui.books

import android.graphics.Bitmap
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.data.books.BookChapter
import eu.kanade.tachiyomi.data.books.BookParser
import eu.kanade.tachiyomi.data.books.BooksStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import logcat.LogPriority
import mihon.domain.ocr.service.OcrPreferences
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Читатель электронных книг с авточтением (TTS) и восстановлением места чтения.
 *
 * Использует структуру [BookChapter] (аналог manga Chapter) с поддержкой
 * иерархии: Том → Глава → Подглава.
 */
data class BooksReaderScreen(
    val bookFileName: String,
    val title: String,
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current

        var loading by remember { mutableStateOf(true) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var chapters by remember { mutableStateOf<List<BookChapter>>(emptyList()) }
        var currentChapterIndex by remember { mutableIntStateOf(0) }
        var currentSentenceIndex by remember { mutableIntStateOf(0) }
        var isPlaying by remember { mutableStateOf(false) }
        val bookPrefs = remember { Injekt.get<OcrPreferences>() }
        var speechRate by remember { mutableFloatStateOf(bookPrefs.bookSpeechRate().get()) }
        var pitch by remember { mutableFloatStateOf(bookPrefs.bookSpeechPitch().get()) }
        var showSettings by remember { mutableStateOf(false) }
        var showChapterList by remember { mutableStateOf(false) }
        var availableVoiceNames by remember { mutableStateOf<List<String>>(emptyList()) }
        var selectedVoiceIndex by remember { mutableIntStateOf(0) }
        val tts = remember { mutableStateOf<TextToSpeech?>(null) }
        val pendingUtterance = remember { AtomicReference<CompletableDeferred<Unit>?>(null) }
        val expectedUtteranceId = remember { AtomicReference<String?>(null) }
        var pdfPageIndex by remember { mutableIntStateOf(0) }
        var pageImage by remember { mutableStateOf<Bitmap?>(null) }

        val bookFile = remember {
            val dir = BooksStore.booksDirectory(context)
            dir?.findFile(bookFileName)
        }

        // Загрузка и парсинг
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val dir = BooksStore.booksDirectory(context) ?: throw IllegalStateException("Нет каталога книг")
                    val bookFile = dir.findFile(bookFileName)
                        ?: throw IllegalStateException("Файл книги не найден")
                    val parsed = BookParser.parse(bookFile, bookFile.uri.toString())
                    val saved = BooksStore.load(context, bookFile)
                    withContext(Dispatchers.Main) {
                        chapters = parsed.chapters
                        currentChapterIndex = saved.chapter.coerceIn(0, parsed.chapters.lastIndex.coerceAtLeast(0))
                        val currentCh = parsed.chapters.getOrNull(currentChapterIndex)
                        val totalSentences = splitSentences(currentCh?.resolvedText.orEmpty()).size
                        currentSentenceIndex = saved.sentence.coerceIn(0, totalSentences.coerceAtLeast(1) - 1)
                        if (currentCh?.isPageBased == true) {
                            pdfPageIndex = saved.sentence.coerceIn(
                                0,
                                currentCh.readablePages.lastIndex.coerceAtLeast(0),
                            )
                        }
                        loading = false
                    }
                }.onFailure { e ->
                    logcat(LogPriority.ERROR, e) { "BooksReader: ошибка чтения $bookFileName" }
                    withContext(Dispatchers.Main) {
                        errorMessage = e.message ?: "Не удалось прочитать книгу"
                        loading = false
                    }
                }
            }
        }

        // TTS init
        LaunchedEffect(Unit) {
            tts.value = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    logcat(LogPriority.INFO) { "BooksReader: TTS init OK" }
                } else {
                    logcat(LogPriority.WARN) { "BooksReader: TTS init failed: $status" }
                }
            }.apply {
                setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (expectedUtteranceId.get() == utteranceId) {
                            expectedUtteranceId.set(null)
                            pendingUtterance.getAndSet(null)?.complete(Unit)
                        }
                    }
                    @Deprecated("Deprecated in Android")
                    override fun onError(utteranceId: String?) {
                        if (expectedUtteranceId.get() == utteranceId) {
                            expectedUtteranceId.set(null)
                            pendingUtterance.getAndSet(null)?.complete(Unit)
                        }
                    }
                })
            }
        }
        LaunchedEffect(tts.value) {
            val engine = tts.value ?: return@LaunchedEffect
            val voices = engine.voices
                ?.filter { v -> v.locale.language in listOf("ru", "en") }
                ?.sortedBy { it.name }
                .orEmpty()
            availableVoiceNames = voices.map { "${it.name} (${it.locale.displayLanguage ?: it.locale.language})" }
            if (voices.isNotEmpty()) {
                val defaultIdx = voices.indexOfFirst { it.locale.language == "ru" }.coerceAtLeast(0)
                selectedVoiceIndex = defaultIdx
                engine.voice = voices[defaultIdx]
            }
        }

        // Авточтение
        LaunchedEffect(isPlaying, currentChapterIndex, currentSentenceIndex, speechRate, pitch, selectedVoiceIndex) {
            if (!isPlaying) return@LaunchedEffect
            val engine = tts.value ?: run { isPlaying = false; return@LaunchedEffect }
            val chapter = chapters.getOrNull(currentChapterIndex)
                ?: run { isPlaying = false; return@LaunchedEffect }
            val sentences = splitSentences(chapter.resolvedText)
            // Страницы без текста (PDF) не озвучиваются — просто останавливаемся
            if (sentences.isEmpty()) {
                isPlaying = false
                return@LaunchedEffect
            }
            if (currentSentenceIndex >= sentences.size) {
                if (currentChapterIndex < chapters.lastIndex) {
                    currentChapterIndex++; currentSentenceIndex = 0; pdfPageIndex = 0
                } else {
                    isPlaying = false
                }
                return@LaunchedEffect
            }
            val voices = engine.voices
            if (voices != null && selectedVoiceIndex < voices.size) {
                engine.voice = voices.elementAt(selectedVoiceIndex)
            }
            engine.setSpeechRate(speechRate)
            engine.setPitch(pitch)
            val utteranceId = "book_${currentChapterIndex}_$currentSentenceIndex"
            val promise = CompletableDeferred<Unit>()
            expectedUtteranceId.set(utteranceId)
            pendingUtterance.set(promise)
            val result = engine.speak(
                sentences[currentSentenceIndex],
                TextToSpeech.QUEUE_ADD,
                null,
                utteranceId,
            )
            if (result == TextToSpeech.ERROR) {
                expectedUtteranceId.set(null)
                pendingUtterance.compareAndSet(promise, null)
                isPlaying = false
                return@LaunchedEffect
            }
            promise.await()
            currentSentenceIndex++
        }

        // Сохранение прогресса
        LaunchedEffect(bookFileName, currentChapterIndex, currentSentenceIndex, pdfPageIndex, chapters.size) {
            if (chapters.isEmpty() || loading) return@LaunchedEffect
            val bk = bookFile ?: return@LaunchedEffect
            val current = chapters.getOrNull(currentChapterIndex)
            val saveSentence = if (current?.isPageBased == true) pdfPageIndex else currentSentenceIndex
            val totalSentencesPerChapter = chapters.map { splitSentences(it.resolvedText).size }
            val total = totalSentencesPerChapter.sum().coerceAtLeast(1)
            val consumedBefore = totalSentencesPerChapter.subList(0, currentChapterIndex).sum() + saveSentence
            val percent = ((consumedBefore.toLong() * 100) / total).toInt().coerceIn(0, 100)
            BooksStore.save(context, bk, BooksStore.Snapshot(currentChapterIndex, saveSentence, percent))
        }

        // Рендер страниц PDF по требованию
        LaunchedEffect(chapters, currentChapterIndex, pdfPageIndex) {
            val chapter = chapters.getOrNull(currentChapterIndex)
            if (chapter?.isPageBased != true) {
                pageImage?.recycle()
                pageImage = null
                return@LaunchedEffect
            }
            val page = chapter.readablePages.getOrNull(pdfPageIndex) ?: run {
                pageImage?.recycle()
                pageImage = null
                return@LaunchedEffect
            }
            val bmp = withContext(Dispatchers.IO) {
                val file = bookFile ?: return@withContext null
                BookParser.renderPage(context, file, page.pageNumber)
            }
            // Только если это всё ещё актуальная страница
            val current = chapters.getOrNull(currentChapterIndex)
            if (current?.isPageBased == true && current.readablePages.getOrNull(pdfPageIndex)?.pageNumber == page.pageNumber) {
                pageImage?.recycle()
                pageImage = bmp
            } else {
                bmp?.recycle()
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                tts.value?.stop()
                tts.value?.shutdown()
                pageImage?.recycle()
            }
        }

        // ---------- UI ----------

        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Загрузка…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            return
        }

        errorMessage?.let { msg ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Ошибка",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                    }
                }
            }
            return
        }

        if (chapters.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Нет текста для чтения", style = MaterialTheme.typography.bodyLarge)
            }
            return
        }

        val currentChapter = chapters.getOrNull(currentChapterIndex)
        val currentText = currentChapter?.resolvedText.orEmpty()
        val sentences = splitSentences(currentText)
        val scrollState = rememberScrollState()

        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = currentChapter?.displayTitle ?: currentChapter?.name ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        tts.value?.stop()
                        navigator.pop()
                    }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { showChapterList = !showChapterList }) {
                        Icon(Icons.AutoMirrored.Outlined.List, contentDescription = "Главы")
                    }
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Настройки")
                    }
                },
            )

            AnimatedVisibility(visible = showChapterList) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                ) {
                    itemsIndexed(chapters) { index, chapter ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentChapterIndex = index
                                    currentSentenceIndex = 0
                                    pdfPageIndex = 0
                                    showChapterList = false
                                }
                                .background(
                                    if (index == currentChapterIndex)
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    else MaterialTheme.colorScheme.surface,
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Индикатор: страница или глава
                            if (chapter.isPageBased) {
                                // Для страниц — показываем номер страницы
                                Column(
                                    modifier = Modifier.width(48.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    val pNum = chapter.pages.firstOrNull()?.pageNumber ?: (index + 1)
                                    Text(
                                        text = "стр",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = "$pNum",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            } else if (chapter.volume != null || chapter.chapter != null) {
                                // Для текстовых глав с номерами
                                Column(
                                    modifier = Modifier.width(48.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    if (chapter.volume != null) {
                                        Text(
                                            text = "T${chapter.volume}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    if (chapter.chapter != null) {
                                        Text(
                                            text = "G${chapter.chapter}",
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = "${index + 1}.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(32.dp),
                                )
                            }
                            Text(
                                text = chapter.displayTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (chapter.read) {
                                Text(
                                    text = "✓",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }

            AnimatedVisibility(visible = showSettings) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(16.dp),
                ) {
                    Text(
                        "Скорость: x${String.format("%.1f", speechRate)}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Slider(
                        value = speechRate,
                        onValueChange = {
                            speechRate = it
                            bookPrefs.bookSpeechRate().set(it)
                        },
                        valueRange = 0.5f..3.0f,
                        steps = 9,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Высота: ${String.format("%.1f", pitch)}", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = pitch,
                        onValueChange = {
                            pitch = it
                            bookPrefs.bookSpeechPitch().set(it)
                        },
                        valueRange = 0.5f..2.0f,
                        steps = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (availableVoiceNames.isNotEmpty()) {
                        Text(
                            "Голос (${selectedVoiceIndex + 1}/${availableVoiceNames.size})",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            availableVoiceNames.take(6).forEachIndexed { idx, name ->
                                Text(
                                    text = name.take(18),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .background(
                                            if (idx == selectedVoiceIndex)
                                                MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surface,
                                        )
                                        .clickable {
                                            selectedVoiceIndex = idx
                                            val engine = tts.value
                                            val voices = engine?.voices
                                            if (voices != null && idx < voices.size) {
                                                engine.voice = voices.elementAt(idx)
                                            }
                                        }
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }

            val isPageBased = currentChapter?.isPageBased == true
            val readablePages = currentChapter?.readablePages.orEmpty()

            // Страница PDF — на весь доступный экран (без отступов и прокрутки);
            // текстовые главы остаются прокручиваемыми с внутренними отступами.
            val contentModifier = if (isPageBased && pageImage != null) {
                Modifier.weight(1f)
            } else {
                Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(16.dp)
            }

            Box(modifier = contentModifier) {
                when {
                    isPageBased && pageImage != null -> {
                        Image(
                            bitmap = pageImage!!.asImageBitmap(),
                            contentDescription = currentChapter?.displayTitle,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    isPageBased -> {
                        val pNum = readablePages.getOrNull(pdfPageIndex)?.pageNumber ?: (currentChapterIndex + 1)
                        Text(
                            text = "Страница $pNum — нет изображения",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                        )
                    }
                    sentences.isNotEmpty() -> {
                        Text(
                            text = buildAnnotatedText(sentences, currentSentenceIndex),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 18.sp,
                                lineHeight = 28.sp,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    else -> {
                        Text(
                            text = "Глава пуста",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                        )
                    }
                }
            }

            if (sentences.isNotEmpty() && !isPageBased) {
                LinearProgressIndicator(
                    progress = { currentSentenceIndex.toFloat() / sentences.size },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val totalFilePages = chapters.lastOrNull()?.pages?.lastOrNull()?.pageNumber ?: chapters.size
            val statusText = if (isPageBased) {
                val pNum = readablePages.getOrNull(pdfPageIndex)?.pageNumber ?: (currentChapterIndex + 1)
                "Страница $pNum из $totalFilePages"
            } else {
                "Глава ${currentChapterIndex + 1}/${chapters.size} · " +
                    "Предложение ${currentSentenceIndex + 1}/${sentences.size.coerceAtLeast(1)}"
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            if (isPageBased && readablePages.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(36.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            if (pdfPageIndex > 0) {
                                pdfPageIndex--
                                isPlaying = false
                                tts.value?.stop()
                            }
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Outlined.SkipPrevious, contentDescription = "Назад")
                    }
                    Text(
                        text = "Страница ${pdfPageIndex + 1}/${readablePages.size} в главе",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(
                        onClick = {
                            if (pdfPageIndex < readablePages.lastIndex) {
                                pdfPageIndex++
                                isPlaying = false
                                tts.value?.stop()
                            }
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Outlined.SkipNext, contentDescription = "Вперёд")
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    if (currentChapterIndex > 0) {
                        currentChapterIndex--; currentSentenceIndex = 0; pdfPageIndex = 0
                    }
                }) {
                    Icon(Icons.Outlined.SkipPrevious, contentDescription = "Предыдущая глава")
                }
                IconButton(
                    onClick = {
                        if (isPlaying) { tts.value?.stop(); isPlaying = false }
                        else isPlaying = true
                    },
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = if (isPlaying) "Пауза" else "Читать",
                        modifier = Modifier.size(32.dp),
                    )
                }
                IconButton(onClick = {
                    if (currentChapterIndex < chapters.lastIndex) {
                        currentChapterIndex++; currentSentenceIndex = 0; pdfPageIndex = 0
                    }
                }) {
                    Icon(Icons.Outlined.SkipNext, contentDescription = "Следующая глава")
                }
            }
        }
    }

    private fun splitSentences(text: String): List<String> {
        return text
            .replace(Regex("\n{2,}"), "\n")
            .split(Regex("(?<=[.!?…])\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun buildAnnotatedText(sentences: List<String>, currentIndex: Int): String {
        return sentences.mapIndexed { idx, s ->
            if (idx == currentIndex) "▸ $s" else s
        }.joinToString("  ")
    }
}
