package eu.kanade.tachiyomi.data.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Протокол opencode Zen: эндпоинт по семейству модели и разбор ответа.
 *
 * Главное, что тут закрывается, — «размышляют null». Размышления у разных
 * семейств лежат в разных местах (`reasoning`, `reasoning_content`, блок
 * `thinking`, флаг `thought`), и раньше читался только `choices[0].message`,
 * поэтому всё остальное молча терялось.
 */
class ZenProtocolTest {

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    @Test
    fun `each model family is sent to its own endpoint`() {
        // Отправлять Claude в /chat/completions — значит не получить ответа
        // вовсе и увидеть модель как сломанную.
        assertEquals(ZenProtocol.Family.CHAT, ZenProtocol.familyOf("big-pickle"))
        assertEquals(ZenProtocol.Family.CHAT, ZenProtocol.familyOf("mimo-v2.6-flash-free"))
        assertEquals(ZenProtocol.Family.CHAT, ZenProtocol.familyOf("nemotron-3-ultra-free"))
        assertEquals(ZenProtocol.Family.CHAT, ZenProtocol.familyOf("qwen3.8-max"))
        assertEquals(ZenProtocol.Family.CHAT, ZenProtocol.familyOf("glm-5.3"))

        assertEquals(ZenProtocol.Family.RESPONSES, ZenProtocol.familyOf("gpt-6-astra"))
        assertEquals(ZenProtocol.Family.RESPONSES, ZenProtocol.familyOf("grok-4.7"))
        assertEquals(ZenProtocol.Family.RESPONSES, ZenProtocol.familyOf("muse-spark-1.3"))

        assertEquals(ZenProtocol.Family.MESSAGES, ZenProtocol.familyOf("claude-opus-5-5"))
        assertEquals(ZenProtocol.Family.GEMINI, ZenProtocol.familyOf("gemini-3.8-flash"))
        assertEquals(ZenProtocol.Family.SYSTEM_ONE, ZenProtocol.familyOf("jev-1.13-free"))

        // Регистр и пробелы не должны ломать определение семейства.
        assertEquals(ZenProtocol.Family.MESSAGES, ZenProtocol.familyOf("  Claude-Opus-5  "))
    }

    @Test
    fun `url is built for the family of the model`() {
        val base = "https://opencode.ai/zen/v1"
        assertEquals("$base/chat/completions", ZenProtocol.urlFor(base, "big-pickle"))
        assertEquals("$base/responses", ZenProtocol.urlFor(base, "gpt-6-sol"))
        assertEquals("$base/messages", ZenProtocol.urlFor(base, "claude-sonnet-5"))
        assertEquals(
            "$base/models/gemini-3.8-flash:generateContent",
            ZenProtocol.urlFor(base, "gemini-3.8-flash"),
        )
        // Лишний слэш в базе не должен давать двойной.
        assertEquals("$base/messages", ZenProtocol.urlFor("$base/", "claude-sonnet-5"))
    }

    @Test
    fun `chat answer is parsed with both reasoning spellings`() {
        val nemotron = ZenProtocol.parse(
            """{"choices":[{"message":{"content":"Привет","reasoning":"Думаю о погоде"}}],
               "usage":{"total_tokens":12}}""",
            "nemotron-3-ultra-free",
        )
        assertEquals("Привет", nemotron.content)
        assertEquals("Думаю о погоде", nemotron.reasoning)
        assertEquals(12, nemotron.tokens)
        assertTrue(nemotron.complete)

        val hy = ZenProtocol.parse(
            """{"choices":[{"message":{"content":"Привет","reasoning_content":"Размышляю"}}]}""",
            "hy3",
        )
        assertEquals("Размышляю", hy.reasoning)
    }

    @Test
    fun `claude reasoning comes as a thinking block`() {
        // Раньше блок thinking не читался вовсе, и в чате было «🤔 null».
        val parsed = ZenProtocol.parse(
            """{"content":[{"type":"thinking","thinking":"Сначала подумать"},
                          {"type":"text","text":"Вот ответ"}],
               "usage":{"input_tokens":5,"output_tokens":7},
               "stop_reason":"end_turn"}""",
            "claude-opus-5",
        )
        assertEquals("Вот ответ", parsed.content)
        assertEquals("Сначала подумать", parsed.reasoning)
        assertEquals(12, parsed.tokens)
        assertTrue(parsed.complete)
    }

