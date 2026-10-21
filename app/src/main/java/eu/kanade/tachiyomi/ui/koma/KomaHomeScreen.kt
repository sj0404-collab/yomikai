package eu.kanade.tachiyomi.ui.koma

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.launch

val Orange = Color(0xFFE7784C)
val Ink = Color(0xFF252721)

@Composable
fun KomaSection(onReaderOpen: (Boolean) -> Unit = {}) {
    val context=LocalContext.current
    val repo=remember { LibraryRepository(context) }
    val engine=remember { KomaOcrEngine(context) }
    var books by remember { mutableStateOf(repo.load()) }
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf("Все") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Book?>(null) }
    var theme by remember { mutableStateOf(repo.prefs.getString("theme","system") ?: "system") }
    val scope=rememberCoroutineScope()
    fun update(list: List<Book>) { books=list;repo.save(list) }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) scope.launch { busy=true;try { val b=repo.import(uri,if(category=="Все")"Манга" else category);update(listOf(b)+books);message="${b.title} добавлена в библиотеку" }catch(e:Throwable){message=e.message ?: "Ошибка импорта: ${e::class.simpleName}"};busy=false }
    }
    val dark=theme=="dark" || (theme=="system" && isSystemInDarkTheme())
    LaunchedEffect(selected) { onReaderOpen(selected!=null) }
    androidx.activity.compose.BackHandler(enabled=selected==null && (tab!=0 || searching)) { if(searching){searching=false;query=""}else tab=0 }
    KomaTheme(darkTheme=dark) {
        if(selected!=null) {
            KomaReaderScreen(selected!!,repo,engine,onClose={selected=null},onUpdate={b->update(books.map{if(it.id==b.id)b else it})},onDelete={b->repo.delete(b);update(books.filter{it.id!=b.id})})
        } else Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("Библиотека" to Icons.Outlined.AutoStories,"Каталог" to Icons.Outlined.Explore,"AI-студия" to Icons.Outlined.AutoAwesome,"Настройки" to Icons.Outlined.Tune).forEachIndexed { i,(title,icon) ->
                    FilterChip(selected=tab==i,onClick={tab=i;if(i!=0)searching=false},label={Text(title,fontSize=12.sp)},leadingIcon={Icon(icon,null,Modifier.size(16.dp))},shape=RoundedCornerShape(12.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal=22.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(35.dp).clip(RoundedCornerShape(10.dp)).background(Ink),contentAlignment=Alignment.Center) { Icon(Icons.Outlined.AutoStories,null,tint=Color(0xFFF8F6F0),modifier=Modifier.size(22.dp));Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(5.dp).background(Orange,CircleShape)) }
                Text("KOMA",Modifier.padding(start=10.dp).weight(1f),fontSize=24.sp,fontWeight=FontWeight.Black,letterSpacing=2.sp)
                IconButton(onClick={searching=!searching;tab=0},modifier=Modifier.size(40.dp).border(1.dp,MaterialTheme.colorScheme.outlineVariant,CircleShape)) { Icon(Icons.Outlined.Search,"Поиск",Modifier.size(21.dp)) }
                Spacer(Modifier.width(10.dp))
                FilledIconButton(onClick={picker.launch(arrayOf("*/*"))},modifier=Modifier.size(40.dp),shape=RoundedCornerShape(13.dp),colors=IconButtonDefaults.filledIconButtonColors(containerColor=Orange)) { Icon(Icons.Default.Add,"Добавить архив") }
            }
            if(searching) OutlinedTextField(query,{query=it},placeholder={Text("Найти на своей полке")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true,modifier=Modifier.fillMaxWidth().padding(horizontal=22.dp),shape=RoundedCornerShape(16.dp))
            when(tab) {
                0 -> LibraryScreen(books,query,category,{category=it},{selected=it},{tab=1},{tab=2},{picker.launch(arrayOf("*/*"))})
                1 -> CatalogScreen(repo,books,onAdded={b->update(listOf(b)+books)},onMessage={message=it})
                2 -> AiStudioScreen(repo,engine){message=it}
                3 -> SettingsScreenKoma(repo,engine,books,theme,onTheme={value->theme=value;repo.prefs.edit().putString("theme",value).apply()},onMessage={message=it})
            }
        }
        if(busy) AlertDialog(onDismissRequest={},title={Text("Добавляем книгу")},text={Row(verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.dp);Spacer(Modifier.width(16.dp));Text("Распаковываем настоящие страницы…")}},confirmButton={})
        if(message!=null) AlertDialog(onDismissRequest={message=null},title={Text("KOMA")},text={Text(message!!)},confirmButton={TextButton(onClick={message=null}){Text("Понятно")}})
    }
}

