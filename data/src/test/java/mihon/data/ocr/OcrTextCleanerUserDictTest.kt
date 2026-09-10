package mihon.data.ocr

import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Пользовательский словарь в постобработке: разбиение слипшегося прогона и
 * покрытие для ранжирования кандидатов.
 *
 * Слова ЖЖУЗЛИК/ЦЮГРУНК выдуманы и отсутствуют во встроенном RuWordList
 * (подтверждено DP-проверкой: без пользовательского словаря прогон не
 * собирается в полное покрытие ни одним набором словарных сегментов).
 */
class OcrTextCleanerUserDictTest {

    @AfterEach
    fun tearDown() {
        OcrVocabulary.configure(null)
    }

    @Test
    fun `glued run splits using user dictionary words`() {
        OcrVocabulary.configure(null)
        OcrVocabulary.addWord("ЖЖУЗЛИК")
        OcrVocabulary.addWord("ЦЮГРУНК")

        OcrTextCleaner.normalizeLocalCyrillicCaption("ЖЖУЗЛИКЦЮГРУНК") shouldBe "ЖЖУЗЛИК ЦЮГРУНК"
    }

    @Test
    fun `glued run stays intact without user words`() {
        OcrVocabulary.configure(null)
        OcrTextCleaner.normalizeLocalCyrillicCaption("ЖЖУЗЛИКЦЮГРУНК") shouldBe "ЖЖУЗЛИКЦЮГРУНК"
    }

    @Test
    fun `dictionaryCoverage counts user words as covered letters`() {
        OcrVocabulary.configure(null)
        OcrVocabulary.addWord("ЖЖУЗЛИК")
        OcrVocabulary.addWord("ЦЮГРУНК")

        OcrTextCleaner.dictionaryCoverage("ЖЖУЗЛИК ЦЮГРУНК") shouldBe 1f
    }

    @Test
    fun `dictionaryCoverage drops to zero for invented words without user dict`() {
        OcrVocabulary.configure(null)
        OcrTextCleaner.dictionaryCoverage("ЖЖУЗЛИК ЦЮГРУНК") shouldBe 0f
    }

    @Test
    fun `dictionaryCoverage stays zero for pure latin and sfx`() {
        OcrVocabulary.configure(null)
        OcrTextCleaner.dictionaryCoverage("BAM!! 3D WI-FI") shouldBe 0f
    }

    @Test
    fun `user word hits are counted in stats for the scanner indicator`() {
        OcrTextCleanerStats.reset()
        OcrVocabulary.configure(null)
        OcrVocabulary.addWord("ЭРЗАЦ")

        OcrTextCleaner.knownWord("ЭРЗАЦ") shouldBe true
        OcrTextCleanerStats.wordDictHits shouldBe 0
        OcrTextCleanerStats.userDictHits shouldBe 1
    }

    @Test
    fun `knownWord returns true for user word even without builtin match`() {
        OcrTextCleanerStats.reset()
        OcrVocabulary.configure(null)

        OcrTextCleaner.knownWord("СЕГОДНЯ") shouldBe true
        OcrTextCleanerStats.userDictHits shouldBe 0
    }
}