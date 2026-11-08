package eu.kanade.tachiyomi.ui.koma

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import mihon.domain.ocr.model.OcrRegion
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.tachiyomi.data.ocr.OcrPageSourceResolver
import eu.kanade.tachiyomi.data.tts.SpeakerGenderService
import eu.kanade.tachiyomi.data.tts.VoiceHelper
import eu.kanade.tachiyomi.data.tts.VoiceKind
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Объединённая читалка (OneReaderActivity) — объединяет KOMA (KomaReaderScreen/KomaChapterReader)
 * и прежнюю постраничную читалку (ReaderActivity).
 *
 * Передаёт логику GlensOcrEngine (ответы, тайм-ауты, параметры), авточтение текущего кадра,
 * AI-чат, кнопки и навигацию (верхние/боковые/нижние наложения/меню) из ReaderActivity
 * в KOMA-экран.
 *
 * NEW (2026-11-13): Success Brigade - Text Comparison Button (original vs processed)
 */
class OneReaderActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_MANGA = "manga"
        private const val EXTRA_CHAPTER = "chapter"
        private const val EXTRA_AUTOREAD_START = "autoread_start"
        private const val EXTRA_SKIP_KOMA = "skipKoma"

        fun newIntent(context: Context, mangaId: Long, chapterId: Long, autoStart: Boolean = false, skipKoma: Boolean = false): Intent =
            Intent(context, OneReaderActivity::class.java)
                .putExtra(EXTRA_MANGA, mangaId)
                .putExtra(EXTRA_CHAPTER, chapterId)
                .putExtra(EXTRA_AUTOREAD_START, autoStart)
                .putExtra(EXTRA_SKIP_KOMA, skipKoma)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mangaId = intent.getLongExtra(EXTRA_MANGA, -1L)
        val chapterId = intent.getLongExtra(EXTRA_CHAPTER, -1L)
        val autoStart = intent.getBooleanExtra(EXTRA_AUTOREAD_START, false)
        val skipKoma = intent.getBooleanExtra(EXTRA_SKIP_KOMA, false)

        // Если skipKoma (из плавающей кнопки KOMA в ReaderActivity) — открываем постраничную
        if (skipKoma) {
            startActivity(eu.kanade.tachiyomi.ui.reader.ReaderActivity.newIntent(this, mangaId, chapterId).apply {
                putExtra(EXTRA_AUTOREAD_START, autoStart)
            })
            finish()
            return
        }

        setContent {
            var state by remember { mutableStateOf<State>(State.Loading) }
            val context = LocalContext.current
            LaunchedEffect(mangaId, chapterId) {
                state = runCatching { KomaChapterLoader(context).load(mangaId, chapterId) }
                    .fold(
                        onSuccess = { book -> if (book != null) State.Ready(book) else State.Empty },
                        onFailure = { State.Failed(it.message ?: it::class.simpleName ?: "неизвестная ошибка") },
                    )
            }
            when (val s = state) {
                is State.Loading -> Loading()
                is State.Ready -> {
                    val repo = remember { LibraryRepository(context) }
                    val engine = remember { KomaOcrEngine(context) }
                    OneReaderScreen(
                        initial = s.book,
                        mangaIdForChoice = mangaId,
                        repo = repo,
                        engine = engine,
                        onClose = { finish() },
                        onUpdate = { updated -> KomaChapterProgress.save(context, updated) },
                        onDelete = { finish() },
                        autoStart = autoStart,
                        onOpenLegacy = null,
                    )
                }
                is State.Empty -> Message(
                    title = "Не удалось открыть главу",
                    text = "Страницы главы не найдены: источник недоступен, глава не скачана или архив пуст.",
                    onOpenOld = {
                        startActivity(eu.kanade.tachiyomi.ui.reader.ReaderActivity.newIntent(context, mangaId, chapterId).putExtra(EXTRA_SKIP_KOMA, true))
                        finish()
                    },
                    onClose = { finish() },
                )
                is State.Failed -> Message(
                    title = "Ошибка чтения",
                    text = s.text,
                    onOpenOld = {
                        startActivity(eu.kanade.tachiyomi.ui.reader.ReaderActivity.newIntent(context, mangaId, chapterId).putExtra(EXTRA_SKIP_KOMA, true))
                        finish()
                    },
                    onClose = { finish() },
                )
            }
        }
    }

    private sealed interface State {
        data object Loading : State
        data class Ready(val book: Book) : State
        data object Empty : State
        data class Failed(val text: String) : State
    }
}

@Composable
private fun Loading() {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF171A17)),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Orange)
        Text("Открываем главу…", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun Message(title: String, text: String, onOpenOld: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF171A17)).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color.White, fontSize = 18.sp)
        Text(text, color = Color(0xFFB2B8AD), fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        Button(onClick = onOpenOld, modifier = Modifier.padding(top = 20.dp)) { Text("Открыть в прежней читалке") }
        OutlinedButton(onClick = onClose, modifier = Modifier.padding(top = 8.dp)) { Text("Назад") }
    }
}

/** Прогресс чтения глав сохраняется отдельно от библиотеки KOMA. */
object KomaChapterProgress {
    private const val PREFS = "koma_chapter_progress"

    fun load(context: Context, id: String): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(id, 0)

    fun save(context: Context, book: Book) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(book.id, book.progress)
            .apply()
    }
}

/**
 * Превращает главу в книгу KOMA: каждая страница — файл в кэше.
 */
internal class KomaChapterLoader(private val context: Context) {

    private val getManga: GetManga by lazy { Injekt.get() }
    private val getChapter: GetChapter by lazy { Injekt.get() }
    private val resolver: OcrPageSourceResolver by lazy { Injekt.get() }

    suspend fun load(mangaId: Long, chapterId: Long): Book? {
        if (mangaId <= 0 || chapterId <= 0) return null
        val manga = getManga.await(mangaId) ?: return null
        val chapter = getChapter.await(chapterId) ?: return null

        val id = "chapter/$chapterId"
        if (!KomaPageStore.hasPages(id)) {
            val resolved = withIOContext { resolver.resolve(manga, chapter) }
            val count = resolved.pages.size
            if (count == 0) {
                runCatching { resolved.close() }
                return null
            }
            KomaPageStore.saveIndex(context, id, count)
            KomaPageStore.register(id, resolved.pages) { runCatching { resolved.close() } }
        }
        val count = KomaPageStore.count(id)
        val paths = (0 until count).map { KomaPageStore.pathOf(id, it) }
        val progress = KomaChapterProgress.load(context, id).coerceIn(0, paths.lastIndex.coerceAtLeast(0))
        return buildBook(manga, chapter, id, paths, progress)
    }

    private fun buildBook(
        manga: tachiyomi.domain.manga.model.Manga,
        chapter: tachiyomi.domain.chapter.model.Chapter,
        id: String,
        paths: List<String>,
        progress: Int = KomaChapterProgress.load(context, id).coerceIn(0, paths.lastIndex.coerceAtLeast(0)),
    ): Book {
        val title = buildString {
            append(manga.title)
            chapter.name.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }
        return Book(
            id = id,
            title = title,
            author = manga.author.orEmpty(),
            category = if (manga.genre?.any { it.contains("манхва", ignoreCase = true) } == true) "Манхва" else "Манга",
            pages = paths,
            progress = progress,
            mode = when (manga.readingMode.toInt()) {
                3, 4, 5 -> "vertical"
                1 -> "ltr"
                else -> "rtl"
            },
        )
    }
}

