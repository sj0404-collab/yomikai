package eu.kanade.tachiyomi.ui.koma

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import mihon.domain.ocr.model.OcrRegion
import eu.kanade.tachiyomi.data.tts.SpeakerGenderService
import eu.kanade.tachiyomi.data.tts.VoiceHelper
import eu.kanade.tachiyomi.data.tts.VoiceKind
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import android.graphics.Bitmap
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KomaReaderScreen(
    initial: Book,
    /**
     * Id серии в библиотеке (Long), если он известен.
     *
     * У книги KOMA свой строковый id, а выбор читалки хранится по id серии,
     * поэтому пункт «Спросить читалку заново» показывается только когда он есть.
     */
    mangaIdForChoice: Long? = null,
    repo: LibraryRepository,
    engine: KomaOcrEngine,
    onClose: () -> Unit,
    onUpdate: (Book) -> Unit,
    onDelete: (Book) -> Unit,
    autoStart: Boolean = false,
    onOpenLegacy: (() -> Unit)? = null,
) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    // Действующие правила (файл/плагин) — объявлены ДО всего, что от них
    // зависит: полосы, пороги распознавания, версия правил в буфере строк.
    // rulesTick меняется кнопкой «Перезагрузить правила»: всё пересчитывается
    // без пересборки APK и без нового OCR.
    var rulesTick by remember { mutableIntStateOf(0) }
    // Счётчик обновлений буфера: без него число строк в шторке «Правила»
    // оставалось бы прежним, хотя распознавание уже дописало строки.
    var bufferTick by remember { mutableIntStateOf(0) }
    val rules = remember(rulesTick) { KomaRulesStore.current(context) }
    // Книга без страниц (пустой архив, удалённые файлы) раньше роняла экран на
    // coerceIn(0, -1) ещё до первой отрисовки — пользователь видел «вылет»
    // сразу после тапа по книге.
    if(initial.pages.isEmpty()) {
        Column(Modifier.fillMaxSize().background(Color(0xFF171A17)).padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
            Text("В этой книге нет страниц",color=Color.White,fontSize=18.sp,fontWeight=FontWeight.Bold)
            Text("Архив пуст или файлы страниц удалены. Импортируйте книгу заново.",color=Color(0xFFB2B8AD),fontSize=13.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center,modifier=Modifier.padding(top=10.dp))
            Button(onClick=onClose,modifier=Modifier.padding(top=20.dp)){Text("Назад")}
        }
        return
    }
    var book by remember(initial.id){mutableStateOf(initial)}
    var mode by remember { mutableStateOf(book.mode) }
    val pager=rememberPagerState(initialPage=book.progress.coerceIn(0,book.pages.lastIndex.coerceAtLeast(0))){book.pages.size}
    val scroll=rememberLazyListState(initialFirstVisibleItemIndex=book.progress.coerceIn(0,book.pages.lastIndex.coerceAtLeast(0)))
    val page=if(mode=="vertical")scroll.firstVisibleItemIndex else pager.currentPage
    val currentPage by rememberUpdatedState(page)
    // Высота каждой страницы в пикселях при вписывании по ширине (для
    // автопрокрутки и для нарезки на полосы внутри длинной страницы-вебтуна).
    val pageHeights = remember { mutableStateMapOf<String,Int>() }
    // Реальная видимая высота ленты (px). Именно она — «экран» для детектора:
    // раньше размером экрана считалась целая страница, и KOMA читала текст
    // ниже видимой области.
    var viewportPx by remember { mutableIntStateOf(0) }
    var viewportWidthPx by remember { mutableIntStateOf(0) }
    // Вертикальный скролл ВНУТРИ текущей страницы (режим «страница», длинный кадр).
    val pageScroll = remember(currentPage) { androidx.compose.foundation.ScrollState(0) }
    // Идёт ли прокрутка прямо сейчас (палец или инерция). В полуручном режиме
    // распознавать и читать можно только когда всё встало — жалоба «он не ждёт,
    // пока я остановлюсь, а сразу начинает сканировать».
    var scrollBusy by remember { mutableStateOf(false) }
    val pageSizes = remember { mutableStateMapOf<String,IntArray>() }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.layoutInfo.viewportSize.height }.collect { if(it>0) viewportPx=it }
    }
    // «Лента едет»: палец, инерция или программная прокрутка. Ждём, пока
    // встанет, и только потом читаем/распознаём — иначе чтение уезжает вперёд
    // того места, на котором человек остановился.
    LaunchedEffect(scroll, pageScroll) {
        snapshotFlow { scroll.isScrollInProgress to pageScroll.isScrollInProgress }
            .collect { (a, b) -> scrollBusy = a || b }
    }
    // ── Полосы страницы ──────────────────────────────────────────────────────
    // Страница выше экрана в несколько раз (вебтун) читается полосами: видимая
    // часть плюс перекрытие. Обычная страница — одна полоса [0..1], и всё
    // работает как раньше.
    val currentPath = book.pages.getOrNull(page)
    val currentHeight = currentPath?.let { pageHeights[it] } ?: 0
    // Доля видимой части страницы. В ленте — всегда экран/страница; в режиме
    // «страница» — то же самое, если кадр длиннее экрана (вебтун в манге).
    val viewportFraction = if(currentHeight>0 && viewportPx>0)
        viewportPx.toFloat()/currentHeight else 1f
    val bands = remember(currentPath,viewportFraction,rules) {
        KomaBands.of(viewportFraction, rules)
    }
    // Какая полоса сейчас на экране: центр видимой области.
    val offsetInPage = if(mode=="vertical")
        (if(scroll.firstVisibleItemIndex==page) scroll.firstVisibleItemScrollOffset else 0)
    else pageScroll.value
    val visibleFraction = if(currentHeight>0) (offsetInPage+viewportPx/2f)/currentHeight else 0f
    val bandIndex = bands.indexOfLast { it.top<=visibleFraction }.coerceAtLeast(0)
    val band = bands.getOrElse(bandIndex) { KomaBand.Whole }
    fun slot(b: KomaBand)= if(b.whole) 0 else b.index+1
    /** Полосы произвольной страницы — для прогрева вперёд и авточтения. */
    fun bandsOf(index: Int): List<KomaBand> {
        val path=book.pages.getOrNull(index) ?: return KomaBands.whole
        val vp=viewportPx
        if(vp<=0) return KomaBands.whole
        val known=pageHeights[path]
        if(known!=null && known>0) return KomaBands.of(vp.toFloat()/known, rules)
        val size=pageSizes[path] ?: engine.pageSize(path)?.let { intArrayOf(it.first,it.second) }?.also { pageSizes[path]=it }
            ?: return KomaBands.of(viewportFraction, rules)
        if(size[0]<=0 || size[1]<=0) return KomaBands.of(viewportFraction, rules)
        // Страница вписана по ширине экрана (так её и показывает читалка).
        val shownWidth = if(viewportWidthPx>0) viewportWidthPx.toFloat() else 1080f
        val shownHeight = shownWidth*size[1]/size[0]
        return KomaBands.of(vp/shownHeight, rules)
    }
    val autoRequested=remember{mutableSetOf<String>()}
    // Кириллица — по умолчанию: библиотека русская, и раньше каждый раз
    // приходилось переключать язык руками (а «Авто» уводил манхву в японский).
    var language by remember { mutableStateOf(repo.prefs.getString("ocrLanguage","Кириллица") ?: "Кириллица") }
    val tts = remember { KomaTts(context) }
    // Очередь распознавания: текущая полоса — первой, следующие страницы
    // греются в фоне, пока читается эта.
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
    // Панели прячутся, когда идёт авточтение: экран остаётся только под текст.
    // Касание страницы возвращает их.
    var barsVisible by remember { mutableStateOf(true) }
    // Панели прячутся, как только начинается авточтение.
    LaunchedEffect(autoReading) { if(autoReading) barsVisible=false }
    var overflow by remember { mutableStateOf(false) }
    var autoScroll by remember { mutableStateOf(repo.prefs.getBoolean("autoScroll",true)) }
    // Полуручной режим: прочитать видимое, остановиться, ждать прокрутки.
    // Значение по умолчанию берётся из правил (файл/плагин), переключатель в
    // настройках читалки главнее.
    var semiManual by remember {
        mutableStateOf(repo.prefs.getBoolean("semiManual", KomaRulesStore.current(context).semiManual))
    }

    var voices by remember { mutableStateOf<List<android.speech.tts.Voice>>(emptyList()) }
    var selectedVoice by remember { mutableStateOf(tts.selectedVoiceName()) }
    // Контекст приложения нужен уведомлению, которое рисует контроллер.
    LaunchedEffect(Unit) { AppContextHolder.context = context.applicationContext }
    // ── Голоса персонажей ────────────────────────────────────────────────────
    // «Разные голоса» и «полуручной режим» живут настройками, чтобы обычное
    // чтение одним голосом оставалось доступным.
    var multiVoice by remember { mutableStateOf(repo.prefs.getBoolean("multiVoice",false)) }
    var manualCasting by remember { mutableStateOf(repo.prefs.getBoolean("manualCasting",false)) }
    val ocrPrefs = remember { runCatching { Injekt.get<mihon.domain.ocr.service.OcrPreferences>() }.getOrNull() }
    // Пол, определённый по картинке (Gemini) — только если введён ключ.
    val genderByText = remember { mutableStateMapOf<String,String>() }
    // ── Четыре голоса: ж1 / ж2 / м1 / м2 ────────────────────────────────────
    // Пользователь: «сделай пресеты по моим голосам… а также на каждый тембр
    // 3 разных голоса, например f1, f2, m1 от Tera TTS, можно и 4 — m2».
    // Слот — это настоящий голос движка + свой тембр, а не «просто питч».
    // Голос по умолчанию НЕ меняется: пока не нажали слот, читает системный.
    var selectedSlot by remember { mutableIntStateOf(repo.prefs.getInt("voiceSlot",-1)) }
    // Раскрыта ли строка голосов в нижней панели.
    var voiceBar by remember { mutableStateOf(false) }
    // Для какого слота сейчас выбирают голос (-1 — диалог закрыт).
    var slotPickerFor by remember { mutableIntStateOf(-1) }
    val slotPitch = remember {
        androidx.compose.runtime.mutableStateListOf(
            KomaCasting.savedPitch(context,0),
            KomaCasting.savedPitch(context,1),
            KomaCasting.savedPitch(context,2),
            KomaCasting.savedPitch(context,3),
        )
    }
    // Ручной выбор голоса для слота (null — автоподбор по полу слота).
    val slotVoices = remember {
        androidx.compose.runtime.mutableStateListOf(
            KomaCasting.savedVoiceName(context,0),
            KomaCasting.savedVoiceName(context,1),
            KomaCasting.savedVoiceName(context,2),
            KomaCasting.savedVoiceName(context,3),
        )
    }
    // Список голосов движка грузим сразу: без него автоподбор ж/м не работает
    // и «выбор голоса» показывал бы пустой список.
    LaunchedEffect(Unit) {
        if (voices.isEmpty()) runCatching { voices = tts.voices() }
    }
    fun autoVoiceName(slot:Int): String? {
        val want = if (slot < 2) VoiceKind.FEMALE else VoiceKind.MALE
        val pool = voices.filter { VoiceHelper.classify(it) == want }
            .sortedBy { it.name }
        val index = slot % 2
        return (pool.getOrNull(index) ?: pool.firstOrNull())?.name
    }
    fun slotVoiceOf(slot:Int): KomaVoice = KomaCasting.slotVoice(
        slot = slot,
        pitch = slotPitch.getOrElse(slot){1f},
        voiceName = slotVoices.getOrNull(slot) ?: autoVoiceName(slot),
    )
    fun savedSlotFor(text:String): Int? =
        if(multiVoice) KomaCasting.savedSlot(context,book.id,text) else null
    // Перерисовка после изменения назначений (они лежат в prefs).
    var castingRev by remember { mutableIntStateOf(0) }
    val genders = genderByText
    // «Как читать диалоги?» — один вопрос при первом открытии книги. Раньше
    // настройка голосов пряталась в шторке «Как читается», и найти её в читалке
    // было невозможно (жалоба пользователя).
    var askVoices by remember(book.id) {
        mutableStateOf(!repo.prefs.getBoolean("voiceAsk_${book.id}", false))
    }
    var result by remember { mutableStateOf<PageOcr?>(null) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    // Прогрев идёт через KomaOcrQueue (см. ниже): одна текущая полоса и две
    // страницы вперёд. Прежний одиночный prefetchJob стартовал только ПОСЛЕ
    // распознавания текущей страницы, поэтому «очереди наперёд» не было.
    var scanning by remember { mutableStateOf(false) }
    var batchProgress by remember { mutableIntStateOf(0) }
    var batchTotal by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var showRegions by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleteConfirm by remember { mutableStateOf(false) }
    val clipboard=LocalClipboardManager.current
    fun save(b: Book) {book=b;onUpdate(b)}
    fun scan(all:Boolean=false,force:Boolean=false) {
        scanJob?.cancel()
        scanJob=scope.launch {
            scanning=true;batchTotal=if(all)book.pages.size else 0;batchProgress=0
            try {
                if(all) {
                    // Одна плохая страница не должна обрывать распознавание книги
                    // и, тем более, ронять приложение.
                    var failed=0
                    for(n in book.pages.indices) {
                        runCatching { engine.recognize(book,n,language,force) }
                            .onSuccess { if(n==currentPage)result=it }
                            .onFailure { failed++ }
                        batchProgress=n+1
                    }
                    engine.lastError?.let { error=it }
                    if(failed>0) error=(error?.plus("\n\n") ?: "")+"Не удалось обработать страниц: $failed"
                } else {
                    // Распознаём ТУ полосу, которая на экране: у длинного
                    // вебтуна это видимая часть, а не вся страница целиком.
                    val current=page
                    val currentBand=band
                    val r=engine.recognize(book,current,language,force,currentBand)
                    if(current==currentPage && currentBand.index==band.index)result=r
                    // Буфер строк: то, что распознавание вернуло, ложится на диск.
                    // Дальше правку правил можно проверять без нового OCR —
                    // «Пересчитать порядок из буфера».
                    runCatching {
                        bufferTick++
                        KomaLineBuffer.putRegions(
                            context=context.applicationContext,
                            book=book.id,
                            page=current,
                            band=currentBand.index,
                            regions=r.regions,
                            rulesVersion=engine.rulesVersion(),
                        )
                    }
                    engine.lastError?.let { error=it }
                }
            } catch(e:CancellationException){throw e}catch(e:Throwable){error=e.localizedMessage ?: e::class.simpleName ?: "Распознавание не удалось"}finally{scanning=false;batchTotal=0}
        }
    }
    LaunchedEffect(page,language,scanning,bandIndex,scrollBusy) {
        save(book.copy(progress=page,mode=mode))
        // В вертикальной ленте ждём реальную высоту страницы: без неё нельзя
        // понять, где кончается экран, и распознавание пошло бы по всей
        // странице (ровно та жалоба «читает за пределами экрана»).
        if(currentHeight==0) return@LaunchedEffect
        // Сначала кэш именно этой полосы, затем — страницы целиком (старый
        // кэш после обновления версии разбора уже не подойдёт, но если полоса
        // совпадает со страницей, он и есть нужный).
        result=engine.cached(book.id,page,language,slot(band)) ?: engine.cached(book.id,page,language)
        val key="$page:$bandIndex:$language"
        // В полуручном режиме не начинаем распознавание, пока лента едет:
        // человек ещё выбирает, где остановиться.
        if(semiManual && scrollBusy) return@LaunchedEffect
        if(repo.prefs.getBoolean("autoOcr",true) && result==null && !scanning && key !in autoRequested) { autoRequested.add(key);scan() }
    }
    LaunchedEffect(page,bandIndex,viewportPx,viewportFraction,language,scrollBusy) {
        if(scanning) return@LaunchedEffect
        if(semiManual && scrollBusy) return@LaunchedEffect
        if(engine.cached(book.id,page,language,slot(band))==null) ocrQueue.enqueue(page,band,urgent=true)
        ocrQueue.warmAhead(page,book.pages.lastIndex) { bandsOf(it) }
    }
    DisposableEffect(Unit){
        val window=(context as? android.app.Activity)?.window
        if(repo.prefs.getBoolean("keepAwake",true))window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose{scanJob?.cancel();KomaAutoRead.stop();tts.shutdown();window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
    }
    // ── Авточтение ────────────────────────────────────────────────────────────
    // Логика живёт в KomaAutoRead: порядок чтения по репликам, фильтр чужой
    // письменности, «уже прочитано», уведомление в шторке и плавающая кнопка.
    // Реплики страницы: строки облачка уже склеены (см. KomaReadingOrder), и
    // этот же список рисуется рамками — экран и озвучка видят одно и то же.
    val bubbles = remember(result, language, mode, rules) {
        result?.let {
            KomaReadingOrder.order(
                regions = it.regions,
                rtl = mode == "rtl",
                onlyCyrillic = language == "Кириллица" || language == "Авто",
                panels = it.panels,
                rules = rules,
            )
        }
    }
    // Реплики, которые уже прозвучали: по ним рамки гаснут до полупрозрачных.
    // Список ведёт движок авточтения (state.spoken), поэтому экран и озвучка
    // показывают одно и то же.
    val spokenOfPage = remember(autoState.spoken) {
        autoState.spoken.map { KomaReadingOrder.key(it) }.toSet()
    }
    var readTrigger by remember { mutableIntStateOf(0) }
    // Авточтение НЕ включается само при открытии страницы. Запуск — только
    // кнопкой воспроизведения или явной командой «Сканировать и читать»
    // (extra autoread_start). Настройка «автостарт чтения» из прежней читалки
    // здесь намеренно не читается: страница должна открыться спокойно.
    LaunchedEffect(Unit) {
        // Сброс только «зависшего» состояния от прошлого экрана: если чтение
        // реально идёт (есть живой синтезатор), его не трогаем — иначе после
        // поворота экрана состояние показывало бы «не читается».
        if (KomaAutoRead.state.value.running && !KomaAutoRead.isActive()) KomaAutoRead.reset()
    }
    LaunchedEffect(autoStart) { if (autoStart) readTrigger++ }
    LaunchedEffect(readTrigger) {
        if(readTrigger==0)return@LaunchedEffect
        KomaAutoRead.run(
            context = context,
            book = book,
            pages = book.pages,
            language = language,
            from = if(mode=="vertical")scroll.firstVisibleItemIndex else pager.currentPage,
            tts = tts,
            semiManual = semiManual,
            // Полуручной: начинаем с той полосы, которую видно.
            fromBandIndex = if(semiManual) bandIndex else 0,
            recognize = { index, pageBand ->
                // Полоса берётся из кэша, только если в ней ЕСТЬ текст.
                //
                // Раньше пустой результат из кэша считался готовым ответом:
                // очередь могла записать «текста нет» (например, не догрузился
                // кадр), и полоса с названием на обложке после этого не
                // распознавалась уже никогда — приложение молча её пролистывало.
                // Теперь пустой кэш = повод распознать заново.
                val ready = engine.cached(book.id,index,language,slot(pageBand))
                    ?.takeIf { it.regions.isNotEmpty() }
                if (ready != null) {
                    ready
                } else {
                    runCatching { engine.recognize(book,index,language,true,pageBand) }.getOrNull()
                        ?: engine.cached(book.id,index,language,slot(pageBand))
                }
            },
            bandsOf = { index -> bandsOf(index) },
            onPage = { index ->
                // В ленте НЕ дёргаем scrollToItem на каждой странице: у элемента
                // страницы это сбрасывает смещение на ноль, и экран прыгал в
                // самый верх страницы, обрывая ручную прокрутку. Переходим,
                // только если нужная страница ещё не на экране.
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
                if(r!=null) {
                    result=r
                    // Снимок прочитанной страницы — в общую вкладку «Скриншоты»,
                    // как это делала прежняя читалка.
                    val lines=KomaReadingOrder.order(
                        regions=r.regions,
                        rtl=mode=="rtl",
                        onlyCyrillic=language=="Кириллица"||language=="Авто",
                        panels=r.panels,
                        rules=rules,
                    )
                    val shotPage=KomaAutoRead.state.value.page
                    runCatching {
                        scope.launch {
                            KomaScreenshots.save(
                                context=context.applicationContext,
                                bookId=book.id,
                                pageIndex=shotPage,
                                pagePath=book.pages.getOrNull(shotPage).orEmpty(),
                                regions=lines.mapIndexed { i,line ->
                                    OcrRegion(
                                        order=i,
                                        text=line.text,
                                        boundingBox=mihon.domain.ocr.model.OcrBoundingBox(
                                            line.left,line.top,line.right,line.bottom,
                                        ),
                                        textOrientation=mihon.domain.ocr.model.OcrTextOrientation.Horizontal,
                                    )
                                },
                                engineName=r.engine,
                            )
                        }
                    }
                }
            },
            onSilentPage = { index ->
                // Кадр без реплик (арт, звуковой эффект) проезжаем плавно, а не
                // перескакиваем: место в главе не теряется, и глаза успевают
                // посмотреть рисунок.
                if (mode == "vertical") {
                    val next = (index + 1).coerceAtMost(book.pages.lastIndex)
                    scrollJob?.cancel()
                    scrollJob = scope.launch {
                        runCatching { scroll.animateScrollToItem(next) }
                    }
                    delay(900)
                } else {
                    pager.animateScrollToPage((index + 1).coerceAtMost(book.pages.lastIndex))
                    delay(900)
                }
            },
            // Один голос на всю книгу: слоты (ж1/ж2/м1/м2) отключены.
            manualCasting = false,
            slotOf = { _, _ -> null },
            voiceForSlot = { slot -> slotVoiceOf(slot) },
            rememberSlot = { _, _ -> },
            onBand = { index, pageBand ->
                // Прокрутка к читаемой полосе — только ВПЕРЁД.
                //
                // Полосы перекрываются на 15% (KomaBands.OVERLAP), чтобы реплика
                // на стыке не потерялась. Но начало следующей полосы лежит ВЫШЕ
                // конца прочитанной, и безусловный `animateScrollToItem(band.top)`
                // откатывал ленту назад на ~0.7 экрана — ровно то, что видно на
                // кадрах 12:00:06 → 12:00:52 (реплики на месте, а сверху снова
                // титульный лист). Теперь едем, только если полоса ниже текущего
                // положения ленты, и никогда не дёргаем экран назад.
                val h = book.pages.getOrNull(index)?.let { pageHeights[it] } ?: 0
                if (h > 0) {
                val target = (pageBand.top * h).toInt()
                if (mode == "vertical") {
                    val curItem = scroll.firstVisibleItemIndex
                    val curOffset = scroll.firstVisibleItemScrollOffset
                    val forward = index > curItem || (index == curItem && target > curOffset + 8)
                    if (forward) {
                        scrollJob?.cancel()
                        scrollJob = scope.launch {
                            runCatching { scroll.animateScrollToItem(index, target) }
                        }
                    }
                } else if (index == page && pageBand.top > 0.01f) {
                    val forward = target > pageScroll.value + 8
                    if (forward) {
                        scrollJob?.cancel()
                        scrollJob = scope.launch {
                            runCatching { pageScroll.animateScrollTo(target) }
                        }
                    }
                }
                }
            },
            onLine = { linePage, line ->
                // Автопрокрутка к текущей реплике — только если её не видно.
                //
                // Раньше на КАЖДУЮ строку ставилась позиция «реплика на 120 px
                // от верха»: страница прыгала к верху, а ручная прокрутка тут же
                // отменялась следующей репликой — экран выглядел заблокированным.
                // Теперь лента не трогается, пока реплика в удобной полосе
                // экрана, и не спорит с пальцем: пока идёт прокрутка жестом,
                // автопрокрутка молчит.
                // Режим «страница» с длинным кадром: реплику тоже надо довести
                // до экрана, иначе голос читает то, чего не видно.
                if (mode != "vertical" && autoScroll && currentHeight > 0 && !pageScroll.isScrollInProgress) {
                    val bubbleTop = (line.top * currentHeight).toInt()
                    val visible = bubbleTop >= pageScroll.value &&
                        bubbleTop <= pageScroll.value + viewportPx * 0.75f
                    if (!visible) {
                        // Куда довести реплику: доля экрана из правил
                        // (KomaRules.autoScrollStep), а не жёсткие 0.25.
                        val desired = (bubbleTop - viewportPx * (1f - rules.autoScrollStep)).toInt().coerceAtLeast(0)
                        scrollJob?.cancel()
                        scrollJob = scope.launch { runCatching { pageScroll.animateScrollTo(desired) } }
                    }
                }
                if (mode == "vertical" && autoScroll && !scroll.isScrollInProgress) {
                    // Страница — та, на которой реально звучит реплика (номер
                    // пришёл из движка), а не «текущая по состоянию»: они
                    // расходились на стыке страниц.
                    val target = linePage.coerceIn(0, book.pages.lastIndex)
                    val path = book.pages.getOrNull(target)
                    val height = path?.let { pageHeights[it] } ?: 0
                    if (height > 0) {
                        val viewport = scroll.layoutInfo.viewportSize.height
                        val itemOffset = if (scroll.firstVisibleItemIndex == target) {
                            scroll.firstVisibleItemScrollOffset
                        } else {
                            0
                        }
                        val bubbleTop = (line.top * height).toInt()
                        val bubbleBottom = (line.bottom * height).toInt()
                        val visibleTop = itemOffset
                        val visibleBottom = itemOffset + viewport
                        // Удобная полоса: реплика целиком в средней части экрана.
                        val comfortableTop = visibleTop + (viewport * 0.15f).toInt()
                        val comfortableBottom = visibleBottom - (viewport * 0.35f).toInt()
                        val visible = bubbleTop >= comfortableTop && bubbleBottom <= comfortableBottom
                        if (!visible && viewport > 0) {
                            val desired = (bubbleTop - viewport * 0.30f).toInt().coerceAtLeast(0)
                            // Доводим реплику до удобной полосы ПОСТЕПЕННО:
                            // мгновенный scrollToItem читался как рывок-сбой,
                            // а не как переход к следующей реплике.
                            scrollJob?.cancel()
                            scrollJob = scope.launch {
                                runCatching { scroll.animateScrollToItem(target, desired) }
                            }
                        }
                    }
                }
            },
        )
        readTrigger=0
    }
    // ── Кто говорит по картинке (необязательно) ──────────────────────────────
    // Работает только когда в настройках OCR введён ключ Gemini: сервис смотрит
    // на лица и хвостики облачков и отвечает male/female для каждой реплики.
    // Без ключа всё решается правилами и полуручным режимом — офлайн.
    LaunchedEffect(autoReading, page, bubbles) {
        if(!autoReading) return@LaunchedEffect
        val prefs=ocrPrefs ?: return@LaunchedEffect
        if(runCatching { prefs.googleApiKey().get() }.getOrNull().isNullOrBlank()) return@LaunchedEffect
        val lines=bubbles.orEmpty().map { it.text }.filter { it.isNotBlank() }
        if(lines.isEmpty()) return@LaunchedEffect
        val jpeg=runCatching {
            withContext(Dispatchers.IO) {
                val bmp=engine.bitmap(book.pages[page])
                java.io.ByteArrayOutputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG,80,out); out.toByteArray()
                }
            }
        }.getOrNull() ?: return@LaunchedEffect
        val genders=runCatching { SpeakerGenderService.detect(jpeg,lines,prefs) }.getOrNull().orEmpty()
        lines.forEachIndexed { i,t -> genders.getOrNull(i)?.let { g -> genderByText[KomaReadingOrder.key(t)]=g } }
    }
    // Уведомление живёт, пока идёт чтение; при уходе с экрана гасим его.
    DisposableEffect(Unit){ onDispose { KomaAutoRead.stop() } }
    BackHandler { if(voiceDialog)voiceDialog=false else if(sheet!=null)sheet=null else if(autoReading)KomaAutoRead.stop() else onClose() }
    val bufferLines = remember(bufferTick, book.id, page, bandIndex) {
        KomaLineBuffer.loadPage(context, book.id, page, bandIndex)
    }
    // Замена файла правил: .json — числа, .dex/.apk — плагин (как расширения
    // Mihon, только файл в приватной папке приложения).
    val rulesPick=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri ->
        if(uri!=null) scope.launch {
            val applied=runCatching {
                val target=File(context.cacheDir, if(uri.toString().endsWith(".dex"))"koma-rules.dex" else "koma-rules.json")
                context.contentResolver.openInputStream(uri)?.use { inp -> target.outputStream().use { out -> inp.copyTo(out) } }
                KomaRulesStore.install(context, target)
            }.getOrNull()
            rulesTick++
            error=if(applied!=null)
                "Правила применены: ${applied.version} (${KomaRulesStore.source})"
            else
                "Файл не подошёл: нужен koma-rules.json или koma-rules.dex"
        }
    }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){uri ->
        if(uri!=null)scope.launch {try {withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { out ->
            out.appendLine(book.title);out.appendLine("Локальное распознавание KOMA. Результат требует проверки.")
            // Экспорт идёт в том же порядке, в каком страница читается: раньше
            // в файл попадали сырые области детектора — строки вперемешку.
            book.pages.indices.forEach { n -> engine.cachedAny(book.id,n,language)?.let { r ->
                val body = KomaReadingOrder.pageText(
                    regions = r.regions,
                    rtl = book.mode == "rtl",
                    onlyCyrillic = language == "Кириллица" || language == "Авто",
                    panels = r.panels,
                ).ifBlank { r.text }
                out.appendLine("\n— Страница ${n+1} —\n$body")
            } }
        }};error="Распознанный текст сохранён"}catch(e:Exception){error=e.message}}
    }
    Scaffold(containerColor=Color(0xFF171A17),topBar={
        // Верхняя панель: навигация и всё, что было в прежней читалке —
        // распознавание, голос, полный экран и меню. Строки идут друг под
        // другом, а при авточтении панель убирается целиком.
        if(!barsVisible) Unit else Column(Modifier.background(Color(0xF2171A17))) {
            Row(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal=6.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=onClose){Icon(Icons.Default.ArrowBack,"Назад",tint=Color.White)}
                Column(Modifier.weight(1f).padding(start=4.dp)){Text(book.title,fontSize=15.sp,fontWeight=FontWeight.Bold,color=Color.White,maxLines=1,overflow=TextOverflow.Ellipsis);Text("KOMA · лента · ${page+1} из ${book.pages.size} · ${book.category} · ${when(mode){"rtl"->"Справа налево";"vertical"->"Вертикально";else->"Слева направо"}}",fontSize=10.sp,color=Color(0xFFB2B8AD))}
                IconButton(onClick={if(result==null && !scanning)scan();sheet="ocr"}){Icon(Icons.Outlined.DocumentScanner,"Распознать",tint=if(result!=null)Orange else Color.White)}
                IconButton(onClick={sheet="settings"}){Icon(Icons.Outlined.RecordVoiceOver,"Голос и авточтение",tint=if(autoReading)Orange else Color.White)}
                IconButton(onClick={barsVisible=false}){Icon(Icons.Outlined.Fullscreen,"Полный экран",tint=Color.White)}
                Box {
                    IconButton(onClick={overflow=true}){Icon(Icons.Default.MoreVert,"Ещё",tint=Color.White)}
                    DropdownMenu(expanded=overflow,onDismissRequest={overflow=false}) {
                        DropdownMenuItem(text={Text(if(book.favorite)"Убрать из избранного" else "В избранное")},onClick={save(book.copy(favorite=!book.favorite));overflow=false})
                        DropdownMenuItem(text={Text("Как читать эту историю")},onClick={sheet="settings";overflow=false})
                        DropdownMenuItem(text={Text("Как читается")},onClick={sheet="speech";overflow=false})
                        DropdownMenuItem(text={Text("Голоса персонажей")},onClick={sheet="voices";overflow=false})
                        DropdownMenuItem(text={Text("Все страницы")},onClick={sheet="pages";overflow=false})
                        DropdownMenuItem(text={Text("Рамки текста")},onClick={showRegions=!showRegions;overflow=false})
                        DropdownMenuItem(text={Text("Правила и буфер строк")},onClick={sheet="rules";overflow=false})
                        DropdownMenuItem(text={Text(if(autoScroll)"Автопрокрутка: вкл" else "Автопрокрутка: выкл")},onClick={autoScroll=!autoScroll;repo.prefs.edit().putBoolean("autoScroll",autoScroll).apply();overflow=false})
                        DropdownMenuItem(text={Text("Экспорт распознанного")},onClick={overflow=false;export.launch("${book.title}_ocr.txt")})
                        // Две читалки подписаны по-разному, и выбор можно
                        // переиграть: «Как читать эту серию» возвращает вопрос.
                        val choiceMangaId = mangaIdForChoice
                        if(choiceMangaId!=null) DropdownMenuItem(
                            text={Text("Спросить читалку заново")},
                            onClick={
                                overflow=false
                                eu.kanade.tachiyomi.ui.reader.ReaderLauncher.forget(context, choiceMangaId)
                                android.widget.Toast
                                    .makeText(context,"При следующем открытии главы спросим: манга постранично или манхва лентой",android.widget.Toast.LENGTH_LONG)
                                    .show()
                            },
                        )
                        if(onOpenLegacy!=null) DropdownMenuItem(text={Text("Открыть в основной читалке · постранично")},onClick={overflow=false;onOpenLegacy()})
                        DropdownMenuItem(text={Text("Удалить из библиотеки")},onClick={overflow=false;deleteConfirm=true})
                    }
                }
            }
        }
    },bottomBar={
        if(!barsVisible) Unit else Surface(color=Color(0xFF232822)) {
            Column(Modifier.navigationBarsPadding().padding(horizontal=18.dp,vertical=10.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {
                    TextButton(onClick={if(result==null && !scanning)scan();sheet="ocr"},contentPadding=PaddingValues(horizontal=8.dp)) {
                        if(scanning)CircularProgressIndicator(Modifier.size(17.dp),strokeWidth=2.dp,color=Orange)else Icon(Icons.Outlined.AutoAwesome,null,Modifier.size(19.dp),tint=Orange)
                        Text(if(scanning)"  Распознаём…" else if(result!=null)"  Текст страницы" else "  Распознать",fontSize=12.sp,color=Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick={ if(autoReading)KomaAutoRead.togglePause() else readTrigger++ }){
                        Icon(if(autoReading && !autoState.paused)Icons.Filled.PauseCircle else Icons.Outlined.PlayCircle,"Авточтение",tint=if(autoReading)Orange else Color(0xFF9EAB9A))
                    }
                    IconToggleButton(checked=autoScroll,onCheckedChange={autoScroll=it;repo.prefs.edit().putBoolean("autoScroll",it).apply()}){Icon(Icons.Outlined.SwapVert,"Автопрокрутка",tint=if(autoScroll)Orange else Color(0xFF9EAB9A))}
                    IconToggleButton(checked=showRegions,onCheckedChange={showRegions=it}){Icon(Icons.Outlined.CropFree,"Рамки текста",tint=if(showRegions)Orange else Color(0xFF9EAB9A))}
                    // Голоса — компактно, рядом с «Рамками текста»: три кнопки
                    // и ползунок тембра выбранного голоса. Никаких окон на пол
                    // экрана после каждой реплики.
                    IconButton(onClick={voiceBar=!voiceBar}){Icon(Icons.Outlined.RecordVoiceOver,"Голос",tint=if(voiceBar)Orange else Color(0xFF9EAB9A))}
                    IconButton(onClick={sheet="pages"}){Icon(Icons.Outlined.GridView,"Все страницы",tint=Color(0xFF9EAB9A))}
                }
                // ── Три голоса: «по умолчанию» + 1/2/3 и ползунок тембра ────
                // Полуручной режим больше не открывает окно: строка подсвечивается,
                // а проход ждёт нажатия одной из трёх кнопок.
                // ── Голос: ОДИН ─────────────────────────────────────────────
                // Четыре слота (ж1/ж2/м1/м2 с ползунком тембра) убраны по
                // просьбе владельца: совмещать разные голоса с разбивкой текста
                // на реплики пока не получается — панель только мешала и
                // разъезжалась по ширине. Вернёмся к этому, когда текст и голоса
                // будут совмещаться надёжно; код слотов остаётся в KomaCasting.
                if(voiceBar) {
                    Row(Modifier.fillMaxWidth().padding(top=2.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("Голос:",fontSize=10.sp,color=Color(0xFFB2B8AD),modifier=Modifier.padding(end=6.dp))
                        Text(
                            selectedVoice?.substringAfterLast('/')?.ifBlank { "по умолчанию" }
                                ?: "по умолчанию",
                            fontSize=10.sp,color=Orange,
                            modifier=Modifier.weight(1f),
                        )
                        TextButton(onClick={ voiceDialog=true },contentPadding=PaddingValues(horizontal=6.dp)) {
                            Text("выбрать",fontSize=10.sp,color=Orange)
                        }
                        IconButton(onClick={ scope.launch { runCatching {
                            tts.speakVoiced(
                                texts=listOf(bubbles?.firstOrNull()?.text?.let { KomaCasting.stripSpeaker(it) } ?: "Так будет звучать голос."),
                                voiceFor={ KomaVoice(voice=selectedVoice) },
                                onLineStart={},
                            )
                        } } },modifier=Modifier.size(28.dp)){Icon(Icons.Outlined.VolumeUp,null,Modifier.size(15.dp),tint=Orange)}
                    }
                }
                if(batchTotal>0){LinearProgressIndicator(progress={batchProgress.toFloat()/batchTotal},modifier=Modifier.fillMaxWidth(),color=Orange);Text("Страница $batchProgress из $batchTotal",fontSize=10.sp,color=Color.White)}
                Row(verticalAlignment=Alignment.CenterVertically){
                    IconButton(onClick={scope.launch {if(mode=="vertical")scroll.animateScrollToItem((page-1).coerceAtLeast(0))else pager.animateScrollToPage((page-1).coerceAtLeast(0))}},enabled=page>0,modifier=Modifier.size(32.dp)){Icon(Icons.Default.ChevronLeft,"Предыдущая",tint=if(page>0)Color.White else Color.Gray)}
                    Slider(value=page.toFloat(),onValueChange={v->scope.launch {if(mode=="vertical")scroll.scrollToItem(v.toInt())else pager.scrollToPage(v.toInt())}},valueRange=0f..book.pages.lastIndex.coerceAtLeast(1).toFloat(),modifier=Modifier.weight(1f).height(32.dp),colors=SliderDefaults.colors(thumbColor=Orange,activeTrackColor=Orange,inactiveTrackColor=Color(0xFF51594D)))
                    Text("${page+1} / ${book.pages.size}",fontSize=10.sp,color=Color.White,modifier=Modifier.padding(horizontal=8.dp))
                    IconButton(onClick={scope.launch {if(mode=="vertical")scroll.animateScrollToItem((page+1).coerceAtMost(book.pages.lastIndex))else pager.animateScrollToPage((page+1).coerceAtMost(book.pages.lastIndex))}},enabled=page<book.pages.lastIndex,modifier=Modifier.size(32.dp)){Icon(Icons.Default.ChevronRight,"Следующая",tint=if(page<book.pages.lastIndex)Color.White else Color.Gray)}
                }
            }
        }
    }) { inset ->
        // Размеры области чтения нужны ОБОИМ режимам: в «страницах» (справа
        // налево / слева направо) длинная страница-вебтун тоже выше экрана, и
        // без видимой высоты она считалась целой страницей — распознавание шло
        // по 30 секунд, а читался текст за пределами экрана.
        BoxWithConstraints(Modifier.fillMaxSize().padding(inset)) {
            val density=androidx.compose.ui.platform.LocalDensity.current
            val visibleH=with(density){maxHeight.roundToPx()}
            val visibleW=with(density){maxWidth.roundToPx()}
            LaunchedEffect(visibleH){ if(visibleH>0) viewportPx=visibleH }
            LaunchedEffect(visibleW){ if(visibleW>0) viewportWidthPx=visibleW }
            if(mode=="vertical")LazyColumn(state=scroll,modifier=Modifier.fillMaxSize()) {
                itemsIndexed(book.pages,key={n,_->n}) { n,path -> PageImage(path,engine,true,if(n==page && (showRegions||autoReading))bubbles else null,if(n==page)autoState.currentLine else null,{h->pageHeights[path]=h},{barsVisible=!barsVisible},spokenKeys=spokenOfPage) }
            } else HorizontalPager(state=pager,reverseLayout=mode=="rtl",modifier=Modifier.fillMaxSize(),beyondViewportPageCount=0) { n ->
                PageImage(
                    path=book.pages[n],
                    engine=engine,
                    vertical=false,
                    boxes=if(n==page && (showRegions||autoReading))bubbles else null,
                    currentLine=if(n==page)autoState.currentLine else null,
                    onHeight={h->pageHeights[book.pages[n]]=h},
                    onTap={barsVisible=!barsVisible},
                    // Длинная страница листается ВНУТРИ страницы: состояние
                    // скролла живёт в экране, чтобы авточтение могло довести
                    // экран до читаемой полосы.
                    scrollState=if(n==page)pageScroll else null,
                    spokenKeys=spokenOfPage,
                )
            }
        }
    }
    // Плавающая кнопка авточтения: не мешает чтению, но всегда под рукой —
    // пауза/продолжить, пропуск реплики и стоп. В прежней читалке за это
    // отвечала отдельная плавающая панель; здесь достаточно одной кнопки.
    // Плавающая панель живёт вместе с панелями: касание страницы убирает её,
    // чтобы экран оставался только под рисунок. Раньше она висела всегда.
    // ── Полуручной режим ─────────────────────────────────────────────────────
    // Стрелка в углу/сбоку: куда прокручивать, зависит от того, как читается
    // история — вниз для ленты, влево для «справа налево», вправо для остальных.
    // Тап по стрелке прокручивает один экран и запускает чтение новой части.
    if(semiManual && autoState.waitingScroll) {
        val align=when(mode){"vertical"->Alignment.BottomCenter;"rtl"->Alignment.CenterStart;else->Alignment.CenterEnd}
        val icon=when(mode){"vertical"->Icons.Filled.KeyboardArrowDown;"rtl"->Icons.Filled.KeyboardArrowLeft;else->Icons.Filled.KeyboardArrowRight}
        Box(Modifier.fillMaxSize().padding(bottom=110.dp,start=8.dp,end=8.dp),contentAlignment=align) {
            Surface(
                shape=RoundedCornerShape(28.dp),color=Color(0xE6232822),shadowElevation=6.dp,
                onClick={
                    val step=(viewportPx * 0.85f).toInt().coerceAtLeast(1)
                    scrollJob?.cancel()
                    scrollJob=scope.launch {
                        runCatching {
                            if(mode=="vertical") {
                                scroll.animateScrollToItem(
                                    scroll.firstVisibleItemIndex,
                                    scroll.firstVisibleItemScrollOffset + step,
                                )
                            } else if(currentHeight>0) {
                                pageScroll.animateScrollTo((pageScroll.value + step).coerceAtLeast(0))
                            }
                        }
                        // Даём кадру доехать и встать: читаем то, что видно, и
                        // только когда прокрутка закончилась.
                        delay(280L + rules.semiManualSettleMs)
                        if(KomaAutoRead.continueFromScroll()) readTrigger++
                    }
                },
            ) {
                Row(Modifier.padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(icon,"Прокрутить дальше",tint=Orange)
                    Text("Прокрутить",fontSize=11.sp,color=Color.White)
                }
            }
        }
    }
    // Ждём прокрутки → человек прокрутил сам → читаем то, что теперь видно.
    LaunchedEffect(page, bandIndex, semiManual, scrollBusy) {
        if(!semiManual || !autoReading) return@LaunchedEffect
        if(autoState.waitingScroll != true) return@LaunchedEffect
        // 1) ждём, пока прокрутка реально встанет;
        if(scrollBusy) return@LaunchedEffect
        // 2) и только потом даём человеку договорить/дочитать кадр глазами.
        delay(rules.semiManualSettleMs.toLong())
        if(scroll.isScrollInProgress || pageScroll.isScrollInProgress) return@LaunchedEffect
        if(KomaAutoRead.continueFromScroll()) readTrigger++
    }
    // Попали обрезки (реплика разрезана краем полосы) — не читаем огрызок, а
    // сами подъезжаем на столько, чтобы реплика была видна целиком, и
    // пересчитываем. Один добор на полосу, чтобы не ездить бесконечно.
    val adjustedBands = remember(book.id) { mutableSetOf<String>() }
    LaunchedEffect(result, bandIndex, semiManual) {
        if(!semiManual) return@LaunchedEffect
        val last=bubbles.orEmpty().lastOrNull() ?: return@LaunchedEffect
        if(!KomaReadingOrder.cutAtBandBottom(last, band, rules.cutMargin)) return@LaunchedEffect
        val key="$page:$bandIndex"
        if(!adjustedBands.add(key)) return@LaunchedEffect
        val h=currentHeight
        if(h<=0) return@LaunchedEffect
        val target=((last.top-rules.cutMargin)*h).toInt().coerceAtLeast(0)
        scrollJob?.cancel()
        scrollJob=scope.launch {
            runCatching {
                if(mode=="vertical") scroll.animateScrollToItem(page, target) else pageScroll.animateScrollTo(target)
            }
            delay(260)
            // Пересчёт уже по новому положению кадра: реплика теперь целиком в нём.
            if(!scanning) scan(force=true)
        }
    }
    if(autoReading && barsVisible) {
        Box(Modifier.fillMaxSize().padding(bottom=140.dp,end=16.dp),contentAlignment=Alignment.BottomEnd) {
            Surface(shape=RoundedCornerShape(28.dp),color=Color(0xE6232822),shadowElevation=8.dp) {
                Row(Modifier.padding(horizontal=10.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={KomaAutoRead.togglePause()}){Icon(if(autoState.paused)Icons.Filled.PlayArrow else Icons.Filled.Pause,"Пауза",tint=Orange)}
                    IconButton(onClick={KomaAutoRead.skip()}){Icon(Icons.Filled.SkipNext,"Пропустить реплику",tint=Color.White)}
                    IconButton(onClick={KomaAutoRead.stop()}){Icon(Icons.Filled.Stop,"Остановить",tint=Color(0xFFE57373))}
                    if(autoState.paused)Text("пауза",fontSize=10.sp,color=Color(0xFFB2B8AD),modifier=Modifier.padding(end=8.dp))
                }
            }
        }
    }
    if(sheet!=null) ModalBottomSheet(onDismissRequest={sheet=null},containerColor=MaterialTheme.colorScheme.background,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        when(sheet){
            // Правила чтения (файл/плагин) и буфер строк: и то и другое меняется
            // без пересборки APK. Один экран, потому что это один сценарий:
            // поправил числа → пересчитал порядок по буферу → посмотрел.
            "rules" -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=28.dp)) {
                Text("Правила и буфер строк",fontSize=23.sp,fontWeight=FontWeight.Bold)
                Text("Числа распознавания и порядка чтения. Меняются файлом правил или плагином — приложение пересобирать не нужно.",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))
                SectionLabel("ДЕЙСТВУЮЩИЕ ПРАВИЛА")
                Text("Версия: ${rules.version}",fontSize=13.sp,fontWeight=FontWeight.Bold)
                Text("Источник: ${KomaRulesStore.source}",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Папка: files/koma-plugins — koma-rules.json (числа) или koma-rules.dex (плагин)",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=2.dp))
                KomaRulesStore.error()?.let { Text(it,fontSize=11.sp,color=Color(0xFFE57373),modifier=Modifier.padding(top=4.dp)) }
                Row(Modifier.fillMaxWidth().padding(top=6.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={ rulesPick.launch(arrayOf("*/*")) },shape=RoundedCornerShape(12.dp),modifier=Modifier.weight(1f)){Text("Заменить файл",fontSize=12.sp)}
                    Button(onClick={
                        rulesTick++
                        val fresh=KomaRulesStore.reload(context)
                        error="Правила перезагружены: ${fresh.version} (${KomaRulesStore.source})"
                    },shape=RoundedCornerShape(12.dp),modifier=Modifier.weight(1f)){Text("Перезагрузить",fontSize=12.sp)}
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    TextButton(onClick={ rulesTick++; KomaRulesStore.reset(context); error="Вернулись встроенные правила" }){Text("Сбросить к встроенным",fontSize=11.sp)}
                    TextButton(onClick={ clipboard.setText(AnnotatedString(rules.toJson())) }){Text("Скопировать JSON",fontSize=11.sp)}
                }
                Box(Modifier.fillMaxWidth().padding(vertical=8.dp).height(1.dp).background(Color(0xFF39412F)))
                SectionLabel("БУФЕР СТРОК")
                Text("Строк в буфере книги: ${KomaLineBuffer.size(context)} · на этой странице: ${bufferLines.size}",fontSize=12.sp)
                Text("Сохранённые строки этого прогона. Правку правил можно проверить без нового распознавания — порядок пересчитается по буферу.",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=2.dp))
                if(bufferLines.isNotEmpty()) Column(Modifier.fillMaxWidth().heightIn(max=150.dp).verticalScroll(rememberScrollState()).padding(top=6.dp)) {
                    bufferLines.forEach { line ->
                        Row(Modifier.fillMaxWidth().padding(vertical=2.dp)) {
                            Text("${line.band+1}",fontSize=10.sp,color=Orange,modifier=Modifier.padding(end=6.dp))
                            Text(line.text,fontSize=11.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick={
                        val lines=KomaLineBuffer.loadPage(context,book.id,page,bandIndex)
                        if(lines.isEmpty()) { error="Буфер этой страницы пуст — сначала «Распознать»" }
                        else {
                            result=PageOcr(regions=lines.map{it.toRegion()},engine="буфер строк",millis=0,panels=result?.panels.orEmpty())
                            error="Порядок пересчитан из буфера: ${lines.size} строк, правила ${rules.version}"
                        }
                    },shape=RoundedCornerShape(12.dp),modifier=Modifier.weight(1f)){Text("Пересчитать из буфера",fontSize=12.sp)}
                    OutlinedButton(onClick={
                        val text=KomaLineBuffer.exportText(context,book.id)
                        runCatching { File(context.filesDir,"koma-buffer.txt").writeText(text) }
                        clipboard.setText(AnnotatedString(text))
                        error="Буфер выгружен: files/koma-buffer.txt (и скопирован)"
                    },shape=RoundedCornerShape(12.dp),modifier=Modifier.weight(1f)){Text("Выгрузить",fontSize=12.sp)}
                }
                TextButton(onClick={ KomaLineBuffer.clearBook(context,book.id); bufferTick++; error="Буфер книги очищен" }){Text("Очистить буфер книги",fontSize=11.sp)}
            }
            "settings" -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=28.dp)) {
                Text("Как читать эту историю",fontSize=23.sp,fontWeight=FontWeight.Bold)
                SectionLabel("ПОРЯДОК ЧТЕНИЯ")
                listOf("rtl" to "Справа налево · манга","ltr" to "Слева направо · комиксы","vertical" to "Вертикальная лента · манхва").forEach { (value,label) ->
                    Row(Modifier.fillMaxWidth().clickable { val old=page;mode=value;save(book.copy(mode=value));scope.launch{if(value=="vertical")scroll.scrollToItem(old)else pager.scrollToPage(old)} }.padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(selected=mode==value,onClick=null);Text(label,fontSize=13.sp,modifier=Modifier.padding(start=12.dp))}
                }
                SectionLabel("КАТЕГОРИЯ")
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){categories.drop(1).forEach { cat->FilterChip(selected=book.category==cat,onClick={save(book.copy(category=cat))},label={Text(cat,fontSize=11.sp)})}}
                SectionLabel("ЯЗЫК OCR")
                LanguageChooser(language){language=it;repo.prefs.edit().putString("ocrLanguage",it).apply()}
                SectionLabel("АВТОЧТЕНИЕ")
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f)){Text("Читать страницы вслух",fontSize=13.sp);Text("Реплики в порядке чтения, дальше листает сам",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    Switch(checked=autoReading,onCheckedChange={ if(it)readTrigger++ else KomaAutoRead.stop() })
                }
                Row(Modifier.fillMaxWidth().padding(top=6.dp),verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f)) {
                        Text("Полуручной режим",fontSize=13.sp)
                        Text("Прочитает то, что видно, и остановится: дальше прокручиваете сами — стрелка в углу подскажет куда",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=semiManual,onCheckedChange={
                        semiManual=it
                        repo.prefs.edit().putBoolean("semiManual",it).apply()
                        if(it && autoReading) readTrigger++
                        error=if(it)"Полуручной режим: чтение ждёт вашей прокрутки" else "Полуручной режим выключен"
                    })
                }
                Row(Modifier.fillMaxWidth().padding(top=10.dp),verticalAlignment=Alignment.CenterVertically){
                    Text("Голос",fontSize=13.sp,modifier=Modifier.weight(1f))
                    TextButton(onClick={ scope.launch{ voices=tts.voices(); voiceDialog=true } }){ Text(selectedVoice ?: "системный",fontSize=12.sp) }
                }
                Row(Modifier.fillMaxWidth().padding(top=6.dp),verticalAlignment=Alignment.CenterVertically){
                    Text("Скорость речи",fontSize=13.sp,modifier=Modifier.weight(1f))
                    listOf(0.8f to "медленно",1f to "обычно",1.3f to "быстро").forEach { (value,label) ->
                        FilterChip(selected=speechRate==value,onClick={speechRate=value;tts.setRate(value)},label={Text(label,fontSize=11.sp)},modifier=Modifier.padding(start=4.dp))
                    }
                }
                TextButton(onClick={ if(result==null)scan() else readTrigger++ }){Icon(Icons.Outlined.VolumeUp,null,Modifier.size(18.dp));Text("  Прочитать эту страницу",fontSize=12.sp)}
                repo.prefs.edit().putBoolean("autoOcr",true).apply()
                Text("Масштаб: щипок или двойное касание. Свайп — следующая страница. Исходное изображение не меняется.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=16.dp))
                Text("${book.pages.size} страниц · ${book.author}",fontSize=12.sp)
                TextButton(onClick={if(book.source.startsWith("https://"))runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(book.source)))}} ,enabled=book.source.startsWith("https://")){Icon(Icons.Outlined.OpenInNew,null,Modifier.size(16.dp));Text("  Источник и права")}
                TextButton(onClick={deleteConfirm=true}){Icon(Icons.Outlined.DeleteOutline,null,Modifier.size(18.dp));Text("  Удалить из библиотеки")}
            }
            "ocr" -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal=24.dp).padding(bottom=22.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(if(bands.size>1 && !band.whole) "Текст страницы ${page+1} · экран ${band.index+1}/${bands.size}" else "Текст страницы ${page+1}",fontSize=23.sp,fontWeight=FontWeight.Bold);Text(result?.engine ?: "$ENGINE_NAME · офлайн",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=5.dp))};Icon(Icons.Outlined.VerifiedUser,null,tint=MaterialTheme.colorScheme.secondary)}
                Spacer(Modifier.height(12.dp))
                LanguageChooser(language){language=it}
                if(scanning){LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical=16.dp));Text(if(batchTotal>0)"Обработано $batchProgress из $batchTotal страниц" else "Нейросеть читает изображение…",fontSize=12.sp)}
                // Текст по порядку чтения — то же, что слышно в озвучке. Сырые
                // области детектора остаются на случай разбора: их видно, если
                // включить «как распознано».
                val orderedText = remember(result, language, mode) {
                    result?.let {
                        KomaReadingOrder.pageText(
                            regions = it.regions,
                            rtl = mode == "rtl",
                            onlyCyrillic = language == "Кириллица" || language == "Авто",
                            panels = it.panels,
                        )
                    }.orEmpty()
                }
                var rawView by remember(result){mutableStateOf(false)}
                var edit by remember(result, orderedText){mutableStateOf(orderedText.ifBlank { result?.text.orEmpty() })}
                val fontPath=repo.prefs.getString("font_${book.category}",null)
                val font=remember(fontPath){if(fontPath!=null)runCatching{FontFamily(androidx.compose.ui.text.font.Typeface(android.graphics.Typeface.createFromFile(fontPath)))}.getOrNull()else null}
                if(result!=null) {
                    Text("${result!!.regions.size} областей · ${"%.1f".format(result!!.millis/1000f)} сек.",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=8.dp))
                    // Что именно уйдёт в озвучку и в каком порядке. Раньше это
                    // можно было только услышать: не хватало слова — и непонятно,
                    // не распозналось оно или потерялось при склейке. Теперь
                    // список реплик виден глазами и читается в том же порядке.
                    val spokenLines = bubbles.orEmpty()
                    if(spokenLines.isNotEmpty()) {
                        Text("Как читается (${spokenLines.size})",fontSize=11.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))
                        Column(Modifier.fillMaxWidth().heightIn(max=180.dp).verticalScroll(rememberScrollState()).padding(top=4.dp)) {
                            spokenLines.forEachIndexed { index, line ->
                                Row(Modifier.fillMaxWidth().padding(vertical=2.dp)) {
                                    Text("${index+1}.",fontSize=11.sp,color=Orange,modifier=Modifier.padding(end=6.dp))
                                    Text(line.text,fontSize=11.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    // Переключатель для разбора: сырые области детектора нужны,
                    // когда что-то потерялось, но по умолчанию текст — реплики
                    // по порядку чтения, без «ЧTО?» и «ПОШЕВЕ- / ЛИЦЬСЯ».
                    Row(Modifier.fillMaxWidth().padding(bottom=6.dp),verticalAlignment=Alignment.CenterVertically){
                        Text("По порядку чтения",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(1f))
                        TextButton(onClick={rawView=!rawView;edit=if(rawView)result?.text.orEmpty() else orderedText.ifBlank { result?.text.orEmpty() }}){Text(if(rawView)"Как распознано" else "Показать как распознано",fontSize=10.sp)}
                    }
                    OutlinedTextField(value=edit,onValueChange={edit=it},modifier=Modifier.fillMaxWidth().heightIn(min=140.dp,max=280.dp),textStyle=LocalTextStyle.current.copy(fontFamily=font ?: when(book.category){"Манга"->FontFamily.Serif;"Маньхуа"->FontFamily.Cursive;"Комиксы"->FontFamily.Monospace;else->FontFamily.SansSerif},fontSize=15.sp),placeholder={Text("Текст не найден. Попробуйте другой язык или более чёткую страницу.")},shape=RoundedCornerShape(14.dp))
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick={result=result!!.copy(edited=edit);engine.store(book.id,page,language,result!!);error="Исправления сохранены"}){Text("Сохранить правки",fontSize=11.sp)};TextButton(onClick={clipboard.setText(AnnotatedString(edit))}){Icon(Icons.Outlined.ContentCopy,null,Modifier.size(15.dp));Text(" Копировать",fontSize=11.sp)}}
                }else if(!scanning){Text("Нажмите «Распознать». Здесь появится текст именно этой страницы, а не пример результата.",fontSize=13.sp,modifier=Modifier.padding(vertical=24.dp))}
                // Сброс кэша: без него непонятно, применились ли правки — при
                // повторном «Распознать» страница бралась из кэша, и выглядело
                // так, будто ничего не изменилось.
                TextButton(onClick={
                    engine.clear()
                    result=null
                    error="Кэш распознавания очищен — нажмите «Распознать»"
                },enabled=!scanning){Icon(Icons.Outlined.DeleteSweep,null,Modifier.size(16.dp));Text("  Очистить кэш распознавания",fontSize=11.sp)}
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Button(onClick={if(scanning)scanJob?.cancel()else scan(force=true)},shape=RoundedCornerShape(12.dp),modifier=Modifier.weight(1f)){Text(if(scanning)"Остановить" else "Распознать заново",fontSize=12.sp)}
                    OutlinedButton(onClick={scan(all=true)},enabled=!scanning,shape=RoundedCornerShape(12.dp),modifier=Modifier.weight(1f)){Text("Всю книгу",fontSize=12.sp)}
                }
                TextButton(onClick={export.launch("${book.title}_ocr.txt")},enabled=engine.cacheCount()>0){Icon(Icons.Outlined.FileDownload,null,Modifier.size(18.dp));Text("  Экспорт распознанных страниц",fontSize=12.sp)}
                Text("AI может ошибаться, особенно в рукописях и звуковых эффектах. Проверьте результат по оригиналу.",fontSize=11.sp,lineHeight=16.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            "speech" -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=28.dp)) {
                Text("Как читается",fontSize=23.sp,fontWeight=FontWeight.Bold)
                Text("Страница ${page+1} · ${language}",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp,bottom=12.dp))
                val list=bubbles.orEmpty()
                if(list.isEmpty()) {
                    Text("Реплик нет: страница ещё не распознана или на ней только рисунок и звуковые эффекты.",fontSize=13.sp)
                    TextButton(onClick={sheet=null;if(result==null)scan()}){Text("Распознать страницу",fontSize=12.sp)}
                }
                list.forEachIndexed { index,line ->
                    Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=Alignment.Top) {
                        Text("${index+1}.",fontSize=12.sp,color=Orange,modifier=Modifier.padding(end=8.dp))
                        Text(line.text,fontSize=12.sp,modifier=Modifier.weight(1f))
                        IconButton(onClick={ scope.launch { sheet=null; tts.speak(line.text) } },modifier=Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.VolumeUp,null,Modifier.size(16.dp),tint=Orange)
                        }
                    }
                }
                if(list.isNotEmpty()) Spacer(Modifier.height(8.dp))
                Text("Порядок — панель за панелью, внутри панели сверху вниз. Эти же фразы уходят в синтезатор.",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.fillMaxWidth().padding(vertical=12.dp).height(1.dp).background(Color(0xFF39412F)))
                Text("Голоса персонажей",fontSize=13.sp,fontWeight=FontWeight.Bold)
                Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Разные голоса",fontSize=12.sp)
                        Text("Каждой реплике — свой голос: пол и возраст через высоту и темп.",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=multiVoice,onCheckedChange={ multiVoice=it;repo.prefs.edit().putBoolean("multiVoice",it).apply() })
                }
                Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Спрашивать, кто говорит",fontSize=12.sp)
                        Text("Проход останавливается на реплике: «мужской» → парень, ребёнок, старик, взрослый.",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=manualCasting,onCheckedChange={ manualCasting=it;repo.prefs.edit().putBoolean("manualCasting",it).apply() })
                }
                if(runCatching { ocrPrefs?.googleApiKey()?.get() }.getOrNull().isNullOrBlank()) {
                    Text("Ключ Google AI не введён: пол по картинке не определяется, работает ручной выбор и подписи вида «ИМЯ: …».",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=6.dp))
                }
                TextButton(onClick={ KomaCasting.clearBook(context,book.id) }){Text("Сбросить назначения голосов",fontSize=11.sp)}
            }
            "voices" -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=22.dp).padding(bottom=26.dp)) {
                Text("Голоса",fontSize=23.sp,fontWeight=FontWeight.Bold)
                Text("Кто читает диалоги в этой книге",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp,bottom=12.dp))
                // Голос движка — то, что звучит по умолчанию.
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Голос озвучки",fontSize=12.sp,fontWeight=FontWeight.Bold)
                        Text(selectedVoice ?: "системный по умолчанию",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                    TextButton(onClick={voiceDialog=true}){Text("Сменить",fontSize=11.sp)}
                }
                Box(Modifier.fillMaxWidth().padding(vertical=8.dp).height(1.dp).background(Color(0xFF39412F)))
                Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Разные голоса",fontSize=13.sp)
                        Text("Каждому персонажу — свой голос (пол и возраст через высоту и темп).",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=multiVoice,onCheckedChange={multiVoice=it;repo.prefs.edit().putBoolean("multiVoice",it).apply()})
                }
                Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Спрашивать, кто говорит",fontSize=13.sp)
                        Text("Проход встаёт на реплике: «мужской» → парень, ребёнок, старик, взрослый.",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=manualCasting,onCheckedChange={manualCasting=it;repo.prefs.edit().putBoolean("manualCasting",it).apply()})
                }
                Box(Modifier.fillMaxWidth().padding(vertical=10.dp).height(1.dp).background(Color(0xFF39412F)))
                val rev=castingRev
                val linesNow=bubbles.orEmpty()
                Text("Реплики страницы ${page+1} (${linesNow.size})",fontSize=12.sp,fontWeight=FontWeight.Bold)
                if(linesNow.isEmpty()) {
                    Text("Реплик нет: страница ещё не распознана или на ней только рисунок.",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp))
                    TextButton(onClick={sheet=null;if(result==null)scan()}){Text("Распознать страницу",fontSize=11.sp)}
                }
                linesNow.forEachIndexed { index,line ->
                    // Назначение голоса реплике — без диалога: нажатие на кнопку
                    // переключает «по умолчанию → 1 → 2 → 3 → по умолчанию».
                    val slot=runCatching { KomaCasting.savedSlot(context,book.id,line.text) }.getOrNull()
                    val auto=when(genderByText[KomaReadingOrder.key(line.text)]){"male"->"муж.";"female"->"жен.";else->null}
                    Row(Modifier.fillMaxWidth().padding(vertical=3.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("${index+1}.",fontSize=11.sp,color=Orange,modifier=Modifier.padding(end=6.dp))
                        Text(line.text,fontSize=11.sp,modifier=Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis)
                        OutlinedButton(onClick={
                            val next=when(slot){null->0;0->1;1->2;else->null}
                            KomaCasting.saveSlot(context,book.id,line.text,next)
                            castingRev++
                        },contentPadding=PaddingValues(horizontal=8.dp,vertical=2.dp),modifier=Modifier.padding(start=6.dp)) {
                            Text(slot?.let { "Голос ${it+1}" } ?: auto ?: "по умолч.",fontSize=10.sp)
                        }
                        IconButton(onClick={
                            val voice=slot?.let { slotVoiceOf(it) }
                            scope.launch { runCatching {
                                tts.speakVoiced(
                                    texts=listOf(KomaCasting.stripSpeaker(line.text)),
                                    voiceFor={ voice ?: KomaVoice() },
                                    onLineStart={},
                                )
                            } }
                        },modifier=Modifier.size(30.dp)) { Icon(Icons.Outlined.VolumeUp,null,Modifier.size(15.dp),tint=Orange) }
                    }
                }
                if(runCatching { ocrPrefs?.googleApiKey()?.get() }.getOrNull().isNullOrBlank()) {
                    Text("Без ключа Google AI пол по картинке не определяется — работает ручной выбор и подписи «ИМЯ: …».",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top=6.dp)) {
                    Text("Тембр голоса настраивается ползунком внизу, у кнопок 1/2/3.",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(1f))
                    TextButton(onClick={ KomaCasting.clearBook(context,book.id);castingRev++ }){Text("Сбросить",fontSize=11.sp)}
                }
            }
            "pages" -> {
                Text("Страницы",Modifier.padding(horizontal=24.dp),fontSize=24.sp,fontWeight=FontWeight.Bold)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max=540.dp).padding(horizontal=16.dp)) {
                    itemsIndexed(book.pages) { n,_ -> ListItem(headlineContent={Text("Страница ${n+1}")},supportingContent={Text(if(engine.cached(book.id,n,language)!=null)"Текст распознан" else "Оригинальная страница")},leadingContent={Icon(if(n==page)Icons.Filled.MenuBook else Icons.Outlined.Description,null,tint=if(n==page)Orange else MaterialTheme.colorScheme.onSurfaceVariant)},modifier=Modifier.clickable{scope.launch{if(mode=="vertical")scroll.scrollToItem(n)else pager.scrollToPage(n);sheet=null}}) }
                }
            }
        }
    }
    // Первое открытие книги: один короткий вопрос. Дальше — только компактная
    // строка голосов внизу, без окон после каждой реплики.
    // Первое открытие книги: сразу спросить, как читать диалоги. Раньше эта
    // настройка лежала в шторке «Как читается», и в читалке её не было видно.
    if(askVoices) AlertDialog(
        onDismissRequest={ askVoices=false;repo.prefs.edit().putBoolean("voiceAsk_${book.id}",true).apply() },
        title={Text("Как читать диалоги?")},
        text={ Column {
            Text(
                "Сейчас чтение идёт ОДНИМ голосом — так текст и озвучка не разъезжаются. " +
                    "Разные голоса для персонажей вернём, когда научимся надёжно совмещать " +
                    "их с распознанным текстом.",
                fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick={ multiVoice=false;manualCasting=false;askVoices=false
                    repo.prefs.edit().putBoolean("multiVoice",false).putBoolean("manualCasting",false)
                        .putBoolean("voiceAsk_${book.id}",true).apply() },
                modifier=Modifier.fillMaxWidth().padding(bottom=6.dp),
            ){ Text("Одним голосом") }
            OutlinedButton(
                onClick={ multiVoice=false;manualCasting=false;askVoices=false
                    repo.prefs.edit().putBoolean("voiceAsk_${book.id}",true).apply()
                    voiceDialog=true },
                modifier=Modifier.fillMaxWidth(),
            ){ Text("Выбрать голос") }
        } },
        confirmButton={},
    )
    // Выбор голоса для слота: список голосов движка, разделённый по полу.
    if(slotPickerFor in 0 until KomaCasting.SLOT_COUNT) {
        val slot = slotPickerFor
        AlertDialog(
            onDismissRequest={ slotPickerFor=-1 },
            title={Text("Голос "+KomaCasting.SLOT_TITLES.getOrElse(slot){"${slot+1}"})},
            text={
                Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "Это настоящие голоса движка TTS. Если в движке один голос, " +
                            "оставьте «Авто» — слоты будут отличаться тембром.",
                        fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth().clickable {
                        slotVoices[slot]=null
                        KomaCasting.saveVoiceName(context,slot,null)
                        slotPickerFor=-1
                    }.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        RadioButton(selected=slotVoices.getOrNull(slot)==null,onClick=null)
                        Text("Авто (по полу слота)",fontSize=13.sp,modifier=Modifier.padding(start=8.dp))
                    }
                    val autoName = autoVoiceName(slot)
                    if(autoName!=null) {
                        Text("Авто сейчас: $autoName",fontSize=10.sp,color=Orange,modifier=Modifier.padding(bottom=6.dp))
                    }
                    voices.forEach { v ->
                        val selected = slotVoices.getOrNull(slot)==v.name
                        Row(Modifier.fillMaxWidth().clickable {
                            slotVoices[slot]=v.name
                            KomaCasting.saveVoiceName(context,slot,v.name)
                            slotPickerFor=-1
                        }.padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                            RadioButton(selected=selected,onClick=null)
                            Column(Modifier.padding(start=8.dp)) {
                                Text(v.name,fontSize=12.sp)
                                Text(
                                    "${v.locale} · ${when(VoiceHelper.classify(v)){
                                        VoiceKind.FEMALE->"жен."
                                        VoiceKind.MALE->"муж."
                                        VoiceKind.TEEN->"дет."
                                        else->"?"
                                    }}",
                                    fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton={ TextButton(onClick={ slotPickerFor=-1 }){Text("Закрыть")} },
        )
    }
    if(voiceDialog) AlertDialog(
        onDismissRequest={voiceDialog=false},
        title={Text("Голос для озвучки")},
        text={
            Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())) {
                if(voices.isEmpty()) Text("Система не отдала список голосов. Установите русский голос в настройках синтеза речи Android.",fontSize=12.sp)
                voices.forEach { v ->
                    Row(Modifier.fillMaxWidth().clickable {
                        tts.selectVoice(v.name); selectedVoice=v.name; voiceDialog=false
                    }.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        RadioButton(selected=selectedVoice==v.name,onClick=null)
                        Text(tts.label(v),fontSize=12.sp,modifier=Modifier.padding(start=10.dp))
                    }
                }
            }
        },
        confirmButton={TextButton(onClick={voiceDialog=false}){Text("Закрыть")}},
    )
    if(error!=null)AlertDialog(onDismissRequest={error=null},title={Text("KOMA")},text={Text(error!!)},confirmButton={TextButton(onClick={error=null}){Text("Понятно")}})
    if(deleteConfirm)AlertDialog(onDismissRequest={deleteConfirm=false},title={Text("Удалить книгу?")},text={Text("Копия в библиотеке будет удалена. Ваш исходный архив останется на месте.")},confirmButton={TextButton(onClick={onDelete(book);onClose()}){Text("Удалить")}},dismissButton={TextButton(onClick={deleteConfirm=false}){Text("Отмена")}})
}

