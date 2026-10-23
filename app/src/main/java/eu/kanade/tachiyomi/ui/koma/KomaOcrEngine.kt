package eu.kanade.tachiyomi.ui.koma

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.*

// Кириллица первой: библиотека русская, и это выбор по умолчанию.
val ocrLanguages = listOf("Кириллица", "Авто", "Японский", "Латиница")
data class OcrRegion(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float, val confidence: Float? = null, val kind: String = "Текст") {
    fun json()=JSONObject().put("text",text).put("l",left).put("t",top).put("r",right).put("b",bottom).put("confidence",confidence).put("kind",kind)
}
data class PageOcr(
    val regions: List<OcrRegion>,
    val engine: String,
    val millis: Long,
    val edited: String?=null,
    /** Панели страницы в порядке чтения (см. [KomaPanels]). Пусто — рамок нет. */
    val panels: List<KomaPanels.Box> = emptyList(),
) {
    val text: String get() = edited ?: regions.joinToString("\n") { it.text }
    fun json()=JSONObject().put("regions",JSONArray().apply { regions.forEach { put(it.json()) } }).put("engine",engine).put("ms",millis).put("edited",edited)
        .put("panels",JSONArray().apply { panels.forEach { put(JSONArray().put(it.left).put(it.top).put(it.right).put(it.bottom)) } })
    companion object {
        fun from(j:JSONObject):PageOcr = PageOcr(
            j.getJSONArray("regions").let { a -> List(a.length()){ val r=a.getJSONObject(it);OcrRegion(r.getString("text"),r.getDouble("l").toFloat(),r.getDouble("t").toFloat(),r.getDouble("r").toFloat(),r.getDouble("b").toFloat(),if(r.isNull("confidence"))null else r.getDouble("confidence").toFloat(),r.optString("kind","Текст")) } },
            j.getString("engine"),
            j.getLong("ms"),
            if(j.isNull("edited"))null else j.getString("edited"),
            j.optJSONArray("panels")?.let { a -> List(a.length()){ val p=a.getJSONArray(it); KomaPanels.Box(p.getDouble(0).toFloat(),p.getDouble(1).toFloat(),p.getDouble(2).toFloat(),p.getDouble(3).toFloat()) } } ?: emptyList(),
        )
    }
}

/** Как движок называется в интерфейсе: ровно как в исходниках KOMA. */
const val ENGINE_NAME="PaddleOCR · кириллица"
/** Версия разбора страницы: меняется вместе с логикой порядка чтения. */
const val READ_ORDER_VERSION="10"

class KomaOcrEngine(private val context: Context) {
    private companion object {
        /** Сетка покрытия страницы для добора потерянных вторым проходом кусков. */
        const val GRID = 200
    }

    /**
     * Словарь языковой починки читается в ФОНЕ: 250 000 слов, на телефоне это
     * заметная работа, и первая страница не должна её ждать. Пока словарь не
     * готов, [KomaWords] ничего не правит — текст показывается как распознан.
     */
    init { thread(name="koma-words", isDaemon=true) { warmWords() } }

    private fun warmWords() {
        if (KomaWords.isReady()) return
        runCatching {
            context.assets.open("koma/dict/ru_250k.txt.gz").use { raw ->
                GZIPInputStream(raw).bufferedReader().use { reader -> KomaWords.load(reader.lineSequence()) }
            }
        }
    }