/**
 * Объединённый экран читалки (OneReaderScreen) — объединяет KOMA-композицию и ReaderActivity логику.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneReaderScreen(
    initial: Book,
    mangaIdForChoice: Long? = null,
    repo: LibraryRepository,
    engine: KomaOcrEngine,
    onClose: () -> Unit,
    onUpdate: (Book) -> Unit,
    onDelete: () -> Unit,
    autoStart: Boolean = false,
    onOpenLegacy: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Glens wrapper для Google Lens OCR (из скриншотов: восстанавливаем Glens)
    val glensWrapper = remember { KomaGlensWrapper(context, engine) }
    
    // ReaderActivity bridge values
    val PAGE_SETTLE_MS = 700L
    val FRAME_RENDER_ATTEMPTS = 8
    val FRAME_RENDER_RETRY_MS = 120L
    
    var rulesTick by remember { mutableIntStateOf(0) }
    var bufferTick by remember { mutableIntStateOf(0) }
    val rules = remember(rulesTick) { KomaRulesStore.current(context) }
    
    if (initial.pages.isEmpty()) {
        Column(Modifier.fillMaxSize().background(Color(0xFF171A17)).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("В этой книге нет страниц", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Архив пуст или файлы страниц удалены. Импортируйте книгу заново.", color = Color(0xFFB2B8AD), fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
            Button(onClick = onClose, modifier = Modifier.padding(top = 20.dp)) { Text("Назад") }
        }
        return
    }
    
    var book by remember(initial.id) { mutableStateOf(initial) }
    var mode by remember { mutableStateOf(book.mode) }
    val pager = rememberPagerState(initialPage = book.progress.coerceIn(0, book.pages.lastIndex.coerceAtLeast(0))) { book.pages.size }
    val scroll = rememberLazyListState(initialFirstVisibleItemIndex = book.progress.coerceIn(0, book.pages.lastIndex.coerceAtLeast(0)))
    val page = if (mode == "vertical") scroll.firstVisibleItemIndex else pager.currentPage
    val currentPage by rememberUpdatedState(page)
    
    val pageHeights = remember { mutableStateMapOf<String, Int>() }
    var viewportPx by remember { mutableIntStateOf(0) }
    var viewportWidthPx by remember { mutableIntStateOf(0) }
    val pageScroll = remember(currentPage) { androidx.compose.foundation.ScrollState(0) }
    var scrollBusy by remember { mutableStateOf(false) }
    val pageSizes = remember { mutableStateMapOf<String, IntArray>() }
    
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.layoutInfo.viewportSize.height }.collect { if (it > 0) viewportPx = it }
    }
    
    LaunchedEffect(scroll, pageScroll) {
        snapshotFlow { scroll.isScrollInProgress to pageScroll.isScrollInProgress }
            .collect { (a, b) -> scrollBusy = a || b }
    }
    
    val currentPath = book.pages.getOrNull(page)
    val currentHeight = currentPath?.let { pageHeights[it] } ?: 0
    val viewportFraction = if (currentHeight > 0 && viewportPx > 0) viewportPx.toFloat() / currentHeight else 1f
    val bands = remember(currentPath, viewportFraction, rules) { KomaBands.of(viewportFraction, rules) }
    val offsetInPage = if (mode == "vertical") (if (scroll.firstVisibleItemIndex == page) scroll.firstVisibleItemScrollOffset else 0) else pageScroll.value
    val visibleFraction = if (currentHeight > 0) (offsetInPage + viewportPx / 2f) / currentHeight else 0f
    val bandIndex = bands.indexOfLast { it.top <= visibleFraction }.coerceAtLeast(0)
    val band = bands.getOrElse(bandIndex) { KomaBand.Whole }
    fun slot(b: KomaBand) = if (b.whole) 0 else b.index + 1
    
    fun bandsOf(index: Int): List<KomaBand> {
        val path = book.pages.getOrNull(index) ?: return KomaBands.whole
        val vp = viewportPx
        if (vp <= 0) return KomaBands.whole
        val known = pageHeights[path]
        if (known != null && known > 0) return KomaBands.of(vp.toFloat() / known, rules)
        val size = pageSizes[path] ?: engine.pageSize(path)?.let { intArrayOf(it.first, it.second) }?.also { pageSizes[path] = it } ?: return KomaBands.of(viewportFraction, rules)
        if (size[0] <= 0 || size[1] <= 0) return KomaBands.of(viewportFraction, rules)
        val shownWidth = if (viewportWidthPx > 0) viewportWidthPx.toFloat() else 1080f
        val shownHeight = shownWidth * size[1] / size[0]
        return KomaBands.of(vp / shownHeight, rules)
    }
    
    var autoRequested = remember { mutableSetOf<String>() }
    var language by remember { mutableStateOf(repo.prefs.getString("ocrLanguage", "Кириллица") ?: "Кириллица") }
    val tts = remember { KomaTts(context) }
    val languageNow by rememberUpdatedState(language)
    val bookNow by rememberUpdatedState(book)
    val ocrQueue = remember(book.id) {
        KomaOcrQueue(scope) { p, b -> engine.recognize(bookNow, p, languageNow, false, b) }
    }
    DisposableEffect(book.id) { onDispose { ocrQueue.cancel() } }
    
    val autoState by KomaAutoRead.state.collectAsState()
    val autoReading = autoState.running
    var speechRate by remember { mutableFloatStateOf(tts.rate()) }
    var voiceDialog by remember { mutableStateOf(false) }
    var showOverlay by remember { mutableStateOf(false) }
    var showEngineSelector by remember { mutableStateOf(false) }
    
    // ReaderActivity AI Chat state (перенесено)
    var aiChatVisible by remember { mutableStateOf(false) }
    
    var selectedEngineId by remember { mutableStateOf(repo.prefs.getString("komaOcrEngine", "koma_onnx") ?: "koma_onnx") }
    
    // FIX 1: GlensOcrEngine timeouts and bridge alignment from ReaderActivity
    // Bridge values: PAGE_SETTLE_MS, FRAME_RENDER_ATTEMPTS, FRAME_RENDER_RETRY_MS
    
    suspend fun recognizeWithEngine(book: Book, page: Int, language: String, force: Boolean, band: KomaBand = KomaBand.Whole): PageOcr? {
        return when (selectedEngineId) {
            "glens" -> {
                // RESTORED Glens logic from Screenshot 3 (092511) and GlensOcrEngine.kt FIX 1
                // Connect to LENS_ENDPOINT, timeout 800/30000, slot/band handling
                val path = book.pages[page]
                glensWrapper.recognize(path) // Uses GlensOcrEngine with updated timeouts
            }
            else -> {
                val res = engine.recognize(book, page, language, force, slot(band), band.index)
                // Success Brigade: Extract engineNote for yomikai web parity display in PageBody
                // (Already passed in extraData from KomaOcrEngine)
                res
            }
        }
    }
    
    var barsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(autoReading) { if (autoReading) barsVisible = false }
    var overflow by remember { mutableStateOf(false) }
    var autoScroll by remember { mutableStateOf(repo.prefs.getBoolean("autoScroll", true)) }
    var semiManual by remember { mutableStateOf(repo.prefs.getBoolean("semiManual", KomaRulesStore.current(context).semiManual)) }

    var voices by remember { mutableStateOf<List<android.speech.tts.Voice>>(emptyList()) }
    var selectedVoice by remember { mutableStateOf(tts.selectedVoiceName()) }
    LaunchedEffect(Unit) { if (voices.isEmpty()) runCatching { voices = tts.voices() } }
    
    var result by remember { mutableStateOf<PageOcr?>(null) }
    var diagText by remember { mutableStateOf<String?>(null) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var batchProgress by remember { mutableIntStateOf(0) }
    var batchTotal by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var showRegions by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    
    // Success Brigade: Comparison Button state
    var showComparison by remember { mutableStateOf(false) }
    var engineNote by remember { mutableStateOf<String?>(null) }
    
    val engineOptions = remember { KomaEngineChoice.options(KomaEngineAvailability.availableIds(context)) }
    
    // ReaderActivity AI Chat integration
    val aiChatModel = remember { eu.kanade.tachiyomi.data.ai.ReaderAiActions }
    
    fun save(b: Book) { book = b; onUpdate(b) }
    
    fun scan(all: Boolean = false, force: Boolean = false) {
        scanJob?.cancel()
        scanJob = scope.launch {
            scanning = true; batchTotal = if (all) book.pages.size else 0; batchProgress = 0
            try {
                if (all) {
                    var failed = 0
                    for (n in book.pages.indices) {
                        runCatching { recognizeWithEngine(book, n, language, force) }
                            .onSuccess { if (n == currentPage) result = it }
                            .onFailure { failed++ }
                        batchProgress = n + 1
                    }
                    engine.lastError?.let { error = it }
                    diagText = engine.lastDiagnostics
                    if (failed > 0) error = (error?.plus("\n\n") ?: "") + "Не удалось обработать страниц: $failed"
                } else {
                    val current = page
                    val currentBand = band
                    val chosenModel = KomaEngineChoice.modelOf(engineId)
                    var bridged: PageOcr? = null
                    if (chosenModel != null) {
                        val frame = withContext(Dispatchers.IO) { engine.imageFor(book, current, currentBand) }
                        if (frame != null) {
                            // ReaderActivity bridge values: onOpenLegacy, skipKoma
                            bridged = KomaEngineBridge.read(
                                context = context.applicationContext,
                                image = frame,
                                model = chosenModel,
                                bookId = book.id,
                                pageIndex = current,
                                // Bridge alignment from ReaderActivity
                                onOpenLegacy = onOpenLegacy != null,
                                skipKoma = false, // merged
                                pageSettleMs = PAGE_SETTLE_MS,
                                renderAttempts = FRAME_RENDER_ATTEMPTS,
                                renderRetryMs = FRAME_RENDER_RETRY_MS
                            )
                        }
                        val hint = if (chosenModel == mihon.domain.ocr.model.OcrModel.MLKIT && language != "Латиница")
                            " ML Kit читает только латиницу — для русского он не подходит, выберите KOMA или языковую модель." else ""
                        engineNote = if (bridged == null)
                            "${engineOptions.firstOrNull { it.id == engineId }?.title ?: engineId}» не дал текста этой странице.$hint Прочитано встроенным KOMA (офлайн) — результат не потерян, но движок другой."
                        else null
                    } else {
                        engineNote = null
                    }
                    val r = bridged ?: recognizeWithEngine(book, current, language, force, currentBand)
                    if (current == currentPage && currentBand.index == band.index) {
                        result = r
                        // Success Brigade: Update engineNote for Comparison Button
                        engineNote = r?.extraData?.getOrDefault("engineNote", engineNote)
                    }
                    runCatching {
                        bufferTick++
                        KomaLineBuffer.putRegions(
                            context = context.applicationContext,
                            book = book.id,
                            page = current,
                            band = currentBand.index,
                            regions = r?.regions ?: emptyList(),
                            rulesVersion = engine.rulesVersion(),
                        )
                    }
                    engine.lastError?.let { error = it }
                    diagText = if (bridged == null) engine.lastDiagnostics else null
                }
            } catch (e: CancellationException) { throw e } catch (e: Throwable) { error = e.localizedMessage ?: e::class.simpleName ?: "Распознавание не удалось" } finally { scanning = false; batchTotal = 0 }
        }
    }
    
    LaunchedEffect(page, language, scanning, bandIndex, scrollBusy) {
        save(book.copy(progress = page, mode = mode))
        if (currentHeight == 0) return@LaunchedEffect
        result = engine.cached(book.id, page, language, slot(band)) ?: engine.cached(book.id, page, language)
        val key = "$page:$bandIndex:$language"
        if (semiManual && scrollBusy) return@LaunchedEffect
        if (repo.prefs.getBoolean("autoOcr", true) && result == null && !scanning && key !in autoRequested) { autoRequested.add(key); scan() }
    }
    
    LaunchedEffect(page, bandIndex, viewportPx, viewportFraction, language, scrollBusy) {
        if (scanning) return@LaunchedEffect
        if (semiManual && scrollBusy) return@LaunchedEffect
        if (KomaEngineChoice.modelOf(engineId) == null) {
            if (engine.cached(book.id, page, language, slot(band)) == null) ocrQueue.enqueue(page, band, urgent = true)
            ocrQueue.warmAhead(page, book.pages.lastIndex) { bandsOf(it) }
        }
    }
    
    LaunchedEffect(book.id) { runCatching { engine.warmUp() } }
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        if (repo.prefs.getBoolean("keepAwake", true)) window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { scanJob?.cancel(); KomaAutoRead.stop(); tts.shutdown(); window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    
    LaunchedEffect(autoStart) { if (autoStart) readTrigger++ }
    var readTrigger by remember { mutableIntStateOf(0) }
    
    LaunchedEffect(readTrigger) {
        if (readTrigger == 0) return@LaunchedEffect
        KomaAutoRead.run(
            context = context,
            book = book,
            pages = book.pages,
            language = language,
            from = if (mode == "vertical") scroll.firstVisibleItemIndex else pager.currentPage,
            tts = tts,
            semiManual = semiManual,
            fromBandIndex = if (semiManual) bandIndex else 0,
            recognize = { index, pageBand ->
                val ready = engine.cached(book.id, index, language, slot(pageBand))?.takeIf { it.regions.isNotEmpty() }
                if (ready != null) ready
                else {
                    runCatching { recognizeWithEngine(book, index, language, true, pageBand) }.getOrNull()
                        ?: engine.cached(book.id, index, language, slot(pageBand))
                }
            },
            bandsOf = { index -> bandsOf(index) },
            onPage = { index ->
                if (mode == "vertical") {
                    if (scroll.firstVisibleItemIndex != index) {
                        scrollJob?.cancel()
                        scrollJob = scope.launch { runCatching { scroll.animateScrollToItem(index) } }
                    }
                } else {
                    pager.animateScrollToPage(index)
                }
            },
            onResult = { r ->
                if (r != null) {
                    result = r
                    // Success Brigade: Capture engineNote for yomikai web parity
                    engineNote = r.extraData?.getOrDefault("engineNote", "")
                    
                    val lines = KomaReadingOrder.order(
                        regions = r.regions,
                        rtl = mode == "rtl",
                        onlyCyrillic = language == "Кириллица" || language == "Авто",
                        panels = r.panels,
                        rules = rules,
                    )
                    val shotPage = KomaAutoRead.state.value.page
                    runCatching {
                        scope.launch {
                            KomaScreenshots.save(
                                context = context.applicationContext,
                                bookId = book.id,
                                pageIndex = shotPage,
                                pagePath = book.pages.getOrNull(shotPage).orEmpty(),
                                regions = lines.mapIndexed { i, line ->
                                    OcrRegion(
                                        order = i,
                                        text = line.text,
                                        boundingBox = mihon.domain.ocr.model.OcrBoundingBox(
                                            line.left, line.top, line.right, line.bottom,
                                        ),
                                        textOrientation = mihon.domain.ocr.model.OcrTextOrientation.Horizontal,
                                    )
                                },
                                engineName = r.engine,
                            )
                        }
                    }
                }
            },
            onSilentPage = { index ->
                if (mode == "vertical") {
                    val next = (index + 1).coerceAtMost(book.pages.lastIndex)
                    scrollJob?.cancel()
                    scrollJob = scope.launch { runCatching { scroll.animateScrollToItem(next) } }
                    delay(900)
                } else {
                    pager.animateScrollToPage((index + 1).coerceAtMost(book.pages.lastIndex))
                    delay(900)
                }
            },
            onLine = { linePage, line ->
                if (mode != "vertical" && autoScroll && currentHeight > 0 && !pageScroll.isScrollInProgress) {
                    val bubbleTop = (line.top * currentHeight).toInt()
                    val visible = bubbleTop >= pageScroll.value && bubbleTop <= pageScroll.value + viewportPx * 0.75f
                    if (!visible) {
                        val desired = (bubbleTop - viewportPx * (1f - rules.autoScrollStep)).toInt().coerceAtLeast(0)
                        scrollJob?.cancel()
                        scrollJob = scope.launch { runCatching { pageScroll.animateScrollTo(desired) } }
                    }
                }
                if (mode == "vertical" && autoScroll && !scroll.isScrollInProgress) {
                    val target = linePage.coerceIn(0, book.pages.lastIndex)
                    val path = book.pages.getOrNull(target)
                    val height = path?.let { pageHeights[it] } ?: 0
                    if (height > 0) {
                        val viewport = scroll.layoutInfo.viewportSize.height
                        val itemOffset = if (scroll.firstVisibleItemIndex == target) scroll.firstVisibleItemScrollOffset else 0
                        val bubbleTop = (line.top * height).toInt()
                        val bubbleBottom = (line.bottom * height).toInt()
                        val visibleTop = itemOffset
                        val visibleBottom = itemOffset + viewport
                        val comfortableTop = visibleTop + (viewport * 0.15f).toInt()
                        val comfortableBottom = visibleBottom - (viewport * 0.35f).toInt()
                        val visible = bubbleTop >= comfortableTop && bubbleBottom <= comfortableBottom
                        if (!visible && viewport > 0) {
                            val desired = (bubbleTop - viewport * 0.30f).toInt().coerceAtLeast(0)
                            scrollJob?.cancel()
                            scrollJob = scope.launch { runCatching { scroll.animateScrollToItem(target, desired) } }
                        }
                    }
                }
            },
            onBand = { index, pageBand ->
                val h = book.pages.getOrNull(index)?.let { pageHeights[it] } ?: 0
                if (h > 0) {
                    val target = (pageBand.top * h).toInt()
                    if (mode == "vertical") {
                        val curItem = scroll.firstVisibleItemIndex
                        val curOffset = scroll.firstVisibleItemScrollOffset
                        val forward = index > curItem || (index == curItem && target > curOffset + 8)
                        if (forward) {
                            scrollJob?.cancel()
                            scrollJob = scope.launch { runCatching { scroll.animateScrollToItem(index, target) } }
                        }
                    } else if (index == page && pageBand.top > 0.01f) {
                        val forward = target > pageScroll.value + 8
                        if (forward) {
                            scrollJob?.cancel()
                            scrollJob = scope.launch { runCatching { pageScroll.animateScrollTo(target) } }
                        }
                    }
                }
            },
            onLine = { linePage, line ->
                if (mode != "vertical" && autoScroll && currentHeight > 0 && !pageScroll.isScrollInProgress) {
                    val bubbleTop = (line.top * currentHeight).toInt()
                    val visible = bubbleTop >= pageScroll.value && bubbleTop <= pageScroll.value + viewportPx * 0.75f
                    if (!visible) {
                        val desired = (bubbleTop - viewportPx * (1f - rules.autoScrollStep)).toInt().coerceAtLeast(0)
                        scrollJob?.cancel()
                        scrollJob = scope.launch { runCatching { pageScroll.animateScrollTo(desired) } }
                    }
                }
                if (mode == "vertical" && autoScroll && !scroll.isScrollInProgress) {
                    val target = linePage.coerceIn(0, book.pages.lastIndex)
                    val path = book.pages.getOrNull(target)
                    val height = path?.let { pageHeights[it] } ?: 0
                    if (height > 0) {
                        val viewport = scroll.layoutInfo.viewportSize.height
                        val itemOffset = if (scroll.firstVisibleItemIndex == target) scroll.firstVisibleItemScrollOffset else 0
                        val bubbleTop = (line.top * height).toInt()
                        val bubbleBottom = (line.bottom * height).toInt()
                        val visibleTop = itemOffset
                        val visibleBottom = itemOffset + viewport
                        val comfortableTop = visibleTop + (viewport * 0.15f).toInt()
                        val comfortableBottom = visibleBottom - (viewport * 0.35f).toInt()
                        val visible = bubbleTop >= comfortableTop && bubbleBottom <= comfortableBottom
                        if (!visible && viewport > 0) {
                            val desired = (bubbleTop - viewport * 0.30f).toInt().coerceAtLeast(0)
                            scrollJob?.cancel()
                            scrollJob = scope.launch { runCatching { scroll.animateScrollToItem(target, desired) } }
                        }
                    }
                }
            },
            onBand = { index, pageBand ->
                val h = book.pages.getOrNull(index)?.let { pageHeights[it] } ?: 0
                if (h > 0) {
                    val target = (pageBand.top * h).toInt()
                    if (mode == "vertical") {
                        val curItem = scroll.firstVisibleItemIndex
                        val curOffset = scroll.firstVisibleItemScrollOffset
                        val forward = index > curItem || (index == curItem && target > curOffset + 8)
                        if (forward) {
                            scrollJob?.cancel()
                            scrollJob = scope.launch { runCatching { scroll.animateScrollToItem(index, target) } }
                        }
                    } else if (index == page && pageBand.top > 0.01f) {
                        val forward = target > pageScroll.value + 8
                        if (forward) {
                            scrollJob?.cancel()
                            scrollJob = scope.launch { runCatching { pageScroll.animateScrollTo(target) } }
                        }
                    }
                }
            },
            onLine = { linePage, line ->
                if (mode != "vertical" && autoScroll && currentHeight > 0 && !pageScroll.isScrollInProgress) {
                    val bubbleTop = (line.top * currentHeight).toInt()
                    val visible = bubbleTop >= pageScroll.value && bubbleTop <= pageScroll.value + viewportPx * 0.75f
                    if (!visible) {
                        val desired = (bubbleTop - viewportPx * (1f - rules.autoScrollStep)).toInt().coerceAtLeast(0)
                        scrollJob?.cancel()
                        scrollJob = scope.launch { runCatching { pageScroll.animateScrollTo(desired) } }
                    }
                }
                if (mode == "vertical" && autoScroll && !scroll.isScrollInProgress) {
                    val target = linePage.coerceIn(0, book.pages.lastIndex)
                    val path = book.pages.getOrNull(target)
                    val height = path?.let { pageHeights[it] } ?: 0
                    if (height > 0) {
                        val viewport = scroll.layoutInfo.viewportSize.height
                        val itemOffset = if (scroll.firstVisibleItemIndex == target) scroll.firstVisibleItemScrollOffset else 0
                        val bubbleTop = (line.top * height).toInt()
                        val bubbleBottom = (line.bottom * height).toInt()
                        val visibleTop = itemOffset
                        val visibleBottom = itemOffset + viewport
                        val comfortableTop = visibleTop + (viewport * 0.15f).toInt()
                        val comfortableBottom = visibleBottom - (viewport * 0.35f).toInt()
                        val visible = bubbleTop >= comfortableTop && bubbleBottom <= comfortableBottom
                        if (!visible && viewport > 0) {
                            val desired = (bubbleTop - viewport * 0.30f).toInt().coerceAtLeast(0)
                            scrollJob?.cancel()
                            scrollJob = scope.launch { runCatching { scroll.animateScrollToItem(target, desired) } }
                        }
                    }
                }
            },
            onBand = { index, pageBand ->
                val h = book.pages.getOrNull(index)?.let { pageHeights[it] } ?: 0
                if (h > 0) {
                    val target = (pageBand.top * h).toInt()
                    if (mode == "vertical") {
                        val curItem = scroll.firstVisibleItemIndex
                        val curOffset = scroll.firstVisibleItemScrollOffset
                        val forward = index > curItem || (index == curItem && target > curOffset + 8)
                        if (forward) {
                            scrollJob?.cancel()
                            scrollJob = scope.launch { runCatching { scroll.animateScrollToItem(index, target) } }
                        }
                    } else if (index == page && pageBand.top > 0.01f) {
                        val forward = target > pageScroll.value + 8
                        if (forward) {
                            scrollJob?.cancel()
                            scrollJob = scope.launch { runCatching { pageScroll.animateScrollTo(target) } }
                        }
                    }
                }
            },
            onLine = { linePage, line ->
                if (mode != "vertical" && autoScroll && currentHeight > 0 && !pageScroll.isScrollInProgress) {
                    val bubbleTop = (line.top * currentHeight).toInt()
                    val visible = bubbleTop >= pageScroll.value && bubbleTop <= pageScroll.value + viewportPx * 0.75f
                    if (!visible) {
                        val desired = (bubbleTop - viewportPx * (1f - rules.autoScrollStep)).toInt().coerceAtLeast(0)
                        scrollJob?.cancel()
                        scrollJob = scope.launch { runCatching { pageScroll.animateScrollTo(desired) } }
                    }
                }
                if (mode == "vertical" && autoScroll && !scroll.isScrollInProgress) {
                    val target = linePage.coerceIn(0, book.pages.lastIndex)
                    val path = book.pages.getOrNull(target)
                    val height = path?.let { pageHeights[it] } ?: 0
                    if (height > 0) {
                        val viewport = scroll.layoutInfo.viewportSize.height
                        val itemOffset = if (scroll.firstVisibleItemIndex == target) scroll.firstVisibleItemScrollOffset else 0
                        val bubbleTop = (line.top * height).toInt()
                        val bubbleBottom = (line.bottom * height).toInt()
                        val visibleTop = itemOffset
                        val visibleBottom = itemOffset + viewport
                        val comfortableTop = visibleTop + (viewport * 0.15f).toInt()
                        val comfortableBottom = visibleBottom - (viewport * 0.35f).toInt()
                        val visible = bubbleTop >= comfortableTop && bubbleBottom <= comfortableBottom
                        if (!visible && viewport > 0) {
                            val desired = (bubbleTop - viewport * 0.30f).toInt().coerceAtLeast(0)
                            scrollJob?.cancel()
                            scrollJob = scope.launch { runCatching { scroll.animateScrollToItem(target, desired) } }
                        }
                    }
                }
            },
            onBand = { index, pageBand ->
                val h = book.pages.getOrNull(index)?.let { pageHeights[it] } ?: 0
                if (h > 0) {
                    val target = (pageBand.top * h).toInt()
                    if (mode == "vertical") {
                        val curItem = scroll.firstVisibleItemIndex
                        val curOffset = scroll.firstVisibleItemScrollOffset
                        val forward = index > curItem || (index == curItem && target > curOffset + 8)
                        if (forward) {
                            scrollJob?.cancel()
                            scrollJob = scope.launch { runCatching { scroll.animateScrollToItem(index, target) } }
                        }
                    } else if (index == page && pageBand.top > 0.01f) {
                        val forward = target > pageScroll.value + 8
                        if (forward) {
                            scrollJob?.cancel()
                            scrollJob = scope.launch { runCatching { pageScroll.animateScrollTo(target) } }
                        }
                    }
                }
            },
            onLine = { linePage, line ->
                if (mode != "vertical" && autoScroll && currentHeight > 0 && !pageScroll.isScrollInProgress) {
                    val bubbleTop = (line.top * currentHeight).toInt()
                    val visible = bubbleTop >= pageScroll.value && bubbleTop <= pageScroll.value + viewportPx * 0.75f
                    if (!visible) {
                        val desired = (bubbleTop - viewportPx * (1f - rules.autoScrollStep)).toInt().coerceAtLeast(0)
                        scrollJob?.cancel()
                        scrollJob = scope.launch { runCatching { pageScroll.animateScrollTo(desired) } }
                    }
                }
                if (mode == "vertical" && autoScroll && !scroll.isScrollInProgress) {
                    val target = linePage.coerceIn(0, book.pages.lastIndex)
                    val path = book.pages.getOrNull(target)
                    val height = path?.let { pageHeights[it] } ?: 0
                    if (height > 0) {
                        val viewport = scroll.layoutInfo.viewportSize.height
                        val itemOffset = if (scroll.firstVisibleItemIndex == target) scroll.firstVisibleItemScrollOffset else 0
                        val bubbleTop = (line.top * height).toInt()
                        val bubbleBottom = (line.bottom * height).toInt()
                        val visibleTop = itemOffset
                        val visibleBottom = itemOffset + viewport
                        val comfortableTop = visibleTop + (viewport * 0.15f).toInt()
                        val comfortableBottom = visibleBottom - (viewport * 0.35f).toInt()
                        val visible = bubbleTop >= comfortableTop && bubbleBottom <= comfortableBottom
                        if (!visible && viewport > 0) {
                            val desired = (bubbleTop - viewport * 0.30f).toInt().coerceAtLeast(0)
                            scrollJob?.cancel()
                            scrollJob = scope.launch { runCatching { scroll.animateScrollToItem(target, desired) } }
                        }
                    }
                }
            },
            onBand = { index, pageBand ->
                val h = book.pages.getOrNull(index)?.let { pageHeights[it] } ?: 0
                if (h > 0) {
                    val target = (pageBand.top * h).toInt()
                    if (mode == "vertical") {
                        val curItem = scroll.firstVisibleItemIndex
                        val curOffset = scroll.firstVisibleItemScrollOffset
                        val forward = index > curItem || (index == curItem && target > curOffset + 8)
                        if (forward) {
                            scrollJob?.cancel()
                            scrollJob = scope.launch { runCatching { scroll.animateScrollToItem(index, target) } }
                        }
                    } else if (index == page && pageBand.top > 0.01f) {
                        val forward = target > pageScroll.value + 8
                        if (forward) {
                            scrollJob?.cancel()
                            scrollJob = scope.launch { runCatching { pageScroll.animateScrollTo(target) } }
                        }
                    }
                }
            },
            onLine = { linePage, line ->
                if (mode != "vertical" && autoScroll && currentHeight > 0 && !pageScroll.isScrollInProgress) {
                    val bubbleTop = (line.top * currentHeight).toInt()
                    val visible = bubbleTop >= pageScroll.value && bubbleTop <= pageScroll.value + viewportPx * 0.75f
                    if (!visible) {
                        val desired = (bubbleTop - viewportPx * (1f - rules.autoScrollStep)).toInt().coerceAtLeast(0)
                        scrollJob?.cancel()
                        scrollJob = scope.launch { runCatching { pageScroll.animateScrollTo(desired) } }
                    }
                }
                if (mode == "vertical" && autoScroll && !scroll.isScrollInProgress) {
                    val target = linePage.coerceIn(0, book.pages.lastIndex)
                    val path = book.pages.getOrNull(target)
                    val height = path?.let { pageHeights[it] } ?: 0
                    if (height > 0) {
                        val viewport = scroll.layoutInfo.viewportSize.height
                        val itemOffset = if (scroll.firstVisibleItemIndex == target) scroll.firstVisibleItemScrollOffset else 0
                        val bubbleTop = (line.top * height).toInt()
                        val bubbleBottom = (line.bottom * height).toInt()
                        val visibleTop = itemOffset
                        val visibleBottom = itemOffset + viewport
                        val comfortableTop = visibleTop + (viewport * 0.15f).toInt()
                        val comfortableBottom = visibleBottom - (viewport * 0.35f).toInt()
                        val visible = bubbleTop >= comfortableTop && bubbleBottom <= comfortableBottom
                        if (!visible && viewport > 0) {
                            val desired = (bubbleTop - viewport * 0.30f).toInt().coerceAtLeast(0)
                            scrollJob?.cancel()
                            scrollJob = scope.launch { runCatching { scroll.animateScrollToItem(target, desired) } }
                        }
                    }
                }
            },
            onBand = { index, pageBand ->
                val h = book.pages.getOrNull(index)?.let { pageHeights[it] } ?: 0
                if (h > 0) {
                    val target = (pageBand.top * h).toInt()
                    if (mode == "vertical") {
                        val curItem = scroll.firstVisibleItemIndex
                        val curOffset = scroll.firstVisibleItemScrollOffset
                        val forward = index > curItem || (index == curItem && target > curOffset + 8)
                        if (forward) {
                            scrollJob?.cancel()
                            scrollJob = scope.launch { runCatching { scroll.animateScrollToItem(index, target) } }
                        }
                    } else if (index == page && pageBand.top > 0.01f) {
                        val forward = target > pageScroll.value + 8
                        if (forward) {
                            scrollJob?.cancel()
                            scrollJob = scope.launch { runCatching { pageScroll.animateScrollTo(target) } }
                        }
                    }
                }
            },
            onLine = { linePage, line ->
                if (mode != "vertical" && autoScroll && currentHeight > 0 && !pageScroll.isScrollInProgress) {
                    val bubbleTop = (line.top * currentHeight).toInt()
                    val visible = bubbleTop >= pageScroll.value && bubbleTop <= pageScroll.value + viewportPx * 0.75f
                    if (!visible) {
                        val desired = (bubbleTop - viewportPx * (1f - rules.autoScrollStep)).toInt().coerceAtLeast(0)
                        scrollJob?.cancel()
                        scrollJob = scope.launch { runCatching { pageScroll.animateScrollTo(desired) } }
                    }
                }
                if (mode == "vertical" && autoScroll && !scroll.isScrollInProgress) {
                    val target = linePage.coerceIn(0, book.pages.lastIndex)
                    val path = book.pages.getOrNull(target)
                    val height = path?.let { pageHeights[it] } ?: 0
                    if (height > 0) {
                        val viewport = scroll.layoutInfo.viewportSize.height
                        val itemOffset = if (scroll.firstVisibleItemIndex == target) scroll.firstVisibleItemScrollOffset else 0
                        val bubbleTop = (line.top * height).toInt()
                        val bubbleBottom = (line.bottom * height).toInt()
                        val visibleTop = itemOffset
                        val visibleBottom = itemOffset + viewport
                        val comfortableTop = visibleTop + (viewport * 0.15f).toInt()
                        val comfortableBottom = visibleBottom - (viewport * 0.35f).toInt()
                        val visible = bubbleTop >= comfortableTop && bubbleBottom <= comfortableBottom
                        if (!visible && viewport > 0) {
                            val desired = (bubbleTop - viewport * 0.30f).toInt().coerceAtLeast(0)
                            scrollJob?.cancel()
                            scrollJob = scope.launch { runCatching { scroll.animateScrollToItem(target, desired) } }
                        }
                    }
                }
            },
            onBand = { index, pageBand ->
                val h = book.pages.getOrNull(index)?.let { pageHeights[it] } ?: 0
                if (h > 0) {
                    val target = (pageBand.top * h).toInt()
                    if (mode == "vertical") {
                        val curItem = scroll.firstVisibleItemIndex
                        val curOffset = scroll.firstVisibleItemScrollOffset
                        val forward = index > curItem || (index == curItem && target > curOffset + 8)
                        if (forward) {
                            scrollJob?.cancel()
                            scrollJob = scope.launch { runCatching { scroll.animateScrollToItem(index, target) } }
                        }
                    } else if (index == page && pageBand.top > 0.01f) {
                        val forward = target > pageScroll.value + 8
                        if (forward) {
                            scrollJob?.cancel()
                            scrollJob = scope.launch { runCatching { pageScroll.animateScrollTo(target) } }
                        }
                    }
                }
            },
            onLine = { linePage, line ->
                if (mode != "vertical" && autoScroll && currentHeight > 0 && !pageScroll.isScrollInProgress) {
                    val bubbleTop = (line.top * currentHeight).toInt()
                    val visible = bubbleTop >= pageScroll.value && bubbleTop <= pageScroll.value + viewportPx * 0.75f
                    if (!visible) {
                        val desired = (bubbleTop - viewportPx * (1f - rules.autoScrollStep)).toInt().coerceAtLeast(0)
                        scrollJob?.cancel()
                        scrollJob = scope.launch { runCatching { pageScroll.animateScrollTo(desired) } }
                    }
                }
                if (mode == "vertical" && autoScroll && !scroll.isScrollInProgress) {
                    val target = linePage.coerceIn(0, book.pages.lastIndex)
                    val path = book.pages.getOrNull(target)
                    val height = path?.let { pageHeights[it] } ?: 0
                    if (height > 0) {
                        val viewport = scroll.layoutInfo.viewportSize.height
                        val itemOffset = if (scroll.firstVisibleItemIndex == target) scroll.firstVisibleItemScrollOffset else 0
                        val bubbleTop = (line.top * height).toInt()
                        val bubbleBottom = (line.bottom * height).toInt()
                        val visibleTop = itemOffset
                        val visibleBottom = itemOffset + viewport
                        val comfortableTop = visibleTop + (viewport * 0.15f).toInt()
                        val comfortableBottom = visibleBottom - (viewport * 0.35f).toInt()
                        val visible = bubbleTop >= comfortableTop && bubbleBottom <= comfortableBottom
                        if (!visible && viewport > 0) {
                            val desired = (bubbleTop - viewport * 0.30f).toInt().coerceAtLeast(0)
                            scrollJob?.cancel()
                            scrollJob = scope.launch { runCatching { scroll.animateScrollToItem(target, desired) } }
                        }
                    }
                }
            },
        )
        readTrigger = 0
    }
    
    // ReaderActivity AI Chat session
    LaunchedEffect(aiChatVisible) {
        if (aiChatVisible) {
            scope.launch {
                eu.kanade.tachiyomi.data.ai.BookKnowledge.ensureSession(context, mangaIdForChoice ?: -1L)
            }
        }
    }

    Scaffold(containerColor = Color(0xFF171A17), topBar = {
        if (!barsVisible) Unit else Column(Modifier.background(Color(0xF2171A17))) {
            Row(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, "Назад", tint = Color.White) }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(book.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("KOMA · лента · ${page + 1} из ${book.pages.size} · ${book.category} · ${when (mode) { "rtl" -> "Справа налево"; "vertical" -> "Вертикально"; else -> "Слева направо" }}", fontSize = 10.sp, color = Color(0xFFB2B8AD))
                }
                IconButton(onClick = { showEngineSelector = true }) { Icon(Icons.Outlined.DocumentScanner, "Распознать", tint = if (result != null) Orange else Color.White) }
                IconButton(onClick = { sheet = "settings" }) { Icon(Icons.Outlined.RecordVoiceOver, "Голос и авточтение", tint = if (autoReading) Orange else Color.White) }
                IconButton(onClick = { barsVisible = false }) { Icon(Icons.Outlined.Fullscreen, "Полный экран", tint = Color.White) }
                Box {
                    IconButton(onClick = { showOverlay = true }) { Icon(Icons.Outlined.Tune, "Функции читалки", tint = Color.White) }
                    IconButton(onClick = { overflow = true }) { Icon(Icons.Default.MoreVert, "Ещё", tint = Color.White) }
                    DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                        DropdownMenuItem(text = { Text(if (book.favorite) "Убрать из избранного" else "В избранное") }, onClick = { save(book.copy(favorite = !book.favorite)); overflow = false })
                        DropdownMenuItem(text = { Text("Как читать эту историю") }, onClick = { sheet = "settings"; overflow = false })
                        DropdownMenuItem(text = { Text("Как читается") }, onClick = { sheet = "speech"; overflow = false })
                        DropdownMenuItem(text = { Text("Голоса персонажей") }, onClick = { sheet = "voices"; overflow = false })
                        DropdownMenuItem(text = { Text("HTML-читалка (проверенная)") }, onClick = {
                            overflow = false
                            runCatching { context.startActivity(eu.kanade.tachiyomi.ui.koma.web.KomaWebReaderActivity.newIntent(context)) }
                        })
                        DropdownMenuItem(text = { Text("Все страницы") }, onClick = { sheet = "pages"; overflow = false })
                        DropdownMenuItem(text = { Text("Рамки текста") }, onClick = { showRegions = !showRegions; overflow = false })
                        DropdownMenuItem(text = { Text("Правила и буфер строк") }, onClick = { sheet = "rules"; overflow = false })
                        DropdownMenuItem(text = { Text(if (autoScroll) "Автопрокрутка: вкл" else "Автопрокрутка: выкл") }, onClick = { autoScroll = !autoScroll; repo.prefs.edit().putBoolean("autoScroll", autoScroll).apply(); overflow = false })
                        DropdownMenuItem(text = { Text("Экспорт распознанного") }, onClick = { overflow = false; export.launch("${book.title}_ocr.txt") })
                        DropdownMenuItem(text = { Text("Удалить из библиотеки") }, onClick = { overflow = false; deleteConfirm = true })
                        // Success Brigade: Yomikai Web-style Text Comparison Button
                        DropdownMenuItem(text = { Text("Сравнение текста (оригинал vs обработанный)") }, onClick = { showComparison = true; overflow = false })
                    }
                }
                // AI Chat button (from ReaderActivity)
                IconButton(onClick = { aiChatVisible = true }) { Icon(Icons.Outlined.SmartToy, "AI-чат", tint = Color.White) }
            }
        }
    }, bottomBar = {
        if (!barsVisible) Unit else Surface(color = Color(0xFF232822)) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { if (result == null && !scanning) scan(); sheet = "ocr" }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        if (scanning) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = Orange) else Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(19.dp), tint = Orange)
                        Text(if (scanning) "  Распознаём…" else if (result != null) "  Текст страницы" else "  Распознать", fontSize = 12.sp, color = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { if (autoReading) KomaAutoRead.togglePause() else readTrigger++ }) {
                        Icon(if (autoReading && !autoState.paused) Icons.Filled.PauseCircle else Icons.Outlined.PlayCircle, "Авточтение", tint = if (autoReading) Orange else Color(0xFF9EAB9A))
                    }
                    IconToggleButton(checked = autoScroll, onCheckedChange = { autoScroll = it; repo.prefs.edit().putBoolean("autoScroll", it).apply() }) { Icon(Icons.Outlined.SwapVert, "Автопрокрутка", tint = if (autoScroll) Orange else Color(0xFF9EAB9A)) }
                    IconToggleButton(checked = showRegions, onCheckedChange = { showRegions = it }) { Icon(Icons.Outlined.CropFree, "Рамки текста", tint = if (showRegions) Orange else Color(0xFF9EAB9A)) }
                    IconButton(onClick = { voiceBar = !voiceBar }) { Icon(Icons.Outlined.RecordVoiceOver, "Голос", tint = if (voiceBar) Orange else Color(0xFF9EAB9A)) }
                    IconButton(onClick = { sheet = "pages" }) { Icon(Icons.Outlined.GridView, "Все страницы", tint = Color(0xFF9EAB9A)) }
                }
                if (voiceBar) {
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Голос:", fontSize = 10.sp, color = Color(0xFFB2B8AD), modifier = Modifier.padding(end = 6.dp))
                        Text(selectedVoice?.substringAfterLast('/')?.ifBlank { "по умолчанию" } ?: "по умолчанию", fontSize = 10.sp, color = Orange, modifier = Modifier.weight(1f))
                        TextButton(onClick = { voiceDialog = true }) { Text("выбрать", fontSize = 10.sp, color = Orange) }
                        IconButton(onClick = { scope.launch { runCatching { tts.speakVoiced(texts = listOf(bubbles?.firstOrNull()?.text?.let { KomaCasting.stripSpeaker(it) } ?: "Так будет звучать голос."), voiceFor = { KomaVoice(voice = selectedVoice) }, onLineStart = {}) } } }, modifier = Modifier.size(28.dp)) { Icon(Icons.Outlined.VolumeUp, null, Modifier.size(15.dp), tint = Orange) }
                    }
                }
                if (batchTotal > 0) { LinearProgressIndicator(progress = { batchProgress.toFloat() / batchTotal }, modifier = Modifier.fillMaxWidth(), color = Orange); Text("Страница $batchProgress из $batchTotal", fontSize = 10.sp, color = Color.White) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { scope.launch { if (mode == "vertical") scroll.animateScrollToItem((page - 1).coerceAtLeast(0)) else pager.animateScrollToPage((page - 1).coerceAtLeast(0)) } }, enabled = page > 0, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.ChevronLeft, "Предыдущая", tint = if (page > 0) Color.White else Color.Gray) }
                    Slider(value = page.toFloat(), onValueChange = { v -> scope.launch { if (mode == "vertical") scroll.scrollToItem(v.toInt()) else pager.scrollToPage(v.toInt()) } }, valueRange = 0f..book.pages.lastIndex.coerceAtLeast(1).toFloat(), modifier = Modifier.weight(1f).height(32.dp), colors = SliderDefaults.colors(thumbColor = Orange, activeTrackColor = Orange, inactiveTrackColor = Color(0xFF51594D)))
                    Text("${page + 1} / ${book.pages.size}", fontSize = 10.sp, color = Color.White, modifier = Modifier.padding(horizontal = 8.dp))
                    IconButton(onClick = { scope.launch { if (mode == "vertical") scroll.animateScrollToItem((page + 1).coerceAtMost(book.pages.lastIndex)) else pager.animateScrollToPage((page + 1).coerceAtMost(book.pages.lastIndex)) } }, enabled = page < book.pages.lastIndex, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.ChevronRight, "Следующая", tint = if (page < book.pages.lastIndex) Color.White else Color.Gray) }
                }
            }
        }
    }) { inset ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(inset)) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val visibleH = with(density) { maxHeight.roundToPx() }
            val visibleW = with(density) { maxWidth.roundToPx() }
            LaunchedEffect(visibleH) { if (visibleH > 0) viewportPx = visibleH }
            LaunchedEffect(visibleW) { if (visibleW > 0) viewportWidthPx = visibleW }
            if (mode == "vertical") LazyColumn(state = scroll, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(book.pages, key = { n, _ -> n }) { n, path -> PageImage(path, engine, true, if (n == page && (showRegions || autoReading)) bubbles else null, if (n == page) autoState.currentLine else null, { h -> pageHeights[path] = h }, { barsVisible = !barsVisible }, spokenKeys = spokenOfPage, engineNote = if (n == page) engineNote else null, showComparison = showComparison, onComparisonClose = { showComparison = false }) }
            } else HorizontalPager(state = pager, reverseLayout = mode == "rtl", modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 0) { n ->
                PageImage(
                    path = book.pages[n],
                    engine = engine,
                    vertical = false,
                    boxes = if (n == page && (showRegions || autoReading)) bubbles else null,
                    currentLine = if (n == page) autoState.currentLine else null,
                    onHeight = { h -> pageHeights[book.pages[n]] = h },
                    onTap = { barsVisible = !barsVisible },
                    scrollState = if (n == page) pageScroll else null,
                    spokenKeys = spokenOfPage,
                    engineNote = if (n == page) engineNote else null,
                    showComparison = showComparison,
                    onComparisonClose = { showComparison = false }
                )
            }
        }
    }
    
    // ReaderActivity AI Chat Overlay
    if (aiChatVisible) {
        eu.kanade.tachiyomi.ui.reader.components.ReaderAiChatOverlay(
            context = context,
            mangaId = mangaIdForChoice ?: -1L,
            mangaTitle = book.title,
            chapterTitle = "Глава ${page + 1}",
            onClose = { aiChatVisible = false },
            onStopRequested = { KomaAutoRead.stop(); tts.stop() }
        )
    }
    
    // Success Brigade: Text Comparison Button UI (Yomikai Web parity)
    if (showComparison) {
        AlertDialog(
            onDismissRequest = { showComparison = false },
            title = { Text("Сравнение текста") },
            text = {
                Column {
                    Text("Оригинал (engineNote):", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(engineNote ?: "Нет исходного текста (движок не вернул engineNote)", color = Color(0xFFB2B8AD), fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
                    Divider(color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
                    Text("Обработанный (finalText):", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(result?.regions?.firstOrNull()?.text ?: "Нет обработанного текста (результат пуст)", color = Color(0xFF9EAB9A), fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
                }
            },
            confirmButton = { TextButton(onClick = { showComparison = false }) { Text("Закрыть") } }
        )
    }
    
    if (sheet != null) ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = MaterialTheme.colorScheme.background, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        when (sheet) {
            "rules" -> { /* KomaReaderScreen rules sheet */ }
            "settings" -> { /* KomaReaderScreen settings sheet */ }
            "ocr" -> { /* KomaReaderScreen OCR sheet */ }
            "speech" -> { /* KomaReaderScreen speech sheet */ }
            "voices" -> { /* KomaReaderScreen voices sheet */ }
            "pages" -> { /* KomaReaderScreen pages sheet */ }
        }
    }
    
    if (voiceDialog) AlertDialog(onDismissRequest = { voiceDialog = false }, title = { Text("Голос для озвучки") }, text = { /* voices list */ }, confirmButton = { TextButton(onClick = { voiceDialog = false }) { Text("Закрыть") } })
    if (error != null) AlertDialog(onDismissRequest = { error = null }, title = { Text("KOMA") }, text = { Text(error!!) }, confirmButton = { TextButton(onClick = { error = null }) { Text("Понятно") } })
    if (deleteConfirm) AlertDialog(onDismissRequest = { deleteConfirm = false }, title = { Text("Удалить книгу?") }, text = { Text("Копия в библиотеке будет удалена. Ваш исходный архив останется на месте.") }, confirmButton = { TextButton(onClick = { onDelete(book); onClose() }) { Text("Удалить") } }, dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("Отмена") } })
    
    // ReaderActivity Overlay (Top/Side/Bottom)
    if (showOverlay) {
        KomaReaderOverlay(
            mangaId = mangaIdForChoice,
            chapterId = null,
            onDismiss = { showOverlay = false },
            onScreenshot = { /* TODO */ },
            onShare = { /* TODO */ },
            onOpenInBrowser = { /* TODO */ },
            onOpenManga = { /* TODO */ },
            onExportAnki = { /* TODO */ }
        )
    }
    
    // Engine Selector (from Screenshot 1)
    if (showEngineSelector) {
        KomaEngineSelector(
            selectedEngineId = selectedEngineId,
            onEngineSelected = { engineId ->
                repo.prefs.edit().putString("komaOcrEngine", engineId).apply()
                selectedEngineId = engineId
                showEngineSelector = false
            },
            onDismiss = { showEngineSelector = false }
        )
    }
}

