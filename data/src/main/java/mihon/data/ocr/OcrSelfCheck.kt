package mihon.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * Самопроверка OCR без телефона под рукой: открывает модели, распознаёт
 * картинку с известным текстом и возвращает отчёт одной строкой.
 *
 * Нужна потому, что жалоба «ONNX не работает / вылет» не говорит ничего о
 * причине: модель может не распаковаться, ONNX Runtime — не найти нужный
 * оператор, а детектор — вернуть нули. Отчёт показывает, на каком шаге всё
 * встало, и его можно просто скопировать и прислать.
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

    /**
     * Короткий статус для экрана настроек: поднимаются ли сессии и читается
     * ли тестовая картинка. Ровно одна строка, без трейса.
     */
    suspend fun status(context: Context): String {
        val started = System.currentTimeMillis()
        return try {
            val engine = KomaOnnxOcrEngine(context)
            try {
                engine.ensureInitialized()
                val bitmap = sampleBitmap(480, 160)
                val lines = try {
                    engine.recognizeRegions(bitmap)
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
                "OK · сессии открыты, строк: ${lines.size}, ${System.currentTimeMillis() - started} мс"
            } finally {
                runCatching { engine.close() }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            "ОШИБКА · ${t::class.simpleName}: ${t.message}"
        }
    }

    /**
     * Полный прогон основного (KOMA/ONNX) движка. Исключения не пробрасываются:
     * отчёт обязан получиться даже если движок не поднялся.
     */
    suspend fun run(context: Context): String = buildString {
        appendLine("Движок основной читалки: KOMA/ONNX")
        appendLine("Модели в APK: ${bundledModels(context)}")

        val engine = try {
            KomaOnnxOcrEngine(context)
        } catch (t: Throwable) {
            appendLine("Создание движка: ОШИБКА ${describe(t)}")
            return@buildString
        }

        try {
            engine.ensureInitialized()
            appendLine("Сессии ONNX: открыты (det + rec)")
        } catch (t: Throwable) {
            appendLine("Сессии ONNX: ОШИБКА ${describe(t)}")
            runCatching { engine.close() }
            return@buildString
        }

        val totalStart = System.currentTimeMillis()
        val bitmap = sampleBitmap()
        try {
            val lines = engine.recognizeRegions(bitmap)
            appendLine("Распознавание: ${lines.size} строк за ${System.currentTimeMillis() - totalStart} мс")
            appendLine("Текст: " + lines.joinToString(" | ") { it.text }.take(240).ifBlank { "(пусто)" })
        } catch (t: Throwable) {
            appendLine("Распознавание: ОШИБКА ${describe(t)}")
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        runCatching { engine.close() }
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

    private fun describe(t: Throwable): String =
        listOfNotNull(t::class.simpleName, t.message).joinToString(": ").ifBlank { "неизвестно" }
}