    private val mutex=Mutex()
    private val root=File(context.filesDir,"ocr").apply { mkdirs() }
    private val mlModels=mutableMapOf<String,TextRecognizer>()
    private var det: OrtSession?=null
    private var rec: OrtSession?=null
    private val env by lazy { OrtEnvironment.getEnvironment() }
    private val dictionary by lazy { listOf("")+context.assets.open("koma/models/cyrillic.txt").bufferedReader().readLines()+listOf(" ") }
    // Версия разбора в имени файла: после правок склейки блоков старый кэш
    // отдавал бы прежние разрезанные реплики, и обновление выглядело бы так,
    // будто ничего не изменилось.
    private fun cacheFile(id:String,page:Int,language:String)=File(root,"${id}_${page}_${language}_$READ_ORDER_VERSION.json")
    fun cached(id:String,page:Int,language:String):PageOcr? = runCatching { PageOcr.from(JSONObject(cacheFile(id,page,language).readText())) }.getOrNull()
    fun store(id:String,page:Int,language:String,result:PageOcr){cacheFile(id,page,language).writeText(result.json().toString())}
    fun cacheCount():Int=root.listFiles()?.size ?: 0
    fun clear(){root.listFiles()?.forEach{it.delete()}}
    /**
     * Кэш декодированных страниц.
     *
     * Битмап нужен дважды: экрану для отрисовки и движку для распознавания.
     * Раньше страница декодировалась каждый раз заново — при чтении это
     * заметная пауза на каждой странице. Кэш маленький: три страницы, чтобы
     * не съесть память на длинной ленте.
     */
    private val pageCache = object : android.util.LruCache<String, Bitmap>(3) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    fun bitmap(path:String):Bitmap {
        pageCache.get(path)?.takeIf { !it.isRecycled }?.let { return it }
        return decode(path).also { runCatching { pageCache.put(path, it) } }
    }

    private fun decode(path:String):Bitmap {
        fun stream()=if(path.startsWith("file:///android_asset/")) context.assets.open(path.removePrefix("file:///android_asset/")) else File(path.removePrefix("file://")).inputStream()
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        stream().use{BitmapFactory.decodeStream(it,null,bounds)}
        if(bounds.outWidth<=0 || bounds.outHeight<=0) throw IllegalStateException("Не удалось прочитать изображение: $path")
        // 10 Мпкс — это ~40 МБ на страницу в ARGB_8888. Раньше допускалось
        // 20 Мпкс (80 МБ): на телефоне с 3 ГБ такой кадр вместе с OOM давал
        // вылет при распознавании. Плюс RGB_565 на больших кадрах: цвет для
        // чтения текста не нужен, а память вдвое меньше.
        var sample=1
        while(bounds.outWidth.toLong()*bounds.outHeight/(sample*sample)>10_000_000L || bounds.outWidth/sample>2800)sample*=2
        val options=BitmapFactory.Options().apply{inSampleSize=sample}
        if(bounds.outWidth/sample>1400)options.inPreferredConfig=Bitmap.Config.RGB_565
        return stream().use { BitmapFactory.decodeStream(it,null,options) } ?: throw IllegalStateException("Повреждённая страница: $path")
    }
    /**
     * Увеличение страницы вдвое перед распознаванием.
     *
     * Мелкий шрифт на странице 700–1200 px по ширине модель читает плохо:
     * путает «ЕЙ»/«5», «ЛУЧШЕ»/«ЛУ4ШЕ», теряет окончания. Увеличение вдвое
     * подняло качество в лаборатории (lab/verify.py). Чтобы не съесть память,
     * увеличиваем только небольшие страницы и не больше 12 Мпкс.
     */
    private fun upscaleForOcr(source:Bitmap):Bitmap {
        val longSide=max(source.width,source.height)
        if(longSide>=1600) return source
        val targetWidth=source.width*2
        val targetHeight=source.height*2
        if(targetWidth.toLong()*targetHeight>12_000_000L) return source
        return try {
            Bitmap.createScaledBitmap(source,targetWidth,targetHeight,true)
        } catch(t:Throwable) {
            source
        }
    }

    /** Последняя ошибка распознавания в читалке: её показывает экран. */
    var lastError:String?=null
        private set

    private fun describe(t:Throwable):String = when(t) {
        is OutOfMemoryError -> "Не хватило памяти на страницу. Закройте другие приложения и повторите."
        is UnsatisfiedLinkError -> "Не загрузилось ядро ONNX для этого телефона: ${t.message}"
        else -> listOfNotNull(t::class.simpleName, t.message).joinToString(": ").ifBlank { "неизвестная ошибка" }
    }

