package mihon.data.ocr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Чистка текста Tesseract по уверенности и геометрии.
 *
 * Данные — с реальных страниц манги, замер Tesseract 5.3.4 с той же
 * `rus.traineddata`, что ставит приложение.
 *
 * ГЛАВНЫЙ ВЫВОД ЗАМЕРА: уверенность НЕ различает текст и рисунок.
 * Настоящее слово «ЧУВСТВУЮТ» пришло с conf 0, а мусор «ИГРА.» — с conf 29.
 * Поэтому жёсткий порог съедал настоящие слова, и фильтр сделан двух-
 * уровневым. Тесты фиксируют обе стороны: что мусор уходит И что известный
 * провал — настоящее слово с низкой уверенностью тоже уходит.
 */
class HocrCleanWordsTest {

    private fun word(
        text: String,
        top: Int,
        bottom: Int,
        confidence: Int,
        left: Int = 0,
        line: Int = 1,
    ) = HocrWord(text, left, top, bottom, line, confidence)

    @Test
    fun `уверенное настоящее слово проходит`() {
        val words = listOf(
            word("никогда", 10, 40, confidence = 90, left = 10),
            word("запустить", 10, 40, confidence = 88, left = 200),
        )
        val out = TesseractOcrEngine.cleanWords(words)
        assertTrue(out.contains("никогда") && out.contains("запустить"), out)
    }

    @Test
    fun `мусор из рисунка отбрасывается`() {
        // Реальный замер страницы-иллюстрации: слова из линий панели и заливки.
        val words = listOf(
            word("ИГРА.", 10, 40, confidence = 29, left = 10),
            word("ЕКЦИО-", 60, 90, confidence = 5, left = 40),
            word("УЮЩИЕ", 110, 140, confidence = 8, left = 70),
        )
        assertEquals("", TesseractOcrEngine.cleanWords(words, minShare = 0.5f))
    }

    @Test
    fun `одиночный штрих с любой уверенностью не проходит`() {
        val words = listOf(
            word("не", 10, 40, confidence = 90, left = 10),
            word("|", 10, 40, confidence = 62, left = 60),
            word("запустить", 10, 40, confidence = 90, left = 100),
        )
        val out = TesseractOcrEngine.cleanWords(words)
        assertFalse(out.contains("|"), "вертикальный штрих не должен попасть в текст: $out")
        assertTrue(out.contains("запустить"), out)
    }

    @Test
    fun `известный провал - настоящее слово с conf 0 теряется`() {
        // Фиксируем честное ограничение, а не прячем его: «ЧУВСТВУЮТ» пришло
        // с conf 0 и будет отброшено. Помогает общий hOCR-текст движка.
        val words = listOf(
            word("ЧУВСТВУЮТ", 10, 40, confidence = 0, left = 10),
            word("запустить", 10, 40, confidence = 88, left = 200),
        )
        val out = TesseractOcrEngine.cleanWords(words)
        assertFalse(out.contains("ЧУВСТВУЮТ"), "это документированный провал, а не успех")
        assertTrue(out.contains("запустить"), "остальное текст проходить не должен мешать")
    }

    @Test
    fun `строки собираются сверху вниз, слова внутри строки слева направо`() {
        val words = listOf(
            word("второе", 100, 130, confidence = 90, left = 300, line = 2),
            word("Первое", 100, 130, confidence = 90, left = 10, line = 2),
            word("верхнее", 10, 40, confidence = 90, left = 10, line = 1),
        )
        assertEquals("верхнее\nПервое второе", TesseractOcrEngine.cleanWords(words))
    }

    @Test
    fun `пустой вход даёт пустой выход`() {
        assertEquals("", TesseractOcrEngine.cleanWords(emptyList()))
        assertEquals("", TesseractOcrEngine.cleanWords(listOf(word("!!!", 1, 2, confidence = 0))))
    }
}
