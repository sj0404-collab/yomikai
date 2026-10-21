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
    suspend fun run(context: Context): String = buildString {
        appendLine("Yomikai · проверка KOMA/ONNX")
        appendLine("Модели: ${bundledModels(context)}")

        mark(context, "шаг 1/6: создаю движок")
        val engine = try {
            KomaOnnxOcrEngine(context)
        } catch (t: Throwable) {
            appendLine("1/6 движок: ОШИБКА ${describe(t)}")
            return@buildString
        }

        try {
            mark(context, "шаг 2/6: сессии ONNX (детектор + распознаватель)")
            try {
                engine.ensureInitialized()
                appendLine("2/6 сессии ONNX: открыты")
            } catch (t: Throwable) {
                appendLine("2/6 сессии ONNX: ОШИБКА ${describe(t)}")
                return@buildString
            }

            // Вывод проверяем на двух разных входах: если дело в размере или в
            // конкретной модели, это станет видно сразу.
            mark(context, "шаг 3/6: детектор, маленький вход 64x64")
            try {
                val small = rawDetInference(engine, 64, 64)
                appendLine("3/6 детектор 64x64: OK (${small.first} выходов, ${small.second} мс)")
            } catch (t: Throwable) {
                appendLine("3/6 детектор 64x64: ОШИБКА ${describe(t)}")
            }

            mark(context, "шаг 4/6: детектор, рабочий вход 480x160")
            try {
                val normal = rawDetInference(engine, 160, 480)
                appendLine("4/6 детектор 480x160: OK (${normal.first} выходов, ${normal.second} мс)")
            } catch (t: Throwable) {
                appendLine("4/6 детектор 480x160: ОШИБКА ${describe(t)}")
            }

            mark(context, "шаг 5/6: распознаватель, строка 48x320")
            try {
                val rec = rawRecInference(engine)
                appendLine("5/6 распознаватель: OK (${rec.first} классов, ${rec.second} мс)")
            } catch (t: Throwable) {
                appendLine("5/6 распознаватель: ОШИБКА ${describe(t)}")
            }

            mark(context, "шаг 6/6: полное распознавание картинки с текстом")
            val bitmap = sampleBitmap()
            try {
                val started = System.currentTimeMillis()
                val lines = engine.recognizeRegions(bitmap)
                appendLine(
                    "6/6 распознавание: ${lines.size} строк за ${System.currentTimeMillis() - started} мс",
                )
                appendLine("Текст: " + lines.joinToString(" | ") { it.text }.take(240).ifBlank { "(пусто)" })
            } catch (t: Throwable) {
                appendLine("6/6 распознавание: ОШИБКА ${describe(t)}")
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        } finally {
            runCatching { engine.close() }
        }
    }

    /** Чистый вывод детектора на заданном размере — без постобработки. */
    private fun rawDetInference(engine: KomaOnnxOcrEngine, height: Int, width: Int): Pair<Int, Long> {
        val data = FloatArray(3 * height * width)
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
    private fun rawRecInference(engine: KomaOnnxOcrEngine): Pair<Int, Long> {
        val data = FloatArray(3 * 48 * 320)
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