    suspend fun recognize(book:Book,page:Int,language:String,force:Boolean=false):PageOcr = withContext(Dispatchers.Default) {
        mutex.withLock {
            lastError=null
            val start=System.currentTimeMillis()
            if(!force) cached(book.id,page,language)?.let{return@withLock it}
            val path=book.pages.getOrNull(page)
                ?: run { lastError="Страница ${page+1} отсутствует в файле"; return@withLock PageOcr(emptyList(),ENGINE_NAME,System.currentTimeMillis()-start) }
            val decoded=try { bitmap(path) } catch(t:Throwable) { lastError=describe(t); return@withLock PageOcr(emptyList(),ENGINE_NAME,System.currentTimeMillis()-start) }
            // Увеличение ×2 перед распознаванием: на маленьких страницах буквы
            // занимают 8–10 px, и модель путает «ЕЙ» с «5», а «ЛУЧШЕ» с «ЛУ4ШЕ».
            // В лаборатории увеличение дало 5 реплик из 5 там, где без него
            // пропадала целая реплика. Предел — 12 Мпкс, чтобы не ловить OOM.
            val image=upscaleForOcr(decoded)
            try {
                val script=if(language=="Авто")if(book.category=="Комиксы")"Латиница" else "Японский" else language
                val regions=mutableListOf<OcrRegion>()
                if(script=="Кириллица") {
                    // ОДНА РАМКА НА ВСЮ ВИДИМУЮ СТРАНИЦУ. Детектор только
                    // отвечает на вопрос «где текст», а на куски для
                    // распознавания страницу режем мы сами (см. paddlePage).
                    ensureActive()
                    val main=paddlePage(image)
                    ensureActive()
                    // Страховочный проход по странице В ИСХОДНОМ размере с
                    // детектором 960. Лаборатория: нет одних условий на всё —
                    // на плотном тексте выигрывает ×2/1600, а мелкий белый
                    // текст («ЧТО?..» на странице 15) при этом читается как
                    // «4TO.» и выбрасывается фильтром языка. Второй проход
                    // возвращает только те куски, чьё место первый не прочитал.
                    val rescue=if(main.size<600) paddlePage(decoded,960f) else emptyList()
                    val merged=main+rescued(main,rescue)
                    merged.forEach { addRegion(regions,it) }
                } else {
                    val tileHeight=if(image.height>2800)1800 else image.height
                    var y=0
                    while(y<image.height) {
                        ensureActive()
                        val h=min(tileHeight,image.height-y)
                        val tile=Bitmap.createBitmap(image,0,y,image.width,h)
                        ml(tile,script).forEach { r ->
                            val mapped=r.copy(top=(y+r.top*h)/image.height,bottom=(y+r.bottom*h)/image.height)
                            addRegion(regions,mapped)
                        }
                        if(tile!==image)tile.recycle()
                        if(y+h>=image.height)break
                        y+=tileHeight-160
                    }
                }
                val sorted=if(book.mode=="rtl" && script=="Японский") regions.sortedWith(compareByDescending<OcrRegion> { (it.left*40).toInt() }.thenBy { it.top }) else regions.sortedWith(compareBy<OcrRegion> { (it.top*80).toInt() }.thenBy { if(book.mode=="rtl")-it.left else it.left })
                // Панели ищутся по тому же изображению, что ушло в распознавание:
                // порядок чтения потом идёт по кадрам (KomaReadingOrder).
                val panels=if(script=="Кириллица") panelsOf(image) else emptyList()
                PageOcr(sorted,if(script=="Кириллица")ENGINE_NAME else "ML Kit · $script",System.currentTimeMillis()-start,panels=panels).also { store(book.id,page,language,it) }
            } catch(t:Throwable) {
                if(t is kotlinx.coroutines.CancellationException) throw t
                // Битая страница, нехватка памяти, сбой нативного ONNX — всё
                // остаётся сообщением на экране, а приложение продолжает жить.
                lastError=describe(t)
                PageOcr(emptyList(),ENGINE_NAME,System.currentTimeMillis()-start)
            } finally {
                // Кэшированный битмап не освобождаем: он принадлежит экрану,
                // и recycle() дал бы «trying to use a recycled bitmap» при
                // отрисовке. Свою копию (если кэш промахнулся) освобождаем.
                val cachedCopy = runCatching { pageCache.get(path) }.getOrNull()
                if (image !== cachedCopy && !image.isRecycled) image.recycle()
                if (decoded !== image && decoded !== cachedCopy && !decoded.isRecycled) decoded.recycle()
            }
        }
    }
    /**
     * Панели страницы — по уменьшенной копии изображения.
     *
     * Уменьшение обязательно: детектор перебирает пиксели, и на полном кадре
     * 3 Мпкс это секунды. 400 px по длинной стороне хватает, чтобы обводки
     * кадров остались линиями, а работа занимала десятки миллисекунд.
     */
    private fun panelsOf(image: Bitmap): List<KomaPanels.Box> = runCatching {
        val scale = KomaPanels.TARGET.toFloat() / max(image.width, image.height)
        val small = if (scale >= 1f) image else Bitmap.createScaledBitmap(
            image,
            max(8, (image.width * scale).toInt()),
            max(8, (image.height * scale).toInt()),
            true,
        )
        try {
            val pixels = IntArray(small.width * small.height)
            small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
            KomaPanels.detect(small.width, small.height, pixels)
        } finally {
            if (small !== image && !small.isRecycled) small.recycle()
        }
    }.getOrDefault(emptyList())