@Composable
fun LibraryScreen(books: List<Book>,query: String,category: String,onCategory: (String)->Unit,onBook: (Book)->Unit,onCatalog: ()->Unit,onAi: ()->Unit,onImport: ()->Unit) {
    var reversed by remember { mutableStateOf(false) }
    var favorites by remember { mutableStateOf(false) }
    val filtered=books.filter { (category=="Все" || it.category==category) && it.title.contains(query,true) && (!favorites || it.favorite) }.let { if(reversed)it.reversed() else it }
    LazyColumn(contentPadding=PaddingValues(bottom=24.dp)) {
        item {
            Row(Modifier.padding(start=22.dp,end=22.dp,top=2.dp,bottom=13.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("Библиотека",fontWeight=FontWeight.Bold,fontSize=30.sp,letterSpacing=(-1).sp,modifier=Modifier.weight(1f))
                Row(verticalAlignment=Alignment.CenterVertically) { Box(Modifier.size(5.dp).background(Color(0xFF7B8D6B),CircleShape));Text("  Локально",fontSize=11.sp,color=MaterialTheme.colorScheme.secondary,fontWeight=FontWeight.Medium) }
            }
            Row(Modifier.padding(horizontal=22.dp).fillMaxWidth().height(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.surface),contentAlignment=Alignment.Center) { Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.FolderOpen,null,Modifier.size(17.dp));Text("  Моя библиотека",fontSize=12.sp,fontWeight=FontWeight.SemiBold)} }
                Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick=onCatalog),contentAlignment=Alignment.Center) { Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.Language,null,Modifier.size(17.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant);Text("  Онлайн",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)} }
            }
            LazyRow(contentPadding=PaddingValues(horizontal=22.dp,vertical=13.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                items(categories) { cat ->
                    Box(Modifier.clip(RoundedCornerShape(50)).background(if(category==cat) Ink else Color.Transparent).border(1.dp,if(category==cat) Ink else MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(50)).clickable{onCategory(cat)}.padding(horizontal=16.dp,vertical=8.dp)) { Text(cat,fontSize=12.sp,fontWeight=FontWeight.Medium,color=if(category==cat) Color.White else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Box(Modifier.padding(horizontal=22.dp).fillMaxWidth().height(177.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFF2B302C))) {
                Image(painterResource(R.drawable.koma_reading_banner),null,Modifier.fillMaxHeight().fillMaxWidth(.61f).align(Alignment.CenterEnd),contentScale=ContentScale.Crop,alignment=Alignment.TopCenter)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xFF2B302C),Color(0xFF2B302C).copy(.97f),Color.Transparent),endX=780f)))
                Column(Modifier.padding(19.dp).fillMaxHeight(),verticalArrangement=Arrangement.SpaceBetween) {
                    Text(if(books.any{it.progress>0})"ПРОДОЛЖИМ ИСТОРИЮ" else "ВРЕМЯ ДЛЯ ИСТОРИЙ",fontSize=9.sp,letterSpacing=1.8.sp,color=Color(0xFFCED2C1),fontWeight=FontWeight.SemiBold)
                    Text("Ещё одна\nглава.",fontSize=29.sp,lineHeight=32.sp,fontWeight=FontWeight.Bold,color=Color(0xFFFFF9EE),letterSpacing=(-.7).sp)
                    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Orange).clickable { books.firstOrNull { it.progress>0 }?.let(onBook) ?: books.firstOrNull()?.let(onBook) ?: onImport() }.padding(horizontal=13.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Default.PlayArrow,null,tint=Color.White,modifier=Modifier.size(15.dp));Text(if(books.any{it.progress>0})"Продолжить" else "Начать читать",color=Color.White,fontSize=11.sp,fontWeight=FontWeight.SemiBold);Spacer(Modifier.width(12.dp));Icon(Icons.Default.ArrowForward,null,tint=Color.White,modifier=Modifier.size(15.dp))
                    }
                }
                Text("読む",Modifier.align(Alignment.BottomEnd).padding(16.dp),fontSize=22.sp,color=Color.White.copy(.7f))
            }
            Row(Modifier.padding(horizontal=22.dp,vertical=12.dp).fillMaxWidth().clip(RoundedCornerShape(15.dp)).border(1.dp,MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(15.dp)).clickable(onClick=onAi).padding(10.dp),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).background(Orange.copy(.1f),RoundedCornerShape(11.dp)),contentAlignment=Alignment.Center){Icon(Icons.Outlined.AutoAwesome,null,tint=Orange,modifier=Modifier.size(20.dp))}
                Column(Modifier.weight(1f).padding(horizontal=11.dp)) { Text("Текст оживает",fontSize=12.sp,fontWeight=FontWeight.Bold);Text("Локальный AI. Без облака.",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=3.dp)) }
                Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(5.dp)).padding(horizontal=6.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(4.dp).background(Color(0xFF7B8D6B),CircleShape));Text("  OFFLINE",fontSize=8.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.secondary)}
                Icon(Icons.Default.ChevronRight,null,Modifier.padding(start=6.dp).size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.padding(start=22.dp,end=16.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(if(favorites)"Избранное" else "На моей полке",fontWeight=FontWeight.Bold,fontSize=19.sp)
                Text("${filtered.size}",Modifier.padding(start=9.dp).background(MaterialTheme.colorScheme.surfaceVariant,CircleShape).padding(horizontal=8.dp,vertical=3.dp),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f));IconButton(onClick={favorites=!favorites},Modifier.size(32.dp)){Icon(if(favorites)Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,"Избранное",Modifier.size(19.dp),tint=if(favorites)Orange else MaterialTheme.colorScheme.onSurfaceVariant)}
                IconButton(onClick={reversed=!reversed},Modifier.size(32.dp)){Icon(Icons.Outlined.SwapVert,"Изменить порядок",Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
        }
        if(filtered.isEmpty()) item { Column(Modifier.fillMaxWidth().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally) { Icon(Icons.Outlined.MenuBook,null,Modifier.size(36.dp),tint=Orange);Spacer(Modifier.height(12.dp));Text("Здесь начнётся новая история",fontWeight=FontWeight.SemiBold);Text("Добавьте CBZ, CBR или PDF",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);TextButton(onClick=onImport){Text("Добавить книгу")}} }
        items(filtered.chunked(2)) { row ->
            Row(Modifier.padding(horizontal=22.dp).padding(bottom=20.dp),horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                row.forEach { book -> BookCard(book,Modifier.weight(1f)){onBook(book)} }
                if(row.size==1) Spacer(Modifier.weight(1f))
            }
        }
        item { Text("CBZ / CBR / PDF  ·  Истории всегда рядом",Modifier.fillMaxWidth().padding(8.dp),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=androidx.compose.ui.text.style.TextAlign.Center) }
    }
}

@Composable
fun BookCard(book: Book,modifier: Modifier=Modifier,onClick: ()->Unit) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick=onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(.73f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            AsyncImage(model=book.cover,contentDescription=book.title,modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(.35f)),startY=230f)))
            Text(book.category.uppercase(),Modifier.align(Alignment.TopStart).padding(9.dp).background(Color(0xFFFBF7EA).copy(.94f),RoundedCornerShape(5.dp)).padding(horizontal=7.dp,vertical=4.dp),fontSize=8.sp,fontWeight=FontWeight.Bold,letterSpacing=.7.sp,color=Ink)
            Row(Modifier.align(Alignment.BottomStart).padding(10.dp),verticalAlignment=Alignment.CenterVertically) { Icon(Icons.Outlined.Description,null,Modifier.size(12.dp),tint=Color.White);Text(" ${book.pages.size} стр.",fontSize=10.sp,color=Color.White) }
            if(book.progress>0) LinearProgressIndicator(progress={ (book.progress+1f)/book.pages.size },modifier=Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter),color=Orange,trackColor=Color.Transparent)
        }
        Text(book.title,Modifier.padding(top=10.dp),fontSize=13.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
        Text(book.author,Modifier.padding(top=4.dp),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}
