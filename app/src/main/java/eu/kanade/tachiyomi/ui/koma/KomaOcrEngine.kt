package eu.kanade.tachiyomi.ui.koma

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.BitmapRegionDecoder
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
const val READ_ORDER_VERSION="11"

class KomaOcrEngine(private val context: Context) {

    /**
     * Действующие правила чтения: встроенные → файл → плагин (см. [KomaRulesStore]).
     *
     * Держим в поле, чтобы не трогать диск на каждом пикселе: перечитываем один
     * раз в начале распознавания страницы. Файл поменяли — следующий проход
     * («Распознать заново») уже работает по новым числам, без пересборки APK.
     */
    @Volatile private var rules: KomaRules = KomaRules.DEFAULT

    private fun refreshRules() { rules = KomaRulesStore.current(context) }

    /** Версия действующих правил — для шторки и буфера строк. */
    fun rulesVersion(): String = rules.version

    fun rulesText(): String = rules.toJson()
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
    // Полоса страницы — отдельная единица распознавания (см. KomaBands): у
    // длинного вебтуна каждая полоса кэшируется сама по себе, иначе кэш одного
    // экрана затирал бы другой.
    private fun cacheFile(id:String,page:Int,language:String,band:Int=0)=File(root,"${id}_${page}_${language}_b$band"+"_$READ_ORDER_VERSION.json")
    fun cached(id:String,page:Int,language:String,band:Int=0):PageOcr? = runCatching { PageOcr.from(JSONObject(cacheFile(id,page,language,band).readText())) }.getOrNull()
    fun store(id:String,page:Int,language:String,result:PageOcr,band:Int=0){cacheFile(id,page,language,band).writeText(result.json().toString())}
    /** Есть ли готовый разбор хотя бы одной полосы страницы. */
    fun cachedAny(id:String,page:Int,language:String):PageOcr? =
        cached(id,page,language) ?: (1..MAX_BANDS).firstNotNullOfOrNull { cached(id,page,language,it) }
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
        // Страница могла ещё копироваться на диск (см. KomaChapterCache):
        // ждём её файл, а не показываем ошибку.
        KomaChapterCache.awaitFile(path)
        fun stream()=if(path.startsWith("file:///android_asset/")) context.assets.open(path.removePrefix("file:///android_asset/")) else File(path.removePrefix("file://")).inputStream()
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        stream().use{BitmapFactory.decodeStream(it,null,bounds)}
        if(bounds.outWidth<=0 || bounds.outHeight<=0) throw IllegalStateException("Не удалось прочитать изображение: $path")
        // Бюджет площади 16 Мпкс в RGB_565 — это ~32 МБ на страницу.
        // Ключевое отличие от прежнего правила: длинный вебтун больше НЕ
        // ужимается по площади до 600 px по ширине. Раньше страница 1200×9000
        // (10,8 Мпкс) получала sample=2 → 600 px, и на экране 1080 px картинка
        // была мыльной, а мелкий текст нечитаемым — это и есть жалоба «большие
        // страницы манхвы не расширяются». Теперь ширина сохраняется, а для
        // распознавания страница всё равно режется на полосы (decodeRegion).
        var sample=1
        while(
            bounds.outWidth.toLong()*bounds.outHeight/(sample.toLong()*sample)>16_000_000L ||
            bounds.outWidth/sample>2600
        ) sample*=2
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

