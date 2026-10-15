package mihon.data.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.nio.FloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import mihon.domain.ocr.model.OcrBoundingBox

/**
 * Компактный верх стека для каналов диагностики: «length=4; index=4» без
 * фреймов ни о чём пользователю не говорит, а с фреймами — это готовый
 * bug report (file:line) прямо на экране настроек.
 */
private fun Throwable.brief(): String {
    val frames = stackTrace
        .asSequence()
        .filter { it.className.startsWith("mihon.") || it.className.startsWith("ai.onnxruntime") }
        .take(6)
        .map { "${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
        .joinToString(" <- ")
    return "${message ?: javaClass.simpleName} @ $frames"
}

/** Aссеты локальной onnx-модели CRNN+CTC (кириллица), вкомпилированы в APK. */
private const val MODEL_ASSET = "onnx_crnn/best.onnx"
private const val META_ASSET = "onnx_crnn/best.meta.json"

/**
 * Область локального ONNX OCR для репозитория: нормированные координаты
 * строки, пиксельная рамка и прочитанный текст.
 */
class OnnxRuRegion(
    val normalizedLeft: Double,
    val normalizedTop: Double,
    val normalizedWidth: Double,
    val normalizedHeight: Double,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val text: String,
    val conf: Float,
) {
    val boundingBox
        get() = OcrBoundingBox(
            left = x.toFloat(),
            top = y.toFloat(),
            right = (x + width).toFloat(),
            bottom = (y + height).toFloat(),
        )
}

/**
 * Локальный офлайн OCR на нашей CRNN+CTC-модели: сегментация страницы на
 * строки и распознавание каждой прямо в приложении, без сети.
 *
 * Пайплайн зеркалит эталонный `~/yomikai-ocr` пакет:
 *  1. полутон (luma→int) + бинаризация порогом 170;
 *  2. морфологическое закрытие 25×3 ×2 (склейка букв строки в один блок);
 *  3. 8-связные компоненты → рамки строк в читательском порядке (сверху-вниз);
 *  4. каждая строка → нормализация h=32 (ширина ×scale до кратной 8,
 *     фон 1.0f) → `best.onnx` (1×1×32×W float32) → greedy CTC-декод
 *     → пост-правила пунктации.
 *
 * Эталон: yomikai_ocr/segment.py (cv2-port) + yomikai_ocr/infer.py (он-онкс).
 */
internal class OnnxRuOcrEngine(
    private val context: Context,
) : LineOcrEngine {

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var sessionError: String? = null
    private val sessionMutex = Mutex()
    private val inferenceMutex = Mutex()

    // ---- ONNX-сессия ----------------------------------------------------

    private suspend fun obtainSession(): OrtSession {
        sessionMutex.withLock {
            session?.let { return it }
            return runCatching {
                val e = OrtEnvironment.getEnvironment()
                env = e
                e.createSession(modelPath()).also { session = it }
            }.onFailure { t ->
                sessionError = t.brief()
                // Если модель в кэше оказалась битой (оборванная копия, не хватило
                // места) — удаляем её: следующая попытка перекачает файл из
                // ассетов, вместо того чтобы падать на битом файле вечно.
                runCatching { modelCacheFile(MODEL_ASSET).delete() }
            }
                .getOrThrow()
        }
    }

    private fun modelCacheFile(name: String): File =
        File(context.cacheDir, name.replace('/', '_'))

    /**
     * Путь к модели: сначала скачанная пользователем копия (пакет
     * `onnx_ru` в «Управлении локальными OCR-моделями» — загружается
     * прямо с релиза GitHub, в обход APK), затем встроенный asset.
     * Канал спасает, если копия в APK окажется устаревшей или битой.
     */
    private fun modelPath(): String =
        OcrModelFiles.resolve(context, MODEL_ASSET) ?: assetFile(MODEL_ASSET).absolutePath

    /**
     * Скопировать ассет в кэш для нативного createSession (нужен путь файла).
     * Раньше проверялось только наличие файла length()>0: первое оборванное
     * копирование (нет места, убит процесс) оставляло обрезанный файл, и ORT
     * падал на нём при КАЖДОМ запуске, навсегда. Теперь копируем атомарно
     * (tmp → rename) и сверяем размер с ассетом; кривой файл перезаписываем.
     */
    private fun assetFile(name: String): File {
        val dest = modelCacheFile(name)
        val expected = runCatching {
            context.assets.openFd(name).use { it.length }
        }.getOrDefault(-1L)
        if (dest.isFile && expected > 0 && dest.length() == expected) return dest
        dest.delete()
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        runCatching { tmp.delete() }
        context.assets.open(name).use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        val copied = tmp.length()
        if (expected > 0 && copied != expected) {
            tmp.delete()
            throw java.io.IOException("asset copy truncated: $name ($copied != $expected)")
        }
        if (!tmp.renameTo(dest)) {
            throw java.io.IOException("cannot place asset cache: ${dest.absolutePath}")
        }
        return dest
    }

    /** Отладочный канал: ассеты читаются, алфавит ядра совместим. */
    fun obtainCharset(): Boolean = runCatching {
        context.assets.open(META_ASSET).use { it.readBytes().isNotEmpty() }
    }.getOrDefault(false)

    /**
     * Текст последней ошибки сессии/инференса — для каналов диагностики
     * (стадий OCR-прохода, истории сканирований), чтобы «нет результатов»
     * не было молчаливой чёрной дырой.
     */
    fun lastError(): String? = sessionError

    // ---- LineOcrEngine --------------------------------------------------

    override suspend fun recognizeText(image: Bitmap): String {
        return recognizeLines(image).joinToString("\n") { it.second }
    }

    override suspend fun recognizeLine(line: Bitmap): String = readLine(line)

    // ---- Пайплайн строк --------------------------------------------------

    internal suspend fun recognizeLines(page: Bitmap): List<Pair<OnnxRegionBox, String>> =
        withContext(Dispatchers.Default) {
            val w = page.width
            val h = page.height
            if (w <= 0 || h <= 0) return@withContext emptyList()
            val scaleIn = minOf(1f, OnnxRuPageSegmenter.SEGMENT_MAX_DIM.toFloat() / maxOf(w, h))
            val sw = maxOf(1, (w * scaleIn).roundToInt())
            val sh = maxOf(1, (h * scaleIn).roundToInt())
            val scaled = if (sw == w && sh == h) page
            else Bitmap.createScaledBitmap(page, sw, sh, true)
            // ВНИМАНИЕ на преобразование в яркость: getPixels отдаёт упакованный
            // ARGB Int, а не полутон 0..255. Пиксели с альфой 0xFF — отрицательные
            // Int, поэтому сырое `gray < 170` в бинаризаторе сегментера считало
            // бы ВСЕ пиксели чернилами: маска сплошная → один бокс на всю
            // страницу → «нет результатов» при любой рабочей модели.
            val argb = IntArray(sw * sh)
            scaled.getPixels(argb, 0, sw, 0, 0, sw, sh)
            val gray = IntArray(sw * sh) { i -> luma(argb[i]) }
            val boxes = OnnxRuPageSegmenter.findLineBoxes(gray, sw, sh)
            val scaleOut = if (scaleIn < 1f) 1f / scaleIn else 1f
            boxes.map { box ->
                val crop = Bitmap.createBitmap(scaled, box.x, box.y, box.width, box.height)
                val text = readLine(crop)
                OnnxRegionBox(
                    (box.x * scaleOut).roundToInt(),
                    (box.y * scaleOut).roundToInt(),
                    (box.width * scaleOut).roundToInt(),
                    (box.height * scaleOut).roundToInt(),
                ) to text
            }
        }

    /**
     * Распознавание для репозитория: сегментируем, читаем, отдаём области
     * с нормированными/пиксельными координатами и текстом.
     */
    internal suspend fun recognizeRegionsInternal(page: Bitmap): List<OnnxRuRegion> =
        withContext(Dispatchers.Default) {
            val pageW = page.width
            val pageH = page.height
            recognizeLines(page).mapNotNull { (box, text) ->
                if (text.isBlank()) return@mapNotNull null
                OnnxRuRegion(
                    normalizedLeft = box.x.toDouble() / pageW,
                    normalizedTop = box.y.toDouble() / pageH,
                    normalizedWidth = box.width.toDouble() / pageW,
                    normalizedHeight = box.height.toDouble() / pageH,
                    x = box.x,
                    y = box.y,
                    width = box.width,
                    height = box.height,
                    text = text,
                    conf = 0f,
                )
            }
        }

    // ---- Одна строка ----------------------------------------------------

    internal suspend fun readLine(line: Bitmap): String {
        val w = line.width
        val h = line.height
        if (w <= 0 || h <= 0) return ""
        val scale = OnnxRuOcrCore.LINE_HEIGHT.toFloat() / h.toFloat()
        val newW = (w * scale).roundToInt()
            .coerceIn(8, OnnxRuOcrCore.LINE_HEIGHT * OnnxRuOcrCore.LINE_MAX_ASPECT)
        val resized = if (w == newW && h == OnnxRuOcrCore.LINE_HEIGHT) line
        else Bitmap.createScaledBitmap(line, newW, OnnxRuOcrCore.LINE_HEIGHT, true)
        val paddedW = ((newW + 7) / 8) * 8
        val floats = FloatArray(OnnxRuOcrCore.LINE_HEIGHT * paddedW) { 1f }
        val px = IntArray(newW)
        for (y in 0 until OnnxRuOcrCore.LINE_HEIGHT) {
            resized.getPixels(px, 0, newW, 0, y, newW, 1)
            val base = y * paddedW
            for (x in 0 until newW) {
                floats[base + x] = luma(px[x]) / 255f
            }
        }
        return runCatching {
            val s = obtainSession()
            val env = this.env ?: OrtEnvironment.getEnvironment()
            inferenceMutex.withLock {
                val shape = longArrayOf(1, 1, OnnxRuOcrCore.LINE_HEIGHT.toLong(), paddedW.toLong())
                OnnxTensor.createTensor(env, FloatBuffer.wrap(floats), shape).use { tensor ->
                    val inputName = s.inputNames.first()
                    s.run(mapOf(inputName to tensor)).use { results ->
                        val raw = results.firstOrNull()?.value ?: return@use ""
                        val flat = flatten(raw) ?: return@use ""
                        val frames = flat.size / OnnxRuOcrCore.NUM_CLASSES
                        if (frames <= 0) return@use ""
                        val decoded = OnnxRuOcrCore.greedyDecode(flat, frames)
                        OnnxRuOcrCore.postprocess(decoded.first)
                    }
                } ?: ""
            } as String
        }.onFailure { t ->
            sessionError = "inference: " + t.brief()
        }.getOrDefault("")
    }

    /** Извлечь логиты формы (T',1,K) или (T',K) в плоский массив. */
    private fun flatten(raw: Any): FloatArray? = when (raw) {
        is FloatArray -> raw
        is Array<*> -> {
            @Suppress("UNCHECKED_CAST")
            val frames = raw as Array<Array<FloatArray>>
            val k = frames.firstOrNull()?.firstOrNull()?.size ?: return null
            val out = FloatArray(frames.size * k)
            for (t in frames.indices) {
                System.arraycopy(frames[t][0], 0, out, t * k, k)
            }
            out
        }
        else -> null
    }

    /** PIL "L": L = (R*299 + G*587 + B*114) // 1000, по контракту с инференсом. */
    private fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    // ---- Прочие общие интерфейсы ----------------------------------------

    /** Проектный канал конфигурации фона, для универсальности хранилища. */
    fun setRegionBackground(background: String) {
        // Локальный движок: фон под строками не цифруется; noop для универсейти.
    }

    /** Публичная обёртка репозитория: всегда page-scan (mode/pageHash — общие). */
    suspend fun recognizeRegions(
        page: Bitmap,
        mode: Int = 0,
        pageHash: Long = 0L,
    ): List<OnnxRuRegion> {
        if (page.width <= 0 || page.height <= 0) return emptyList()
        return recognizeRegionsInternal(page)
    }

    /** Закрыть сессию и окружение (пересоздастся при следующем запросе). */
    override fun close() {
        runCatching { session?.close() }
        session = null
        runCatching { env?.close() }
        env = null
        sessionError = null
    }
}
