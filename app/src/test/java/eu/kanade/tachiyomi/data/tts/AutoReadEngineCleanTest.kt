package eu.kanade.tachiyomi.data.tts

import mihon.domain.ocr.model.OcrBoundingBox
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Юнит-тесты чистки OCR-мусора и порядка чтения для авточтения.
 *
 * Фидбек с устройства: одиночные слова («Я», «но», «И») не читались, а знак
 * «!!» — читался; порядок на манге шёл «лесенкой». Ниже фиксируем исправленное
 * поведение чистыми функциями (без Android), которые гоняет CI.
 */
class AutoReadEngineCleanTest {

    @Test
    fun `russian single-letter words are meaningful`() {
        assertTrue(AutoReadEngine.isMeaningful("Я", "ru"))
        assertTrue(AutoReadEngine.isMeaningful("и", "ru"))
        assertTrue(AutoReadEngine.isMeaningful("но", "ru"))
        assertTrue(AutoReadEngine.isMeaningful("не", "ru"))
    }

    @Test
    fun `punctuation only is not meaningful`() {
        assertFalse(AutoReadEngine.isMeaningful("!!", "ru"))
        assertFalse(AutoReadEngine.isMeaningful("...", "ru"))
        assertFalse(AutoReadEngine.isMeaningful("?", "ru"))
    }

    @Test
    fun `garbage is removed but short words survive`() {
        assertEquals("Я", AutoReadEngine.cleanOcrGarbage("Я", "ru"))
        assertEquals("но", AutoReadEngine.cleanOcrGarbage("но", "ru"))
        assertEquals("", AutoReadEngine.cleanOcrGarbage("!!", "ru"))
        // Строка из одних палок/стрелок — целиком мусор.
        assertEquals("", AutoReadEngine.cleanOcrGarbage("| | > |", "ru"))
        // Осмысленная реплика не кромсается.
        assertEquals("МЕНЯ НА РУЧКИ", AutoReadEngine.cleanOcrGarbage("МЕНЯ НА РУЧКИ", "ru"))
    }

    @Test
    fun `short russian word helper`() {
        assertTrue(AutoReadEngine.isShortRussianWord("Я"))
        assertTrue(AutoReadEngine.isShortRussianWord("но"))
        assertTrue(AutoReadEngine.isShortRussianWord("и"))
        assertFalse(AutoReadEngine.isShortRussianWord("например"))
        assertFalse(AutoReadEngine.isShortRussianWord(""))
    }

    @Test
    fun `orderRegions groups by row and sorts ltr`() {
        val lines = listOf(
            AutoReadEngine.Line("A", OcrBoundingBox(0.1f, 0.05f, 0.4f, 0.20f)),
            AutoReadEngine.Line("B", OcrBoundingBox(0.6f, 0.05f, 0.9f, 0.20f)),
            AutoReadEngine.Line("C", OcrBoundingBox(0.1f, 0.55f, 0.5f, 0.70f)),
        )
        val out = AutoReadEngine.orderRegions(lines, "ltr")
        assertEquals(listOf("A", "B", "C"), out.map { it.text })
    }

    @Test
    fun `orderRegions sorts rtl within row`() {
        val lines = listOf(
            AutoReadEngine.Line("A", OcrBoundingBox(0.1f, 0.05f, 0.4f, 0.20f)),
            AutoReadEngine.Line("B", OcrBoundingBox(0.6f, 0.05f, 0.9f, 0.20f)),
            AutoReadEngine.Line("C", OcrBoundingBox(0.1f, 0.55f, 0.5f, 0.70f)),
        )
        val out = AutoReadEngine.orderRegions(lines, "rtl")
        assertEquals(listOf("B", "A", "C"), out.map { it.text })
    }

    @Test
    fun `orderRegions vertical sorts top to bottom`() {
        val lines = listOf(
            AutoReadEngine.Line("C", OcrBoundingBox(0.1f, 0.55f, 0.5f, 0.70f)),
            AutoReadEngine.Line("A", OcrBoundingBox(0.1f, 0.05f, 0.4f, 0.20f)),
        )
        val out = AutoReadEngine.orderRegions(lines, "vertical")
        assertEquals(listOf("A", "C"), out.map { it.text })
    }
}
