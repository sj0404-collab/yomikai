package eu.kanade.tachiyomi.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Форма ответа и эндпоинт у opencode Zen зависят от семейства модели, а не
 * от того, откуда послали запрос. Игнорировать это нельзя: Claude на
 * `/chat/completions` не отвечает вовсе, поэтому модель выглядит «сломанной».
 *
 * Здесь собраны и маршрутизация, и разбор ответа. Обе части чистые, без сети
 * и без Android, поэтому их можно проверять тестами. Разбор сделан на
 * kotlinx.serialization, а не на org.json: org.json в юнит-тестах Android
 * нерабочий («not mocked»).
 */
object ZenProtocol {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Куда уходит запрос для конкретной модели. */
    enum class Family(val endpoint: String) {
        /** OpenAI-совместимый, основная масса моделей. */
        CHAT("/chat/completions"),

        /** GPT, Grok, Muse Spark — OpenAI Responses API. */
        RESPONSES("/responses"),

        /** Claude — сообщения Anthropic. */
        MESSAGES("/messages"),

        /** Gemini — свой путь с именем модели. */
        GEMINI(""),

        /** Jev — System One, не чат: возвращает оценки состояния. */
        SYSTEM_ONE("/systemone"),
    }

    /**
     * Семейство модели по её id.
     *
     * Порядок проверок важен: «flash» встречается и у Gemini, и у Qwen, и у
     * Nemotron, поэтому сначала отсекаем то, что уникально, и только потом
     * смотрим на остаток.
     */
    fun familyOf(model: String): Family {
        val id = model.trim().lowercase()
        return when {
            id.startsWith("jev-") -> Family.SYSTEM_ONE
            id.startsWith("claude-") -> Family.MESSAGES
            id.startsWith("gpt-") || id.startsWith("codex") -> Family.RESPONSES
            id.startsWith("grok-") || id.startsWith("muse-spark") -> Family.RESPONSES
            id.startsWith("gemini-") -> Family.GEMINI
            else -> Family.CHAT
        }
    }

    /** Полный URL запроса для модели. */
    fun urlFor(baseUrl: String, model: String): String {
        val base = baseUrl.trimEnd('/')
        return when (familyOf(model)) {
            Family.GEMINI -> "$base/models/$model:generateContent"
            else -> base + familyOf(model).endpoint
        }
    }

    /** Тело запроса в форме, которую ждёт эндпоинт. */
    fun requestBody(
        model: String,
        userPrompt: String,
        systemPrompt: String?,
        maxTokens: Int,
    ): JsonObject {
        val system = systemPrompt.orEmpty()
        val limit = maxTokens.coerceAtLeast(1)
        val part = { value: String ->
            buildJsonObject {
                put("type", "text")
                put("text", value)
            }
        }
        val userMessage = { value: String ->
            buildJsonObject {
                put("role", "user")
                put("content", value)
            }
        }
        return when (familyOf(model)) {
            // У Anthropic system — отдельное поле, а не сообщение в массиве.
            Family.MESSAGES -> buildJsonObject {
                put("model", model)
                put("max_tokens", limit)
                put("system", system)
                put("messages", JsonArray(listOf(userMessage(userPrompt))))
            }
            // У Gemini системная инструкция отдельная, ответ приходит в
            // candidates[].content.parts[].
            Family.GEMINI -> buildJsonObject {
                put(
                    "contents",
                    JsonArray(
                        listOf(
                            buildJsonObject { put("parts", JsonArray(listOf(part(userPrompt)))) },
                        ),
                    ),
                )
                if (system.isNotBlank()) {
                    put(
                        "systemInstruction",
                        buildJsonObject { put("parts", JsonArray(listOf(part(system)))) },
                    )
                }
                put("generationConfig", buildJsonObject { put("maxOutputTokens", limit) })
            }
            // У Responses API инструкции отдельным полем, а не сообщением.
            Family.RESPONSES -> buildJsonObject {
                put("model", model)
                if (system.isNotBlank()) put("instructions", system)
                put("input", JsonArray(listOf(userMessage(userPrompt))))
                put("max_output_tokens", limit)
            }
            else -> buildJsonObject {
                put("model", model)
                val messages = buildList {
                    if (system.isNotBlank()) {
                        add(
                            buildJsonObject {
                                put("role", "system")
                                put("content", system)
                            },
                        )
                    }
                    add(userMessage(userPrompt))
                }
                put("messages", JsonArray(messages))
                put("max_tokens", limit)
                put("stream", false)
            }
        }
    }

    /** Разобранный ответ модели. */
    data class Parsed(
        val content: String?,
        val reasoning: String?,
        val tokens: Int,
        val complete: Boolean,
    )

