package eu.kanade.tachiyomi.data.ai

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Инструменты генерации и голосов AI-чата.
 *
 * Проверяется контракт, который видит модель: имена инструментов, их
 * документация в системном промпте и защита имён от самодельных плагинов.
 * Сама генерация звука требует сети Edge TTS и записи в workspace, поэтому
 * в юнит-тесты не входит.
 */
class AiChatToolsTest {

    @Test
    fun `tool names are unique`() {
        AiChatTools.TOOL_NAMES shouldContainExactly listOf(
            "render_audio",
            "voice_list",
            "voice_preview",
            "voice_set",
        )
        AiChatTools.TOOL_NAMES.distinct().size shouldBe AiChatTools.TOOL_NAMES.size
    }

    @Test
    fun `generation tools cannot be shadowed by a developer plugin`() {
        // AiPlugins.save() отвергает имя из этого набора, поэтому плагин
        // разработчика не может перехватить генерацию звука или смену голоса.
        AiChatTools.TOOL_NAMES.forEach { (it in AiPlugins.RESERVED_TOOL_NAMES) shouldBe true }
    }

    @Test
    fun `every tool is documented in the system prompt exactly once`() {
        AiChatTools.SYSTEM_PROMPT_LINES.size shouldBe AiChatTools.TOOL_NAMES.size
        AiChatTools.TOOL_NAMES.forEach { name ->
            AiChatTools.SYSTEM_PROMPT_LINES.count { "@tool $name " in it } shouldBe 1
        }
    }

    @Test
    fun `default edge voice is documented`() {
        // Значение по умолчанию из настроек (OcrPreferences.edgeVoice) должно быть
        // в документации инструментов и в каталоге — иначе агент предлагал бы
        // внешние голоса как «текущие».
        val docs = AiChatTools.SYSTEM_PROMPT_LINES.joinToString("\n")
        docs.contains("ru-RU-SvetlanaNeural") shouldBe true
    }

    @Test
    fun `reserved names still include reader tools`() {
        AiReaderTools.TOOL_NAMES.forEach { (it in AiPlugins.RESERVED_TOOL_NAMES) shouldBe true }
    }
}