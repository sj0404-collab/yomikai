package mihon.data.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * RapidOCR: восточнославянский распознаватель PP-OCRv5 в ONNX, без PaddlePaddle.
 *
 * Почему именно он. В репозитории `manga-ocr` на 63 реальных страницах
 * русской манхвы прогнаны восемь движков. По итогам RapidOCR с
 * `eslav`-распознавателем — самый быстрый (~1.3 с/стр на CPU) при качестве
 * того же PaddleOCR, который сам по себе под Android не запускается. Кириллицу
 * читает, в отличие от manga-ocr от bluolightning, который заточен под японский.
 *
 * Три сети, всё локально:
 *  * det — DBNet, ищет текстовые боксы на странице;
 *  * cls — классификатор угла (0/180), ровняет перевёрнутые строки;
 *  * rec — CTC-распознаватель по словарю `ppocr_keys`.
 *
 * Языковой модели (n-gram LM) здесь нет намеренно: она «догадывается» и молча
 * меняет слова, а задача приложения — показывать ровно то, что на странице.
 *
 * Модели не вшиты в APK, а лежат в `ocr_models/rapidocr/` и скачиваются
 * загрузчиком моделей вместе с остальными.
 */
internal class RapidOcrEngine(context: Context) : LineOcrEngine {

    private val detPath = OcrModelFiles.resolve(context, DET_MODEL)
    private val clsPath = OcrModelFiles.resolve(context, CLS_MODEL)
    private val recPath = OcrModelFiles.resolve(context, REC_MODEL)
    private val dictPath = OcrModelFiles.resolve(context, DICT_FILE)

    private val dictionary: List<String>? by lazy { loadDictionary() }

    private var detSession: OrtSession? = null
    private var clsSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var tried = false

    override suspend fun recognizeText(image: Bitmap): String =
        recognizePage(image).joinToString("\n").trim()

    override suspend fun recognizeLine(image: Bitmap): String =
        recognizeCrop(image).orEmpty()

    /** Строки страницы в порядке чтения. Движок отдаёт строки, а не рамки. */
    fun recognizePage(image: Bitmap): List<String> {
        if (!ensureSessions()) return emptyList()
        return detect(image).mapNotNull { box ->
            val crop = crop(image, box) ?: return@mapNotNull null
            try {
                recognizeCrop(crop)
            } finally {
                if (!crop.isRecycled) crop.recycle()
            }
        }.filter { it.isNotBlank() }
    }

    // --- Сессии ---

    private fun ensureSessions(): Boolean {
        if (tried) return detSession != null && recSession != null
        tried = true
        val dict = dictionary
        if (detPath == null || recPath == null || dict == null) {
            logcat(LogPriority.WARN) {
                "RapidOCR: нет моделей (det=$detPath rec=$recPath dict=$dictPath)"
            }
            return false
        }
        val env = runCatching { OrtEnvironment.getEnvironment() }.getOrNull()
        if (env == null) {
            logcat(LogPriority.WARN) { "RapidOCR: OrtEnvironment недоступен" }
            return false
        }
        detSession = openSession(env, detPath)
        // cls необязателен: без него перевёрнутые строки просто не ровняются.
        clsSession = clsPath?.let { openSession(env, it) }
        recSession = openSession(env, recPath)
        if (detSession == null || recSession == null) {
            logcat(LogPriority.WARN) { "RapidOCR: сессия ONNX не открылась" }
            return false
        }
        return true
    }

    private fun openSession(env: OrtEnvironment, path: String): OrtSession? = runCatching {
        env.createSession(path)
    }.onFailure {
        logcat(LogPriority.WARN, it) { "RapidOCR: не открылась сессия $path" }
    }.getOrNull()

    private fun loadDictionary(): List<String>? {
        val p = dictPath ?: return null
        val f = File(p)
        if (!f.isFile) return null
        return runCatching {
            f.readLines()
                .filter { it.isNotBlank() }
                .map { it.substringBefore('\t') }
                .takeIf { it.isNotEmpty() }
        }.onFailure {
            logcat(LogPriority.WARN, it) { "RapidOCR: словарь не прочитан" }
        }.getOrNull()
    }

