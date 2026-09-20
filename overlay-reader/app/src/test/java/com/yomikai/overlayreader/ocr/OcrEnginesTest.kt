package com.yomikai.overlayreader.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CyrillicTranslitFixerTest {

    @Test
    fun `lookalikes convert latin omoglyphs to cyrillic`() {
        assertEquals("ПРИВЕТ", CyrillicTranslitFixer.fixLookalikes("ПPИBET"))
        assertEquals("СЕТКА", CyrillicTranslitFixer.fixLookalikes("CETKA"))
        assertEquals("ВЕТКА", CyrillicTranslitFixer.fixLookalikes("BETKA"))
        assertEquals("мама", CyrillicTranslitFixer.fixLookalikes("мама"))
    }

    @Test
    fun `translit maps russian words to cyrillic`() {
        assertEquals("привет мир", CyrillicTranslitFixer.translitToCyrillic("privet mir"))
        assertEquals("здравствуй", CyrillicTranslitFixer.translitToCyrillic("zdravstvuj"))
        assertEquals("щука", CyrillicTranslitFixer.translitToCyrillic("shchuka"))
    }

    @Test
    fun `auto fix prefers lookalikes`() {
        assertEquals("ПРИВЕТ МИР", CyrillicTranslitFixer.autoFixCyrillic("ПPИBET МИP"))
    }

    @Test
    fun `auto fix transliterates all-latin word`() {
        assertEquals("привет", CyrillicTranslitFixer.autoFixCyrillic("privet"))
    }

    @Test
    fun `email-like strings are not blanked`() {
        val out = CyrillicTranslitFixer.autoFixCyrillic("Привет, мир!")
        assertTrue(out.contains("Привет"))
    }
}

class OcrTextCleanerTest {

    @Test
    fun `clean keeps text and punctuation`() {
        assertEquals("Привет, мир 123", OcrTextCleaner.clean("Привет, мир  @# 123"))
    }

    @Test
    fun `postprocess joins hyphens before cleaning`() {
        assertEquals("ХОРОШО", OcrTextCleaner.postprocess("ХО-\nРОШО"))
    }

    @Test
    fun `postprocess merges dvoeno`() {
        assertEquals("очень хорошо", OcrTextCleaner.postprocess("очень\nхорошо"))
    }

    @Test
    fun `clean removes emoji and unterminated junk`() {
        assertEquals("Погода сегодня", OcrTextCleaner.postprocess("Погода\nсегодня ✈"))
    }
}

class GlyphTemplatesTest {

    @Test
    fun `toCellMask returns fixed cell size`() {
        val mask = GlyphTemplates.toCellMask(50, 50) { x, y -> x in 10..20 && y in 10..12 }
        assertEquals(GlyphTemplates.CELL_W * GlyphTemplates.CELL_H, mask.size)
    }

    @Test
    fun `toCellMask centers small blob`() {
        val mask = GlyphTemplates.toCellMask(8, 8) { x, y -> x == 4 && y == 4 }
        val ones = mask.count { it == 1f }
        assertTrue("single-pixel blob renders one cell, got $ones", ones == 1)
    }

    @Test
    fun `empty mask is blank`() {
        val mask = GlyphTemplates.toCellMask(8, 8) { _, _ -> false }
        assertTrue(mask.all { it == 0f })
    }
}