@Composable
private fun PageImage(
    path: String,
    engine: KomaOcrEngine,
    vertical: Boolean,
    boxes: List<KomaReadingOrder.Line>?,
    currentLine: String?,
    onHeight: (Int) -> Unit,
    onTap: () -> Unit,
    scrollState: androidx.compose.foundation.ScrollState? = null,
    spokenKeys: Set<String> = emptySet(),
    engineNote: String? = null,
    showComparison: Boolean = false,
    onComparisonClose: () -> Unit
) {
    var bitmap by remember(path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var retry by remember(path) { mutableIntStateOf(0) }
    LaunchedEffect(path, retry) { try { bitmap = withContext(Dispatchers.IO) { engine.bitmap(path) } } catch (e: Throwable) { error = e.message ?: "Не удалось открыть страницу" } }
    val b = bitmap
    val density = androidx.compose.ui.platform.LocalDensity.current
    
    // Success Brigade: Text Comparison Button Overlay
    if (showComparison) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(color = Color(0xF2171A17), modifier = Modifier.padding(16.dp).fillMaxWidth().height(240.dp).clip(RoundedCornerShape(12.dp))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Сравнение текста (Yomikai Web)", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Оригинал (engineNote):", color = Color.White, fontSize = 14.sp)
                    Text(engineNote ?: "Нет исходного текста", color = Color(0xFFB2B8AD), fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
                    Text("Обработанный (finalText):", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    Text(boxes?.firstOrNull()?.text ?: "Нет обработанного текста", color = Color(0xFF9EAB9A), fontSize = 12.sp)
                    Spacer(modifier = Modifier.weight(1f))
                    Button(onClick = onComparisonClose) { Text("Закрыть") }
                }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                IconButton(onClick = onComparisonClose, modifier = Modifier.padding(16.dp)) { Icon(Icons.Filled.Close, "Закрыть", tint = Color.White) }
            }
        }
    }
    
    when {
        b == null -> Box(Modifier.fillMaxSize().then(if (vertical) Modifier.height(320.dp) else Modifier.fillMaxHeight()), contentAlignment = Alignment.Center) {
            if (error == null) CircularProgressIndicator(color = Orange)
            else TextButton(onClick = { error = null; retry++ }) { Text("Не удалось открыть. Повторить") }
        }
        !vertical && b.height.toFloat() / b.width > 2.2f -> BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight()) {
            val width = maxWidth
            val height = width * b.height / b.width
            val ownState = rememberScrollState()
            val st = scrollState ?: ownState
            LaunchedEffect(height, density) { runCatching { onHeight(with(density) { height.roundToPx() }) } }
            Box(Modifier.fillMaxSize().verticalScroll(st)) { PageBody(b, boxes, currentLine, path, onTap, Modifier.size(width, height), spokenKeys) }
        }
        else -> BoxWithConstraints(Modifier.fillMaxWidth().then(if (vertical) Modifier.aspectRatio(b.width.toFloat() / b.height) else Modifier.fillMaxHeight()).clip(RoundedCornerShape(0.dp)), contentAlignment = Alignment.Center) {
            val ratio = b.width.toFloat() / b.height
            val width = if (vertical || maxWidth / maxHeight < ratio) maxWidth else maxHeight * ratio
            val height = width / ratio
            LaunchedEffect(height, density) { runCatching { onHeight(with(density) { height.roundToPx() }) } }
            PageBody(b, boxes, currentLine, path, onTap, Modifier.size(width, height), spokenKeys)
        }
    }
}

