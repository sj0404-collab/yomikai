package mihon.data.ocr

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Отбраковка результатов, целиком состоящих из слов, которых нет в словаре.
 *
 * Строки взяты из реальных скриншотов страницы готической манги:
 * логотип-заставка давал «Небниня» вместо «Небожития», а титульный лист —
 * «тій». Обе проходили прежний гейт, потому что он проверял только алфавит:
 * это корректная кириллица, значит и корректный текст.
 *
 * Плюс одиночные буквы-вставки: в реплике «ДОЛЖЕН ЛИ Я ПОЗВАТЬ Х ВАШУ
 * ГОРНИЧНУЮ» распознаватель вставил «Х» между словами.
 */
class OcrDictionaryGarbageTest {

    @Test
    fun `unknown word with no russian vowel is garbage`() {
        // «і» — украинская гласная, в русском тексте такого слова нет.
        OcrTextCleaner.isDictionaryGarbage("тій") shouldBe true
    }

    @Test
    fun `logo misread is NOT caught by the conservative rule`() {
        // «Небниня» — распознанная «Небожития» из готического логотипа.
        // Лексически она неотличима от редкого имени: оба слова в словаре
        // отсутствуют, оба с русскими гласными, оба длиннее трёх букв.
        // Поэтому гейт её НЕ режет — иначе он съедал бы «Артур» и «Астерия».
        // Такие заставки отсекаются на уровне страницы, а не результата.
        OcrTextCleaner.isDictionaryGarbage("Небниня") shouldBe false
    }

    @Test
    fun `short unknown run is garbage`() {
        OcrTextCleaner.isDictionaryGarbage("ббб") shouldBe true
    }

    @Test
    fun `real sentence with dictionary words survives`() {
        OcrTextCleaner.isDictionaryGarbage("Да кто же играет в солдатиков с горничной") shouldBe false
    }

    @Test
    fun `proper noun longer than three letters is kept`() {
        // Ради таких имён порог и поднят: длинное неизвестное слово с
        // русскими гласными не считаем мусором.
        OcrTextCleaner.isDictionaryGarbage("Небожития") shouldBe false
    }

    @Test
    fun `blank and non cyrillic text is not garbage`() {
        OcrTextCleaner.isDictionaryGarbage("") shouldBe false
        OcrTextCleaner.isDictionaryGarbage("   ") shouldBe false
        OcrTextCleaner.isDictionaryGarbage("Wi-Fi") shouldBe false
    }

    @Test
    fun `stray letter inside a real sentence is dropped`() {
        val cleaned = OcrTextCleaner.filterGarbageTokens("ДОЛЖЕН ЛИ Я ПОЗВАТЬ Х ВАШУ ГОРНИЧНУЮ")
        cleaned shouldBe "ДОЛЖЕН ЛИ Я ПОЗВАТЬ ВАШУ ГОРНИЧНУЮ"
    }

    @Test
    fun `real one-letter prepositions are kept`() {
        // «В», «К», «С», «У» — настоящие слова, резать их нельзя.
        OcrTextCleaner.filterGarbageTokens("ПОГОВОРИ С НИМ") shouldBe "ПОГОВОРИ С НИМ"
        OcrTextCleaner.filterGarbageTokens("Я К НЕМУ ПОШЁЛ") shouldBe "Я К НЕМУ ПОШЁЛ"
    }

    @Test
    fun `stray letter counter moves`() {
        OcrTextCleanerStats.reset()
        OcrTextCleaner.filterGarbageTokens("ПОЗВАТЬ Х ВАШУ")
        OcrTextCleanerStats.droppedStrayLetters shouldBe 1
        OcrTextCleanerStats.reset()
    }

    @Test
    fun `title page with only a logo misread counts as speechless`() {
        // Реальный случай: титульный лист, движок выдал «Небниня».
        OcrTextCleaner.isSpeechlessPage(listOf("Небниня")) shouldBe true
    }

    @Test
    fun `real dialogue page is not speechless`() {
        OcrTextCleaner.isSpeechlessPage(
            listOf(
                "ДОЛЖЕН ЛИ Я ПОЗВАТЬ ВАШУ ГОРНИЧНУЮ",
                "ДА КТО ЖЕ ИГРАЕТ В СОЛДАТИКОВ",
            ),
        ) shouldBe false
    }

    @Test
    fun `busy page with many regions is never speechless`() {
        val many = List(5) { "Небниня" }
        OcrTextCleaner.isSpeechlessPage(many) shouldBe false
    }

    @Test
    fun `page with a known word survives even if short`() {
        OcrTextCleaner.isSpeechlessPage(listOf("Нет", "Иди")) shouldBe false
    }
}