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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.*

// Кириллица первой: библиотека русская, и это выбор по умолчанию.
val ocrLanguages = listOf("Кириллица", "Авто", "Японский", "Латиница")
data class OcrRegion(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float, val confidence: Float? = null, val kind: String = "Текст") {
    fun json()=JSONObject().put("text",text).put("l",left).put("t",top).put("r",right).put("b",bottom).put("confidence",confidence).put("kind",kind)
}
data class PageOcr(val regions: List<OcrRegion>, val engine: String, val millis: Long, val edited: String?=null) {
    val text: String get() = edited ?: regions.joinToString("\n") { it.text }
    fun json()=JSONObject().put("regions",JSONArray().apply { regions.forEach { put(it.json()) } }).put("engine",engine).put("ms",millis).put("edited",edited)
    companion object {
        fun from(j:JSONObject):PageOcr = PageOcr(j.getJSONArray("regions").let { a -> List(a.length()){ val r=a.getJSONObject(it);OcrRegion(r.getString("text"),r.getDouble("l").toFloat(),r.getDouble("t").toFloat(),r.getDouble("r").toFloat(),r.getDouble("b").toFloat(),if(r.isNull("confidence"))null else r.getDouble("confidence").toFloat(),r.optString("kind","Текст")) } },j.getString("engine"),j.getLong("ms"),if(j.isNull("edited"))null else j.getString("edited"))
    }
}

/** Как движок называется в интерфейсе: ровно как в исходниках KOMA. */
const val ENGINE_NAME="PaddleOCR · кириллица"

class KomaOcrEngine(private val context: Context) {
    private val mutex=Mutex()
    private val root=File(context.filesDir,"ocr").apply { mkdirs() }
    private val mlModels=mutableMapOf<String,TextRecognizer>()
    private var det: OrtSession?=null
    private var rec: OrtSession?=null
    private val env by lazy { OrtEnvironment.getEnvironment() }
    private val dictionary by lazy { listOf("")+context.assets.open("koma/models/cyrillic.txt").bufferedReader().readLines()+listOf(" ") }
    private fun cacheFile(id:String,page:Int,language:String)=File(root,"${id}_${page}_${language}.json")
    fun cached(id:String,page:Int,language:String):PageOcr? = runCatching { PageOcr.from(JSONObject(cacheFile(id,page,language).readText())) }.getOrNull()
    fun store(id:String,page:Int,language:String,result:PageOcr){cacheFile(id,page,language).writeText(result.json().toString())}
    fun cacheCount():Int=root.listFiles()?.size ?: 0
    fun clear(){root.listFiles()?.forEach{it.delete()}}
    fun bitmap(path:String):Bitmap {
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
            val image=try { bitmap(path) } catch(t:Throwable) { lastError=describe(t); return@withLock PageOcr(emptyList(),ENGINE_NAME,System.currentTimeMillis()-start) }
            try {
                val script=if(language=="Авто")if(book.category=="Комиксы")"Латиница" else "Японский" else language
                val regions=mutableListOf<OcrRegion>()
                val tileHeight=if(image.height>2800)1800 else image.height
                var y=0
                while(y<image.height) {
                    ensureActive()
                    val h=min(tileHeight,image.height-y)
                    val tile=Bitmap.createBitmap(image,0,y,image.width,h)
                    val found=if(script=="Кириллица")paddle(tile)else ml(tile,script)
                    found.forEach { r ->
                        val mapped=r.copy(top=(y+r.top*h)/image.height,bottom=(y+r.bottom*h)/image.height)
                        if(regions.none { it.text==mapped.text && abs(it.top-mapped.top)<.015f })regions.add(mapped)
                    }
                    if(tile!==image)tile.recycle()
                    if(y+h>=image.height)break
                    y+=tileHeight-160
                }
                val sorted=if(book.mode=="rtl" && script=="Японский") regions.sortedWith(compareByDescending<OcrRegion> { (it.left*40).toInt() }.thenBy { it.top }) else regions.sortedWith(compareBy<OcrRegion> { (it.top*80).toInt() }.thenBy { if(book.mode=="rtl")-it.left else it.left })
                PageOcr(sorted,if(script=="Кириллица")ENGINE_NAME else "ML Kit · $script",System.currentTimeMillis()-start).also { store(book.id,page,language,it) }
            } catch(t:Throwable) {
                if(t is kotlinx.coroutines.CancellationException) throw t
                // Битая страница, нехватка памяти, сбой нативного ONNX — всё
                // остаётся сообщением на экране, а приложение продолжает жить.
                lastError=describe(t)
                PageOcr(emptyList(),ENGINE_NAME,System.currentTimeMillis()-start)
            } finally { if(!image.isRecycled) image.recycle() }
        }
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
    @Suppress("UNCHECKED_CAST")
    private fun paddle(bitmap:Bitmap):List<OcrRegion> {
        if(det==null)det=session("detection.onnx")
        if(rec==null)rec=session("cyrillic.onnx")
        val ratio=min(1f,960f/max(bitmap.width,bitmap.height))
        val w=max(32,(bitmap.width*ratio/32).roundToInt()*32);val h=max(32,(bitmap.height*ratio/32).roundToInt()*32)
        val scaled=Bitmap.createScaledBitmap(bitmap,w,h,true)
        val pixels=IntArray(w*h);scaled.getPixels(pixels,0,w,0,0,w,h)
        if(scaled!==bitmap)scaled.recycle()
        val data=FloatArray(3*w*h);val mean=floatArrayOf(.485f,.456f,.406f);val std=floatArrayOf(.229f,.224f,.225f)
        for(c in 0..2)for(i in pixels.indices){val channel=(pixels[i] shr (c*8)) and 255;data[c*w*h+i]=(channel/255f-mean[c])/std[c]}
        val boxes=OnnxTensor.createTensor(env,FloatBuffer.wrap(data),longArrayOf(1,3,h.toLong(),w.toLong())).use { tensor ->
            det!!.run(mapOf(det!!.inputNames.first() to tensor)).use { result ->
                val map=(result[0].value as Array<Array<Array<FloatArray>>>)[0][0]
                components(map)
            }
        }
        val out=mutableListOf<OcrRegion>()
        for(b in boxes.take(400)) {
            val x1=(b.left.toFloat()/w*bitmap.width).toInt().coerceIn(0,bitmap.width-1);val x2=(b.right.toFloat()/w*bitmap.width).toInt().coerceIn(x1+1,bitmap.width)
            val y1=(b.top.toFloat()/h*bitmap.height).toInt().coerceIn(0,bitmap.height-1);val y2=(b.bottom.toFloat()/h*bitmap.height).toInt().coerceIn(y1+1,bitmap.height)
            var crop=Bitmap.createBitmap(bitmap,x1,y1,x2-x1,y2-y1)
            if(crop.height>crop.width*1.8f){val rotated=Bitmap.createBitmap(crop,0,0,crop.width,crop.height,Matrix().apply { postRotate(90f) },true);if(crop!==bitmap)crop.recycle();crop=rotated}
            val recognition=decode(crop)
            if(crop!==bitmap)crop.recycle()
            if(recognition.first.isNotBlank() && recognition.second>.25f)out.add(OcrRegion(recognition.first,x1.toFloat()/bitmap.width,y1.toFloat()/bitmap.height,x2.toFloat()/bitmap.width,y2.toFloat()/bitmap.height,recognition.second))
        }
        return out
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
