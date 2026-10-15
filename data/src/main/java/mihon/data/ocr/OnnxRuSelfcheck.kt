package mihon.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * Самопроверка локального ONNX CRNN-движка «в один тап»: рендерим тестовую
 * строку и прогоняем через ПОЛНЫЙ пайплайн (сегментер → сессия → CTC-декод
 * → пост-процессинг). Возвращает человекочитаемый итог — распознанный текст
 * или точный текст ошибки (sessionError), чтобы жалоба «нет результатов»
 * превращалась в конкретику без logcat.
 */
object OnnxRuSelfcheck {

    private const val TEST_TEXT = "Тест строки 123?"

    suspend fun run(context: Context): String {
        val engine = OnnxRuOcrEngine(context)
        return try {
            val bmp = Bitmap.createBitmap(900, 128, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            val paint = Paint().apply {
                isAntiAlias = true
                color = Color.BLACK
                textSize = 64f
            }
            canvas.drawText(TEST_TEXT, 32f, 88f, paint)

            val lines = engine.recognizeLines(bmp)
            val read = lines.map { it.second }.filter { it.isNotBlank() }
            when {
                read.isNotEmpty() -> "ОК (сегментер: ${lines.size} строк): «" +
                    read.joinToString(" / ") + "» [контроль: «$TEST_TEXT»]"
                !engine.lastError().isNullOrBlank() ->
                    "ОШИБКА движка: ${engine.lastError()}"
                else -> "Движок запустился, но тестовую строку не прочитал " +
                    "(сегментер строк: ${lines.size})"
            }
        } catch (t: Throwable) {
            "ОШИБКА: ${t.message ?: t.javaClass.simpleName}"
        } finally {
            engine.close()
        }
    }
}
