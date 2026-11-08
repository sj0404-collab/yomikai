package mihon.data.ocr

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Проверка «нарезка режет слова или буквы».
 *
 * Читатель: чем больше текста он отправляет, тем лучше результат, а на
 * одном-трёх словах текст ломается — «на выходе вижу текст что-то»
 * превращалось в «на выходе вижу текст ч то т». Длинная строка читается
 * идеально, потому что распознаватель ставит в ней пробелы и нарезка на
 * слова не запускается; короткая строка пробелов не получает, и её режет
 * вертикальная проекция уже внутри слов.
 */
class OcrWordSegmentationTest {

    @Test
    fun `letter sized pieces mean the split cut letters`() {
        // Ровно то, что видел читатель: «ч» «то» «т».
        segmentationIsDestructive("навыходевижутекстчто-то", listOf("ч", "то", "т")) shouldBe true
    }

    @Test
    fun `a single letter line is fragmentation too`() {
        segmentationIsDestructive("текст", listOf("т", "е", "к", "с", "т")) shouldBe true
    }

    @Test
    fun `real words are kept`() {
        // Нарезка сделала ровно то, зачем нужна: вернула слова с пробелами.
        val whole = "ИПАЛПОДЛЕЗВИЕМ"
        segmentationIsDestructive(whole, listOf("И", "ПАЛ", "ПОД", "ЛЕЗВИЕМ")) shouldBe false
    }

    @Test
    fun `a lone short word among real words is not fragmentation`() {
        // «да нет а» — короткие слова настоящие: медиана длиннее одной буквы,
        // и символы целой строки не потеряны. Нарезку отменять нельзя.
        segmentationIsDestructive("данета", listOf("да", "нет", "а")) shouldBe false
    }

    @Test
    fun `pieces that lost most of the line are rejected`() {
        // Куски вырезаны не из этого кропа или срезаны по краям — символов
        // почти не осталось, читать нечего.
        segmentationIsDestructive("навыходевижутекст", listOf("на", "текст")) shouldBe true
    }

    @Test
    fun `nothing recognized is not fragmentation`() {
        // Пустой результат нарезки разбирается самой recognizeLineBitmap.
        segmentationIsDestructive("навыходевижутекст", emptyList()) shouldBe false
    }

    @Test
    fun `whitespace does not count towards piece length`() {
        segmentationIsDestructive("навыходевижутекстчто-то", listOf(" ч ", " то ", " т ")) shouldBe true
    }
}
