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
import logcat.LogPriority
import mihon.domain.ocr.exception.OcrException
import mihon.domain.ocr.model.OcrBoundingBox
import tachiyomi.core.common.util.system.logcat
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Кириллический OCR-движок, перенесённый из KOMA (ONNX Runtime).
 *
 * Отличие от [CyrillicOcrEngine]: тот живёт на LiteRT-моделях PP-OCRv3/v5 с
 * верификатором и «спасательными» проходами, а здесь ровно две модели и один
 * проход — PP-OCRv4-детектор (`det.onnx`) и независимый CTC-распознаватель
 * East Slavic PP-OCRv5 (`rec.onnx`) со своим словарём. Никаких онлайн-цепочек,
 * никакого выбора кандидатов: быстро и предсказуемо.
 *
 * Почему отдельный движок, а не замена внутри [CyrillicOcrEngine]: TFLite и
 * ONNX дают разные числа на одном и том же кропе (нормализация, паддинг,
 * декодер). Смешивать их в одном классе — значит потерять возможность
 * откатиться на прежний путь одной настройкой.
 *
 * Модели берутся снаружи APK через [OcrModelFiles] (папка `ocr_models/`):
 * `koma_det.onnx`, `koma_rec.onnx`, `koma_dict.txt`.
 */
internal class KomaOnnxOcrEngine(
    private val context: Context,
) : OcrEngine, LineOcrEngine {

    private val mutex = Mutex()
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var det: OrtSession? = null
    private var rec: OrtSession? = null

    /** Индекс 0 — CTC-blank, последний — пробел: так устроен словарь модели. */
    private val dictionary: List<String> by lazy {
        val path = OcrModelFiles.resolve(context, DICT_PATH)
            ?: throw OcrException.InitializationError(
                IllegalStateException("Словарь KOMA не установлен: $DICT_FLAT"),
            )
        val lines = runCatching { java.io.File(path).readLines() }
            .getOrElse { throw OcrException.InitializationError(it) }
        buildList(lines.size + 2) {
            add("")
            addAll(lines)
            add(" ")
        }
    }

    private fun session(path: String): OrtSession {
        // resolve() отдаёт абсолютный путь к файлу во внешней папке моделей:
        // в APK их нет намеренно, поэтому «нет файла» — это InitializationError,
        // а не падение на пустом пути.
        val file = OcrModelFiles.resolve(context, path)
            ?: throw OcrException.InitializationError(
                IllegalStateException("Модель KOMA не установлена: $path"),
            )
        return try {
            OrtSession.SessionOptions().use { options ->
                // Два потока: детектор и распознаватель идут последовательно, а
                // больше потоков на телефоне только греет процессор.
                options.setIntraOpNumThreads(2)
                env.createSession(file, options)
            }
        } catch (e: Exception) {
            throw OcrException.InitializationError(e)
        }
    }

    private fun ensureDet(): OrtSession = det ?: run {
        OcrBreadcrumbs.mark(context, "onnx: открываю детектор")
        session(DET_PATH).also {
            det = it
            OcrBreadcrumbs.mark(context, "onnx: детектор открыт")
        }
    }

    private fun ensureRec(): OrtSession = rec ?: run {
        OcrBreadcrumbs.mark(context, "onnx: открываю распознаватель")
        session(REC_PATH).also {
            rec = it
            OcrBreadcrumbs.mark(context, "onnx: распознаватель открыт")
        }
    }

    suspend fun ensureInitialized() {
        withContext(Dispatchers.Default) {
            mutex.withLock {
                ensureDet()
                ensureRec()
                dictionary.size
            }
        }
    }

    override suspend fun recognizeText(image: Bitmap): String {
        return recognizeRegions(image).joinToString("\n") { it.text }
    }

    override suspend fun recognizeLine(image: Bitmap): String = try {
        recognizeLineUnsafe(image)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        logcat(LogPriority.WARN, e) { "KOMA OCR: строка не распознана" }
        ""
    }

    private suspend fun recognizeLineUnsafe(image: Bitmap): String = withContext(Dispatchers.Default) {
        mutex.withLock {
            require(!image.isRecycled) { "Input bitmap is recycled" }
            ensureRec()
            decode(image).first
        }
    }

    /** Рамки найденного текста в нормализованных координатах страницы. */
    suspend fun detectRegions(image: Bitmap): List<OcrBoundingBox> = try {
        detectRegionsUnsafe(image)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        logcat(LogPriority.WARN, e) { "KOMA OCR: детектор не ответил" }
        emptyList()
    }

    private suspend fun detectRegionsUnsafe(image: Bitmap): List<OcrBoundingBox> = withContext(Dispatchers.Default) {
        mutex.withLock {
            require(!image.isRecycled) { "Input bitmap is recycled" }
            ensureDet()
            // Детектор PP-OCRv4 режет страницу на слова и даже на куски слов.
            // Для читалки это плохо дважды: CTC-декодер на коротком кропе
            // выдаёт мусор («TH E», «1/1/22»), и озвучка идёт по обрывкам.
            // Склеиваем фрагменты в строки — так же, как их видит человек.
            val boxes = mergeIntoLines(detectBoxes(image))
            boxes.mapNotNull { box ->
                OcrBoxGeometry.normalize(
                    left = box.left,
                    top = box.top,
                    right = box.right,
                    bottom = box.bottom,
                    imageWidth = image.width,
                    imageHeight = image.height,
                )
            }
        }
    }

    /**
     * Полный проход по странице: детектор → кроп каждого бокса → CTC.
     *
     * Длинные страницы (вебтуны) режутся на плитки с перекрытием 160 px: так
     * детектор не «слепнет» на масштабе и не съедает память. Дубли на стыке
     * отбрасываются по тексту и близости верхней границы.
     */
    suspend fun recognizeRegions(image: Bitmap): List<KomaRegion> = withContext(Dispatchers.Default) {
        try {
            recognizeRegionsUnsafe(image)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Движок не должен ронять читалку: битая модель, нехватка памяти,
            // отсутствующий словарь — всё это превращается в «текста нет», и
            // авточтение честно остановится по счётчику пустых кадров.
            logcat(LogPriority.WARN, e) { "KOMA OCR: страница не распознана" }
            emptyList()
        }
    }

    private suspend fun recognizeRegionsUnsafe(image: Bitmap): List<KomaRegion> = withContext(Dispatchers.Default) {
        mutex.withLock {
            require(!image.isRecycled) { "Input bitmap is recycled" }
            ensureDet()
            ensureRec()
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val found = mutableListOf<KomaRegion>()
            val tileHeight = if (image.height > 2800) 1800 else image.height
            var y = 0
            while (y < image.height) {
                val h = min(tileHeight, image.height - y)
                val tile = if (h == image.height && y == 0) {
                    image
                } else {
                    Bitmap.createBitmap(image, 0, y, image.width, h)
                }
                for (region in recognizeTile(tile)) {
                    val top = (y + region.box.top * h) / image.height
                    val bottom = (y + region.box.bottom * h) / image.height
                    val mapped = region.copy(box = region.box.copy(top = top, bottom = bottom))
                    val duplicate = found.any {
                        it.text == mapped.text && kotlin.math.abs(it.box.top - mapped.box.top) < 0.015f
                    }
                    if (!duplicate) found += mapped
                }
                if (tile !== image) tile.recycle()
                if (y + h >= image.height) break
                y += tileHeight - 160
            }
            logcat(LogPriority.VERBOSE) {
                "KOMA OCR: ${found.size} регионов за ${android.os.SystemClock.elapsedRealtime() - startedAt} мс"
            }
            // Порядок чтения: сверху вниз, внутри строки — слева направо.
            found.sortedWith(compareBy<KomaRegion> { (it.box.top * 80).roundToInt() }.thenBy { it.box.left })
        }
    }

    private fun recognizeTile(tile: Bitmap): List<KomaRegion> {
        val out = mutableListOf<KomaRegion>()
        val boxes = mergeIntoLines(detectBoxes(tile)).take(MAX_BOXES)
        for (box in boxes) {
            val x1 = box.left.coerceIn(0, tile.width - 1)
            val x2 = box.right.coerceIn(x1 + 1, tile.width)
            val y1 = box.top.coerceIn(0, tile.height - 1)
            val y2 = box.bottom.coerceIn(y1 + 1, tile.height)
            var crop = Bitmap.createBitmap(tile, x1, y1, x2 - x1, y2 - y1)
            // Вертикальные надписи: распознаватель ждёт горизонтальную строку.
            if (crop.height > crop.width * 1.8f) {
                val rotated = Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height, Matrix().apply { postRotate(90f) }, true)
                crop.recycle()
                crop = rotated
            }
            val (text, confidence) = decode(crop)
            crop.recycle()
            if (text.isBlank() || confidence <= MIN_LINE_SCORE) continue
            out += KomaRegion(
                text = text,
                box = OcrBoundingBox(
                    left = x1.toFloat() / tile.width,
                    top = y1.toFloat() / tile.height,
                    right = x2.toFloat() / tile.width,
                    bottom = y2.toFloat() / tile.height,
                ),
                confidence = confidence,
            )
        }
        return out
    }

    /** PP-OCRv4 det: изображение → карта вероятностей → компоненты связности. */
    @Suppress("UNCHECKED_CAST")
    private fun detectBoxes(bitmap: Bitmap): List<Rect> {
        val ratio = min(1f, DET_LIMIT / max(bitmap.width, bitmap.height).toFloat())
        val w = max(32, (bitmap.width * ratio / 32f).roundToInt() * 32)
        val h = max(32, (bitmap.height * ratio / 32f).roundToInt() * 32)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()

        val data = FloatArray(3 * w * h)
        for (c in 0..2) {
            for (i in pixels.indices) {
                val channel = (pixels[i] shr (c * 8)) and 0xFF
                data[c * w * h + i] = (channel / 255f - DET_MEAN[c]) / DET_STD[c]
            }
        }

        val session = det ?: return emptyList()
        OcrBreadcrumbs.mark(context, "onnx: детекция ${w}x$h")
        val map = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, h.toLong(), w.toLong())).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                ((result[0].value as Array<Array<Array<FloatArray>>>)[0][0])
            }
        }
        return components(map).map { unit ->
            Rect(
                (unit.left.toFloat() / w * bitmap.width).toInt().coerceIn(0, bitmap.width - 1),
                (unit.top.toFloat() / h * bitmap.height).toInt().coerceIn(0, bitmap.height - 1),
                (unit.right.toFloat() / w * bitmap.width).toInt().coerceIn(1, bitmap.width),
                (unit.bottom.toFloat() / h * bitmap.height).toInt().coerceIn(1, bitmap.height),
            )
        }.filter { it.width() > 2 && it.height() > 2 }
    }

    /**
     * Склейка фрагментов детектора в строки.
     *
     * PP-OCRv4 на манга-странице отдаёт по компоненту на слово (а на стилизованном
     * шрифте — на пару букв). Распознаватель ждёт строку: на обрывке он выдаёт
     * огрызки вида «TH E» или «1/1/22», и порядок чтения рассыпается на десятки
     * «реплик». Склеиваем прямоугольники, у которых совпадает вертикальная полоса
     * и между которыми нет большого горизонтального разрыва.
     */
    private fun mergeIntoLines(boxes: List<Rect>): List<Rect> {
        if (boxes.size < 2) return boxes
        // Полосы сверху вниз: соседние фрагменты одной строки почти всегда идут
        // подряд после сортировки по верхней границе.
        val sorted = boxes.sortedWith(compareBy<Rect> { it.top }.thenBy { it.left })
        val lines = mutableListOf<Rect>()
        for (box in sorted) {
            val target = lines.indexOfLast { line ->
                val lineHeight = line.height().coerceAtLeast(1)
                val overlap = min(line.bottom, box.bottom) - max(line.top, box.top)
                val sameBand = overlap > lineHeight * LINE_MIN_VERTICAL_OVERLAP
                val gap = box.left - line.right
                val closeEnough = gap <= lineHeight * LINE_MAX_GAP_FACTOR && gap > -box.width()
                sameBand && closeEnough
            }
            if (target >= 0) {
                val line = lines[target]
                lines[target] = Rect(
                    min(line.left, box.left),
                    min(line.top, box.top),
                    max(line.right, box.right),
                    max(line.bottom, box.bottom),
                )
            } else {
                lines += box
            }
        }
        // Строка-«мусор» высотой в несколько строк текста (вертикальная колонка,
        // слипшиеся баблы) не должна уходить в распознаватель одним куском.
        return lines.filter { it.width() > 2 && it.height() > 2 }
    }

    /** Заливка по порогу 0.3 — без OpenCV, как в KOMA: BFS + отбраковка мелочи. */
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
                if (visited[index] || map[y][x] < DET_PIXEL_THRESHOLD) continue
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
                            if (!visited[ni] && map[ny][nx] > DET_PIXEL_THRESHOLD) {
                                visited[ni] = true
                                queue[end++] = ni
                            }
                        }
                    }
                }
                if (end < MIN_COMPONENT_PIXELS || score / end < DET_COMPONENT_SCORE) continue
                if (maxX - minX < 2 || maxY - minY < 2) continue
                val expand = max(
                    2,
                    ((maxX - minX) * (maxY - minY) * 1.5f / (2 * (maxX - minX + maxY - minY))).roundToInt(),
                )
                boxes += Rect(
                    max(0, minX - expand),
                    max(0, minY - expand),
                    min(w, maxX + expand + 1),
                    min(h, maxY + expand + 1),
                )
            }
        }
        return boxes.sortedBy { it.top }
    }

    /**
     * CTC-жадный декодер. Вход 3x48xW, нормализация (x/255 - 0.5)/0.5,
     * ширина добивается нулями до 320: модель фиксированной минимальной ширины.
     */
    @Suppress("UNCHECKED_CAST")
    private fun decode(bitmap: Bitmap): Pair<String, Float> {
        if (bitmap.width < 2 || bitmap.height < 2) return "" to 0f
        val width = (REC_HEIGHT * bitmap.width / bitmap.height.toFloat()).roundToInt().coerceIn(8, MAX_REC_WIDTH)
        val padded = max(MIN_REC_WIDTH, width)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, REC_HEIGHT, true)
        val pixels = IntArray(width * REC_HEIGHT)
        scaled.getPixels(pixels, 0, width, 0, 0, width, REC_HEIGHT)
        if (scaled !== bitmap) scaled.recycle()

        val data = FloatArray(3 * REC_HEIGHT * padded)
        for (c in 0..2) {
            for (y in 0 until REC_HEIGHT) {
                for (x in 0 until width) {
                    data[c * REC_HEIGHT * padded + y * padded + x] =
                        (((pixels[y * width + x] shr (c * 8)) and 0xFF) / 255f - 0.5f) / 0.5f
                }
            }
        }

        val session = rec ?: return "" to 0f
        val index = dictionary
        return OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(data),
            longArrayOf(1, 3, REC_HEIGHT.toLong(), padded.toLong()),
        ).use { tensor ->
            OcrBreadcrumbs.mark(context, "onnx: распознавание строки ${padded}px")
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val values = (result[0].value as Array<Array<FloatArray>>)[0]
                val text = StringBuilder(values.size)
                var prev = 0
                var score = 0f
                var count = 0
                for (logits in values) {
                    var best = 0
                    for (i in logits.indices) if (logits[i] > logits[best]) best = i
                    if (best != 0 && best != prev) {
                        text.append(index.getOrElse(best) { "" })
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

    internal data class KomaRegion(
        val text: String,
        val box: OcrBoundingBox,
        val confidence: Float,
    )

    companion object {
        const val DET_PATH = "koma_ocr/det.onnx"
        const val REC_PATH = "koma_ocr/rec.onnx"
        const val DICT_PATH = "koma_ocr/dict.txt"

        const val DET_FLAT = "koma_det.onnx"
        const val REC_FLAT = "koma_rec.onnx"
        const val DICT_FLAT = "koma_dict.txt"

        private const val DET_LIMIT = 960f
        private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        private const val DET_PIXEL_THRESHOLD = 0.3f
        private const val DET_COMPONENT_SCORE = 0.5f
        private const val MIN_COMPONENT_PIXELS = 6
        private const val MAX_BOXES = 400

        private const val REC_HEIGHT = 48
        private const val MIN_REC_WIDTH = 320
        private const val MAX_REC_WIDTH = 960
        private const val MIN_LINE_SCORE = 0.25f

        /** Перекрытие по вертикали, при котором фрагменты — одна строка. */
        private const val LINE_MIN_VERTICAL_OVERLAP = 0.5f

        /** Допустимый горизонтальный разрыв между фрагментами строки (в высотах строки). */
        private const val LINE_MAX_GAP_FACTOR = 0.9f
    }
}