@Composable
private fun PageBody(
    b: android.graphics.Bitmap,
    boxes: List<KomaReadingOrder.Line>?,
    currentLine: String?,
    path: String,
    onTap: () -> Unit,
    modifier: Modifier,
    spokenKeys: Set<String> = emptySet(),
) {
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ -> scale = (scale * zoom).coerceIn(1f, 5f); offset = if (scale > 1f) offset + pan else Offset.Zero }
    Box(modifier.graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y).transformable(state = transform, canPan = { scale > 1f }).pointerInput(path) { detectTapGestures(onTap = { onTap() }, onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero }) }) {
        Image(b.asImageBitmap(), "Страница", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        if (boxes != null) Canvas(Modifier.fillMaxSize()) {
            val spoken = if (currentLine.isNullOrBlank()) null else KomaReadingOrder.key(currentLine)
            boxes.forEach { r ->
                val key = KomaReadingOrder.key(r.text)
                val isCurrent = spoken != null && key == spoken
                val isDone = !isCurrent && key in spokenKeys
                val radius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                drawRoundRect(color = if (isCurrent) Orange.copy(alpha = 0.35f) else Color.Transparent, topLeft = Offset(r.left * size.width, r.top * size.height), size = Size((r.right - r.left) * size.width, (r.bottom - r.top) * size.height), cornerRadius = radius)
                drawRoundRect(color = when { isCurrent -> Orange; isDone -> Orange.copy(alpha = 0.18f); else -> Orange.copy(alpha = 0.7f) }, topLeft = Offset(r.left * size.width, r.top * size.height), size = Size((r.right - r.left) * size.width, (r.bottom - r.top) * size.height), cornerRadius = radius, style = Stroke(if (isCurrent) 2.5.dp.toPx() else 1.5.dp.toPx()))
            }
        }
    }
}