    @Test
    fun `responses reasoning is a separate output item`() {
        val parsed = ZenProtocol.parse(
            """{"output":[{"type":"reasoning","summary":[{"type":"summary_text","text":"Ход мысли"}]},
                          {"type":"message","content":[{"type":"output_text","text":"Итог"}]}],
               "usage":{"total_tokens":30},"status":"completed"}""",
            "gpt-6-sol",
        )
        assertEquals("Итог", parsed.content)
        assertEquals("Ход мысли", parsed.reasoning)
        assertEquals(30, parsed.tokens)
    }

    @Test
    fun `gemini marks reasoning with a thought flag`() {
        val parsed = ZenProtocol.parse(
            """{"candidates":[{"content":{"parts":[
                 {"text":"Обдумываю","thought":true},
                 {"text":"Ответ"}]},"finishReason":"STOP"}],
               "usageMetadata":{"totalTokenCount":9}}""",
            "gemini-3.8-flash",
        )
        assertEquals("Ответ", parsed.content)
        assertEquals("Обдумываю", parsed.reasoning)
        assertEquals(9, parsed.tokens)
    }

    @Test
    fun `literal null never reaches the reader`() {
        // Тот самый баг со скриншота: вместо размышлений показывалось «null».
        assertNull(ZenProtocol.textOrNull(null))
        assertNull(ZenProtocol.textOrNull(kotlinx.serialization.json.JsonNull))
        assertNull(ZenProtocol.textOrNull(kotlinx.serialization.json.JsonPrimitive("null")))
        assertNull(ZenProtocol.textOrNull(kotlinx.serialization.json.JsonPrimitive("  ")))
        assertEquals("текст", ZenProtocol.textOrNull(kotlinx.serialization.json.JsonPrimitive("текст")))

        val parsed = ZenProtocol.parse(
            """{"choices":[{"message":{"content":"Привет","reasoning":null}}]}""",
            "big-pickle",
        )
        assertEquals("Привет", parsed.content)
        assertNull(parsed.reasoning)
    }

    @Test
    fun `broken answer does not throw`() {
        // Мусор в ответе не должен ронять разбор: лучше пустой результат,
        // чем исключение в фоне.
        val parsed = ZenProtocol.parse("не json вовсе", "big-pickle")
        assertNull(parsed.content)

        val empty = ZenProtocol.parse("{}", "claude-opus-5")
        assertNull(empty.content)
        assertNull(empty.reasoning)
    }

    @Test
    fun `truncated answers are marked incomplete`() {
        val cut = ZenProtocol.parse(
            """{"choices":[{"message":{"content":"обрыв"},"finish_reason":"length"}]}""",
            "big-pickle",
        )
        assertTrue(!cut.complete)

        val anth = ZenProtocol.parse("""{"content":[],"stop_reason":"max_tokens"}""", "claude-opus-5")
        assertTrue(!anth.complete)
    }

    @Test
    fun `request body matches the endpoint`() {
        val chat = ZenProtocol.requestBody("big-pickle", "вопрос", "система", 100)
        assertEquals("big-pickle", chat.str("model"))
        assertEquals(100, chat.int("max_tokens"))
        // В chat/completions системная инструкция — обычное сообщение.
        val chatMessages = chat["messages"] as JsonArray
        assertEquals(2, chatMessages.size)
        assertEquals("system", (chatMessages[0] as JsonObject).str("role"))

        // У Anthropic системная инструкция отдельным полем, а в сообщениях
        // только реплика читателя.
        val anthropic = ZenProtocol.requestBody("claude-opus-5", "вопрос", "система", 100)
        assertEquals("система", anthropic.str("system"))
        assertEquals(100, anthropic.int("max_tokens"))
        val anthropicMessages = anthropic["messages"] as JsonArray
        assertEquals(1, anthropicMessages.size)
        assertEquals("user", (anthropicMessages[0] as JsonObject).str("role"))

        // У Responses инструкции отдельным полем, у Gemini — systemInstruction.
        val responses = ZenProtocol.requestBody("gpt-6-sol", "вопрос", "система", 100)
        assertEquals("система", responses.str("instructions"))
        assertEquals(100, responses.int("max_output_tokens"))

        val gemini = ZenProtocol.requestBody("gemini-3.8-flash", "вопрос", "система", 100)
        assertNotNull(gemini["contents"])
        assertNotNull(gemini["systemInstruction"])

        // Без системной инструкции лишние пустые поля не отправляются.
        val noSystem = ZenProtocol.requestBody("big-pickle", "вопрос", null, 100)
        val noSystemMessages = noSystem["messages"] as JsonArray
        assertEquals(1, noSystemMessages.size)
    }
}

