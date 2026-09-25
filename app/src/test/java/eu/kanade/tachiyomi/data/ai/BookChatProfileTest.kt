package eu.kanade.tachiyomi.data.ai

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Настройки работы ассистента с книгой: режим, 18+, цензура.
 *
 * Проверяется чистая часть — блок ограничений для промпта и маскирование
 * грубой лексики. Запись в файл книги требует Android и в тесты не входит.
 */
class BookChatProfileTest {

    @Test
    fun `chat mode is businesslike and forbids mature scenes by default`() {
        val block = BookChatProfile.render(
            mode = BookChatProfile.MODE_CHAT,
            matureAllowed = false,
            censorship = false,
            bookTitle = "Падший лисёнок",
        )
        block.contains("деловой") shouldBe true
        block.contains("18+") shouldBe true
        // Жёсткая граница есть в любом режиме — это не настройка.
        block.contains("несовершеннолетними") shouldBe true
    }

    @Test
    fun `roleplay mode asks for character voice`() {
        val block = BookChatProfile.render(
            mode = BookChatProfile.MODE_ROLEPLAY,
            matureAllowed = false,
            censorship = true,
            bookTitle = "Падший лисёнок",
        )
        block.contains("отыгрываешь") shouldBe true
        block.contains("Падший лисёнок") shouldBe true
        // Знания книги режим не отменяет.
        block.contains("Знания книги") shouldBe true
    }

    @Test
    fun `mature flag only changes the book level rule`() {
        val allowed = BookChatProfile.render(
            BookChatProfile.MODE_CHAT, matureAllowed = true, censorship = false,
        )
        val denied = BookChatProfile.render(
            BookChatProfile.MODE_CHAT, matureAllowed = false, censorship = false,
        )
        allowed.contains("помечена 18+") shouldBe true
        denied.contains("не помечена 18+") shouldBe true
        // Запрет на несовершеннолетних остаётся в обоих случаях.
        allowed.contains("несовершеннолетними") shouldBe true
        denied.contains("несовершеннолетними") shouldBe true
    }

    @Test
    fun `censorship masks profanity but keeps the word shape`() {
        // Маска сохраняет длину слова: звёздочек на одну меньше, потому что
        // первая буква остаётся читаемой.
        BookChatProfile.maskCensored("это блядь") shouldBe "это б****"
        BookChatProfile.hasCensoredWords("это блядь") shouldBe true
        // Регистр кириллицы: без флага `u` «БЛЯДЬ» проходило бы мимо фильтра.
        BookChatProfile.maskCensored("БЛЯДЬ") shouldBe "Б****"
        BookChatProfile.hasCensoredWords("БЛЯДЬ") shouldBe true
        // Слово-основа ловится в любой форме, и длина не «съезжает».
        BookChatProfile.maskCensored("дрочись").length shouldBe "дрочись".length
    }

    @Test
    fun `censorship does not touch ordinary words`() {
        // Основы в списке короткие, поэтому важно, чтобы они не задевали
        // похожие обычные слова: «хуже» — это не «хуе».
        BookChatProfile.maskCensored("хуже") shouldBe "хуже"
        BookChatProfile.maskCensored("сюжет и сюжетный") shouldBe "сюжет и сюжетный"
        BookChatProfile.hasCensoredWords("сюжет") shouldBe false
        BookChatProfile.maskCensored("") shouldBe ""
    }

    @Test
    fun `mode titles are readable`() {
        BookChatProfile.modeTitle(BookChatProfile.MODE_ROLEPLAY) shouldBe "Отыгрыш"
        BookChatProfile.modeTitle(BookChatProfile.MODE_CHAT) shouldBe "Чат"
        // Неизвестное значение не должно ломать UI.
        BookChatProfile.modeTitle("что-то") shouldBe "Чат"
    }
}
