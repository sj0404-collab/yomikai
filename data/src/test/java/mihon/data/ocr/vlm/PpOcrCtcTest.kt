package mihon.data.ocr.vlm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PpOcrCtcTest {

    // словарь: 0=blank, 1..5=П,Р,И,Ц,Й, 6=пробел (последний класс)
    private val charset = PpOcrCtc.loadCharset("П\nР\nИ\nЦ\nЙ\n")

    private fun logits(vararg perStep: Int): FloatArray {
        val classes = charset.size
        val out = FloatArray(perStep.size * classes)
        perStep.forEachIndexed { t, cls -> out[t * classes + cls] = 0.9f; out[t * classes] = if (cls == 0) 0.9f else 0.01f }
        return out
    }

    @Test
    fun `словарь начинается с blank и кончается пробелом`() {
        assertEquals("", charset.first())
        assertEquals(" ", charset.last())
        assertEquals("П", charset[1])
    }

    @Test
    fun `повторы подряд схлопываются`() {
        val r = PpOcrCtc.decode(logits(1, 0, 1, 0, 2), 5, charset.size, charset)
        assertEquals("ПИ", r.text)
    }

    @Test
    fun `повтор через blank остаётся двумя буквами`() {
        val r = PpOcrCtc.decode(logits(1, 0, 1), 3, charset.size, charset)
        assertEquals("ПП", r.text)
    }

    @Test
    fun `пробел последним классом попадает в текст`() {
        val r = PpOcrCtc.decode(logits(1, 6, 2), 3, charset.size, charset)
        assertEquals("П И", r.text)
    }

    @Test
    fun `слабый символ не добавляется при отсечке`() {
        val classes = charset.size
        val lg = FloatArray(4 * classes)
        lg[0 * classes + 1] = 0.9f      // П уверенно
        lg[1 * classes + 2] = 0.2f      // И слабо — должно быть отброшено
        lg[2 * classes + 3] = 0.8f      // Ц уверенно
        lg[3 * classes + 4] = 0.85f     // Й уверенно
        val r = PpOcrCtc.decode(lg, 4, classes, charset, minScore = 0.5f)
        assertEquals("ПЦЙ", r.text)
    }

    @Test
    fun `обрезанный вход не падает и не выдумывает текст`() {
        val r = PpOcrCtc.decode(FloatArray(3), 10, 50, charset)
        assertEquals("", r.text)
        assertTrue(r.symbols == 0)
    }
}