    override fun close() {
        runCatching { detSession?.close() }
        runCatching { clsSession?.close() }
        runCatching { recSession?.close() }
        detSession = null
        clsSession = null
        recSession = null
    }

    // --- Детектор ---

    private fun detect(image: Bitmap): List<RectF> {
        val session = detSession ?: return emptyList()
        val scaled = scaleToMaxSide(image, DET_MAX_SIDE)
        val w = scaled.width
        val h = scaled.height
        val scaleX = image.width.toFloat() / w
        val scaleY = image.height.toFloat() / h

        val map = runDet(session, scaled, w, h)
        if (!scaled.isRecycled) scaled.recycle()
        map ?: return emptyList()

        val (probW, probH, data) = map
        val boxes = dbPostprocess(data, probW, probH, scaleX, scaleY, image.width, image.height)
        return sortReadingOrder(boxes)
    }

    private fun runDet(
        session: OrtSession,
        bitmap: Bitmap,
        w: Int,
        h: Int,
    ): Triple<Int, Int, FloatArray>? {
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val input = FloatArray(3 * w * h)
        var p = 0
        for (i in pixels.indices) {
            val c = pixels[i]
            // Нормализация DBNet: (v/255 - 0.5) / 0.5
            input[p++] = ((((c shr 16) and 0xFF) / 255f) - 0.5f) / 0.5f
            input[p++] = ((((c shr 8) and 0xFF) / 255f) - 0.5f) / 0.5f
            input[p++] = (((c and 0xFF) / 255f) - 0.5f) / 0.5f
        }
        // Выход детектора: [1, 1, H, W] — карта вероятностей.
        val shape = longArrayOf(1, 1, h.toLong(), w.toLong())
        return infer(session, shape, input) { dims, data ->
            val outH = dims[dims.size - 2].toInt()
            val outW = dims[dims.size - 1].toInt()
            Triple(outW, outH, data)
        }
    }