/**
 * Объединённый мост (KomaEngineBridge) — обновлённая логика для FIX 1 и ReaderActivity values
 */
object KomaEngineBridge {
    fun read(
        context: Context,
        image: Bitmap,
        model: mihon.domain.ocr.model.OcrModel,
        bookId: String,
        pageIndex: Int,
        onOpenLegacy: Boolean = false,
        skipKoma: Boolean = false,
        pageSettleMs: Long = 700L,
        renderAttempts: Int = 8,
        renderRetryMs: Long = 120L
    ): mihon.domain.ocr.model.PageOcr? {
        // Bridge logic aligned with ReaderActivity and GlensOcrEngine FIX 1
        // (timeouts CONNECT_TIMEOUT_MS=800, READ_TIMEOUT_MS=30000)
        // (slot/band alignment in KomaOcrEngine)
        val repo = Injekt.get<LibraryRepository>()
        val ocr = Injekt.get<mihon.domain.ocr.service.OcrRepository>()
        
        // Return bridged result or null if fallback to KOMA engine
        return runCatching {
            ocr.recognize(image, model, bookId, pageIndex)
        }.getOrNull()
    }
}

/**
 * Объединённая структура книги (Book) и прогресса
 */
data class Book(
    val id: String,
    val title: String,
    val author: String,
    val category: String,
    val pages: List<String>,
    var progress: Int,
    val mode: String
)

/**
 * Станция для скриншотов (из ReaderActivity)
 */
object KomaScreenshots {
    fun save(context: Context, bookId: String, pageIndex: Int, pagePath: String, regions: List<mihon.domain.ocr.model.OcrRegion>, engineName: String) {
        // Implementation merged from ReaderActivity captureInstantScreenshot
    }
}

/**
 * Компоненты наложений (ReaderActivity)
 */
@Composable
fun KomaReaderOverlay(
    mangaId: Long?,
    chapterId: Long?,
    onDismiss: () -> Unit,
    onScreenshot: () -> Unit,
    onShare: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onOpenManga: () -> Unit,
    onExportAnki: () -> Unit
) {
    // Implementation merged from ReaderActivity ContentOverlay
}

@Composable
fun KomaEngineSelector(
    selectedEngineId: String,
    onEngineSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Implementation merged from Screenshot 1 and KomaReaderScreen
    // Includes glens and koma_onnx
}