    /**
     * Текст в поле, которое может оказаться пустым.
     *
     * Отдельно отсекается строка "null": провайдеры шлют её и как JSON null, и
     * как обычную строку, а читатель видел в чате «🤔 null» вместо ответа.
     */
    fun textOrNull(element: JsonElement?): String? {
        val raw = (element as? JsonPrimitive)?.contentOrNull ?: return null
        val t = raw.trim()
        return t.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    /**
     * Разбор ответа любого из известных форматов. У каждой формы свои ключи,
     * не пересекающиеся с остальными, поэтому явная ветка по семейству читается
     * проще, чем поиск «какая форма пришла».
     */
    fun parse(body: String, model: String): Parsed {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return Parsed(null, null, 0, true)
        return when (familyOf(model)) {
            Family.CHAT -> parseChat(root)
            Family.RESPONSES -> parseResponses(root)
            Family.MESSAGES -> parseMessages(root)
            Family.GEMINI -> parseGemini(root)
            // Jev не чат: у него нет ни текста, ни размышлений.
            Family.SYSTEM_ONE -> Parsed(null, null, 0, true)
        }
    }

    private fun JsonObject.str(key: String): String? = textOrNull(this[key])

    private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0

    private fun JsonObject.flag(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    private fun JsonObject.array(key: String): List<JsonElement> = this[key] as? JsonArray ?: emptyList()

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.firstObject(key: String): JsonObject? = array(key).firstOrNull() as? JsonObject

    private fun join(vararg parts: StringBuilder): String? =
        parts.joinToString("") { it.toString() }
            .trim()
            .takeIf { it.isNotBlank() }

    private fun StringBuilder.add(value: String?) {
        if (!value.isNullOrBlank()) append(value)
    }

    private fun parseChat(root: JsonObject): Parsed {
        val choice = root.firstObject("choices")
        val message = choice?.obj("message")
        val finish = choice?.str("finish_reason").orEmpty()
        return Parsed(
            content = message?.str("content"),
            // nemotron отдаёт «reasoning», hy3 — «reasoning_content».
            reasoning = message?.str("reasoning")
                ?: message?.str("reasoning_content")
                ?: root.str("reasoning"),
            tokens = root.obj("usage")?.int("total_tokens") ?: 0,
            complete = finish != "length",
        )
    }

    private fun parseResponses(root: JsonObject): Parsed {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        for (item in root.array("output")) {
            val obj = item as? JsonObject ?: continue
            when (obj.str("type")) {
                // Размышления приходят отдельным типом, текстом или списком.
                "reasoning" -> {
                    reasoning.add(obj.str("text"))
                    for (part in obj.array("summary")) {
                        reasoning.add((part as? JsonObject)?.str("text"))
                    }
                }
                "message" -> {
                    for (part in obj.array("content")) {
                        content.add((part as? JsonObject)?.str("text"))
                    }
                }
            }
        }
        return Parsed(
            content = join(content) ?: root.str("output_text"),
            reasoning = join(reasoning) ?: root.str("reasoning"),
            tokens = root.obj("usage")?.int("total_tokens") ?: 0,
            complete = root.str("status")?.lowercase() != "incomplete",
        )
    }

    private fun parseMessages(root: JsonObject): Parsed {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        for (block in root.array("content")) {
            val b = block as? JsonObject ?: continue
            when (b.str("type")) {
                "text" -> content.add(b.str("text"))
                // Claude отдаёт размышления отдельным блоком thinking.
                "thinking", "redacted_thinking" -> reasoning.add(b.str("thinking"))
            }
        }
        val usage = root.obj("usage")
        return Parsed(
            content = join(content),
            reasoning = join(reasoning),
            tokens = (usage?.int("input_tokens") ?: 0) + (usage?.int("output_tokens") ?: 0),
            complete = root.str("stop_reason")?.lowercase() != "max_tokens",
        )
    }

    private fun parseGemini(root: JsonObject): Parsed {
        val candidate = root.firstObject("candidates")
        val parts = candidate?.obj("content")?.array("parts").orEmpty()
        val content = StringBuilder()
        val reasoning = StringBuilder()
        for (part in parts) {
            val p = part as? JsonObject ?: continue
            val t = p.str("text") ?: continue
            // Размышления помечены флагом thought у самой части текста.
            if (p.flag("thought")) reasoning.add(t) else content.add(t)
        }
        return Parsed(
            content = join(content),
            reasoning = join(reasoning),
            tokens = root.obj("usageMetadata")?.int("totalTokenCount") ?: 0,
            complete = candidate?.str("finishReason")?.uppercase() != "MAX_TOKENS",
        )
    }
}
