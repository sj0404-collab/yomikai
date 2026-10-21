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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KomaReaderScreen(initial: Book,repo: LibraryRepository,engine: KomaOcrEngine,onClose: ()->Unit,onUpdate: (Book)->Unit,onDelete: (Book)->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    // Книга без страниц (пустой архив, удалённые файлы, битые ассеты) раньше
    // роняла читалку на coerceIn(0, -1) ещё до первой отрисовки: пользователь
    // видел «вылет» сразу после тапа по книге. Теперь это сообщение и кнопка
    // «назад».
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
    var language by remember { mutableStateOf(repo.prefs.getString("ocrLanguage","Авто") ?: "Авто") }
    var result by remember { mutableStateOf<PageOcr?>(null) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
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
                if(all) for(n in book.pages.indices) { val r=engine.recognize(book,n,language,force);if(n==currentPage)result=r;batchProgress=n+1 }
                else { val current=page;val r=engine.recognize(book,current,language,force);if(current==currentPage)result=r }
            } catch(e:CancellationException){throw e}catch(e:Exception){error=e.localizedMessage ?: "Распознавание не удалось"}finally{scanning=false;batchTotal=0}
        }
    }
    LaunchedEffect(page,language,scanning) {
        save(book.copy(progress=page,mode=mode))
        result=engine.cached(book.id,page,language)
        val key="$page:$language"
        if(repo.prefs.getBoolean("autoOcr",false) && result==null && !scanning && key !in autoRequested) { autoRequested.add(key);scan() }
    }
    DisposableEffect(Unit){
        val window=(context as? android.app.Activity)?.window
        if(repo.prefs.getBoolean("keepAwake",true))window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose{scanJob?.cancel();window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
    }
    BackHandler { if(sheet!=null)sheet=null else onClose() }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){uri ->
        if(uri!=null)scope.launch {try {withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { out ->
            out.appendLine(book.title);out.appendLine("Локальное распознавание KOMA. Результат требует проверки.")
            book.pages.indices.forEach { n -> engine.cached(book.id,n,language)?.let { r -> out.appendLine("\n— Страница ${n+1} —\n${r.text}") } }
        }};error="Распознанный текст сохранён"}catch(e:Exception){error=e.message}}
    }
    Scaffold(containerColor=Color(0xFF171A17),topBar={
        Row(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal=10.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onClose){Icon(Icons.Default.ArrowBack,"Назад",tint=Color.White)}
            Column(Modifier.weight(1f)){Text(book.title,fontSize=15.sp,fontWeight=FontWeight.Bold,color=Color.White,maxLines=1,overflow=TextOverflow.Ellipsis);Text("${book.category} · ${when(mode){"rtl"->"Справа налево";"vertical"->"Вертикально";else->"Слева направо"}}",fontSize=10.sp,color=Color(0xFFB2B8AD))}
            IconButton(onClick={save(book.copy(favorite=!book.favorite))}){Icon(if(book.favorite)Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,"В избранное",tint=if(book.favorite)Orange else Color.White)}
            IconButton(onClick={sheet="settings"}){Icon(Icons.Outlined.Tune,"Настройки чтения",tint=Color.White)}
        }
    },bottomBar={
        Surface(color=Color(0xFF232822)) {
            Column(Modifier.navigationBarsPadding().padding(horizontal=18.dp,vertical=10.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {
                    TextButton(onClick={if(result==null && !scanning)scan();sheet="ocr"},contentPadding=PaddingValues(horizontal=8.dp)) {
                        if(scanning)CircularProgressIndicator(Modifier.size(17.dp),strokeWidth=2.dp,color=Orange)else Icon(Icons.Outlined.AutoAwesome,null,Modifier.size(19.dp),tint=Orange)
                        Text(if(scanning)"  Распознаём…" else if(result!=null)"  Текст страницы" else "  Распознать",fontSize=12.sp,color=Color.White)
                    }
                    Spacer(Modifier.weight(1f))
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
                itemsIndexed(book.pages,key={n,_->n}) { n,path -> PageImage(path,engine,true,if(n==page && showRegions)result else null) }
            } else HorizontalPager(state=pager,reverseLayout=mode=="rtl",modifier=Modifier.fillMaxSize(),beyondViewportPageCount=0) { n -> PageImage(book.pages[n],engine,false,if(n==page && showRegions)result else null) }
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
                LanguageChooser(language){language=it}
                Text("Масштаб: щипок или двойное касание. Свайп — следующая страница. Исходное изображение не меняется.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=16.dp))
                Text("${book.pages.size} страниц · ${book.author}",fontSize=12.sp)
                TextButton(onClick={if(book.source.startsWith("https://"))runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(book.source)))}} ,enabled=book.source.startsWith("https://")){Icon(Icons.Outlined.OpenInNew,null,Modifier.size(16.dp));Text("  Источник и права")}
                TextButton(onClick={deleteConfirm=true}){Icon(Icons.Outlined.DeleteOutline,null,Modifier.size(18.dp));Text("  Удалить из библиотеки")}
            }
            "ocr" -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal=24.dp).padding(bottom=22.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Текст страницы ${page+1}",fontSize=23.sp,fontWeight=FontWeight.Bold);Text(result?.engine ?: "Распознавание на устройстве",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=5.dp))};Icon(Icons.Outlined.VerifiedUser,null,tint=MaterialTheme.colorScheme.secondary)}
                Spacer(Modifier.height(12.dp))
                LanguageChooser(language){language=it}
                if(scanning){LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical=16.dp));Text(if(batchTotal>0)"Обработано $batchProgress из $batchTotal страниц" else "Нейросеть читает изображение…",fontSize=12.sp)}
                var edit by remember(result){mutableStateOf(result?.text.orEmpty())}
                val fontPath=repo.prefs.getString("font_${book.category}",null)
                val font=remember(fontPath){if(fontPath!=null)runCatching{FontFamily(androidx.compose.ui.text.font.Typeface(android.graphics.Typeface.createFromFile(fontPath)))}.getOrNull()else null}
                if(result!=null) {
                    Text("${result!!.regions.size} областей · ${"%.1f".format(result!!.millis/1000f)} сек.",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=8.dp))
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
            "pages" -> {
                Text("Страницы",Modifier.padding(horizontal=24.dp),fontSize=24.sp,fontWeight=FontWeight.Bold)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max=540.dp).padding(horizontal=16.dp)) {
                    itemsIndexed(book.pages) { n,_ -> ListItem(headlineContent={Text("Страница ${n+1}")},supportingContent={Text(if(engine.cached(book.id,n,language)!=null)"Текст распознан" else "Оригинальная страница")},leadingContent={Icon(if(n==page)Icons.Filled.MenuBook else Icons.Outlined.Description,null,tint=if(n==page)Orange else MaterialTheme.colorScheme.onSurfaceVariant)},modifier=Modifier.clickable{scope.launch{if(mode=="vertical")scroll.scrollToItem(n)else pager.scrollToPage(n);sheet=null}}) }
                }
            }
        }
    }
    if(error!=null)AlertDialog(onDismissRequest={error=null},title={Text("KOMA")},text={Text(error!!)},confirmButton={TextButton(onClick={error=null}){Text("Понятно")}})
    if(deleteConfirm)AlertDialog(onDismissRequest={deleteConfirm=false},title={Text("Удалить книгу?")},text={Text("Копия в библиотеке будет удалена. Ваш исходный архив останется на месте.")},confirmButton={TextButton(onClick={onDelete(book);onClose()}){Text("Удалить")}},dismissButton={TextButton(onClick={deleteConfirm=false}){Text("Отмена")}})
}