    /** Порог, компоненты связности и минимальный прямоугольник каждой компоненты. */
    private fun dbPostprocess(
        map: FloatArray,
        probW: Int,
        probH: Int,
        scaleX: Float,
        scaleY: Float,
        srcW: Int,
        srcH: Int,
    ): List<RectF> {
        val total = probW * probH
        if (probW <= 0 || probH <= 0 || map.size < total) return emptyList()

        val binary = BooleanArray(total)
        var any = false
        for (i in 0 until total) {
            if (map[i] > DET_THRESHOLD) {
                binary[i] = true
                any = true
            }
        }
        if (!any) return emptyList()

        val visited = BooleanArray(total)
        val queue = IntArray(total)
        val boxes = ArrayList<RectF>()

        for (start in binary.indices) {
            if (!binary[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var minX = probW
            var maxX = -1
            var minY = probH
            var maxY = -1
            var area = 0

            while (head < tail) {
                val idx = queue[head++]
                val x = idx % probW
                val y = idx / probW
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                area++
                // 8-связность: у DBNet соседние строки соприкасаются по диагонали.
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || ny < 0 || nx >= probW || ny >= probH) continue
                        val n = ny * probW + nx
                        if (binary[n] && !visited[n]) {
                            visited[n] = true
                            queue[tail++] = n
                        }
                    }
                }
            }

            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            // Мелкие пятна — шум манги, а не буквы.
            if (area < DET_MIN_AREA || bw < 2 || bh < 2) continue
            if (bw.toFloat() / bh > DET_MAX_ASPECT) continue

            val padX = bw * DET_PAD_X
            val padY = bh * DET_PAD_Y
            boxes += RectF(
                (minX - padX) * scaleX,
                (minY - padY) * scaleY,
                ((maxX + 1 + padX) * scaleX).coerceAtMost(srcW.toFloat()),
                ((maxY + 1 + padY) * scaleY).coerceAtMost(srcH.toFloat()),
            )
        }
        return boxes
    }

    /**
     * Порядок чтения: сверху вниз, в одной строке — слева направо.
     * Без этого реплики шли в произвольном порядке обхода сетки детектора.
     */
    private fun sortReadingOrder(boxes: List<RectF>): List<RectF> {
        if (boxes.size <= 1) return boxes
        val sorted = boxes.sortedBy { it.top }
        val out = ArrayList<RectF>(sorted.size)
        var i = 0
        while (i < sorted.size) {
            var j = i + 1
            val h = max(1f, sorted[i].height())
            while (j < sorted.size && verticalOverlap(sorted[i], sorted[j]) >= h * ROW_OVERLAP) j++
            out += sorted.subList(i, j).sortedBy { it.left }
            i = j
        }
        return out
    }

    private fun verticalOverlap(a: RectF, b: RectF): Float =
        min(a.bottom, b.bottom) - max(a.top, b.top)

    // --- Распознаватель ---

    private fun recognizeCrop(bitmap: Bitmap): String? {
        val session = recSession ?: return null
        val dict = dictionary ?: return null
        val upright = straighten(bitmap)

        val scale = REC_HEIGHT.toFloat() / upright.height
        val newW = (upright.width * scale).toInt().coerceIn(REC_MIN_WIDTH, REC_MAX_WIDTH)
        val scaled = Bitmap.createScaledBitmap(upright, newW, REC_HEIGHT, true)
        if (upright !== bitmap && !upright.isRecycled) upright.recycle()

        val pixels = IntArray(newW * REC_HEIGHT)
        scaled.getPixels(pixels, 0, newW, 0, 0, newW, REC_HEIGHT)
        if (!scaled.isRecycled) scaled.recycle()

        val input = FloatArray(3 * REC_HEIGHT * newW)
        var p = 0
        for (i in pixels.indices) {
            val c = pixels[i]
            input[p++] = ((c shr 16) and 0xFF) / 255f - 0.5f
            input[p++] = ((c shr 8) and 0xFF) / 255f - 0.5f
            input[p++] = (c and 0xFF) / 255f - 0.5f
        }

        // Раскладка подтверждена на самой модели eslav_PP-OCRv5_rec_mobile.onnx:
        // вход x = [batch, 3, 48, W], выход = [batch, T, 519].
        val shape = longArrayOf(1, 3, REC_HEIGHT.toLong(), newW.toLong())
        val out = infer(session, shape, input) { dims, data ->
            Triple(dims[dims.size - 1].toInt(), dims[dims.size - 2].toInt(), data)
        } ?: return null
        val text = ctcDecode(out.third, out.first, out.second, dict)
        return text.takeIf { it.isNotBlank() }
    }

    /** Классификатор угла 0/180. Без сети возвращает исходный bitmap. */
    private fun straighten(src: Bitmap): Bitmap {
        val session = clsSession ?: return src
        val pixels = IntArray(CLS_H * CLS_W)
        val small = Bitmap.createScaledBitmap(src, CLS_W, CLS_H, true)
        small.getPixels(pixels, 0, CLS_W, 0, 0, CLS_W, CLS_H)
        if (!small.isRecycled) small.recycle()

        val input = FloatArray(3 * CLS_H * CLS_W)
        var p = 0
        for (i in pixels.indices) {
            val c = pixels[i]
            input[p++] = ((c shr 16) and 0xFF) / 255f - 0.5f
            input[p++] = ((c shr 8) and 0xFF) / 255f - 0.5f
            input[p++] = (c and 0xFF) / 255f - 0.5f
        }

        val shape = longArrayOf(1, 3, CLS_H.toLong(), CLS_W.toLong())
        val scores = infer(session, shape, input) { _, data -> Triple(0, 0, data) }?.third
            ?: return src

        // Индекс 1 означает «строка перевёрнута».
        if (scores.size < 2 || scores[1] <= scores[0]) return src
        val m = android.graphics.Matrix().apply { postRotate(180f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /**
     * Жадный CTC-декод: повтор подряд повторяющегося класса схлопывается,
     * blank (индекс 0) не печатается и разрывает повтор.
     */
    private fun ctcDecode(data: FloatArray, classes: Int, steps: Int, dict: List<String>): String {
        if (classes <= 1 || steps <= 0) return ""
        val width = min(classes, data.size / steps)
        if (width <= 1) return ""
        val sb = StringBuilder()
        var prev = -1
        for (t in 0 until steps) {
            val base = t * classes
            var bestIdx = 0
            var bestVal = Float.NEGATIVE_INFINITY
            for (c in 0 until width) {
                val v = data[base + c]
                if (v > bestVal) {
                    bestVal = v
                    bestIdx = c
                }
            }
            if (bestIdx != 0 && bestIdx != prev) {
                dict.getOrNull(bestIdx - 1)?.let { sb.append(it) }
            }
            prev = bestIdx
        }
        return sb.toString()
    }

    // --- Общее ---

    /** Прогон сети и разбор её выхода. Сессия не закрывается — она кэшируется. */
    private fun <T> infer(
        session: OrtSession,
        shape: LongArray,
        input: FloatArray,
        map: (LongArray, FloatArray) -> T,
    ): T? {
        val env = runCatching { OrtEnvironment.getEnvironment() }.getOrNull() ?: return null
        var tensor: OnnxTensor? = null
        var results: OrtSession.Result? = null
        val outcome = runCatching {
            tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape)
            results = session.run(mapOf(session.inputNames.first() to tensor))
            val value = results.get(0)
            val data = value.getValue() as? FloatArray
            val info = value.getInfo() as? TensorInfo
            if (data == null || info == null) null else map(info.getShape(), data)
        }.onFailure {
            logcat(LogPriority.WARN, it) { "RapidOCR: inference провалился" }
        }
        runCatching { results?.close() }
        runCatching { tensor?.close() }
        return outcome.getOrNull()
    }

    private fun crop(src: Bitmap, box: RectF): Bitmap? {
        val l = box.left.toInt().coerceIn(0, max(0, src.width - 1))
        val t = box.top.toInt().coerceIn(0, max(0, src.height - 1))
        val r = box.right.toInt().coerceIn(l + 1, src.width)
        val b = box.bottom.toInt().coerceIn(t + 1, src.height)
        if (r <= l || b <= t) return null
        return runCatching { Bitmap.createBitmap(src, l, t, r - l, b - t) }.getOrNull()
    }

    private fun scaleToMaxSide(src: Bitmap, maxSide: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxSide) return src
        val r = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            max(1, (src.width * r).toInt()),
            max(1, (src.height * r).toInt()),
            true,
        )
    }

    companion object {
        /**
         * Все четыре файла на месте? Пак не в APK, поэтому без этой проверки
         * выбор движка по умолчанию давал бы пустые страницы.
         */
        fun modelsInstalled(context: Context): Boolean =
            listOf(DET_MODEL, REC_MODEL, DICT_FILE).all {
                OcrModelFiles.resolve(context, it) != null
            }

        const val DET_MODEL = "rapidocr/det.onnx"
        const val CLS_MODEL = "rapidocr/cls.onnx"
        const val REC_MODEL = "rapidocr/rec.onnx"
        const val DICT_FILE = "rapidocr/ppocr_keys.txt"

        /** Длинная сторона детектора: больше — медленнее без заметного выигрыша. */
        private const val DET_MAX_SIDE = 960
        private const val DET_THRESHOLD = 0.3f
        private const val DET_MIN_AREA = 12
        private const val DET_MAX_ASPECT = 20f
        private const val DET_PAD_X = 0.02f
        private const val DET_PAD_Y = 0.06f
        private const val ROW_OVERLAP = 0.5f

        private const val REC_HEIGHT = 48
        private const val REC_MIN_WIDTH = 16
        private const val REC_MAX_WIDTH = 320
        // Классификатор угла жёстко требует ровно 80×160 — проверено на модели:
        // при 48×48 ONNX Runtime отклоняет вход по размерности.
        private const val CLS_H = 80
        private const val CLS_W = 160
    }
}