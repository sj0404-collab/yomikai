package mihon.data.ocr

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Тесты автоматического исправления латиница/кириллица в OCR-тексте.
 *
 * Ключевой кейс из фидбека: русское слово/имя, распознанное латиницей
 * («Мибли» → «Mibli»), должно читаться ЦЕЛЬНО по-русски, а не диктоваться
 * по буквам («эм и бэ эл и»). Для этого одиночное латинское слово, которое
 * транслитерацией полностью становится кириллицей, переводится автоматически.
 */
class CyrillicTranslitFixerTest {

    @Test
    fun `pure latin proper noun becomes cyrillic for reading`() {
        // Mibli -> Мибли (M→М, i→и, b→б, l→л, i→и). Ранее возвращалось как есть
        // латиницей, и TTS диктовал по буквам.
        assertEquals("Мибли", CyrillicTranslitFixer.autoFixCyrillic("Mibli"))
    }

    @Test
    fun `cyrillic text stays unchanged`() {
        assertEquals("Привет", CyrillicTranslitFixer.autoFixCyrillic("Привет"))
    }

    @Test
    fun `lookalike cyrillic is fixed to real cyrillic`() {
        // X→Х, A→А: «XAA» омоглифная кириллица -> «ХАА».
        assertEquals("ХАА", CyrillicTranslitFixer.autoFixCyrillic("XAA"))
    }

    @Test
    fun `multi-word latin is not force-transliterated`() {
        // Пробел -> не одиночное слово, оставляем как есть (не выдумываем).
        val out = CyrillicTranslitFixer.autoFixCyrillic("Hello world")
        assertTrue(out.contains(" "), "ожидалось сохранение пробелов, got=$out")
    }
}