@Composable
private fun PageImage(
    path:String,
    engine:KomaOcrEngine,
    vertical:Boolean,
    boxes:List<KomaReadingOrder.Line>?,
    currentLine:String?=null,
    onHeight:(Int)->Unit={},
    onTap:()->Unit={},
    scrollState:androidx.compose.foundation.ScrollState?=null,
    spokenKeys:Set<String> = emptySet(),
) {
    var bitmap by remember(path){mutableStateOf<android.graphics.Bitmap?>(null)}
    var error by remember(path){mutableStateOf<String?>(null)}
    var retry by remember(path){mutableIntStateOf(0)}
    LaunchedEffect(path,retry){try{bitmap=withContext(Dispatchers.IO){engine.bitmap(path)}}catch(e:Throwable){error=e.message ?: "Не удалось открыть страницу"}}
    // Битмап НЕ освобождаем вручную: recycle() во время отрисовки даёт
    // «Canvas: trying to use a recycled bitmap» — падение рендер-потока.
    val b=bitmap
    val density=androidx.compose.ui.platform.LocalDensity.current
    when {
        b==null -> Box(
            Modifier.fillMaxWidth().then(if(vertical)Modifier.height(320.dp)else Modifier.fillMaxHeight()),
            contentAlignment=Alignment.Center,
        ){
            if(error==null)CircularProgressIndicator(color=Orange)
            else TextButton(onClick={error=null;retry++}){Text("Не удалось открыть. Повторить")}
        }
        // ── Длинный кадр в режиме «страница» ────────────────────────────────
        // Раньше такая страница вписывалась по ВЫСОТЕ: вебтун 900×5400 на
        // экране 1080×2400 превращался в полоску шириной ~150 px — ровно то,
        // что видно на скриншотах 21:00 («большие страницы не расширяются»).
        // Теперь страница занимает всю ширину, а листается вниз; полосы
        // распознавания считаются по той же высоте.
        !vertical && b.height.toFloat()/b.width>2.2f -> BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight()) {
            val width=maxWidth
            val height=width*b.height/b.width
            val ownState=rememberScrollState()
            val st=scrollState ?: ownState
            LaunchedEffect(height,density){ runCatching { onHeight(with(density){ height.roundToPx() }) } }
            Box(Modifier.fillMaxSize().verticalScroll(st)) {
                PageBody(b,boxes,currentLine,path,onTap,Modifier.size(width,height),spokenKeys)
            }
        }
        else -> BoxWithConstraints(
            Modifier.fillMaxWidth().then(if(vertical)Modifier.aspectRatio(b.width.toFloat()/b.height)else Modifier.fillMaxHeight()).clip(RoundedCornerShape(0.dp)),
            contentAlignment=Alignment.Center,
        ) {
            val ratio=b.width.toFloat()/b.height
            val width=if(vertical || maxWidth/maxHeight<ratio)maxWidth else maxHeight*ratio
            val height=width/ratio
            // Высота страницы при вписывании по ширине — нужна автопрокрутке,
            // чтобы прокрутить длинный вебтун до текущей реплики.
            LaunchedEffect(height,density){ runCatching { onHeight(with(density){ height.roundToPx() }) } }
            PageBody(b,boxes,currentLine,path,onTap,Modifier.size(width,height),spokenKeys)
        }
    }
}

