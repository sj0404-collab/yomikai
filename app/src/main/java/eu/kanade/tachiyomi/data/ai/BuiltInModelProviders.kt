package eu.kanade.tachiyomi.data.ai

/**
 * Нужен ли провайдеру API-ключ.
 *  • NONE — работает без ключа, поля ключа нет;
 *  • OPTIONAL — без ключа работает, ключ поднимает лимиты;
 *  • REQUIRED — без ключа не отвечает, пользователь обязан ввести ключ.
 */
enum class KeyPolicy { NONE, OPTIONAL, REQUIRED }

/**
 * Встроенный OpenAI-совместимый провайдер с выбором модели.
 *
 * Модели — из списка, а не из свободного ввода: так в диалоге видно, что
 * точно работает. Ключ хранится по id провайдера (`pref_ai_key_<id>`), модель —
 * `pref_ai_model_<id>`. Ответы идут строго по /chat/completions (plainChat).
 */
data class BuiltInModelProvider(
    val id: String,
    val title: String,
    val summary: String,
    val baseUrl: String,
    val models: List<String>,
    val keyPolicy: KeyPolicy,
    val keyLabel: String = "",
    val keyHint: String = "",
    /** Строка вместо пустого ключа (LLM7 без заголовка отвечает «Missing API key»). */
    val anonymousKey: String = "",
) {
    val defaultModel: String get() = models.first()
}

object BuiltInModelProviders {

    /**
     * Списки моделей на момент сентября 2026 г.
     *
     * Проверено живым запросом: Pollinations (openai, openai-fast без ключа),
     * LLM7 (mistral-Nemo-Instruct-2407, codestral-latest с заглушкой ключа).
     * Остальное — по публичным сведениям о снятии моделей (DeepSeek, Groq, Grok,
     * Gemini, Mistral); живой проверки с ключом не было. Старые id (deepseek-chat,
     * deepseek-reasoner, grok-3-mini, llama-3.3-70b-versatile и др.) сняты провайдерами.
     */
    val ALL: List<BuiltInModelProvider> = listOf(
        BuiltInModelProvider(
            id = "pollinations",
            title = "Pollinations",
            summary = "Бесплатно, без ключа: модели openai и openai-fast. Лимит: примерно 1 ответ в 45 секунд.",
            baseUrl = "https://text.pollinations.ai/openai",
            models = listOf("openai-fast", "openai"),
            keyPolicy = KeyPolicy.NONE,
        ),
        BuiltInModelProvider(
            id = "llm7",
            title = "LLM7",
            summary = "Бесплатные модели LLM7. Токен необязателен.",
            baseUrl = "https://api.llm7.io/v1",
            models = listOf("mistral-Nemo-Instruct-2407", "codestral-latest"),
            keyPolicy = KeyPolicy.OPTIONAL,
            keyLabel = "LLM7 токен (необязательно)",
            keyHint = "Без токена работают бесплатные модели. Токен поднимает лимиты: token.llm7.io",
            anonymousKey = "unused",
        ),
        BuiltInModelProvider(
            id = "deepseek",
            title = "DeepSeek",
            summary = "Модели DeepSeek V4 напрямую. Нужен ключ.",
            baseUrl = "https://api.deepseek.com/v1",
            models = listOf("deepseek-v4-flash", "deepseek-v4-pro"),
            keyPolicy = KeyPolicy.REQUIRED,
            keyLabel = "DeepSeek API-ключ",
            keyHint = "Ключ создаётся в platform.deepseek.com",
        ),
        BuiltInModelProvider(
            id = "grok",
            title = "Grok (xAI)",
            summary = "Модели Grok напрямую от xAI. Нужен ключ.",
            baseUrl = "https://api.x.ai/v1",
            models = listOf("grok-4.3", "grok-4.20-0309-reasoning", "grok-4.20-0309-non-reasoning"),
            keyPolicy = KeyPolicy.REQUIRED,
            keyLabel = "xAI API-ключ",
            keyHint = "Ключ создаётся в console.x.ai",
        ),
        BuiltInModelProvider(
            id = "groq",
            title = "Groq",
            summary = "Быстрый инференс открытых моделей. Нужен ключ.",
            baseUrl = "https://api.groq.com/openai/v1",
            models = listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.6-27b"),
            keyPolicy = KeyPolicy.REQUIRED,
            keyLabel = "Groq API-ключ",
            keyHint = "Ключ создаётся в console.groq.com",
        ),
        BuiltInModelProvider(
            id = "mistral",
            title = "Mistral",
            summary = "Модели Mistral напрямую. Нужен ключ.",
            baseUrl = "https://api.mistral.ai/v1",
            models = listOf("mistral-large-latest", "mistral-small-latest", "codestral-latest"),
            keyPolicy = KeyPolicy.REQUIRED,
            keyLabel = "Mistral API-ключ",
            keyHint = "Ключ создаётся в console.mistral.ai",
        ),
        BuiltInModelProvider(
            id = "gemini",
            title = "Gemini (Google)",
            summary = "Модели Gemini через OpenAI-совместимый адрес Google. Нужен ключ. Старые gemini-2.5-* снимаются 1 декабря 2026.",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            models = listOf("gemini-3.8-flash", "gemini-3.5-flash-lite"),
            keyPolicy = KeyPolicy.REQUIRED,
            keyLabel = "Gemini API-ключ (для чата)",
            keyHint = "Ключ создаётся в aistudio.google.com",
        ),
    )

    fun byId(id: String?): BuiltInModelProvider? = ALL.firstOrNull { it.id == id }
}