    /**
     * Добавляет найденный блок, схлопывая перекрытия от соседних тайлов.
     *
     * Тайлы нарезаны с перекрытием, поэтому одна и та же строка приходит дважды,
     * а строка на границе приходит разрезанной пополам. Раньше сравнивался
     * только текст и верхняя граница: разрезанный пополам обрывок оставался
     * второй, «лишней» строкой, и реплика на экране шла кусками. Теперь блоки,
     * перекрывающиеся по площади, склеиваются в один — побеждает тот, где больше
     * букв, а рамка берётся по объединению.
     */
    private fun addRegion(regions:MutableList<OcrRegion>,block:OcrRegion) {
        val blockWidth=max(block.right-block.left,1e-4f)
        val blockHeight=max(block.bottom-block.top,1e-4f)
        var best=-1
        var bestShare=0f
        for (index in regions.indices) {
            val other=regions[index]
            val overlapWidth=min(other.right,block.right)-max(other.left,block.left)
            val overlapHeight=min(other.bottom,block.bottom)-max(other.top,block.top)
            if (overlapWidth<=0f || overlapHeight<=0f) continue
            val otherArea=max((other.right-other.left)*(other.bottom-other.top),1e-6f)
            val share=overlapWidth*overlapHeight/min(otherArea,blockWidth*blockHeight)
            // 60% площади меньшего блока — это тот же самый текст, а не соседняя
            // строка: соседние строки перекрываются только рамками, площадью — нет.
            if (share>bestShare) { bestShare=share;best=index }
        }
        if (best<0 || bestShare<.6f) { regions.add(block);return }
        val other=regions[best]
        val letters={r:OcrRegion -> r.text.count { c->c.isLetterOrDigit() }}
        val text=if (letters(block)>letters(other)) block.text else other.text
        regions[best]=OcrRegion(
            text,
            min(other.left,block.left),
            min(other.top,block.top),
            max(other.right,block.right),
            max(other.bottom,block.bottom),
            listOfNotNull(other.confidence,block.confidence).maxOrNull(),
            other.kind,
        )
    }

