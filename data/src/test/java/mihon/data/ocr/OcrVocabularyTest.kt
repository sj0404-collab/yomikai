package mihon.data.ocr

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class OcrVocabularyTest {

    @AfterEach
    fun tearDown() {
        OcrVocabulary.configure(null)
    }

    @Test
    fun `memory-only dictionary adds and remembers words without file`() {
        OcrVocabulary.configure(null)
        OcrVocabulary.addFromText("Петрович домой") shouldBe 2
        OcrVocabulary.isUserWord("ПЕТРОВИЧ") shouldBe true
        OcrVocabulary.isUserWord("петрович") shouldBe true
        OcrVocabulary.isKnownWord("домой") shouldBe true
    }

    @Test
    fun `addFromText ignores punctuation and garbage but counts new words only`() {
        OcrVocabulary.configure(null)
        OcrVocabulary.addFromText("Охотничий-пёс! … «ЛЕЗВИЕ», 123") shouldBe 3
        OcrVocabulary.addFromText("лезвие") shouldBe 0
        OcrVocabulary.size() shouldBe 3
        OcrVocabulary.words().contains("охотничий-пёс") shouldBe true
    }

    @Test
    fun `addWord and removeWord round trip`() {
        OcrVocabulary.configure(null)
        OcrVocabulary.addWord(" аки ") shouldBe true
        OcrVocabulary.addWord("АКИ") shouldBe false
        OcrVocabulary.removeWord("аки") shouldBe true
        OcrVocabulary.size() shouldBe 0
    }

    @Test
    fun `words persist to the file and reload`() {
        val dir = kotlin.io.path.createTempDirectory("ocr-vocab").toFile()
        val file = java.io.File(dir, "vocab.txt")
        try {
            OcrVocabulary.configure(file.absolutePath)
            OcrVocabulary.addWord("Петрович")
            OcrVocabulary.configure(file.absolutePath)
            OcrVocabulary.isUserWord("петрович") shouldBe true
            OcrVocabulary.size() shouldBe 1
        } finally {
            dir.deleteRecursively()
            OcrVocabulary.configure(null)
        }
    }

    @Test
    fun `clear empties the dictionary`() {
        OcrVocabulary.configure(null)
        OcrVocabulary.addFromText("ИВАНОВ ЕРШОВ") shouldBe 2
        OcrVocabulary.clear()
        OcrVocabulary.size() shouldBe 0
        OcrVocabulary.isUserWord("ИВАНОВ") shouldBe false
    }
}