/** Рисунок страницы с рамками реплик и жестами (щипок, двойной тап). */
@Composable
private fun PageBody(
    b:android.graphics.Bitmap,
    boxes:List<KomaReadingOrder.Line>?,
    currentLine:String?,
    path:String,
    onTap:()->Unit,
    modifier:Modifier,
    spokenKeys:Set<String> = emptySet(),
) {
    var scale by remember(path){mutableFloatStateOf(1f)}
    var offset by remember(path){mutableStateOf(Offset.Zero)}
    val transform=rememberTransformableState { zoom,pan,_-> scale=(scale*zoom).coerceIn(1f,5f);offset=if(scale>1f)offset+pan else Offset.Zero }
    Box(modifier.graphicsLayer(scaleX=scale,scaleY=scale,translationX=offset.x,translationY=offset.y).transformable(state=transform,canPan={scale>1f}).pointerInput(path){detectTapGestures(onTap={onTap()},onDoubleTap={scale=if(scale>1f)1f else 2.5f;offset=Offset.Zero})}) {
        Image(b.asImageBitmap(),"Страница",Modifier.fillMaxSize(),contentScale=ContentScale.FillBounds)
        if(boxes!=null)Canvas(Modifier.fillMaxSize()) {
            // Одна рамка на РЕПЛИКУ: строки облачка уже склеены в
            // KomaReadingOrder, поэтому «Я ТОЖЕ ТАК СЧИТАЮ…» — это один
            // прямоугольник, а не пять полос с отдельным чтением каждой.
            val spoken=if(currentLine.isNullOrBlank())null else KomaReadingOrder.key(currentLine)
            boxes.forEach { r ->
                val key=KomaReadingOrder.key(r.text)
                val isCurrent=spoken!=null && key==spoken
                // Уже прозвучавшая реплика гаснет до полупрозрачной рамки: видно,
                // что её прочли, и она больше не «захватывает» внимание. Текст в
                // озвучку повторно не попадает — движок помнит прочитанное.
                val isDone=!isCurrent && key in spokenKeys
                val radius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                drawRoundRect(
                    color = if(isCurrent)Orange.copy(alpha=0.35f) else Color.Transparent,
                    topLeft = Offset(r.left*size.width,r.top*size.height),
                    size = Size((r.right-r.left)*size.width,(r.bottom-r.top)*size.height),
                    cornerRadius = radius,
                )
                drawRoundRect(
                    color = when {
                        isCurrent -> Orange
                        isDone -> Orange.copy(alpha=0.18f)
                        else -> Orange.copy(alpha=0.7f)
                    },
                    topLeft = Offset(r.left*size.width,r.top*size.height),
                    size = Size((r.right-r.left)*size.width,(r.bottom-r.top)*size.height),
                    cornerRadius = radius,
                    style = Stroke(if(isCurrent)2.5.dp.toPx() else 1.5.dp.toPx()),
                )
            }
        }
    }
}

@Composable
fun SectionLabel(text:String){Text(text,Modifier.padding(top=22.dp,bottom=9.dp),fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=1.3.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}

@Composable
fun LanguageChooser(selected:String,onSelect:(String)->Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){ocrLanguages.forEach { name->FilterChip(selected=selected==name,onClick={onSelect(name)},label={Text(name,fontSize=11.sp)},shape=RoundedCornerShape(10.dp))}}
}

/** Маленькая кнопка голоса в нижней панели: «об.», «1», «2», «3». */
@Composable
private fun VoiceSlotChip(label:String,selected:Boolean,onClick:()->Unit) {
    Surface(
        color = if(selected) Orange.copy(alpha=0.22f) else Color(0xFF2E342C),
        shape = RoundedCornerShape(10.dp),
        onClick = onClick,
    ) {
        Text(
            label,
            fontSize = 10.sp,
            fontWeight = if(selected) FontWeight.Bold else FontWeight.Normal,
            color = if(selected) Orange else Color(0xFFCBD2C6),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
}
