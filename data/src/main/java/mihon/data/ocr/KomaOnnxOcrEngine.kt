package mihon.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Кириллица — движок из KOMA, дословно.
 *
 * Инференс (`session`, `paddle`, `components`, `decode`) взят из
 * `com/agon/app/data/OcrEngine.kt` исходников KOMA без изменений: те же
 * две модели (PP-OCRv4-детектор + CTC PP-OCRv5 East Slavic), та же
 * нормализация, те же пороги, тот же порядок операций. Правились только
 * обёртки, которые нужны yomikai: имена типов, `suspend` и результат в виде
 * строки. Runtime — тот же, что в KOMA: `onnxruntime-android:1.22.0` и
 * `setIntraOpNumThreads(2)`.
 *
 * Почему дословно: сборка KOMA на этом же устройстве читает кириллицу
 * исправно, а наши собственные варианты движка падали нативно на выводе
 * моделей. Это не лечится настройками — лечится тем, чтобы не отличаться от
 * работающего исходника.
 */
internal class KomaOnnxOcrEngine(
    private val context: Context,
) : OcrEngine, LineOcrEngine {

    private val mutex = Mutex()
    private var det: OrtSession? = null
    private var rec: OrtSession? = null
    private val env by lazy { OrtEnvironment.getEnvironment() }

    /** Индекс 0 — CTC-blank, последний — пробел: так устроен словарь модели. */
    private val dictionary by lazy {
        listOf("") + context.assets.open("koma/models/cyrillic.txt").bufferedReader().readLines() + listOf(" ")
    }

    override suspend fun recognizeText(image: Bitmap): String = withContext(Dispatchers.Default) {
        mutex.withLock { page(image).joinToString("\n") { it.text } }
    }

    /**
     * Длинные страницы (вебтуны) режутся на плитки с перекрытием 160 px —
     * дословно цикл из KOMA: `tileHeight = if (image.height > 2800) 1800 else
     * image.height`, дубли на стыке отбрасываются по тексту и близости верха.
     */
    private fun page(image: Bitmap): List<Region> {
        val regions = mutableListOf<Region>()
        val tileHeight = if (image.height > 2800) 1800 else image.height
        var y = 0
        while (y < image.height) {
            val h = min(tileHeight, image.height - y)
            val tile = Bitmap.createBitmap(image, 0, y, image.width, h)
            paddle(tile).forEach { r ->
                val mapped = r.copy(top = (y + r.top * h) / image.height, bottom = (y + r.bottom * h) / image.height)
                if (regions.none { it.text == mapped.text && kotlin.math.abs(it.top - mapped.top) < .015f }) regions.add(mapped)
            }
            if (tile !== image) tile.recycle()
            if (y + h >= image.height) break
            y += tileHeight - 160
        }
        return regions
    }

    /**
     * Вызов из репозитория: строки страницы с рамками в долях картинки.
     * Обёртка над дословным [paddle]; сама логика — KOMA.
     */
    suspend fun recognizePage(image: Bitmap): List<Region> = withContext(Dispatchers.Default) {
        mutex.withLock { page(image) }
    }

    override suspend fun recognizeLine(image: Bitmap): String = withContext(Dispatchers.Default) {
        mutex.withLock { decode(image).first }
    }

    /**
     * Открытие модели: файл копируется из assets в filesDir (ONNX Runtime умеет
     * читать только путь, но не сжатый asset), затем сессия с двумя потоками —
     * ровно как `session()` в KOMA. Копия помечена versionCode, иначе после
     * обновления APK в filesDir оставалась бы ПРЕЖНЯЯ модель.
     */
    private fun session(name: String): OrtSession {
        val version = runCatching {
            @Suppress("DEPRECATION")
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
            }
        }.getOrDefault(0L)
        runCatching {
            context.filesDir.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("koma-models-") && it.name != "koma-models-$version" }
                ?.forEach { it.deleteRecursively() }
        }
        val dir = File(context.filesDir, "koma-models-$version").apply { mkdirs() }
        val f = File(dir, name)
        if (!f.exists() || f.length() == 0L) {
            context.assets.open("koma/models/$name").use { i -> f.outputStream().use { i.copyTo(it) } }
        }
        return OrtSession.SessionOptions().use { opts ->
            opts.setIntraOpNumThreads(2)
            env.createSession(f.absolutePath, opts)
        }
    }

    /**
     * Рамки текста в пикселях картинки — тот же код, что в начале [paddle],
     * но без распознавания: нужен там, где нужны только области.
     */
    suspend fun detectRegions(image: Bitmap): List<mihon.domain.ocr.model.OcrBoundingBox> =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                if (det == null) det = session("detection.onnx")
                detectBoxes(image).map { box ->
                    mihon.domain.ocr.model.OcrBoundingBox(
                        left = box.left.toFloat() / image.width,
                        top = box.top.toFloat() / image.height,
                        right = box.right.toFloat() / image.width,
                        bottom = box.bottom.toFloat() / image.height,
                    )
                }
            }
        }

    /** Детектор → кроп каждого бокса → CTC. Дословно из KOMA. */
    @Suppress("UNCHECKED_CAST")
    private fun paddle(bitmap: Bitmap): List<Region> {
        if (det == null) det = session("detection.onnx")
        if (rec == null) rec = session("cyrillic.onnx")
        val boxes = detectBoxes(bitmap)
        val out = mutableListOf<Region>()
        for (b in boxes.take(400)) {
            val x1 = b.left
            val x2 = b.right
            val y1 = b.top
            val y2 = b.bottom
            var crop = Bitmap.createBitmap(bitmap, x1, y1, x2 - x1, y2 - y1)
            if (crop.height > crop.width * 1.8f) {
                val rotated = Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height, Matrix().apply { postRotate(90f) }, true)
                if (crop !== bitmap) crop.recycle()
                crop = rotated
            }
            val recognition = decode(crop)
            if (crop !== bitmap) crop.recycle()
            if (recognition.first.isNotBlank() && recognition.second > .25f) {
                out.add(
                    Region(
                        recognition.first,
                        x1.toFloat() / bitmap.width,
                        y1.toFloat() / bitmap.height,
                        x2.toFloat() / bitmap.width,
                        y2.toFloat() / bitmap.height,
                        recognition.second,
                    ),
                )
            }
        }
        return out
    }

    /** Детектор PP-OCRv4: картинка → карта вероятностей → компоненты связности. */
    @Suppress("UNCHECKED_CAST")
    private fun detectBoxes(bitmap: Bitmap): List<Rect> {
        val ratio = min(1f, 960f / max(bitmap.width, bitmap.height))
        val w = max(32, (bitmap.width * ratio / 32).roundToInt() * 32)
        val h = max(32, (bitmap.height * ratio / 32).roundToInt() * 32)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()
        val data = FloatArray(3 * w * h)
        val mean = floatArrayOf(.485f, .456f, .406f)
        val std = floatArrayOf(.229f, .224f, .225f)
        for (c in 0..2) {
            for (i in pixels.indices) {
                val channel = (pixels[i] shr (c * 8)) and 255
                data[c * w * h + i] = (channel / 255f - mean[c]) / std[c]
            }
        }
        val units = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, h.toLong(), w.toLong())).use { tensor ->
            det!!.run(mapOf(det!!.inputNames.first() to tensor)).use { result ->
                val map = (result[0].value as Array<Array<Array<FloatArray>>>)[0][0]
                components(map)
            }
        }
        return units.map { b ->
            val x1 = (b.left.toFloat() / w * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
            val x2 = (b.right.toFloat() / w * bitmap.width).toInt().coerceIn(x1 + 1, bitmap.width)
            val y1 = (b.top.toFloat() / h * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
            val y2 = (b.bottom.toFloat() / h * bitmap.height).toInt().coerceIn(y1 + 1, bitmap.height)
            Rect(x1, y1, x2, y2)
        }
    }

    /** Заливка по порогу 0.3: BFS + отбраковка мелочи. Дословно из KOMA. */
    private fun components(map: Array<FloatArray>): List<Rect> {
        val h = map.size
        val w = map[0].size
        val visited = BooleanArray(w * h)
        val queue = IntArray(w * h)
        val boxes = mutableListOf<Rect>()
        val dx = intArrayOf(-1, 1, 0, 0)
        val dy = intArrayOf(0, 0, -1, 1)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val index = y * w + x
                if (visited[index] || map[y][x] < .3f) continue
                var start = 0
                var end = 1
                queue[0] = index
                visited[index] = true
                var minX = x
                var maxX = x
                var minY = y
                var maxY = y
                var score = 0f
                while (start < end) {
                    val n = queue[start++]
                    val xx = n % w
                    val yy = n / w
                    score += map[yy][xx]
                    minX = min(minX, xx)
                    maxX = max(maxX, xx)
                    minY = min(minY, yy)
                    maxY = max(maxY, yy)
                    for (i in 0..3) {
                        val nx = xx + dx[i]
                        val ny = yy + dy[i]
                        if (nx in 0 until w && ny in 0 until h) {
                            val ni = ny * w + nx
                            if (!visited[ni] && map[ny][nx] > .3f) {
                                visited[ni] = true
                                queue[end++] = ni
                            }
                        }
                    }
                }
                if (end < 6 || score / end < .5f || maxX - minX < 2 || maxY - minY < 2) continue
                val expand = max(2, ((maxX - minX) * (maxY - minY) * 1.5f / (2 * (maxX - minX + maxY - minY))).roundToInt())
                boxes.add(Rect(max(0, minX - expand), max(0, minY - expand), min(w, maxX + expand + 1), min(h, maxY + expand + 1)))
            }
        }
        return boxes.sortedBy { it.top }
    }

    /** CTC-жадный декодер: вход 3x48xW, нормализация (x/255−0.5)/0.5. Дословно из KOMA. */
    @Suppress("UNCHECKED_CAST")
    private fun decode(bitmap: Bitmap): Pair<String, Float> {
        val width = (48f * bitmap.width / bitmap.height).roundToInt().coerceIn(8, 960)
        val padded = max(320, width)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, 48, true)
        val pixels = IntArray(width * 48)
        scaled.getPixels(pixels, 0, width, 0, 0, width, 48)
        if (scaled !== bitmap) scaled.recycle()
        val data = FloatArray(3 * 48 * padded)
        for (c in 0..2) {
            for (y in 0 until 48) {
                for (x in 0 until width) {
                    data[c * 48 * padded + y * padded + x] = (((pixels[y * width + x] shr (c * 8)) and 255) / 255f - .5f) / .5f
                }
            }
        }
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, 48, padded.toLong())).use { tensor ->
            rec!!.run(mapOf(rec!!.inputNames.first() to tensor)).use { result ->
                val values = (result[0].value as Array<Array<FloatArray>>)[0]
                val text = StringBuilder()
                var prev = 0
                var score = 0f
                var count = 0
                values.forEach { logits ->
                    var best = 0
                    for (i in logits.indices) if (logits[i] > logits[best]) best = i
                    if (best != 0 && best != prev) {
                        text.append(dictionary.getOrElse(best) { "" })
                        score += logits[best]
                        count++
                    }
                    prev = best
                }
                text.toString() to if (count > 0) score / count else 0f
            }
        }
    }

    override fun close() {
        runCatching { det?.close() }
        runCatching { rec?.close() }
        det = null
        rec = null
    }

    /** Строка, найденная детектором: текст и рамка в долях картинки. */
    data class Region(
        val text: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val confidence: Float,
    )
}
