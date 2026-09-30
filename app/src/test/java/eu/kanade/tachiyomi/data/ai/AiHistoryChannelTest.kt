package eu.kanade.tachiyomi.data.ai

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Разделение истории на каналы: обычный чат и отыгрыш.
 *
 * Проверяется чистая часть — какой канал соответствует режиму. Обращения к
 * файлу требуют Android, поэтому имя файла здесь не проверяется.
 */
class AiHistoryChannelTest {

    @Test
    fun `roleplay mode gets its own channel`() {
        // Ключевая идея: переключение режима не должно подмешивать реплики
        // отыгрыша в обычный чат и наоборот.
        AiHistoryManager.channelOf(BookChatProfile.MODE_ROLEPLAY) shouldBe
            AiHistoryManager.CHANNEL_ROLEPLAY
        AiHistoryManager.channelOf(BookChatProfile.MODE_CHAT) shouldBe
            AiHistoryManager.CHANNEL_CHAT
    }

    @Test
    fun `unknown mode falls back to the ordinary chat`() {
        // Мусор в файле настроек не должен отправлять переписку в чужой канал.
        AiHistoryManager.channelOf("что-то") shouldBe AiHistoryManager.CHANNEL_CHAT
        AiHistoryManager.channelOf("") shouldBe AiHistoryManager.CHANNEL_CHAT
    }

    @Test
    fun `channels differ from each other`() {
        AiHistoryManager.CHANNEL_ROLEPLAY shouldBe "roleplay"
        AiHistoryManager.CHANNEL_CHAT shouldBe "chat"
    }
}
