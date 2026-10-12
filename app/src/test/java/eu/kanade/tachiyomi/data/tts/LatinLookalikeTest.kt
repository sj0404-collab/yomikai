package eu.kanade.tachiyomi.data.tts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Кириллический OCR регулярно подмешивает латинские буквы-омоглифы внутрь
 * слова («Bзлёт», «Cон», «здесь»). Такое слово TTS читает неверно, поэтому
 * латиница внутри слова заменяется на кириллическую.
 */
class LatinLookalikeTest {

    @Test
    fun `латинские буквы внутри слова заменяются на кириллицу`() {
        assertEquals("Взлёт", AutoReadEngine.replaceLatinLookalikes("Bзлёт"))
        assertEquals("Сон", AutoReadEngine.replaceLatinLookalikes("Cон"))
        assertEquals("всё", AutoReadEngine.replaceLatinLookalikes("всё"))
        assertEquals("привет", AutoReadEngine.replaceLatinLookalikes("привeт"))
        assertEquals("дом", AutoReadEngine.replaceLatinLookalikes("дoм"))
    }

    @Test
    fun `слово с латиницей больше половины не трогаем`() {
        // Это уже не опечатка внутри слова, а чужой текст (латинская вывеска).
        // Его должен отсечь фильтр иноязычных надписей.
        assertEquals("here we go", AutoReadEngine.replaceLatinLookalikes("here we go"))
        assertEquals("Ok", AutoReadEngine.replaceLatinLookalikes("Ok"))
    }

    @Test
    fun `кириллица не трогается и знаки сохраняются`() {
        assertEquals("Привет, мир!", AutoReadEngine.replaceLatinLookalikes("Привет, мир!"))
        // l и i в русском — другие буквы, их не переводим.
        assertEquals("lila", AutoReadEngine.replaceLatinLookalikes("lila"))
    }

    @Test
    fun `пунктуация и пробелы сохраняются между словами`() {
        assertEquals("а, б — в!", AutoReadEngine.replaceLatinLookalikes("а, б — в!"))
        assertEquals("дом 5", AutoReadEngine.replaceLatinLookalikes("дом 5"))
    }
}
