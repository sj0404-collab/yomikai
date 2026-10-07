package eu.kanade.tachiyomi.ui.books

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.reader.TtsVoicePickerDialog
import eu.kanade.tachiyomi.data.books.BookAmbience
import eu.kanade.tachiyomi.data.books.BookChapter
import eu.kanade.tachiyomi.data.books.BookOcr
import eu.kanade.tachiyomi.data.books.BookProgress
import eu.kanade.tachiyomi.data.books.BookParser
import eu.kanade.tachiyomi.data.books.BookSpeechRole
import eu.kanade.tachiyomi.data.books.BookSpeechSegment
import eu.kanade.tachiyomi.data.books.BookTtsScript
import eu.kanade.tachiyomi.data.books.BooksStore
import eu.kanade.tachiyomi.data.tts.EdgeTts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.ocr.service.OcrPreferences
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Читатель электронных книг с авточтением (TTS) и восстановлением места чтения.
 *
 * Профессиональный режим чтения:
 *  • два TTS-движка: системный Android TextToSpeech (оффлайн) и Edge TTS (онлайн);
 *  • голоса по ролям: рассказчик своим голосом, персонажи — отдельными (Edge) или
 *    сдвигом высоты (системный);
 *  • распознавание сканированных страниц (image-PDF) штатным OCR-движком;
 *  • фоновая музыка-эмбиент под настроение книги (процедурная, без файлов);
 *  • полноэкранный режим без панелей и системных баров.
 * Все тяжёлые операции обёрнуты в runCatching — читалка не падает.
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
        var showVoicePicker by remember { mutableStateOf(false) }
        var pdfPageIndex by remember { mutableIntStateOf(0) }
        var pageImage by remember { mutableStateOf<Bitmap?>(null) }
        // Новые профессиональные фичи
        var roleVoices by remember { mutableStateOf(bookPrefs.bookRoleVoices().get()) }
        var pageOcrEnabled by remember { mutableStateOf(bookPrefs.bookPageOcr().get()) }
        var musicEnabled by remember { mutableStateOf(bookPrefs.bookMusicEnabled().get()) }
        var musicVolume by remember { mutableFloatStateOf(bookPrefs.bookMusicVolume().get()) }
        var fullscreen by remember { mutableStateOf(bookPrefs.bookFullscreen().get()) }
        var pageOcrBusy by remember { mutableStateOf(false) }
        var pageSegments by remember { mutableStateOf(mutableMapOf<Int, List<BookSpeechSegment>>()) }
        var musicPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
        val scrollState = rememberScrollState()
        val listState = rememberLazyListState()
        val snackbarHostState = remember { SnackbarHostState() }
        val readerScope = rememberCoroutineScope()
        val chapterSegmentsCache = remember(chapters) {
            mutableMapOf<Pair<Int, Boolean>, List<BookSpeechSegment>>()
        }
        // Число предложений главы — для прогресса. Раньше его пересчитывали
        // заново для КАЖДОЙ главы на КАЖДУЮ озвученную реплику: splitSentences
        // разбивал весь текст книги на список строк, а нужна была только длина.
        // На книге в 40 глав это десятки тысяч аллокаций на каждое предложение
        // — прямой источник подёргиваний при авточтении.
        val sentenceCountCache = remember(chapters) { mutableMapOf<Int, Int>() }

        // --- TTS Engine state ---
        var ttsEngine by remember { mutableStateOf(bookPrefs.bookTtsEngine().get()) }
        var selectedVoiceSpec by remember { mutableStateOf(bookPrefs.bookVoiceSpec().get()) }
        var selectedVoiceLabel by remember { mutableStateOf(bookPrefs.bookVoiceLabel().get()) }
        var edgeVoiceName by remember { mutableStateOf(selectedVoiceSpec) }
        val isEdgeTts = ttsEngine == "edge"

        // --- System TTS ---
        val tts = remember { mutableStateOf<TextToSpeech?>(null) }
        var systemTtsInitFailed by remember { mutableStateOf(false) }
        val pendingUtterance = remember { java.util.concurrent.atomic.AtomicReference<CompletableDeferred<Unit>?>(null) }
        val expectedUtteranceId = remember { java.util.concurrent.atomic.AtomicReference<String?>(null) }

        // --- Edge TTS ---
        var edgePlayer by remember { mutableStateOf<MediaPlayer?>(null) }

        val bookFile = remember {
            val dir = BooksStore.booksDirectory(context)
            dir?.findFile(bookFileName)
        }

        // ---------- Вспомогательные локальные функции ----------

        val stopAll: () -> Unit = {
            isPlaying = false
            runCatching { tts.value?.stop() }
            edgePlayer?.let { if (it.isPlaying) runCatching { it.stop() } }
            musicPlayer?.let { runCatching { if (it.isPlaying) it.pause() } }
        }

        val togglePlay: () -> Unit = {
            if (isPlaying) stopAll() else isPlaying = true
        }

        fun moodKeywords(): String = buildString {
            append(title)
            chapters.getOrNull(currentChapterIndex)?.displayTitle?.let { append(' ').append(it) }
        }

        val ocrPage: suspend (Int) -> Unit = { pageNumber ->
            pageOcrBusy = true
            val bmp = withContext(Dispatchers.IO) {
                bookFile?.let { BookParser.renderPage(context, it, pageNumber) }
            }
            // Причина неудачи — читателю, а не в logcat: иначе страница молчала,
            // а он гадал, что сломалось.
            var failureReason: String? = null
            val text = bmp?.let {
                when (val r = BookOcr.recognizeResult(it)) {
                    is BookOcr.Result.Text -> r.text
                    is BookOcr.Result.Unavailable -> {
                        failureReason = r.reason
                        ""
                    }
                }
            } ?: ""
            if (bmp != null && !bmp.isRecycled) bmp.recycle()
            pageSegments = pageSegments.toMutableMap().apply {
                put(pageNumber, segmentsForReading(text, roleVoices))
            }
            failureReason?.let { reason ->
                eu.kanade.tachiyomi.data.ai.AiConsole.ocr(
                    title = "Страница $pageNumber: локальный OCR не дал текста",
                    detail = reason,
                    level = eu.kanade.tachiyomi.data.ai.AiConsole.Level.WARN,
                )
                readerScope.launch {
                    snackbarHostState.showSnackbar(
                        "Страница $pageNumber: $reason. Для PDF нужен скачанный пакет моделей " +
                            "(Настройки → Распознавание текста).",
                    )
                }
            }
            pageOcrBusy = false
        }

        suspend fun speakSegment(segment: BookSpeechSegment): Boolean {
            if (isEdgeTts) {
                val narratorVoice = edgeVoiceName.ifBlank { EdgeTts.DEFAULT_VOICE }
                val voice = if (roleVoices) {
                    BookTtsScript.edgeVoiceFor(segment.role, segment.speaker, narratorVoice)
                } else {
                    narratorVoice
                }
                val ratePercent = ((speechRate - 1.0f) * 100).toInt()
                val baseHz = ((pitch - 1.0f) * 100).toInt()
                val roleHz = if (roleVoices) {
                    BookTtsScript.edgePitchHzFor(segment.role, segment.speaker)
                } else 0
                val file = EdgeTts.synthesizeToFile(context, segment.text, voice, ratePercent, baseHz + roleHz)
                if (file == null) {
                    readerScope.launch {
                        snackbarHostState.showSnackbar(
                            "Edge TTS не озвучил фрагмент (нет сети или сервер недоступен).",
                        )
                    }
                    isPlaying = false
                    return false
                }
                val promise = CompletableDeferred<Unit>()
                withContext(Dispatchers.Main) {
                    runCatching {
                        val old = edgePlayer
                        edgePlayer = MediaPlayer().apply {
                            setDataSource(file.absolutePath)
                            setOnCompletionListener { promise.complete(Unit) }
                            setOnErrorListener { _, _, _ ->
                                promise.complete(Unit)
                                true
                            }
                            prepare()
                            start()
                        }
                        old?.release()
                    }.onFailure { promise.complete(Unit) }
                }
                promise.await()
                return true
            } else {
                // --- System TTS ---
                if (systemTtsInitFailed) {
                    readerScope.launch {
                        snackbarHostState.showSnackbar(
                            "Системный TTS недоступен на этом устройстве (движок не установлен). " +
                                "Установите голосовой движок в настройках Android или выберите «🌐 Edge TTS».",
                        )
                    }
                    isPlaying = false
                    return false
                }
                val engine = tts.value
                if (engine == null) {
                    isPlaying = false
                    return false
                }
                if (selectedVoiceSpec.contains("::")) {
                    val voiceName = selectedVoiceSpec.substringAfterLast("::")
                    runCatching {
                        engine.voices?.firstOrNull { it.name == voiceName }?.let { engine.setVoice(it) }
                    }
                }
                runCatching { engine.setSpeechRate(speechRate) }
                val factor = if (roleVoices) {
                    BookTtsScript.systemPitchFactorFor(segment.role, segment.speaker)
                } else 1f
                runCatching { engine.setPitch((pitch * factor).coerceIn(0.5f, 2f)) }
                val utteranceId = "book_${currentChapterIndex}_$currentSentenceIndex"
                val promise = CompletableDeferred<Unit>()
                expectedUtteranceId.set(utteranceId)
                pendingUtterance.set(promise)
                val result = engine.speak(
                    segment.text,
                    TextToSpeech.QUEUE_ADD,
                    null,
                    utteranceId,
                )
                if (result == TextToSpeech.ERROR) {
                    expectedUtteranceId.set(null)
                    pendingUtterance.compareAndSet(promise, null)
                    readerScope.launch {
                        snackbarHostState.showSnackbar(
                            "Системный TTS не озвучил текст. Проверьте голосовой движок " +
                                "в настройках Android или используйте Edge TTS.",
                        )
                    }
                    isPlaying = false
                    return false
                }
                promise.await()
                return true
            }
        }

        // ---------- Загрузка и парсинг ----------

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val dir = BooksStore.booksDirectory(context) ?: throw IllegalStateException("Нет каталога книг")
                    val bk = dir.findFile(bookFileName)
                        ?: throw IllegalStateException("Файл книги не найден")
                    val parsed = BookParser.parse(bk, bk.uri.toString(), context)
                    val saved = BooksStore.load(context, bk)
                    withContext(Dispatchers.Main) {
                        chapters = parsed.chapters
                        currentChapterIndex = saved.chapter.coerceIn(0, parsed.chapters.lastIndex.coerceAtLeast(0))
                        val currentCh = parsed.chapters.getOrNull(currentChapterIndex)
                        // Постраничная глава считается страницами, а не
                        // предложениями: текста в ней до распознавания нет.
                        val totalSentences = if (currentCh?.isPageBased == true) {
                            currentCh.pageCount
                        } else {
                            splitSentences(currentCh?.resolvedText.orEmpty()).size
                        }
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

        // System TTS init
        DisposableEffect(context) {
            val engine = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    logcat(LogPriority.INFO) { "BooksReader: System TTS init OK" }
                } else {
                    systemTtsInitFailed = true
                    logcat(LogPriority.WARN) { "BooksReader: System TTS init failed: $status" }
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
            tts.value = engine
            onDispose {
                engine.stop()
                engine.shutdown()
                tts.value = null
            }
        }

        // Освобождение плееров при закрытии экрана
        DisposableEffect(Unit) {
            onDispose {
                runCatching { edgePlayer?.release() }
                edgePlayer = null
                runCatching { musicPlayer?.release() }
                musicPlayer = null
            }
        }

        // Полноэкранный режим: прячем системные бары
        val view = LocalView.current
        DisposableEffect(fullscreen) {
            val window = findActivity(view.context)?.window
            if (window == null) {
                onDispose {}
            } else {
                val controller = WindowCompat.getInsetsController(window, view)
                val mask = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                if (fullscreen) {
                    controller.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    runCatching { controller.hide(mask) }
                } else {
                    runCatching { controller.show(mask) }
                }
                onDispose {
                    runCatching { controller.show(mask) }
                }
            }
        }

        // --- Voice picker dialog ---
        if (showVoicePicker) {
            TtsVoicePickerDialog(
                onDismissRequest = { showVoicePicker = false },
                onPickSystem = { spec ->
                    ttsEngine = "system"
                    selectedVoiceSpec = spec
                    selectedVoiceLabel = spec.substringAfterLast("::")
                    bookPrefs.bookTtsEngine().set("system")
                    bookPrefs.bookVoiceSpec().set(spec)
                    bookPrefs.bookVoiceLabel().set(selectedVoiceLabel)
                    showVoicePicker = false
                },
                onPickEdge = { shortName ->
                    ttsEngine = "edge"
                    edgeVoiceName = shortName
                    selectedVoiceSpec = shortName
                    selectedVoiceLabel = shortName
                    bookPrefs.bookTtsEngine().set("edge")
                    bookPrefs.bookVoiceSpec().set(shortName)
                    bookPrefs.bookVoiceLabel().set(shortName)
                    showVoicePicker = false
                },
            )
        }

        // Фоновая музыка: старт/пауза синхронизированы с чтением
        LaunchedEffect(isPlaying, musicEnabled, musicVolume, currentChapterIndex) {
            if (!musicEnabled) {
                musicPlayer?.let { runCatching { it.pause() } }
                return@LaunchedEffect
            }
            if (!isPlaying) {
                musicPlayer?.let { runCatching { it.pause() } }
                return@LaunchedEffect
            }
            val mood = BookAmbience.moodFor(moodKeywords())
            val vol = musicVolume.coerceIn(0f, 1f)
            val file = withContext(Dispatchers.IO) { BookAmbience.fileFor(context, mood) }
            if (file == null) return@LaunchedEffect
            withContext(Dispatchers.Main) {
                runCatching {
                    val mp = musicPlayer ?: MediaPlayer().also { musicPlayer = it }
                    if (mp.isPlaying) {
                        mp.setVolume(vol, vol)
                    } else {
                        mp.reset()
                        mp.setDataSource(file.absolutePath)
                        mp.isLooping = true
                        mp.setVolume(vol, vol)
                        mp.prepare()
                        mp.start()
                    }
                }
            }
        }

        // --- Auto-read loop ---
        LaunchedEffect(
            isPlaying, currentChapterIndex, currentSentenceIndex, pdfPageIndex,
            speechRate, pitch, ttsEngine, edgeVoiceName, roleVoices,
            pageOcrEnabled, pageOcrBusy, pageSegments,
        ) {
            if (!isPlaying) return@LaunchedEffect
            val chapter = chapters.getOrNull(currentChapterIndex)
                ?: run { isPlaying = false; return@LaunchedEffect }

            if (chapter.isPageBased) {
                // --- Сканированные страницы: озвучиваем распознанный текст страницы ---
                if (!pageOcrEnabled) {
                    readerScope.launch {
                        snackbarHostState.showSnackbar(
                            "Включите «Распознавать страницы (OCR)», чтобы озвучивать сканированные страницы.",
                        )
                    }
                    isPlaying = false
                    return@LaunchedEffect
                }
                val page = chapter.readablePages.getOrNull(pdfPageIndex)
                    ?: run { isPlaying = false; return@LaunchedEffect }
                val segments = pageSegments[page.pageNumber].orEmpty()
                if (segments.isEmpty()) {
                    if (!pageOcrBusy) readerScope.launch { ocrPage(page.pageNumber) }
                    return@LaunchedEffect
                }
                if (currentSentenceIndex >= segments.size) {
                    if (pdfPageIndex < chapter.readablePages.lastIndex) {
                        pdfPageIndex++
                        currentSentenceIndex = 0
                    } else if (currentChapterIndex < chapters.lastIndex) {
                        currentChapterIndex++
                        currentSentenceIndex = 0
                        pdfPageIndex = 0
                    } else {
                        isPlaying = false
                    }
                    return@LaunchedEffect
                }
                val segment = segments[currentSentenceIndex]
                if (!speakSegment(segment)) return@LaunchedEffect
                currentSentenceIndex++
                return@LaunchedEffect
            }

            // --- Текстовые главы ---
            val segments = chapterSegmentsCache.getOrPut(currentChapterIndex to roleVoices) {
                segmentsForReading(chapter.resolvedText, roleVoices)
            }
            if (segments.isEmpty()) {
                isPlaying = false
                return@LaunchedEffect
            }
            if (currentSentenceIndex >= segments.size) {
                if (currentChapterIndex < chapters.lastIndex) {
                    currentChapterIndex++
                    currentSentenceIndex = 0
                    pdfPageIndex = 0
                } else {
                    isPlaying = false
                }
                return@LaunchedEffect
            }
            val segment = segments[currentSentenceIndex]
            if (!speakSegment(segment)) return@LaunchedEffect
            currentSentenceIndex++
        }

        // Сохранение прогресса
        LaunchedEffect(bookFileName, currentChapterIndex, currentSentenceIndex, pdfPageIndex, chapters.size) {
            if (chapters.isEmpty() || loading) return@LaunchedEffect
            val bk = bookFile ?: return@LaunchedEffect
            val current = chapters.getOrNull(currentChapterIndex)
            val saveSentence = if (current?.isPageBased == true) pdfPageIndex else currentSentenceIndex
            // Расчёт в чистой функции: в composable он был сломан (единицы
            // прогресса у глав разные, а у PDF текст появляется только после
            // распознавания), и его не покрывали тесты.
            // Счётчики предложений кэшируются по главам: текст главы не
            // меняется, а прогресс пересчитывается на каждой фразе.
            val percent = BookProgress.percent(
                chapters = chapters,
                sentencesInText = { i: Int ->
                    val chapter = chapters[i]
                    // Постраничная глава меряется страницами, а её текст ещё и
                    // меняется по мере OCR — считать там предложения нельзя.
                    if (chapter.isPageBased) {
                        0
                    } else {
                        sentenceCountCache.getOrPut(i) {
                            countSentences(chapter.resolvedText)
                        }
                    }
                },
                currentIndex = currentChapterIndex,
                positionInChapter = saveSentence,
            )
            runCatching { BooksStore.save(context, bk, BooksStore.Snapshot(currentChapterIndex, saveSentence, percent)) }
        }

        // Рендер страниц PDF по требованию
        LaunchedEffect(chapters, currentChapterIndex, pdfPageIndex, pageSegments) {
            val chapter = chapters.getOrNull(currentChapterIndex)
            if (chapter?.isPageBased != true) {
                pageImage = null
                return@LaunchedEffect
            }
            val page = chapter.readablePages.getOrNull(pdfPageIndex) ?: run {
                pageImage = null
                return@LaunchedEffect
            }
            // Если страница уже распознана — картинка не нужна, показываем текст
            if (pageSegments[page.pageNumber].isNullOrEmpty()) {
                val bmp = withContext(Dispatchers.IO) {
                    val file = bookFile ?: return@withContext null
                    BookParser.renderPage(context, file, page.pageNumber)
                }
                val current = chapters.getOrNull(currentChapterIndex)
                if (current?.isPageBased == true &&
                    current.readablePages.getOrNull(pdfPageIndex)?.pageNumber == page.pageNumber
                ) {
                    val old = pageImage
                    pageImage = bmp
                    old?.recycle()
                } else {
                    bmp?.recycle()
                }
            } else {
                val old = pageImage
                pageImage = null
                old?.recycle()
            }
        }

        // ---------- UI ----------

        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }

        if (errorMessage != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = errorMessage!!, color = MaterialTheme.colorScheme.error)
            }
            return
        }

        val currentChapter = chapters.getOrNull(currentChapterIndex)
        val isPageBased = currentChapter?.isPageBased == true
        val readablePages = currentChapter?.readablePages.orEmpty()
        val pageNum = readablePages.getOrNull(pdfPageIndex)?.pageNumber
        val segments = if (currentChapter == null) emptyList() else {
            if (isPageBased) {
                pageSegments[pageNum].orEmpty()
            } else {
                chapterSegmentsCache.getOrPut(currentChapterIndex to roleVoices) {
                    segmentsForReading(currentChapter.resolvedText, roleVoices)
                }
            }
        }
        val headerCount = if (isPageBased && segments.isNotEmpty()) 1 else 0

        // Лента чтения: текст вперемешку с иллюстрациями (в той же очерёдности,
        // в которой картинки шли в EPUB; TTS на них не реагирует).
        val viewItems = if (currentChapter == null || isPageBased) {
            emptyList()
        } else {
            remember(currentChapterIndex, roleVoices, segments.size) {
                buildBookViewItems(currentChapter.resolvedText, segments, roleVoices)
            }
        }

        // Автопрокрутка к читаемому сегменту
        LaunchedEffect(currentChapterIndex, currentSentenceIndex, isPageBased) {
            if (segments.isNotEmpty()) {
                val target = (currentSentenceIndex + headerCount).coerceIn(0, segments.lastIndex + headerCount)
                listState.animateScrollToItem(target)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(visible = !fullscreen) {
                    TopAppBar(
                        title = {
                            Text(
                                text = title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { navigator.pop() }) {
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

                    // --- Chapter list ---
                    AnimatedVisibility(visible = showChapterList) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        ) {
                            itemsIndexed(chapters, key = { _, ch -> ch.id }) { index, chapter ->
                                Text(
                                    text = chapter.displayTitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (index == currentChapterIndex)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            currentChapterIndex = index
                                            currentSentenceIndex = 0
                                            pdfPageIndex = 0
                                            isPlaying = false
                                        }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                HorizontalDivider()
                            }
                        }
                    }

                    // --- Settings panel ---
                    //
                    // Высота ОГРАНИЧЕНА. Без потолка прокручивающийся Column
                    // сообщает бесконечную желаемую высоту и забирает весь экран:
                    // панель наезжала на верхнюю панель, а список глав и текст
                    // страницы уезжали вниз и обрезались. Фон непрозрачный по
                    // той же причине — на просвете читался текст страницы.
                    AnimatedVisibility(visible = showSettings) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                                .background(MaterialTheme.colorScheme.surface)
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                        ) {
                            Text(
                                "Движок озвучки",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(vertical = 4.dp),
                            ) {
                                FilterChip(
                                    selected = !isEdgeTts,
                                    onClick = {
                                        ttsEngine = "system"
                                        bookPrefs.bookTtsEngine().set("system")
                                    },
                                    label = { Text("📱 Системный") },
                                )
                                FilterChip(
                                    selected = isEdgeTts,
                                    onClick = {
                                        ttsEngine = "edge"
                                        bookPrefs.bookTtsEngine().set("edge")
                                    },
                                    label = { Text("🌐 Edge TTS") },
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Voice picker button
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showVoicePicker = true }
                                    .padding(vertical = 4.dp),
                            ) {
                                Text(
                                    text = "Голос: ${selectedVoiceLabel.ifBlank { "По умолчанию" }}",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "▸",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

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

                            SettingSwitchRow(
                                label = "Голоса по ролям",
                                checked = roleVoices,
                                onCheckedChange = { v ->
                                    roleVoices = v
                                    bookPrefs.bookRoleVoices().set(v)
                                    currentSentenceIndex = 0
                                    isPlaying = false
                                },
                            )
                            SettingSwitchRow(
                                label = "Распознавать страницы (OCR)",
                                checked = pageOcrEnabled,
                                onCheckedChange = { v ->
                                    pageOcrEnabled = v
                                    bookPrefs.bookPageOcr().set(v)
                                },
                            )
                            SettingSwitchRow(
                                label = "Фоновая музыка",
                                checked = musicEnabled,
                                onCheckedChange = { v ->
                                    musicEnabled = v
                                    bookPrefs.bookMusicEnabled().set(v)
                                    if (!v) musicPlayer?.let { runCatching { it.pause() } }
                                },
                            )
                            if (musicEnabled) {
                                Text(
                                    "Громкость музыки",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Slider(
                                    value = musicVolume,
                                    onValueChange = {
                                        musicVolume = it
                                        bookPrefs.bookMusicVolume().set(it)
                                    },
                                    valueRange = 0.04f..0.8f,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            SettingSwitchRow(
                                label = "Во весь экран",
                                checked = fullscreen,
                                onCheckedChange = { v ->
                                    fullscreen = v
                                    bookPrefs.bookFullscreen().set(v)
                                    showSettings = false
                                },
                            )
                        }
                    }
                }

                val contentModifier = Modifier.weight(1f)

                Box(modifier = contentModifier) {
                    when {
                        isPageBased && segments.isNotEmpty() -> {
                            // Распознанный текст страницы — поверхностный список с подсветкой
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                state = listState,
                            ) {
                                item(key = "__page_header__") {
                                    Text(
                                        text = "Страница $pageNum · распознанный текст",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 4.dp),
                                    )
                                }
                                itemsIndexed(segments, key = { idx, _ -> idx }) { idx, segment ->
                                    val isCurrent = idx == currentSentenceIndex
                                    Text(
                                        text = if (isCurrent) "▸ ${segment.text}" else segment.text,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontSize = 18.sp,
                                            lineHeight = 28.sp,
                                        ),
                                        color = if (isCurrent)
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(
                                                if (isCurrent)
                                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                                else Color.Transparent,
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                        isPageBased && pageImage != null -> {
                            Image(
                                bitmap = pageImage!!.asImageBitmap(),
                                contentDescription = currentChapter?.displayTitle,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        }
                        isPageBased -> {
                            val pNum = pageNum ?: (currentChapterIndex + 1)
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = if (pageOcrBusy) {
                                        "Страница $pNum — распознаю текст…"
                                    } else {
                                        "Страница $pNum — нет изображения"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                                if (!pageOcrBusy && !pageOcrEnabled) {
                                    Text(
                                        text = "Включите «Распознавать страницы (OCR)» для чтения сканов.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                            }
                        }
                        viewItems.isNotEmpty() -> {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                state = listState,
                            ) {
                                itemsIndexed(viewItems, key = { idx, _ -> idx }) { _, item ->
                                    when (item) {
                                        is BookImageItem -> {
                                            val bmp = remember(item.path) {
                                                BookIllustrations.get(item.path)
                                            }
                                            if (bmp != null) {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 8.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                ) {
                                                    Image(
                                                        bitmap = bmp.asImageBitmap(),
                                                        contentDescription = item.caption
                                                            .ifBlank { "Иллюстрация" },
                                                        contentScale = ContentScale.FillWidth,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clip(RoundedCornerShape(4.dp)),
                                                    )
                                                    if (item.caption.isNotBlank()) {
                                                        Text(
                                                            text = item.caption,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            textAlign = TextAlign.Center,
                                                            modifier = Modifier.padding(top = 4.dp),
                                                        )
                                                    }
                                                }
                                            } else if (item.caption.isNotBlank()) {
                                                Text(
                                                    text = "(иллюстрация: ${item.caption})",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 6.dp),
                                                )
                                            }
                                        }
                                        is BookTextItem -> {
                                            val isCurrent = item.index == currentSentenceIndex
                                            Text(
                                                text = if (isCurrent) "▸ ${item.segment.text}" else item.segment.text,
                                                style = MaterialTheme.typography.bodyLarge.copy(
                                                    fontSize = 18.sp,
                                                    lineHeight = 28.sp,
                                                ),
                                                color = if (isCurrent)
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                else MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(
                                                        if (isCurrent)
                                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                                        else Color.Transparent,
                                                    )
                                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                            }
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

                if (!fullscreen && segments.isNotEmpty()) {
                    LinearProgressIndicator(
                        progress = { currentSentenceIndex.toFloat() / segments.size },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                val totalFilePages = chapters.lastOrNull()?.pages?.lastOrNull()?.pageNumber ?: chapters.size
                val statusText = if (isPageBased) {
                    val pNum = pageNum ?: (currentChapterIndex + 1)
                    if (segments.isNotEmpty()) {
                        "Страница $pNum · ${currentSentenceIndex + 1}/${segments.size}"
                    } else {
                        "Страница $pNum из $totalFilePages"
                    }
                } else {
                    "Глава ${currentChapterIndex + 1}/${chapters.size} · " +
                        "Предложение ${currentSentenceIndex + 1}/${segments.size.coerceAtLeast(1)}"
                }
                if (!fullscreen) {
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
                                        stopAll()
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
                                        stopAll()
                                    }
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.Outlined.SkipNext, contentDescription = "Вперёд")
                            }
                        }
                    }

                    // --- Bottom controls ---
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isPageBased) {
                            // Ручное распознавание страницы
                            val cur = chapters.getOrNull(currentChapterIndex)
                            val pg = cur?.readablePages?.getOrNull(pdfPageIndex)
                            IconButton(
                                onClick = {
                                    val p = pg ?: return@IconButton
                                    if (!pageSegments.containsKey(p.pageNumber) && !pageOcrBusy) {
                                        readerScope.launch { ocrPage(p.pageNumber) }
                                    }
                                },
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.DocumentScanner,
                                    contentDescription = "Распознать страницу",
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                        // Prev chapter
                        IconButton(onClick = {
                            if (currentChapterIndex > 0) {
                                currentChapterIndex--
                                currentSentenceIndex = 0
                                pdfPageIndex = 0
                            }
                        }) {
                            Icon(Icons.Outlined.SkipPrevious, contentDescription = "Предыдущая глава")
                        }

                        // Stop
                        IconButton(
                            onClick = stopAll,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Stop,
                                contentDescription = "Стоп",
                                modifier = Modifier.size(24.dp),
                            )
                        }

                        // Play / Pause
                        IconButton(
                            onClick = togglePlay,
                            modifier = Modifier.size(56.dp),
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                contentDescription = if (isPlaying) "Пауза" else "Читать",
                                modifier = Modifier.size(32.dp),
                            )
                        }

                        // Next chapter
                        IconButton(onClick = {
                            if (currentChapterIndex < chapters.lastIndex) {
                                currentChapterIndex++
                                currentSentenceIndex = 0
                                pdfPageIndex = 0
                            }
                        }) {
                            Icon(Icons.Outlined.SkipNext, contentDescription = "Следующая глава")
                        }
                    }
                }
            }

            // Компактные плавающие кнопки в полноэкранном режиме
            if (fullscreen) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        fullscreen = false
                        bookPrefs.bookFullscreen().set(false)
                    }) {
                        Icon(Icons.Outlined.FullscreenExit, contentDescription = "Вернуть панели")
                    }
                    IconButton(onClick = stopAll, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.Stop, contentDescription = "Стоп", modifier = Modifier.size(22.dp))
                    }
                    IconButton(onClick = togglePlay, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = if (isPlaying) "Пауза" else "Читать",
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = if (fullscreen) 56.dp else 0.dp),
            )
        }
    }

    /**
     * Строка-маркер иллюстрации в тексте главы EPUB: «⟦путь-к-файлу⟧|подпись».
     * TTS и счётчики предложений такие строки не видят (картинки не
     * озвучиваем), читалка рендерит их настоящим изображением.
     */
    private val BOOK_IMG_MARKER = Regex("^⟦(.+?)⟧\|?([^\n]*)$")

    private fun stripIllustrations(text: String): String {
        if ('⟦' !in text) return text
        return text.lineSequence()
            .filter { BOOK_IMG_MARKER.matchEntire(it.trim()) == null }
            .joinToString("\n")
    }

    /** Элемент ленты чтения книги: предложение или иллюстрация. */
    private data class BookTextItem(val index: Int, val segment: BookSpeechSegment)
    private data class BookImageItem(val path: String, val caption: String)

    /**
     * Собирает ленту отображения главы: строки-маркеры иллюстраций становятся
     * картинками между абзацами, остальное — предложения из [segments] (в том
     * же порядке, чтобы подсветка и индексы озвучки совпадали).
     */
    private fun buildBookViewItems(
        text: String,
        segments: List<BookSpeechSegment>,
        roleVoices: Boolean,
    ): List<Any> {
        if ('⟦' !in text) return segments.mapIndexed { i, seg -> BookTextItem(i, seg) }
        val items = ArrayList<Any>(segments.size + 4)
        var segIdx = 0
        val block = StringBuilder()
        fun flushBlock() {
            if (block.isEmpty()) return
            val blockSegsCount = segmentsForReading(block.toString(), roleVoices).size
            block.setLength(0)
            repeat(blockSegsCount) {
                if (segIdx < segments.size) {
                    items += BookTextItem(segIdx, segments[segIdx])
                    segIdx++
                }
            }
        }
        for (line in text.lineSequence()) {
            val m = BOOK_IMG_MARKER.matchEntire(line.trim())
            if (m != null) {
                flushBlock()
                items += BookImageItem(m.groupValues[1], m.groupValues[2].trim())
            } else {
                if (block.isNotEmpty()) block.append('\n')
                block.append(line)
            }
        }
        flushBlock()
        // Гарантия: ни один озвученный сегмент не потерян при расхождении счёта.
        while (segIdx < segments.size) {
            items += BookTextItem(segIdx, segments[segIdx])
            segIdx++
        }
        return items
    }

    /** Кэш распакованных иллюстраций по абсолютным путям (маленький LRU). */
    private object BookIllustrations {
        private const val CAPACITY = 16
        private val map = java.util.LinkedHashMap<String, Bitmap>(CAPACITY, 0.75f, true)

        @Synchronized
        fun get(path: String): Bitmap? {
            map[path]?.let { return it }
            val bmp = decode(path) ?: return null
            while (map.size >= CAPACITY) {
                val eldest = map.entries.first().key
                map.remove(eldest)
            }
            map[path] = bmp
            return bmp
        }

        private fun decode(path: String): Bitmap? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 2048) sample *= 2
            BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }.getOrNull()
    }

    private fun segmentsForReading(text: String, roleVoices: Boolean): List<BookSpeechSegment> {
        val clean = stripIllustrations(text)
        return if (roleVoices) {
            BookTtsScript.split(clean)
        } else {
            splitSentences(clean).map { BookSpeechSegment(BookSpeechRole.NARRATOR, 0, it) }
        }
    }

    private val MULTI_NEWLINE = Regex("\n{2,}")
    private val SENTENCE_SPLIT = Regex("(?<=[.!?…])\\s+")

    private fun splitSentences(text: String): List<String> {
        return stripIllustrations(text)
            .replace(MULTI_NEWLINE, "\n")
            .split(SENTENCE_SPLIT)
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    /**
     * Число предложений без построчного списка.
     *
     * Считает ровно то же, что [splitSentences] вернул бы по длине, но не
     * создаёт ни одной строки-реплики: прогрессу нужна только цифра, а
     * прогресс пересчитывается на каждой озвученной фразе.
     */
    private fun countSentences(text: String): Int {
        val clean = stripIllustrations(text)
        if (clean.isBlank()) return 0
        val normalized = clean.replace(MULTI_NEWLINE, "\n")
        val matcher = SENTENCE_SPLIT.toPattern().matcher(normalized)
        var count = 0
        var start = 0
        while (matcher.find()) {
            if (hasNonWhitespace(normalized, start, matcher.start())) count++
            start = matcher.end()
        }
        if (hasNonWhitespace(normalized, start, normalized.length)) count++
        return count
    }

    private fun hasNonWhitespace(s: String, from: Int, to: Int): Boolean {
        for (i in from until to) if (!s[i].isWhitespace()) return true
        return false
    }
}

@Composable
private fun SettingSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private tailrec fun findActivity(context: Context?): Activity? = when (context) {
    is Activity -> context
    is ContextWrapper -> findActivity(context.baseContext)
    else -> null
}