    /**
     * Полоса страницы в её исходном разрешении.
     *
     * `BitmapRegionDecoder` читает с диска только нужные строки: страница
     * 1200×9000 больше не декодируется целиком ради одной полосы, и на вход
     * модели попадает полная ширина (а не 600 px после общего даунскейла).
     */
    private fun region(path:String,band:KomaBand):Bitmap {
        KomaChapterCache.awaitFile(path)
        val plain=path.removePrefix("file://")
        val decoder=try {
            when {
                path.startsWith("file:///android_asset/") -> {
                    val bytes=context.assets.open(path.removePrefix("file:///android_asset/")).use{ it.readBytes() }
                    BitmapRegionDecoder.newInstance(bytes,0,bytes.size)
                }
                android.os.Build.VERSION.SDK_INT>=31 -> {
                    val fd=android.os.ParcelFileDescriptor.open(File(plain),android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                    BitmapRegionDecoder.newInstance(fd)
                }
                else -> @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(plain,false)
            }
        } catch(t:Throwable) { null }
            ?: return bitmap(path)
        return try {
            val core=decoder.width/2400
            var sample=1
            while(sample*2<=core.coerceAtLeast(1)) sample*=2
            val top=(band.top*decoder.height).toInt().coerceIn(0,decoder.height-1)
            val bottom=(band.bottom*decoder.height).toInt().coerceIn(top+1,decoder.height)
            val options=BitmapFactory.Options().apply {
                inSampleSize=sample
                if(decoder.width/sample>1400) inPreferredConfig=Bitmap.Config.RGB_565
            }
            decoder.decodeRegion(android.graphics.Rect(0,top,decoder.width,bottom),options) ?: bitmap(path)
        } catch(t:Throwable) {
            bitmap(path)
        } finally {
            runCatching { decoder.recycle() }
        }
    }

    /** Размер страницы в пикселях без декодирования картинки. */
    fun pageSize(path:String, waitMs:Long=0):Pair<Int,Int>? {
        // Ждать файл на главном потоке нельзя: экран спрашивает размер для
        // прогрева. Ждём только когда явно попросили.
        if(waitMs>0) KomaChapterCache.awaitFile(path,waitMs)
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        val read=runCatching {
            if(path.startsWith("file:///android_asset/")) {
                context.assets.open(path.removePrefix("file:///android_asset/")).use{ BitmapFactory.decodeStream(it,null,bounds) }
            } else {
                File(path.removePrefix("file://")).inputStream().use{ BitmapFactory.decodeStream(it,null,bounds) }
            }
        }
        if(read.isFailure) return null
        if(bounds.outWidth<=0 || bounds.outHeight<=0) return null
        return Pair(bounds.outWidth,bounds.outHeight)
    }

    /** Последняя ошибка распознавания в читалке: её показывает экран. */
    var lastError:String?=null
        private set

    private fun describe(t:Throwable):String = when(t) {
        is OutOfMemoryError -> "Не хватило памяти на страницу. Закройте другие приложения и повторите."
        is UnsatisfiedLinkError -> "Не загрузилось ядро ONNX для этого телефона: ${t.message}"
        else -> listOfNotNull(t::class.simpleName, t.message).joinToString(": ").ifBlank { "неизвестная ошибка" }
    }

    suspend fun recognize(book:Book,page:Int,language:String,force:Boolean=false,band:KomaBand=KomaBand.Whole):PageOcr = withContext(Dispatchers.Default) {
        mutex.withLock {
            lastError=null
            val start=System.currentTimeMillis()
            // Ключ кэша: 0 — страница целиком, 1..N — полосы. Иначе кэш одного
            // экрана длинного вебтуна затирал бы соседние.
            val slot=if(band.whole)0 else band.index+1
            if(!force) cached(book.id,page,language,slot)?.let{return@withLock it}
            val path=book.pages.getOrNull(page)
                ?: run { lastError="Страница ${page+1} отсутствует в файле"; return@withLock PageOcr(emptyList(),ENGINE_NAME,System.currentTimeMillis()-start) }
            val decoded=try { if(band.whole) bitmap(path) else region(path,band) } catch(t:Throwable) { lastError=describe(t); return@withLock PageOcr(emptyList(),ENGINE_NAME,System.currentTimeMillis()-start) }
            // Увеличение ×2 перед распознаванием: на маленьких страницах буквы
            // занимают 8–10 px, и модель путает «ЕЙ» с «5», а «ЛУЧШЕ» с «ЛУ4ШЕ».
            // В лаборатории увеличение дало 5 реплик из 5 там, где без него
            // пропадала целая реплика. Предел — 12 Мпкс, чтобы не ловить OOM.
            refreshRules()
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
                    val rescue=if(main.size<rules.rescueMinRegions) paddlePage(decoded,rules.rescueSide) else emptyList()
                    // Третий проход — по кадру с растянутыми уровнями: бледный
                    // текст (серое название на обложке) ни на одной из первых
                    // двух попыток не находится.
                    val boosted=if(main.size<rules.rescueMinRegions) contrastBoost(decoded) else null
                    val faint=boosted?.let { runCatching { paddlePage(it,rules.rescueSide) }.getOrDefault(emptyList()) }.orEmpty()
                    if(boosted!=null && boosted!==decoded && !boosted.isRecycled) boosted.recycle()
                    val merged=main+rescued(main,rescue+faint)
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
                val rawPanels=if(script=="Кириллица") panelsOf(image) else emptyList()
                // Координаты полосы переводим в координаты СТРАНИЦЫ: экран, рамки
                // и подсветка работают с одним и тем же масштабом.
                val onPage=if(band.whole) sorted else sorted.map { it.copy(top=band.mapY(it.top),bottom=band.mapY(it.bottom)) }
                val panels=if(band.whole) rawPanels else rawPanels.map { KomaPanels.Box(it.left,band.mapY(it.top),it.right,band.mapY(it.bottom)) }
                PageOcr(onPage,if(script=="Кириллица")ENGINE_NAME else "ML Kit · $script",System.currentTimeMillis()-start,panels=panels).also { store(book.id,page,language,it,slot) }
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
     * Распознавание произвольного кадра: вкладка «Браузер», снимок экрана,
     * выделенная область. Путь тот же, что у страницы книги — два прохода,
     * рамки по строкам, панели и порядок чтения, — только без книги и кэша.
     *
     * Нужен, чтобы в браузере были ТЕ ЖЕ рамки и тот же порядок чтения, что в
     * читалке: до этого там работал старый движок кириллицы (до KOMA), и рамки
     * на экране были чужие.
     */
    suspend fun recognizeImage(source: Bitmap): PageOcr = withContext(Dispatchers.Default) {
        mutex.withLock {
            lastError = null
            val start = System.currentTimeMillis()
            val image = upscaleForOcr(source)
            try {
                ensureActive()
                val main = paddlePage(image)
                ensureActive()
                refreshRules()
                val rescue = if (main.size < rules.rescueMinRegions) paddlePage(source, rules.rescueSide) else emptyList()
                // Тот же третий проход, что в читалке: бледный текст находится
                // только на кадре с растянутыми уровнями. Читалка и браузер
                // должны вести себя одинаково.
                val boosted = if (main.size < rules.rescueMinRegions) contrastBoost(source) else null
                val faint = boosted?.let { runCatching { paddlePage(it, rules.rescueSide) }.getOrDefault(emptyList()) }.orEmpty()
                if (boosted != null && boosted !== source && !boosted.isRecycled) boosted.recycle()
                val regions = mutableListOf<OcrRegion>()
                (main + rescued(main, rescue + faint)).forEach { addRegion(regions, it) }
                val sorted = regions.sortedWith(
                    compareBy<OcrRegion> { (it.top * 80).toInt() }.thenByDescending { it.left },
                )
                PageOcr(sorted, ENGINE_NAME, System.currentTimeMillis() - start, panels = panelsOf(image))
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                lastError = describe(t)
                PageOcr(emptyList(), ENGINE_NAME, System.currentTimeMillis() - start)
            } finally {
                if (image !== source && !image.isRecycled) image.recycle()
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
    private fun paddlePage(image:Bitmap, requestedSide:Float = -1f):List<OcrRegion> {
        // Сторона детектора: явный аргумент (страховочные проходы) главнее
        // значения из правил.
        val side = if (requestedSide > 0f) requestedSide else rules.pageSide
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
                val recognition=decodeBetter(crop)
                if(!crop.isRecycled)crop.recycle()
                if(recognition.first.isNotBlank() && recognition.second>rules.minConfidence) {
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
        // Порог «чернил» считается по самому участку, а не берётся числом 150.
        //
        // Жалоба владельца: «раньше он видел серый текст, а теперь пропускает
        // даже при повторном сканировании». Светло-серое каллиграфическое
        // название на белом (яркость букв ~150–170 при фоне 255) в жёсткий
        // порог 150 не попадало — строки считались пустыми, и текст исчезал
        // целиком, ещё до распознавания.
        val dark=inkThreshold(luma)
        if(dark<0) return emptyList()
        // Несколько шрифтов на одной странице: заголовок каллиграфией и реплика
        // жирным могли попасть в ОДИН бокс, и один порог на двоих вставал по
        // тёмному тексту — серый выпадал целиком. Теперь бокс режется на
        // горизонтальные подблоки, у каждого свой порог (KomaRules.inkTileRows).
        val tileRows=rules.inkTileRows.coerceIn(1,16)
        val tileH=((h+tileRows-1)/tileRows).coerceAtLeast(1)
        val tileThresholds=IntArray(tileRows) { ti ->
            val y0=ti*tileH
            val y1=min(h,y0+tileH)
            if(y1<=y0) -1 else inkThreshold(luma.copyOfRange(y0*w,y1*w))
        }
        // Второй уровень «чернил» (KomaRules.inkLevels = 2): серый диапазон
        // яркости тоже считается текстом. Опция, потому что добавляет
        // найденного — вместе с шумом.
        val grey=if(rules.inkLevels>=2) greyInkRange(luma) else null
        fun thresholdAt(y:Int):Int = tileThresholds[(y/tileH).coerceIn(0,tileRows-1)]
        fun isInk(y:Int,x:Int):Boolean {
            val l=luma[y*w+x]
            if(l<thresholdAt(y)) return true
            return grey!=null && l in grey
        }
        val runs=mutableListOf<IntArray>()
        var y=0
        while(y<h) {
            if(!rowHasInk(luma,w,y,thresholdAt(y),grey)) { y++;continue }
            var end=y
            while(end<h && rowHasInk(luma,w,end,thresholdAt(end),grey)) end++
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
                if(isInk(yy,xx)) { if(xx<x0)x0=xx; if(xx>x1)x1=xx }
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

    /**
     * Порог яркости, ниже которого пиксель считается «чернилами».
     *
     * Берутся 2-й и 98-й процентили яркости участка: 2 % — цвет букв, 98 % —
     * фон. Порог ставится между ними. Однородный участок (пустое поле, ровная
     * заливка) даёт -1: там текста нет, и распознавать нечего.
     */
    private fun inkThreshold(luma:IntArray):Int {
        if(luma.isEmpty()) return -1
        val hist=IntArray(256)
        for(v in luma) hist[v.coerceIn(0,255)]++
        fun percentile(p:Float):Int {
            val target=(luma.size*p).toInt().coerceAtLeast(1)
            var acc=0
            for(i in 0..255) { acc+=hist[i]; if(acc>=target) return i }
            return 255
        }
        val r = rules
        val low=percentile(r.inkLowPercentile)
        val high=percentile(r.inkHighPercentile)
        if(high-low<r.inkFlatSpread) return -1
        return (low+(high-low)*r.inkCoefficient).toInt().coerceIn(r.inkMin,r.inkMax)
    }

    /**
     * Копия кадра с растянутыми уровнями яркости.
     *
     * Нужна как страховочный проход: бледно-серый текст (название на обложке,
     * тонкие волосяные линии) детектор на исходной яркости не находит, а после
     * растяжки уровней — находит. Исходный битмап не меняется: он принадлежит
     * кэшу страницы и рисуется на экране.
     */
    private fun contrastBoost(src:Bitmap):Bitmap? = try {
        val gain=rules.contrastGain
        val shift=rules.contrastOffset
        val out=Bitmap.createBitmap(src.width,src.height,Bitmap.Config.ARGB_8888)
        val paint=Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter=ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        gain,0f,0f,0f,shift,
                        0f,gain,0f,0f,shift,
                        0f,0f,gain,0f,shift,
                        0f,0f,0f,1f,0f,
                    ),
                ),
            )
        }
        Canvas(out).drawBitmap(src,0f,0f,paint)
        out
    } catch(t:Throwable) { null }

    private fun rowHasInk(luma:IntArray,width:Int,y:Int,dark:Int,grey:IntRange?=null):Boolean {
        val offset=y*width
        val need=max(1,width/60)
        var count=0
        for(x in 0 until width) {
            val l=luma[offset+x]
            if(l<dark || (grey!=null && l in grey)) { count++;if(count>=need) return true }
        }
        return false
    }

    /**
     * Диапазон яркости «серых чернил» — второго уровня текста на участке.
     *
     * Ищем горб гистограммы между буквами и фоном: три моды (фон, серый текст,
     * тёмный текст) — обычное дело на обложке с каллиграфическим названием.
     * Если среднего горба нет, возвращаем null и работаем как раньше: второй
     * уровень не выдумываем, иначе на пустых полях полезет шум.
     */
    private fun greyInkRange(luma:IntArray):IntRange? {
        if(luma.size<64) return null
        val hist=IntArray(256)
        for(v in luma) hist[v.coerceIn(0,255)]++
        fun percentile(p:Float):Int {
            val target=(luma.size*p).toInt().coerceAtLeast(1)
            var acc=0
            for(i in 0..255) { acc+=hist[i]; if(acc>=target) return i }
            return 255
        }
        val low=percentile(rules.inkLowPercentile)
        val high=percentile(rules.inkHighPercentile)
        val spread=high-low
        if(spread<24) return null
        val from=low+spread*0.25f
        val to=low+spread*0.80f
        var bestBin=-1
        var bestMass=0
        // Сглаживание по трём соседним бинам: одиночный пиксель — не мода.
        for(i in from.toInt().coerceAtLeast(0)..to.toInt().coerceAtMost(255)) {
            val mass=hist[max(0,i-1)]+hist[i]+hist[min(255,i+1)]
            if(mass>bestMass) { bestMass=mass; bestBin=i }
        }
        // Мода должна быть заметной: меньше 0.2 % пикселей — это не шрифт, а шум.
        if(bestBin<0 || bestMass<max(3,luma.size/500)) return null
        val lo=(bestBin-spread*0.10f).toInt().coerceAtLeast(low+1)
        val hi=(bestBin+spread*0.18f).toInt().coerceAtMost(high-1)
        if(hi<=lo) return null
        return lo..hi
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
    /**
     * Распознавание кропа с повтором по бинаризованному изображению.
     *
     * Модель обучена на книжном тексте: чёрные буквы на светлом фоне она читает
     * хорошо, а светлые буквы на тёмном скринтоне — нет. На стр. 16 так терялось
     * слово «ТРИ» в облачке «ТРИ РАЗА»: с исходного кропа модель отдавала одну
     * букву «И» (0.31), и строка уходила в фильтр языка.
     *
     * Проверено перебором на этом облачке: с бинаризацией (оба направления)
     * «ТРИ» читается уверенно — 0.73–0.81 против 0.31, «РАЗА» — 0.79.
     * Поэтому: если с первого раза получилось мало букв или низкая уверенность,
     * пробуем два бинарных варианта и берём лучший по числу букв.
     */
    private fun decodeBetter(crop:Bitmap):Pair<String,Float> {
        val first=decode(crop)
        // Слово уже прочитано целиком (три буквы и больше) — не трогаем.
        // Раньше повтор запускался ещё и по низкой уверенности, и бинаризация
        // портила верное слово: «ГОРА» → «ГСРА» (на скринтоне О сливается с С).
        if(first.first.count { it.isLetter() }>=3) return first
        var best=first
        for(polarity in 0..1) {
            val binar=binarize(crop,polarity)
            val attempt=decode(binar)
            if(binar!==crop && !binar.isRecycled) binar.recycle()
            // Заменяем только на вариант, где букв СТРОГО больше: «И» → «ТРИ»
            // (слово было потеряно), но «ГОРА» не превращается в «ГСРА».
            if(attempt.first.count { it.isLetter() }>best.first.count { it.isLetter() }) best=attempt
        }
        return best
    }

    /** Чёрно-белая копия кропа: 0 — тёмное на белом, 1 — светлое на чёрном. */
    private fun binarize(src:Bitmap,polarity:Int):Bitmap {
        val w=src.width
        val h=src.height
        if(w<2 || h<2) return src
        return try {
            val pixels=IntArray(w*h)
            src.getPixels(pixels,0,w,0,0,w,h)
            val lumas=IntArray(w*h)
            for(i in pixels.indices) {
                val pixel=pixels[i]
                lumas[i]=(((pixel shr 16) and 0xFF)*77+((pixel shr 8) and 0xFF)*151+(pixel and 0xFF)*28) shr 8
            }
            // Порог — по кропу, а не константа 110: иначе бледно-серые буквы
            // бинаризация превращала в белое поле, и повтор не помогал.
            val threshold=inkThreshold(lumas).let { if(it<0) rules.binarizeFallback else it }
            for(i in pixels.indices) {
                val luma=lumas[i]
                val dark=luma<threshold
                val value=if(polarity==0) { if(dark) 0 else 255 } else { if(dark) 255 else 0 }
                pixels[i]=(0xFF shl 24) or (value shl 16) or (value shl 8) or value
            }
            Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
        } catch(t:Throwable) {
            src
        }
    }

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
