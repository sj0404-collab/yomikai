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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KomaReaderScreen(
    initial: Book,
    repo: LibraryRepository,
    engine: KomaOcrEngine,
    onClose: () -> Unit,
    onUpdate: (Book) -> Unit,
    onDelete: (Book) -> Unit,
    autoStart: Boolean = false,
    onOpenLegacy: (() -> Unit)? = null,
) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
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
    val autoRequested=remember{mutableSetOf<String>()}
    // Кириллица — по умолчанию: библиотека русская, и раньше каждый раз
    // приходилось переключать язык руками (а «Авто» уводил манхву в японский).
    var language by remember { mutableStateOf(repo.prefs.getString("ocrLanguage","Кириллица") ?: "Кириллица") }
    val tts = remember { KomaTts(context) }
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
    // Высота каждой страницы в пикселях при вписывании по ширине (для
    // автопрокрутки к текущей реплике внутри длинной страницы-вебтуна).
    val pageHeights = remember { mutableStateMapOf<String,Int>() }
    var voices by remember { mutableStateOf<List<android.speech.tts.Voice>>(emptyList()) }
    var selectedVoice by remember { mutableStateOf(tts.selectedVoiceName()) }
    // Контекст приложения нужен уведомлению, которое рисует контроллер.
    LaunchedEffect(Unit) { AppContextHolder.context = context.applicationContext }
    var result by remember { mutableStateOf<PageOcr?>(null) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    // Прогрев следующей страницы: пока голос читает текущую, распознавание уже
    // идёт. Именно это даёт «страница готова к моменту, когда дочитали», вместо
    // паузы в несколько секунд на каждой странице.
    var prefetchJob by remember { mutableStateOf<Job?>(null) }
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
                    val current=page
                    val r=engine.recognize(book,current,language,force)
                    if(current==currentPage)result=r
                    engine.lastError?.let { error=it }
                }
            } catch(e:CancellationException){throw e}catch(e:Throwable){error=e.localizedMessage ?: e::class.simpleName ?: "Распознавание не удалось"}finally{scanning=false;batchTotal=0}
        }
    }
    LaunchedEffect(page,language,scanning) {
        save(book.copy(progress=page,mode=mode))
        result=engine.cached(book.id,page,language)
        val key="$page:$language"
        if(repo.prefs.getBoolean("autoOcr",true) && result==null && !scanning && key !in autoRequested) { autoRequested.add(key);scan() }
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
    val bubbles = remember(result, language, mode) {
        result?.let {
            KomaReadingOrder.order(
                regions = it.regions,
                rtl = mode == "rtl",
                onlyCyrillic = language == "Кириллица" || language == "Авто",
                panels = it.panels,
            )
        }
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
            recognize = { index ->
                val ready = engine.cached(book.id,index,language)
                if (ready != null) ready else {
                    val result = runCatching { engine.recognize(book,index,language) }.getOrNull()
                    // Пока эта страница озвучивается — распознаём следующую.
                    val next = index + 1
                    if (next < book.pages.size && prefetchJob?.isActive != true) {
                        prefetchJob = scope.launch {
                            runCatching { engine.recognize(book,next,language) }
                        }
                    }
                    result
                }
            },
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
            onLine = { line ->
                // Автопрокрутка к текущей реплике — только если её не видно.
                //
                // Раньше на КАЖДУЮ строку ставилась позиция «реплика на 120 px
                // от верха»: страница прыгала к верху, а ручная прокрутка тут же
                // отменялась следующей репликой — экран выглядел заблокированным.
                // Теперь лента не трогается, пока реплика в удобной полосе
                // экрана, и не спорит с пальцем: пока идёт прокрутка жестом,
                // автопрокрутка молчит.
                if (mode == "vertical" && autoScroll && !scroll.isScrollInProgress) {
                    val target = autoState.page
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
                            scrollJob?.cancel()
                            scrollJob = scope.launch {
                                runCatching { scroll.scrollToItem(target, desired) }
                            }
                        }
                    }
                }
            },
        )
        readTrigger=0
    }
    // Уведомление живёт, пока идёт чтение; при уходе с экрана гасим его.
    DisposableEffect(Unit){ onDispose { KomaAutoRead.stop() } }
    BackHandler { if(voiceDialog)voiceDialog=false else if(sheet!=null)sheet=null else if(autoReading)KomaAutoRead.stop() else onClose() }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){uri ->
        if(uri!=null)scope.launch {try {withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { out ->
            out.appendLine(book.title);out.appendLine("Локальное распознавание KOMA. Результат требует проверки.")
            book.pages.indices.forEach { n -> engine.cached(book.id,n,language)?.let { r -> out.appendLine("\n— Страница ${n+1} —\n${r.text}") } }
        }};error="Распознанный текст сохранён"}catch(e:Exception){error=e.message}}
    }
    Scaffold(containerColor=Color(0xFF171A17),topBar={
        // Верхняя панель: навигация и всё, что было в прежней читалке —
        // распознавание, голос, полный экран и меню. Строки идут друг под
        // другом, а при авточтении панель убирается целиком.
        if(!barsVisible) Unit else Column(Modifier.background(Color(0xF2171A17))) {
            Row(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal=6.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=onClose){Icon(Icons.Default.ArrowBack,"Назад",tint=Color.White)}
                Column(Modifier.weight(1f).padding(start=4.dp)){Text(book.title,fontSize=15.sp,fontWeight=FontWeight.Bold,color=Color.White,maxLines=1,overflow=TextOverflow.Ellipsis);Text("${page+1} из ${book.pages.size} · ${book.category} · ${when(mode){"rtl"->"Справа налево";"vertical"->"Вертикально";else->"Слева направо"}}",fontSize=10.sp,color=Color(0xFFB2B8AD))}
                IconButton(onClick={if(result==null && !scanning)scan();sheet="ocr"}){Icon(Icons.Outlined.DocumentScanner,"Распознать",tint=if(result!=null)Orange else Color.White)}
                IconButton(onClick={sheet="settings"}){Icon(Icons.Outlined.RecordVoiceOver,"Голос и авточтение",tint=if(autoReading)Orange else Color.White)}
                IconButton(onClick={barsVisible=false}){Icon(Icons.Outlined.Fullscreen,"Полный экран",tint=Color.White)}
                Box {
                    IconButton(onClick={overflow=true}){Icon(Icons.Default.MoreVert,"Ещё",tint=Color.White)}
                    DropdownMenu(expanded=overflow,onDismissRequest={overflow=false}) {
                        DropdownMenuItem(text={Text(if(book.favorite)"Убрать из избранного" else "В избранное")},onClick={save(book.copy(favorite=!book.favorite));overflow=false})
                        DropdownMenuItem(text={Text("Как читать эту историю")},onClick={sheet="settings";overflow=false})
                        DropdownMenuItem(text={Text("Как читается")},onClick={sheet="speech";overflow=false})
                        DropdownMenuItem(text={Text("Все страницы")},onClick={sheet="pages";overflow=false})
                        DropdownMenuItem(text={Text("Рамки текста")},onClick={showRegions=!showRegions;overflow=false})
                        DropdownMenuItem(text={Text(if(autoScroll)"Автопрокрутка: вкл" else "Автопрокрутка: выкл")},onClick={autoScroll=!autoScroll;repo.prefs.edit().putBoolean("autoScroll",autoScroll).apply();overflow=false})
                        DropdownMenuItem(text={Text("Экспорт распознанного")},onClick={overflow=false;export.launch("${book.title}_ocr.txt")})
                        if(onOpenLegacy!=null) DropdownMenuItem(text={Text("Прежняя читалка (резерв)")},onClick={overflow=false;onOpenLegacy()})
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
                    IconButton(onClick={sheet="pages"}){Icon(Icons.Outlined.GridView,"Все страницы",tint=Color(0xFF9EAB9A))}
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
        Box(Modifier.fillMaxSize().padding(inset)) {
            if(mode=="vertical")LazyColumn(state=scroll,modifier=Modifier.fillMaxSize()) {
                itemsIndexed(book.pages,key={n,_->n}) { n,path -> PageImage(path,engine,true,if(n==page && (showRegions||autoReading))bubbles else null,if(n==page)autoState.currentLine else null,{h->pageHeights[path]=h},{barsVisible=!barsVisible}) }
            } else HorizontalPager(state=pager,reverseLayout=mode=="rtl",modifier=Modifier.fillMaxSize(),beyondViewportPageCount=0) { n -> PageImage(book.pages[n],engine,false,if(n==page && (showRegions||autoReading))bubbles else null,if(n==page)autoState.currentLine else null,onTap={barsVisible=!barsVisible}) }

        }
    }
    // Плавающая кнопка авточтения: не мешает чтению, но всегда под рукой —
    // пауза/продолжить, пропуск реплики и стоп. В прежней читалке за это
    // отвечала отдельная плавающая панель; здесь достаточно одной кнопки.
    // Плавающая панель живёт вместе с панелями: касание страницы убирает её,
    // чтобы экран оставался только под рисунок. Раньше она висела всегда.
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
                Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Текст страницы ${page+1}",fontSize=23.sp,fontWeight=FontWeight.Bold);Text(result?.engine ?: "$ENGINE_NAME · офлайн",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=5.dp))};Icon(Icons.Outlined.VerifiedUser,null,tint=MaterialTheme.colorScheme.secondary)}
                Spacer(Modifier.height(12.dp))
                LanguageChooser(language){language=it}
                if(scanning){LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical=16.dp));Text(if(batchTotal>0)"Обработано $batchProgress из $batchTotal страниц" else "Нейросеть читает изображение…",fontSize=12.sp)}
                var edit by remember(result){mutableStateOf(result?.text.orEmpty())}
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
                    OutlinedTextField(value=edit,onValueChange={edit=it},modifier=Modifier.fillMaxWidth().heightIn(min=140.dp,max=280.dp),textStyle=LocalTextStyle.current.copy(fontFamily=font ?: when(book.category){"Манга"->FontFamily.Serif;"Маньхуа"->FontFamily.Cursive;"Комиксы"->FontFamily.Monospace;else->FontFamily.SansSerif},fontSize=15.sp),placeholder={Text("Текст не найден. Попробуйте другой язык или более чёткую страницу.")},shape=RoundedCornerShape(14.dp))
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick={result=result!!.copy(edited=edit);engine.store(book.id,page,language,result!!);error="Исправления сохранены"}){Text("Сохранить правки",fontSize=11.sp)};TextButton(onClick={clipboard.setText(AnnotatedString(edit))}){Icon(Icons.Outlined.ContentCopy,null,Modifier.size(15.dp));Text(" Копировать",fontSize=11.sp)}}
                }else if(!scanning){Text("Нажмите «Распознать». Здесь появится текст именно этой страницы, а не пример результата.",fontSize=13.sp,modifier=Modifier.padding(vertical=24.dp))}
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Button(onClick={if(scanning)scanJob?.cancel()else scan(force=true)},shape=RoundedCornerShape(12.dp),modifier=Modifier.weight(1f)){Text(if(scanning)"Остановить" else "Распознать",fontSize=12.sp)}
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
            }
            "pages" -> {
                Text("Страницы",Modifier.padding(horizontal=24.dp),fontSize=24.sp,fontWeight=FontWeight.Bold)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max=540.dp).padding(horizontal=16.dp)) {
                    itemsIndexed(book.pages) { n,_ -> ListItem(headlineContent={Text("Страница ${n+1}")},supportingContent={Text(if(engine.cached(book.id,n,language)!=null)"Текст распознан" else "Оригинальная страница")},leadingContent={Icon(if(n==page)Icons.Filled.MenuBook else Icons.Outlined.Description,null,tint=if(n==page)Orange else MaterialTheme.colorScheme.onSurfaceVariant)},modifier=Modifier.clickable{scope.launch{if(mode=="vertical")scroll.scrollToItem(n)else pager.scrollToPage(n);sheet=null}}) }
                }
            }
        }
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
private fun PageImage(path:String,engine:KomaOcrEngine,vertical:Boolean,boxes:List<KomaReadingOrder.Line>?,currentLine:String?=null,onHeight:(Int)->Unit={},onTap:()->Unit={}) {
    var bitmap by remember(path){mutableStateOf<android.graphics.Bitmap?>(null)}
    var error by remember(path){mutableStateOf<String?>(null)}
    var retry by remember(path){mutableIntStateOf(0)}
    var scale by remember(path){mutableFloatStateOf(1f)}
    var offset by remember(path){mutableStateOf(Offset.Zero)}
    LaunchedEffect(path,retry){try{bitmap=withContext(Dispatchers.IO){engine.bitmap(path)}}catch(e:Throwable){error=e.message ?: "Не удалось открыть страницу"}}
    // Битмап НЕ освобождаем вручную: recycle() во время отрисовки даёт
    // «Canvas: trying to use a recycled bitmap» — падение рендер-потока.
    // Память ограничиваем на входе (потолок 10 Мпкс, RGB_565), а сборщик
    // забирает кадр, как только страница уходит из композиции.
    val b=bitmap
    if(b==null) Box(Modifier.fillMaxWidth().then(if(vertical)Modifier.height(320.dp)else Modifier.fillMaxHeight()),contentAlignment=Alignment.Center){if(error==null)CircularProgressIndicator(color=Orange)else TextButton(onClick={error=null;retry++}){Text("Не удалось открыть. Повторить")}}
    else BoxWithConstraints(Modifier.fillMaxWidth().then(if(vertical)Modifier.aspectRatio(b.width.toFloat()/b.height)else Modifier.fillMaxHeight()).clip(RoundedCornerShape(0.dp)),contentAlignment=Alignment.Center) {
        val ratio=b.width.toFloat()/b.height
        val width=if(vertical || maxWidth/maxHeight<ratio)maxWidth else maxHeight*ratio
        val height=width/ratio
        // Высота страницы при вписывании по ширине — нужна автопрокрутке,
        // чтобы прокрутить длинный вебтун до текущей реплики.
        val density=androidx.compose.ui.platform.LocalDensity.current
        LaunchedEffect(height,density){ runCatching { onHeight(with(density){ height.roundToPx() }) } }
        val transform=rememberTransformableState { zoom,pan,_-> scale=(scale*zoom).coerceIn(1f,5f);offset=if(scale>1f)offset+pan else Offset.Zero }
        Box(Modifier.size(width,height).graphicsLayer(scaleX=scale,scaleY=scale,translationX=offset.x,translationY=offset.y).transformable(state=transform,canPan={scale>1f}).pointerInput(path){detectTapGestures(onTap={onTap()},onDoubleTap={scale=if(scale>1f)1f else 2.5f;offset=Offset.Zero})}) {
            Image(b.asImageBitmap(),"Страница",Modifier.fillMaxSize(),contentScale=ContentScale.FillBounds)
            if(boxes!=null)Canvas(Modifier.fillMaxSize()) {
                // Одна рамка на РЕПЛИКУ: строки облачка уже склеены в
                // KomaReadingOrder, поэтому «Я ТОЖЕ ТАК СЧИТАЮ…» — это один
                // прямоугольник, а не пять полос с отдельным чтением каждой.
                val spoken=if(currentLine.isNullOrBlank())null else KomaReadingOrder.key(currentLine)
                boxes.forEach { r ->
                    val isCurrent=spoken!=null && KomaReadingOrder.key(r.text)==spoken
                    // Скруглённый прямоугольник — как контур реплики в KOMA.
                    val radius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                    drawRoundRect(
                        color = if(isCurrent)Orange.copy(alpha=0.35f) else Color.Transparent,
                        topLeft = Offset(r.left*size.width,r.top*size.height),
                        size = Size((r.right-r.left)*size.width,(r.bottom-r.top)*size.height),
                        cornerRadius = radius,
                    )
                    drawRoundRect(
                        color = if(isCurrent)Orange else Orange.copy(alpha=0.7f),
                        topLeft = Offset(r.left*size.width,r.top*size.height),
                        size = Size((r.right-r.left)*size.width,(r.bottom-r.top)*size.height),
                        cornerRadius = radius,
                        style = Stroke(if(isCurrent)2.5.dp.toPx() else 1.5.dp.toPx()),
                    )
                }
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
