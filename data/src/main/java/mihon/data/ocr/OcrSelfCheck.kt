package mihon.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * Пошаговая самопроверка OCR.
 *
 * Падение нативного слоя (ONNX Runtime) не оставляет Java-трейса: процесс
 * просто умирает. Поэтому проверка разбита на короткие шаги, и КАЖДЫЙ шаг
 * помечается на диске ДО его начала. Если процесс умрёт, в журнале останется
 * последний начатый шаг — по нему видно, что именно падает: создание сессии,
 * вывод на маленьком входе, вывод на рабочем размере или CTC-декодирование.
 */
object OcrSelfCheck {

    private const val SAMPLE_TEXT = "ПРИВЕТ МИР 123"

    /** Сторона миниатюры реальной страницы для проверочных прогонов. */
    private const val PAGE_SIDE = 128

    /** Картинка с крупным чёрным текстом на белом: то, что движок обязан прочитать. */
    fun sampleBitmap(width: Int = 720, height: Int = 240): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = height * 0.32f
            isFakeBoldText = true
        }
        canvas.drawText(SAMPLE_TEXT, 24f, height * 0.6f, paint)
        return bitmap
    }

    /** Короткий статус в одну строку (для строки «Состояние ONNX»). */
    suspend fun status(context: Context): String {
        val engine = runCatching { KomaOnnxOcrEngine(context) }.getOrElse {
            return "ОШИБКА · движок не создан: ${describe(it)}"
        }
        return try {
            engine.ensureInitialized()
            "OK · сессии открыты"
        } catch (t: Throwable) {
            "ОШИБКА · сессии: ${describe(t)}"
        } finally {
            runCatching { engine.close() }
        }
    }

    /**
     * Полный прогон по шагам. Исключения ловятся, падение нативного слоя — нет:
     * именно поэтому каждый шаг сначала пишется в журнал.
     */
    /**
     * Полный прогон по шагам. Исключения ловятся, падение нативного слоя — нет:
     * именно поэтому каждый шаг сначала пишется в журнал, а после — сообщается
     * наружу через [progress] (отдельный процесс успевает записать его на диск
     * до того, как умрёт).
     *
     * Шаги подобраны так, чтобы различать причины:
     *  0 — сам ONNX Runtime на крошечной модели (не KOMA): если падает здесь,
     *      дело в рантайме/процессоре, а не в моделях и не в размере входа;
     *  3 — детектор на синтетическом входе 64x64 (гипотеза «маленький вход»);
     *  4 — детектор на РЕАЛЬНОЙ странице в рабочем размере;
     *  5 — распознаватель на реальных пикселях;
     *  6 — полное распознавание.
     */
    suspend fun run(context: Context, progress: (String) -> Unit = {}): String = buildString {
        appendLine("Yomikai · проверка KOMA/ONNX")
        appendLine("Модели: ${bundledModels(context)}")

        mark(context, "шаг 0/7: сам ONNX Runtime (крошечная модель, не KOMA)")
        progress("0/7 ONNX Runtime: начат")
        try {
            val ms = selfTestInference(context)
            appendLine("0/7 ONNX Runtime: OK · своя модель считается за $ms мс")
            progress("0/7 ONNX Runtime: OK")
        } catch (t: Throwable) {
            appendLine("0/7 ONNX Runtime: ОШИБКА ${describe(t)}")
            progress("0/7 ONNX Runtime: ОШИБКА")
            return@buildString
        }

        mark(context, "шаг 1/7: создаю движок KOMA")
        progress("1/7 движок KOMA: начат")
        val engine = try {
            KomaOnnxOcrEngine(context)
        } catch (t: Throwable) {
            appendLine("1/7 движок: ОШИБКА ${describe(t)}")
            progress("1/7 движок: ОШИБКА")
            return@buildString
        }

        try {
            mark(context, "шаг 2/7: сессии ONNX (детектор + распознаватель)")
            progress("2/7 сессии ONNX: начаты")
            try {
                engine.ensureInitialized()
                appendLine("2/7 сессии ONNX: открыты")
                progress("2/7 сессии ONNX: OK")
            } catch (t: Throwable) {
                appendLine("2/7 сессии ONNX: ОШИБКА ${describe(t)}")
                progress("2/7 сессии ONNX: ОШИБКА")
                return@buildString
            }

            // Синтетический вход 64x64 — проверка гипотезы «маленький вход».
            mark(context, "шаг 3/7: детектор, синтетический вход 64x64")
            progress("3/7 детектор 64x64: начат")
            try {
                val small = rawDetInference(engine, 64, 64)
                appendLine("3/7 детектор 64x64: OK (${small.first} выходов, ${small.second} мс)")
                progress("3/7 детектор 64x64: OK")
            } catch (t: Throwable) {
                appendLine("3/7 детектор 64x64: ОШИБКА ${describe(t)}")
                progress("3/7 детектор 64x64: ОШИБКА")
            }

            // Рабочий размер, но с настоящими пикселями страницы.
            val page = pagePixels(context)
            if (page == null) {
                appendLine("4/7 детектор 480x160: пропущен (нет картинки в assets)")
            } else {
                mark(context, "шаг 4/7: детектор, реальная страница 160x480")
                progress("4/7 детектор 160x480: начат")
                try {
                    val normal = rawDetInference(engine, 160, 480, page)
                    appendLine("4/7 детектор 160x480: OK (${normal.first} выходов, ${normal.second} мс)")
                    progress("4/7 детектор 160x480: OK")
                } catch (t: Throwable) {
                    appendLine("4/7 детектор 160x480: ОШИБКА ${describe(t)}")
                    progress("4/7 детектор 160x480: ОШИБКА")
                }
                mark(context, "шаг 5/7: распознаватель, реальные пиксели 48x320")
                progress("5/7 распознаватель 48x320: начат")
                try {
                    val rec = rawRecInference(engine, page)
                    appendLine("5/7 распознаватель: OK (${rec.first} классов, ${rec.second} мс)")
                    progress("5/7 распознаватель: OK")
                } catch (t: Throwable) {
                    appendLine("5/7 распознаватель: ОШИБКА ${describe(t)}")
                    progress("5/7 распознаватель: ОШИБКА")
                }
            }

            mark(context, "шаг 6/7: полное распознавание картинки с текстом")
            progress("6/7 полное распознавание: начато")
            val bitmap = sampleBitmap()
            try {
                val started = System.currentTimeMillis()
                val lines = engine.recognizeRegions(bitmap)
                appendLine(
                    "6/7 распознавание: ${lines.size} строк за ${System.currentTimeMillis() - started} мс",
                )
                appendLine("Текст: " + lines.joinToString(" | ") { it.text }.take(240).ifBlank { "(пусто)" })
                progress("6/7 распознавание: OK (${lines.size} строк)")
            } catch (t: Throwable) {
                appendLine("6/7 распознавание: ОШИБКА ${describe(t)}")
                progress("6/7 распознавание: ОШИБКА")
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        } finally {
            runCatching { engine.close() }
        }
    }

    /**
     * Сам ONNX Runtime, без единой модели KOMA: крошечная свёртка
     * (`assets/koma/models/selftest.onnx`, 1 КБ). Если умирает здесь — виноват
     * рантайм или процессор устройства, а не наши модели.
     */
    private fun selfTestInference(context: Context): Long {
        val path = OcrModelFiles.resolve(context, "koma_ocr/selftest.onnx")
            ?: error("нет selftest.onnx в APK")
        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions()
        val started = System.currentTimeMillis()
        env.createSession(path, opts).use { session ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(3 * 32 * 32)), longArrayOf(1, 3, 32, 32))
                .use { tensor ->
                    session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                        check(result.size() > 0)
                    }
                }
        }
        return System.currentTimeMillis() - started
    }

    /** Настоящие пиксели страницы из демо-книг APK, приведённые к 128x128 (CHW). */
    private fun pagePixels(context: Context): FloatArray? = runCatching {
        val book = context.assets.list("koma/books")?.firstOrNull() ?: return null
        val page = context.assets.list("koma/books/$book")?.firstOrNull { it.endsWith(".jpg") } ?: return null
        val decoded = context.assets.open("koma/books/$book/$page").use {
            android.graphics.BitmapFactory.decodeStream(it)
        } ?: return null
        val side = PAGE_SIDE
        val small = android.graphics.Bitmap.createScaledBitmap(decoded, side, side, true)
        if (small !== decoded && !decoded.isRecycled) decoded.recycle()
        val pixels = IntArray(side * side)
        small.getPixels(pixels, 0, side, 0, 0, side, side)
        val chw = FloatArray(3 * side * side)
        for (i in pixels.indices) {
            val c = pixels[i]
            chw[i] = ((c shr 16) and 0xFF) / 255f
            chw[side * side + i] = ((c shr 8) and 0xFF) / 255f
            chw[2 * side * side + i] = (c and 0xFF) / 255f
        }
        if (!small.isRecycled) small.recycle()
        chw
    }.getOrNull()

    /** Чистый вывод детектора на заданном размере — без постобработки. */
    private fun rawDetInference(
        engine: KomaOnnxOcrEngine,
        height: Int,
        width: Int,
        source: FloatArray? = null,
    ): Pair<Int, Long> {
        val data = sampleFrom(source, height, width)
        val started = System.currentTimeMillis()
        val session: OrtSession = engine.detSession()
        return OnnxTensor.createTensor(
            engine.environment,
            FloatBuffer.wrap(data),
            longArrayOf(1, 3, height.toLong(), width.toLong()),
        ).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                (result.size()) to (System.currentTimeMillis() - started)
            }
        }
    }

    /** Чистый вывод распознавателя: одна «строка» 48x320. */
    private fun rawRecInference(engine: KomaOnnxOcrEngine, source: FloatArray? = null): Pair<Int, Long> {
        val data = sampleFrom(source, 48, 320)
        val started = System.currentTimeMillis()
        val session: OrtSession = engine.recSession()
        return OnnxTensor.createTensor(
            engine.environment,
            FloatBuffer.wrap(data),
            longArrayOf(1, 3, 48L, 320L),
        ).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val values = (result[0].value as Array<Array<FloatArray>>)[0]
                values[0].size to (System.currentTimeMillis() - started)
            }
        }
    }

    /**
     * Ресайз настоящих пикселей (PAGE_SIDE x PAGE_SIDE, CHW) под нужный вход.
     * Нужно, чтобы рабочий размер проверялся на реальной странице, а не на нулях:
     * нули и настоящая картинка могут идти разными путями в ядрах ONNX.
     */
    private fun sampleFrom(source: FloatArray?, height: Int, width: Int): FloatArray {
        val out = FloatArray(3 * height * width)
        if (source == null) return out
        val side = PAGE_SIDE
        val plane = side * side
        for (c in 0 until 3) {
            for (y in 0 until height) {
                val sy = (y.toLong() * side / height).toInt().coerceIn(0, side - 1)
                for (x in 0 until width) {
                    val sx = (x.toLong() * side / width).toInt().coerceIn(0, side - 1)
                    out[c * height * width + y * width + x] = source[c * plane + sy * side + sx]
                }
            }
        }
        return out
    }

    private fun bundledModels(context: Context): String {
        return listOf(
            "koma_ocr/det.onnx" to "детектор",
            "koma_ocr/rec.onnx" to "распознаватель",
            "koma_ocr/dict.txt" to "словарь",
        ).joinToString(", ") { (path, title) ->
            val file = OcrModelFiles.resolve(context, path)?.let { java.io.File(it) }
            "$title ${if (file != null && file.length() > 0) "${file.length() / 1024} КБ" else "НЕТ"}"
        }
    }

    private fun mark(context: Context, step: String) = OcrBreadcrumbs.mark(context, step)

    private fun describe(t: Throwable): String =
        listOfNotNull(t::class.simpleName, t.message).joinToString(": ").ifBlank { "неизвестно" }
}
