package eu.kanade.tachiyomi.data.tts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Кириллический OCR путает цифры с буквами: «стол» приходит как «5тОл»,
 * «зАОО» как «3АОО». Цифра внутри слова с кириллицей — это буква.
 *
 * Настоящие числа («глава 5», «2/89») трогать нельзя: там кириллицы в том же
 * слове нет.
 */
class DigitLookalikeTest {

    private fun repair(text: String) = AutoReadEngine.repairDigitsInCyrillicWords(text)

    @Test
    fun `цифры внутри кириллического слова становятся буквами`() {
        // Регистр латинских букв сохраняется, цифра становится строчной «с».
        assertEquals("стОл", repair("5тОл"))
        assertEquals("зАОО", repair("3АОО"))
        assertEquals("восемь", repair("8осемь"))
    }

    @Test
    fun `настоящие числа остаются числами`() {
        assertEquals("глава 5", repair("глава 5"))
        assertEquals("2/89", repair("2/89"))
        assertEquals("15", repair("15"))
        assertEquals("2000", repair("2000"))
    }

    @Test
    fun `пунктуация и пробелы не меняются`() {
        assertEquals("Привет, мир! 5", repair("Привет, мир! 5"))
        // 5 внутри слова — это «с», а не цифра.
        assertEquals("асб", repair("а5б"))
    }

    @Test
    fun `слово без цифр не трогается`() {
        assertEquals("стол", repair("стол"))
        assertEquals("дом", repair("дом"))
    }
}
