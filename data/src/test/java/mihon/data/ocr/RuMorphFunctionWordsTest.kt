package mihon.data.ocr

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Тесты классификации слов для цветовой подсветки OCR-плашки: служебные
 * части речи и местоимения красятся отдельным цветом от «реплик».
 */
class RuMorphFunctionWordsTest {

    @Test
    fun `pronouns are function words`() {
        for (w in listOf("я", "ты", "он", "она", "мы", "они", "меня", "его", "ей", "этот", "это", "все", "твой", "что", "кто", "который", "никто")) {
            assertTrue(RuMorph.isFunctionWord(w), "местоимение: $w")
        }
    }

    @Test
    fun `prepositions conjunctions and particles are function words`() {
        for (w in listOf("в", "на", "под", "из-за", "и", "но", "или", "чтобы", "нежели", "не", "бы", "же", "лишь", "даже", "вот")) {
            assertTrue(RuMorph.isFunctionWord(w), "служебное: $w")
        }
    }

    @Test
    fun `content words are not function words`() {
        for (w in listOf("кота", "бежали", "мама", "дерево", "красивый", "шла", "сестра", "прятался")) {
            assertFalse(RuMorph.isFunctionWord(w), "реплика: $w")
        }
    }

    @Test
    fun `case and punctuation are ignored`() {
        assertTrue(RuMorph.isFunctionWord("ОН"))
        assertTrue(RuMorph.isFunctionWord("его,"))
        assertTrue(RuMorph.isFunctionWord("«это»"))
        assertFalse(RuMorph.isFunctionWord("//"))
        assertFalse(RuMorph.isFunctionWord(""))
    }

    @Test
    fun `non cyrillic and whitespace are not function words`() {
        assertFalse(RuMorph.isFunctionWord("you"))
        assertFalse(RuMorph.isFunctionWord(" привет "))
    }
}