    private suspend fun ml(bitmap:Bitmap,script:String):List<OcrRegion> {
        val recognizer=mlModels.getOrPut(script) { when(script){
            "Японский"->TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            else->TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        } }
        val result=com.google.android.gms.tasks.Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap,0)))
        return result.textBlocks.flatMap { it.lines }.mapNotNull { line -> line.boundingBox?.let { r -> OcrRegion(line.text,r.left.toFloat()/bitmap.width,r.top.toFloat()/bitmap.height,r.right.toFloat()/bitmap.width,r.bottom.toFloat()/bitmap.height) } }
    }
    private fun session(name:String):OrtSession = try {
        // Копия помечена versionCode: без этого после обновления приложения
        // в filesDir оставалась ПРЕЖНЯЯ модель, и новые веса молча не ехали.
        val version=runCatching{appVersionCode()}.getOrDefault(0L)
        // Старые копии моделей удаляем: 12 МБ в filesDir никому не нужны.
        runCatching{context.filesDir.listFiles()?.filter{it.isDirectory && it.name.startsWith("koma-models-") && it.name!="koma-models-$version"}?.forEach{it.deleteRecursively()}}
        val dir=File(context.filesDir,"koma-models-$version").apply{mkdirs()}
        val f=File(dir,name)
        if(!f.exists()||f.length()==0L)context.assets.open("koma/models/$name").use { i->f.outputStream().use { i.copyTo(it) } }
        OrtSession.SessionOptions().use { opts->opts.setIntraOpNumThreads(2); env.createSession(f.absolutePath,opts) }
    } catch(t:Throwable) {
        throw IllegalStateException("Модель KOMA/ONNX $name не открылась: ${t.message ?: t::class.simpleName}",t)
    }

    @Suppress("DEPRECATION")
    private fun appVersionCode():Long {
        val info=context.packageManager.getPackageInfo(context.packageName,0)
        return if(android.os.Build.VERSION.SDK_INT>=28)info.longVersionCode else info.versionCode.toLong()
    }

    /** Освобождение моделей: вызывается при выходе из читалки. */
    fun close() {
        runCatching { det?.close() };det=null
        runCatching { rec?.close() };rec=null
        mlModels.values.forEach { runCatching { it.close() } };mlModels.clear()
    }
    /**
     * Одна рамка на видимую часть страницы, а на строки её режем мы сами.
     *
     * Детектор отвечает только на вопрос «где текст». Дальше:
     *
     *  1. детектор проходит страницу тайлами (модель видит максимум 960 px по
     *     длинной стороне) и отдаёт боксы в пикселях СТРАНИЦЫ;
     *  2. бокс часто накрывает не строку, а весь блок из нескольких строк —
     *     поэтому внутри бокса считается проекция «есть тёмные пиксели» по
     *     строкам и по столбцам, и блок режется на отдельные текстовые строки
     *     (по вертикали — по пустым промежуткам, по горизонтали — по краям
     *     текста);
     *  3. каждая строка распознаётся ОТДЕЛЬНО и из полного разрешения страницы:
     *     раньше фрагмент резался ровно по рамке детектора, буквы на краю
     *     срезались, и в реплике оставались обрывки («ХАНУ.», «И РАСЧИТЫВАЮ»);
     *  4. у рамки результата координаты САМОЙ строки, без полей, — иначе при
     *     склейке в облачко строки наезжали друг на друга.
     */
    private fun paddlePage(image:Bitmap, side:Float = 1600f):List<OcrRegion> {
        if(det==null)det=session("detection.onnx")
        if(rec==null)rec=session("cyrillic.onnx")
        val out=mutableListOf<OcrRegion>()
        for(box in detectPage(image,side).take(400)) {
            for(line in textLines(image,box)) {
                var crop=Bitmap.createBitmap(image,line.left,line.top,line.width(),line.height())
                if(crop.height>crop.width*1.8f) {
                    val rotated=Bitmap.createBitmap(crop,0,0,crop.width,crop.height,Matrix().apply { postRotate(90f) },true)
                    if(!crop.isRecycled)crop.recycle()
                    crop=rotated
                }
                val recognition=decode(crop)
                if(!crop.isRecycled)crop.recycle()
                if(recognition.first.isNotBlank() && recognition.second>.25f) {
                    out.add(OcrRegion(
                        recognition.first,
                        line.left.toFloat()/image.width,
                        line.top.toFloat()/image.height,
                        line.right.toFloat()/image.width,
                        line.bottom.toFloat()/image.height,
                        recognition.second,
                    ))
                }
            }
        }
        return out
    }

    /**
     * Добор потерянного вторым (страховочным) проходом.
     *
     * Берём только те куски, чьё место НЕ покрыто годным текстом основного
     * прохода, — иначе текст размножается («ПОЧЕМУ ПОЧЕМУ», «С НАМИ С НАМИ»).
     * «Годный» — это то, что переживёт починку двойников и фильтр языка, иначе
     * выброшенная первым проходом абракадабра («4TO.») закрывала бы своё место.
     */
    private fun rescued(main:List<OcrRegion>, extra:List<OcrRegion>):List<OcrRegion> {
        if(extra.isEmpty()) return emptyList()
        fun usable(r:OcrRegion):Boolean {
            val text=KomaText.cleanSpeech(r.text)
            if(text.count { it.isLetterOrDigit() }<2) return false
            return KomaText.isRussianLine(text)
        }
        val grid=BooleanArray(GRID*GRID)
        fun mark(r:OcrRegion, value:Boolean) {
            val x1=(r.left*GRID).toInt().coerceIn(0,GRID-1)
            val x2=(r.right*GRID).toInt().coerceIn(x1+1,GRID)
            val y1=(r.top*GRID).toInt().coerceIn(0,GRID-1)
            val y2=(r.bottom*GRID).toInt().coerceIn(y1+1,GRID)
            for(y in y1 until y2) for(x in x1 until x2) grid[y*GRID+x]=value
        }
        for(r in main) if(usable(r)) mark(r,true)
        val out=mutableListOf<OcrRegion>()
        for(r in extra) {
            val text=KomaText.cleanSpeech(r.text)
            val letters=text.count { it.isLetterOrDigit() }
            // «ЧТО?» (3 буквы с вопросом) добираем, «енс» и «,е.е» — обрывки.
            if(letters<3) continue
            val tail=text.trim().lastOrNull()
            if(letters==3 && (tail==null || tail !in "?!.")) continue
            val lettersAll=text.filter { it.isLetterOrDigit() }
            val russian=lettersAll.count { KomaText.isRussianLetter(it) }
            if(russian*10<lettersAll.length*6) continue
            val x1=(r.left*GRID).toInt().coerceIn(0,GRID-1)
            val x2=(r.right*GRID).toInt().coerceIn(x1+1,GRID)
            val y1=(r.top*GRID).toInt().coerceIn(0,GRID-1)
            val y2=(r.bottom*GRID).toInt().coerceIn(y1+1,GRID)
            var hit=0;var total=0
            for(y in y1 until y2) for(x in x1 until x2) { total++; if(grid[y*GRID+x]) hit++ }
            if(total>0 && hit.toFloat()/total>=0.6f) continue
            out.add(r.copy(text=text))
        }
        return out
    }

    /** Боксы детектора по всей странице, в пикселях страницы. */
    private fun detectPage(image:Bitmap, side:Float = 1600f):List<Rect> {
        val tileHeight=if(image.height>2800)1800 else image.height
        val found=mutableListOf<Rect>()
        var y=0
        while(y<image.height) {
            val h=min(tileHeight,image.height-y)
            val tile=Bitmap.createBitmap(image,0,y,image.width,h)
            detectTile(tile,side).forEach { found.add(Rect(it.left,it.top+y,it.right,it.bottom+y)) }
            if(tile!==image && !tile.isRecycled)tile.recycle()
            if(y+h>=image.height)break
            y+=tileHeight-160
        }
        return found
    }

    /** Детектор на одном тайле: боксы в пикселях тайла. */
    @Suppress("UNCHECKED_CAST")
    private fun detectTile(bitmap:Bitmap, side:Float = 1600f):List<Rect> {
        // Длинная сторона входа детектора: 1600 для основного прохода (на 960
        // мельче реплики терялись целиком — в лаборатории это видно на
        // странице 2, пропадала целая реплика) и 960 для страховочного.
        val ratio=min(1f,side/max(bitmap.width,bitmap.height))
        val w=max(32,(bitmap.width*ratio/32).roundToInt()*32)
        val h=max(32,(bitmap.height*ratio/32).roundToInt()*32)
        val scaled=Bitmap.createScaledBitmap(bitmap,w,h,true)
        val pixels=IntArray(w*h);scaled.getPixels(pixels,0,w,0,0,w,h)
        if(scaled!==bitmap && !scaled.isRecycled)scaled.recycle()
        // Пиксели 0..1 без ImageNet-нормализации: в лаборатории (lab/) именно
        // так карта вероятностей совпадает с тем, что показывает приложение, а
        // с нормализацией она уходит в ноль. Чтобы не зависеть от поведения
        // рантайма на конкретном телефоне, если так боксов не нашлось —
        // повторяем с нормализацией.
        fun run(normalize:Boolean):List<Rect> {
            val data=FloatArray(3*w*h)
            val mean=floatArrayOf(.485f,.456f,.406f);val std=floatArrayOf(.229f,.224f,.225f)
            for(c in 0..2)for(i in pixels.indices){
                val channel=((pixels[i] shr (c*8)) and 255)/255f
                data[c*w*h+i]=if(normalize)(channel-mean[c])/std[c] else channel
            }
            return OnnxTensor.createTensor(env,FloatBuffer.wrap(data),longArrayOf(1,3,h.toLong(),w.toLong())).use { tensor ->
                det!!.run(mapOf(det!!.inputNames.first() to tensor)).use { result ->
                    val map=(result[0].value as Array<Array<Array<FloatArray>>>)[0][0]
                    components(map)
                }
            }
        }
        var boxes=run(false)
        if(boxes.isEmpty()) boxes=run(true)
        return boxes.take(400).map { b ->
            val x1=(b.left.toFloat()/w*bitmap.width).toInt().coerceIn(0,bitmap.width-1)
            val x2=(b.right.toFloat()/w*bitmap.width).toInt().coerceIn(x1+1,bitmap.width)
            val y1=(b.top.toFloat()/h*bitmap.height).toInt().coerceIn(0,bitmap.height-1)
            val y2=(b.bottom.toFloat()/h*bitmap.height).toInt().coerceIn(y1+1,bitmap.height)
            Rect(x1,y1,x2,y2)
        }
    }

    /**
     * Режет бокс детектора на отдельные текстовые строки.
     *
     * Бокс от детектора часто накрывает весь блок строк: если распознавать его
     * целиком, модель видит четыре строки, сжатые в одну, и выдаёт мусор
     * («ЕЙ НЕ ПОМОЖЕТ ДАЖЕ ПОЛНОЧНИЦА?» → «5ЕН АЯ ПОДНОШЕНИЯ? ПООИАЕТТ»).
     * Поэтому внутри бокса считается профиль тёмных пикселей: пустые полосы —
     * границы строк, а по горизонтали строка обрезается по краям текста.
     */
    private fun textLines(image:Bitmap,box:Rect):List<Rect> {
        val pad=max(2,(box.height()*0.12f).toInt())
        val left=(box.left-pad).coerceAtLeast(0)
        val right=(box.right+pad).coerceAtMost(image.width)
        val top=(box.top-pad).coerceAtLeast(0)
        val bottom=(box.bottom+pad).coerceAtMost(image.height)
        if(right-left<4 || bottom-top<4) return emptyList()
        val scale=min(1f,400f/(right-left))
        val w=max(4,((right-left)*scale).roundToInt())
        val h=max(4,((bottom-top)*scale).roundToInt())
        val raw=Bitmap.createBitmap(image,left,top,right-left,bottom-top)
        val small=Bitmap.createScaledBitmap(raw,w,h,true)
        if(raw!==small && !raw.isRecycled)raw.recycle()
        val pixels=IntArray(w*h);small.getPixels(pixels,0,w,0,0,w,h)
        if(!small.isRecycled)small.recycle()
        val luma=IntArray(w*h)
        for(i in pixels.indices) {
            val p=pixels[i]
            luma[i]=(299*((p shr 16) and 255)+587*((p shr 8) and 255)+114*(p and 255))/1000
        }
        val dark=150
        val runs=mutableListOf<IntArray>()
        var y=0
        while(y<h) {
            if(!rowHasInk(luma,w,y,dark)) { y++;continue }
            var end=y
            while(end<h && rowHasInk(luma,w,end,dark)) end++
            if(end>y) runs.add(intArrayOf(y,end))
            y=end.coerceAtLeast(y+1)
        }
        // Ни одной тёмной строки — значит, мы стоим на участке без текста.
        // Возвращать весь бокс нельзя: на нём распознавание выдаёт мусор.
        if(runs.isEmpty()) return emptyList()
        val heights=runs.map{it[1]-it[0]}.sorted()
        val median=heights[heights.size/2].coerceAtLeast(1)
        // Склеиваем полосы, разделённые крошечным зазором: точка над «й» или
        // просвет внутри буквы не должны рвать строку.
        val merged=mutableListOf<IntArray>()
        for(run in runs) {
            val last=merged.lastOrNull()
            if(last!=null && run[0]-last[1]<=max(1,(median*0.35f).toInt())) last[1]=run[1]
            else merged.add(intArrayOf(run[0],run[1]))
        }
        val lines=mutableListOf<Rect>()
        for(run in merged) {
            if(run[1]-run[0]<max(2,(median*0.4f).toInt())) continue
            var x0=w;var x1=-1
            for(yy in run[0] until run[1]) for(xx in 0 until w) {
                if(luma[yy*w+xx]<dark) { if(xx<x0)x0=xx; if(xx>x1)x1=xx }
            }
            if(x1<0) continue
            val px0=(left+x0/scale).toInt().coerceIn(left,right-1)
            val px1=(left+(x1+1)/scale).toInt().coerceIn(px0+1,right)
            val py0=(top+run[0]/scale).toInt().coerceIn(top,bottom-1)
            val py1=(top+run[1]/scale).toInt().coerceIn(py0+1,bottom)
            lines.add(Rect(px0,py0,px1,py1))
        }
        return lines
    }

    private fun rowHasInk(luma:IntArray,width:Int,y:Int,dark:Int):Boolean {
        val offset=y*width
        val need=max(1,width/60)
        var count=0
        for(x in 0 until width) if(luma[offset+x]<dark) { count++;if(count>=need) return true }
        return false
    }

    private fun components(map:Array<FloatArray>):List<Rect> {
        val h=map.size;val w=map[0].size;val visited=BooleanArray(w*h);val queue=IntArray(w*h);val boxes=mutableListOf<Rect>()
        val dx=intArrayOf(-1,1,0,0);val dy=intArrayOf(0,0,-1,1)
        for(y in 0 until h)for(x in 0 until w) {
            val index=y*w+x;if(visited[index] || map[y][x]<.3f)continue
            var start=0;var end=1;queue[0]=index;visited[index]=true
            var minX=x;var maxX=x;var minY=y;var maxY=y;var score=0f
            while(start<end){val n=queue[start++];val xx=n%w;val yy=n/w;score+=map[yy][xx];minX=min(minX,xx);maxX=max(maxX,xx);minY=min(minY,yy);maxY=max(maxY,yy)
                for(i in 0..3){val nx=xx+dx[i];val ny=yy+dy[i];if(nx in 0 until w && ny in 0 until h){val ni=ny*w+nx;if(!visited[ni] && map[ny][nx]>.3f){visited[ni]=true;queue[end++]=ni}}}
            }
            if(end<6 || score/end<.5f || maxX-minX<2 || maxY-minY<2)continue
            val expand=max(2,((maxX-minX)*(maxY-minY)*1.5f/(2*(maxX-minX+maxY-minY))).roundToInt())
            boxes.add(Rect(max(0,minX-expand),max(0,minY-expand),min(w,maxX+expand+1),min(h,maxY+expand+1)))
        }
        return boxes.sortedBy { it.top }
    }
    @Suppress("UNCHECKED_CAST")
    private fun decode(bitmap:Bitmap):Pair<String,Float> {
        if(bitmap.width<2 || bitmap.height<2) return "" to 0f
        val width=(48f*bitmap.width/bitmap.height).roundToInt().coerceIn(8,960);val padded=max(320,width)
        val scaled=Bitmap.createScaledBitmap(bitmap,width,48,true);val pixels=IntArray(width*48);scaled.getPixels(pixels,0,width,0,0,width,48)
        if(scaled!==bitmap)scaled.recycle()
        val data=FloatArray(3*48*padded)
        for(c in 0..2)for(y in 0 until 48)for(x in 0 until width)data[c*48*padded+y*padded+x]=(((pixels[y*width+x] shr (c*8)) and 255)/255f-.5f)/.5f
        return OnnxTensor.createTensor(env,FloatBuffer.wrap(data),longArrayOf(1,3,48,padded.toLong())).use { tensor ->
            rec!!.run(mapOf(rec!!.inputNames.first() to tensor)).use { result ->
                val values=(result[0].value as Array<Array<FloatArray>>)[0];val text=StringBuilder();var prev=0;var score=0f;var count=0
                values.forEach { logits -> var best=0;for(i in logits.indices)if(logits[i]>logits[best])best=i
                    if(best!=0 && best!=prev){text.append(dictionary.getOrElse(best){""});score+=logits[best];count++};prev=best
                }
                text.toString() to if(count>0)score/count else 0f
            }
        }
    }
}
