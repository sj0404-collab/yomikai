package eu.kanade.tachiyomi.ui.koma

import android.content.Intent
import android.net.Uri
import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

@Composable
fun CatalogScreen(repo:LibraryRepository,books:List<Book>,onAdded:(Book)->Unit,onMessage:(String)->Unit) {
    val scope=rememberCoroutineScope();val context=LocalContext.current
    var search by remember{mutableStateOf("")}
    var results by remember{mutableStateOf(curatedCatalog)}
    var loading by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}
    var downloading by remember{mutableStateOf<String?>(null)}
    var urlDialog by remember{mutableStateOf(false)}
    var url by remember{mutableStateOf("")}
    var category by remember{mutableStateOf("Манга")}
    fun find(){scope.launch{loading=true;error=null;try{results=repo.search(search)}catch(e:Exception){error="Каталог недоступен. Проверьте интернет и повторите."};loading=false}}
    LazyColumn(contentPadding=PaddingValues(start=22.dp,end=22.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { Column{Text("Найти свою историю",fontSize=29.sp,fontWeight=FontWeight.Bold,letterSpacing=(-.8).sp);Text("Открытые архивы. Новые открытия.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=7.dp))} }
        item { OutlinedTextField(search,{search=it},modifier=Modifier.fillMaxWidth(),placeholder={Text("Название книги",fontSize=13.sp)},leadingIcon={Icon(Icons.Outlined.Search,null)},trailingIcon={IconButton(onClick={find()}){Icon(Icons.Default.ArrowForward,"Найти")}},singleLine=true,shape=RoundedCornerShape(15.dp),keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={find()})) }
        item {
            Column(Modifier.fillMaxWidth().background(Color(0xFFE7EBDC),RoundedCornerShape(20.dp)).padding(20.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.Public,null,tint=Color(0xFF526647),modifier=Modifier.size(19.dp));Text("  INTERNET ARCHIVE",fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=1.sp,color=Color(0xFF526647))}
                Text("Классика вне времени",fontSize=21.sp,fontWeight=FontWeight.Bold,color=Ink,modifier=Modifier.padding(top=12.dp))
                Text("Оригинальные сканы, настоящие страницы.\nСкачайте книгу и читайте без интернета.",fontSize=12.sp,lineHeight=19.sp,color=Color(0xFF68745F),modifier=Modifier.padding(top=7.dp))
                TextButton(onClick={urlDialog=true},contentPadding=PaddingValues(0.dp)){Text("Добавить по своей ссылке",fontSize=12.sp,color=Color(0xFF526647));Icon(Icons.Default.ArrowForward,null,Modifier.padding(start=8.dp).size(15.dp),tint=Color(0xFF526647))}
            }
        }
        item {Row(verticalAlignment=Alignment.CenterVertically){Text(if(search.isBlank())"Архивная коллекция" else "Результаты поиска",fontSize=19.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));IconButton(onClick={search="";find()}){Icon(Icons.Outlined.Refresh,"Обновить",tint=MaterialTheme.colorScheme.onSurfaceVariant)}}}
        if(loading)item {Box(Modifier.fillMaxWidth().padding(32.dp),contentAlignment=Alignment.Center){CircularProgressIndicator()}}
        else if(error!=null)item{Column(Modifier.padding(20.dp)){Text(error!!,fontSize=13.sp);TextButton(onClick={find()}){Text("Повторить")}}}
        else if(results.isEmpty())item{Column(Modifier.padding(20.dp)){Icon(Icons.Outlined.SearchOff,null,tint=Orange);Text("История пока не нашлась",fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=12.dp));Text("Поиск ограничен архивными изданиями 1800–1930 годов. Попробуйте Hokusai или добавьте собственную ссылку.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp))}}
        else items(results,key={it.id}) { item ->
            val exists=books.any{it.source=="https://archive.org/details/${item.id}"}
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(MaterialTheme.colorScheme.surface).border(1.dp,MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(17.dp)).padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                AsyncImage(item.cover,item.title,Modifier.width(76.dp).height(105.dp).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceVariant),contentScale=ContentScale.Crop)
                Column(Modifier.weight(1f).padding(start=14.dp)) {
                    Text("${item.category.uppercase()}  ${item.year}",fontSize=8.sp,letterSpacing=.7.sp,color=MaterialTheme.colorScheme.secondary,fontWeight=FontWeight.Bold)
                    Text(item.title,fontSize=14.sp,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=6.dp))
                    Text(item.author,fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=5.dp))
                    Row(verticalAlignment=Alignment.CenterVertically){
                        TextButton(enabled=downloading==null && !exists,onClick={scope.launch {downloading=item.id;try{onAdded(repo.download(item));onMessage("Книга сохранена в локальную библиотеку")}catch(e:Exception){onMessage(e.message ?: "Не удалось скачать книгу")};downloading=null}},contentPadding=PaddingValues(0.dp)) {
                            if(downloading==item.id)CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=2.dp)else Icon(if(exists)Icons.Outlined.CheckCircle else Icons.Outlined.FileDownload,null,Modifier.size(15.dp))
                            Text(if(downloading==item.id)"  Скачиваем…" else if(exists)"  На вашей полке" else "  В библиотеку",fontSize=11.sp)
                        }
                        Spacer(Modifier.weight(1f));IconButton(onClick={runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://archive.org/details/${item.id}")))}} ,modifier=Modifier.size(28.dp)){Icon(Icons.Outlined.OpenInNew,"Источник",Modifier.size(15.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)}
                    }
                }
            }
        }
        item{Text("Доступность зависит от источника. Проверяйте права на скачивание в вашей стране. Для авторских библиотек используйте собственные легальные архивы и HTTPS-ссылки.",fontSize=10.sp,lineHeight=16.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
    if(urlDialog)AlertDialog(onDismissRequest={if(downloading==null)urlDialog=false},title={Text("Книга по ссылке")},text={Column{Text("Прямая HTTPS-ссылка на CBZ, CBR или PDF, до 200 МБ.",fontSize=12.sp);OutlinedTextField(url,{url=it},label={Text("https://…")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),modifier=Modifier.padding(top=12.dp));Row(Modifier.horizontalScroll(rememberScrollState())){categories.drop(1).forEach{c->FilterChip(selected=category==c,onClick={category=c},label={Text(c,fontSize=10.sp)},modifier=Modifier.padding(end=5.dp))}};if(downloading=="url")LinearProgressIndicator(Modifier.fillMaxWidth())}},confirmButton={TextButton(enabled=url.startsWith("https://")&&downloading==null,onClick={scope.launch{downloading="url";try{onAdded(repo.downloadUrl(url,category));urlDialog=false;url="";onMessage("Книга добавлена")}catch(e:Exception){onMessage(e.message ?: "Ошибка загрузки")};downloading=null}}){Text("Скачать")}},dismissButton={TextButton(enabled=downloading==null,onClick={urlDialog=false}){Text("Отмена")}})
}

@Composable
fun AiStudioScreen(repo:LibraryRepository,engine:KomaOcrEngine,onMessage:(String)->Unit) {
    val scope=rememberCoroutineScope();val context=LocalContext.current
    var language by remember{mutableStateOf(repo.prefs.getString("ocrLanguage","Авто") ?: "Авто")}
    var auto by remember{mutableStateOf(repo.prefs.getBoolean("autoOcr",false))}
    var category by remember{mutableStateOf("Манга")}
    var fontVersion by remember{mutableIntStateOf(0)}
    var downloading by remember{mutableStateOf(false)}
    var style by remember(category){mutableStateOf(repo.prefs.getString("style_$category","Диалоги") ?: "Диалоги")}
    val fontPath=remember(category,fontVersion){repo.prefs.getString("font_$category",null)}
    val font=remember(fontPath){if(fontPath!=null)runCatching{FontFamily(androidx.compose.ui.text.font.Typeface(Typeface.createFromFile(fontPath)))}.getOrNull()else null}
    val fontName=remember(category,fontVersion){repo.prefs.getString("fontname_$category","Системный шрифт") ?: "Системный шрифт"}
    fun install(f:File,name:String){Typeface.createFromFile(f);repo.prefs.edit().putString("font_$category",f.absolutePath).putString("fontname_$category",name).apply();fontVersion++}
    val fontPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)scope.launch{try{val name=repo.displayName(uri);val f=File(context.filesDir,"font_${category}_${System.currentTimeMillis()}.ttf");withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri)?.use { input->f.outputStream().use{input.copyTo(it)} } ?: error("Не удалось открыть шрифт")};install(f,name);onMessage("Шрифт подключён для категории «$category»")}catch(e:Exception){onMessage("Не удалось подключить шрифт. Нужен корректный TTF или OTF.")}}}
    LazyColumn(contentPadding=PaddingValues(start=22.dp,end=22.dp,bottom=28.dp)) {
        item {Text("AI-студия",fontSize=30.sp,fontWeight=FontWeight.Bold,letterSpacing=(-.8).sp);Text("Больше смысла в каждой странице",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=7.dp,bottom=22.dp))}
        item {
            Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF303A32),Color(0xFF53614B))),RoundedCornerShape(22.dp)).padding(22.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.Memory,null,tint=Color(0xFFE8EBD6),modifier=Modifier.size(24.dp));Spacer(Modifier.weight(1f));Text("ON-DEVICE",Modifier.background(Color.White.copy(.12f),RoundedCornerShape(6.dp)).padding(horizontal=9.dp,vertical=5.dp),fontSize=8.sp,letterSpacing=1.4.sp,color=Color.White)}
                Text("Всё остаётся\nмежду вами и историей.",fontSize=24.sp,lineHeight=30.sp,fontWeight=FontWeight.Bold,color=Color.White,modifier=Modifier.padding(top=16.dp))
                Text("Японский, латиница и кириллица — прямо в приложении.\nСтраницы не отправляются на сервер.",fontSize=12.sp,lineHeight=19.sp,color=Color(0xFFD0D9C6),modifier=Modifier.padding(top=12.dp))
            }
        }
        item{SectionLabel("РАСПОЗНАВАНИЕ");Text("Язык оригинала",fontSize=17.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(8.dp));LanguageChooser(language){language=it;repo.prefs.edit().putString("ocrLanguage",it).apply()};Text("Авто выбирает движок по категории: манга — японский, манхва — корейский, маньхуа — китайский, комиксы — латиница. Для русского выберите кириллицу.",fontSize=11.sp,lineHeight=17.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=7.dp,bottom=12.dp))}
        item{SettingsSwitch(Icons.Outlined.DocumentScanner,"Распознавать при чтении","Автоматически для каждой открытой страницы",auto){auto=it;repo.prefs.edit().putBoolean("autoOcr",it).apply()}}
        item{Spacer(Modifier.height(14.dp));EngineRow("ML Kit Text Recognition","Латиница · 日本語 · 中文 · 한국어",Icons.Outlined.Translate);Spacer(Modifier.height(8.dp));EngineRow("PaddleOCR + ONNX","Кириллица · детектор + CTC-распознавание",Icons.Outlined.Memory)}
        item{SectionLabel("ТИПОГРАФИКА ПО КАТЕГОРИЯМ");Text("У каждой истории свой голос",fontSize=18.sp,fontWeight=FontWeight.Bold);Row(Modifier.horizontalScroll(rememberScrollState()).padding(top=9.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){categories.drop(1).forEach { c->FilterChip(selected=category==c,onClick={category=c},label={Text(c,fontSize=11.sp)})}}}
        item{
            Column(Modifier.fillMaxWidth().padding(top=12.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(18.dp)){
                Text("${category.uppercase()} / ${style.uppercase()}",fontSize=9.sp,letterSpacing=1.5.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.fillMaxWidth().padding(vertical=16.dp).background(MaterialTheme.colorScheme.surface,when(style){"Диалоги"->RoundedCornerShape(50);"Рукопись"->RoundedCornerShape(3.dp);else->RoundedCornerShape(12.dp)}).border(1.dp,MaterialTheme.colorScheme.outlineVariant,when(style){"Диалоги"->RoundedCornerShape(50);"Рукопись"->RoundedCornerShape(3.dp);else->RoundedCornerShape(12.dp)}).padding(22.dp),contentAlignment=Alignment.Center){Text("У каждой истории\nесть продолжение…",fontSize=19.sp,lineHeight=27.sp,fontFamily=font ?: when(category){"Манга"->FontFamily.Serif;"Маньхуа"->FontFamily.Cursive;"Комиксы"->FontFamily.Monospace;else->FontFamily.SansSerif},textAlign=androidx.compose.ui.text.style.TextAlign.Center)}
                Text(fontName,fontSize=11.sp,fontWeight=FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp)){listOf("Диалоги","Без облачка","Рукопись").forEach { s->FilterChip(selected=style==s,onClick={style=s;repo.prefs.edit().putString("style_$category",s).apply()},label={Text(s,fontSize=9.sp)})}}
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    OutlinedButton(onClick={fontPicker.launch(arrayOf("*/*"))},modifier=Modifier.weight(1f),contentPadding=PaddingValues(horizontal=8.dp),shape=RoundedCornerShape(10.dp)){Icon(Icons.Outlined.FileUpload,null,Modifier.size(15.dp));Text("  TTF / OTF",fontSize=10.sp)}
                    Button(enabled=!downloading,onClick={scope.launch{downloading=true;try{val f=File(context.filesDir,"external_caveat.ttf");withContext(Dispatchers.IO){val conn=URL("https://raw.githubusercontent.com/google/fonts/main/ofl/caveat/Caveat%5Bwght%5D.ttf").openConnection();conn.connectTimeout=15000;conn.readTimeout=25000;conn.getInputStream().use{ i->f.outputStream().use{i.copyTo(it)} }};install(f,"Caveat · Google Fonts");onMessage("Caveat загружен. Кириллица и латиница доступны для отображения текста.")}catch(e:Exception){onMessage("Не удалось загрузить шрифт. Проверьте интернет.")};downloading=false}},modifier=Modifier.weight(1f),contentPadding=PaddingValues(horizontal=8.dp),shape=RoundedCornerShape(10.dp)){if(downloading)CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=2.dp)else Icon(Icons.Outlined.CloudDownload,null,Modifier.size(15.dp));Text("  Google Fonts",fontSize=10.sp)}
                }
                if(fontPath!=null)TextButton(onClick={repo.prefs.edit().remove("font_$category").remove("fontname_$category").apply();fontVersion++}){Text("Вернуть системный шрифт",fontSize=10.sp)}
            }
        }
        item {Text("Шрифты внешние, не встроены в нейросеть. Они меняют отображение результата, а не знания OCR. Покрытие кириллицы и латиницы зависит от выбранного файла.",fontSize=11.sp,lineHeight=17.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=14.dp))}
        item {SectionLabel("ВАЖНО О ТОЧНОСТИ");Text("Распознавание настоящее, но не безошибочное. Рукописи, декоративные эффекты и старые японские начертания требуют ручной проверки. Детектор ищет текст по всей странице, в том числе вне облачков. Специальной модели сегментации панелей и классификации облачков здесь нет.",fontSize=12.sp,lineHeight=19.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}

@Composable
private fun EngineRow(title:String,subtitle:String,icon:ImageVector){Row(Modifier.fillMaxWidth().border(1.dp,MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(14.dp)).padding(14.dp),verticalAlignment=Alignment.CenterVertically){Icon(icon,null,tint=MaterialTheme.colorScheme.secondary,modifier=Modifier.size(24.dp));Column(Modifier.weight(1f).padding(horizontal=12.dp)){Text(title,fontSize=13.sp,fontWeight=FontWeight.SemiBold);Text(subtitle,fontSize=9.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))};Icon(Icons.Outlined.CheckCircle,null,tint=MaterialTheme.colorScheme.secondary,modifier=Modifier.size(18.dp))}}