@Composable
private fun PageImage(path:String,engine:KomaOcrEngine,vertical:Boolean,result:PageOcr?) {
    var bitmap by remember(path){mutableStateOf<android.graphics.Bitmap?>(null)}
    var error by remember(path){mutableStateOf<String?>(null)}
    var retry by remember(path){mutableIntStateOf(0)}
    var scale by remember(path){mutableFloatStateOf(1f)}
    var offset by remember(path){mutableStateOf(Offset.Zero)}
    LaunchedEffect(path,retry){try{bitmap=withContext(Dispatchers.IO){engine.bitmap(path)}}catch(e:Exception){error=e.message}}
    val b=bitmap
    if(b==null) Box(Modifier.fillMaxWidth().then(if(vertical)Modifier.height(320.dp)else Modifier.fillMaxHeight()),contentAlignment=Alignment.Center){if(error==null)CircularProgressIndicator(color=Orange)else TextButton(onClick={error=null;retry++}){Text("Не удалось открыть. Повторить")}}
    else BoxWithConstraints(Modifier.fillMaxWidth().then(if(vertical)Modifier.aspectRatio(b.width.toFloat()/b.height)else Modifier.fillMaxHeight()).clip(RoundedCornerShape(0.dp)),contentAlignment=Alignment.Center) {
        val ratio=b.width.toFloat()/b.height
        val width=if(vertical || maxWidth/maxHeight<ratio)maxWidth else maxHeight*ratio
        val height=width/ratio
        val transform=rememberTransformableState { zoom,pan,_-> scale=(scale*zoom).coerceIn(1f,5f);offset=if(scale>1f)offset+pan else Offset.Zero }
        Box(Modifier.size(width,height).graphicsLayer(scaleX=scale,scaleY=scale,translationX=offset.x,translationY=offset.y).transformable(state=transform,canPan={scale>1f}).pointerInput(path){detectTapGestures(onDoubleTap={scale=if(scale>1f)1f else 2.5f;offset=Offset.Zero})}) {
            Image(b.asImageBitmap(),"Страница",Modifier.fillMaxSize(),contentScale=ContentScale.FillBounds)
            if(result!=null)Canvas(Modifier.fillMaxSize()) { result.regions.forEach { r -> drawRect(Orange,Offset(r.left*size.width,r.top*size.height),Size((r.right-r.left)*size.width,(r.bottom-r.top)*size.height),style=Stroke(1.5.dp.toPx())) } }
        }
    }
}

@Composable
fun SectionLabel(text:String){Text(text,Modifier.padding(top=22.dp,bottom=9.dp),fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=1.3.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}

@Composable
fun LanguageChooser(selected:String,onSelect:(String)->Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){ocrLanguages.forEach { name->FilterChip(selected=selected==name,onClick={onSelect(name)},label={Text(name,fontSize=11.sp)},shape=RoundedCornerShape(10.dp))}}
}