@Composable
fun SettingsScreenKoma(repo:LibraryRepository,engine:KomaOcrEngine,books:List<Book>,theme:String,onTheme:(String)->Unit,onMessage:(String)->Unit){
    val context=LocalContext.current;var awake by remember{mutableStateOf(repo.prefs.getBoolean("keepAwake",true))};var cache by remember{mutableIntStateOf(engine.cacheCount())};var about by remember{mutableStateOf(false)}
    LazyColumn(contentPadding=PaddingValues(start=22.dp,end=22.dp,bottom=28.dp)) {
        item{Text("В своём ритме",fontSize=30.sp,fontWeight=FontWeight.Bold,letterSpacing=(-.8).sp);Text("Маленькие детали большого чтения",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=7.dp,bottom=14.dp))}
        item{SectionLabel("ВНЕШНИЙ ВИД");Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth()){listOf("light" to "Бумага","dark" to "Ночь","system" to "Система").forEach{(value,title)->Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).border(if(theme==value)2.dp else 1.dp,if(theme==value)Orange else MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(16.dp)).clickable{onTheme(value)}.padding(vertical=18.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(when(value){"light"->Icons.Outlined.LightMode;"dark"->Icons.Outlined.DarkMode;else->Icons.Outlined.BrightnessAuto},null,tint=if(theme==value)Orange else MaterialTheme.colorScheme.onSurfaceVariant);Text(title,fontSize=11.sp,modifier=Modifier.padding(top=8.dp))}}}}
        item{SectionLabel("ЧИТАЛКА");SettingsSwitch(Icons.Outlined.WbSunny,"Не гасить экран","Пока открыта книга",awake){awake=it;repo.prefs.edit().putBoolean("keepAwake",it).apply()}}
        item{SectionLabel("НА УСТРОЙСТВЕ");Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(16.dp)).padding(18.dp),horizontalArrangement=Arrangement.SpaceBetween){Stat("${books.size}","книги");Stat("${books.sumOf{it.pages.size}}","страниц");Stat("$cache","OCR-страниц")}}
        item{ActionRow(Icons.Outlined.CleaningServices,"Очистить кэш OCR","Книги и исходные страницы останутся"){engine.clear();cache=0;onMessage("Кэш распознавания очищен")}}
        item{SectionLabel("О ПРИЛОЖЕНИИ");ActionRow(Icons.Outlined.Info,"KOMA · версия 1.0","Читалка без лишнего шума"){about=true};ActionRow(Icons.Outlined.Code,"Исходники и APK","Проект Kotlin + Compose · GoFile"){runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://gofile.io/d/7M33MUei")))}};ActionRow(Icons.Outlined.Language,"Открыть Internet Archive","Источники и права на архивные книги"){runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://archive.org")))}}}
        item{Column(Modifier.fillMaxWidth().padding(top=26.dp),horizontalAlignment=Alignment.CenterHorizontally){Text("KOMA",fontSize=25.sp,letterSpacing=4.sp,fontWeight=FontWeight.Black,color=MaterialTheme.colorScheme.onSurfaceVariant.copy(.5f));Text("Твоя история. Твой ритм.",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=6.dp))}}
    }
    if(about)AlertDialog(onDismissRequest={about=false},title={Text("Сделано для историй")},text={Column(Modifier.verticalScroll(rememberScrollState())){Text("KOMA 1.0\n\nCBZ (ZIP), CBR (RAR4) и PDF. RAR5 и защищённые архивы не поддерживаются.\n\nOCR работает локально: ML Kit (японский, латиница) и PaddleOCR / ONNX (кириллица). Точность не гарантируется.\n\nВ комплекте — полные архивные сканы: «Хокусай. Манга», тома II и VI (Кацусика Хокусай), Blue Beetle № 15, 1942 (Holyoke Publishing). Источники доступны в меню каждой книги.\n\nManrope и Caveat — SIL Open Font License. PaddleOCR — Apache 2.0. ONNX Runtime — MIT. Junrar — UnRAR license. Обложки книг — страницы архивов; декоративный баннер создан AI и не является страницей манги.\n\nПриложение не отправляет изображения на сервер и не требует аккаунта. Сеть нужна только для каталога, загрузки книг и внешних шрифтов.",fontSize=12.sp,lineHeight=19.sp)}},confirmButton={TextButton(onClick={about=false}){Text("Продолжить историю")}})
}

@Composable
fun SettingsSwitch(icon:ImageVector,title:String,description:String,value:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Icon(icon,null,Modifier.size(21.dp),tint=MaterialTheme.colorScheme.secondary);Column(Modifier.weight(1f).padding(horizontal=13.dp)){Text(title,fontSize=12.sp,fontWeight=FontWeight.SemiBold);Text(description,fontSize=10.sp,lineHeight=15.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))};Switch(value,onCheckedChange=onChange)}}
@Composable
private fun ActionRow(icon:ImageVector,title:String,description:String,onClick:()->Unit){Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick=onClick).padding(vertical=16.dp),verticalAlignment=Alignment.CenterVertically){Icon(icon,null,Modifier.size(21.dp),tint=MaterialTheme.colorScheme.secondary);Column(Modifier.weight(1f).padding(horizontal=13.dp)){Text(title,fontSize=13.sp,fontWeight=FontWeight.SemiBold);Text(description,fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))};Icon(Icons.Default.ChevronRight,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)}}
@Composable
private fun Stat(value:String,label:String){Column(horizontalAlignment=Alignment.CenterHorizontally){Text(value,fontSize=23.sp,fontWeight=FontWeight.Bold);Text(label,fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
