package eu.kanade.tachiyomi.data.ai

import kotlinx.serialization.Serializable

/**
 * Одно зафиксированное знание о книге: что читать, в каком порядке, какие
 * области/баблы брать, как расшифровывать, какие голоса у ролей.
 *
 * Правило всегда помнит источник (URL сайта, «пользователь» или «агент») и
 * автора: книга, на которую ссылаются, со временем меняется, и через месяц
 * должно быть видно, откуда взялось правило.
 */
@Serializable
data class LearningRule(
    val kind: String = "note",
    val text: String = "",
    val source: String = "",
    val author: String = "agent",
    val time: Long = 0L,
)

/**
 * Чистая логика знаний о книге: виды правил, очистка чужого текста и разбор
 * присланного списка правил. Android здесь не нужен — поэтому правила
 * вынесены в отдельный объект и покрыты обычными юнит-тестами.
 *
 * Про prompt injection: внешний текст (страница сайта, сообщение источника)
 * считается ДАННЫМИ, а не инструкциями. [sanitize] вырезает синтаксис
 * вызовов инструментов и управляющие символы, а [BookKnowledge.render]
 * отдаёт такие правимости модели в блоке, помеченном как данные, поэтому
 * чужой «игнорируй правила» не может переписать системный промпт агента.
 */
object BookLearning {

    const val KIND_SITE = "site"
    const val KIND_SUMMARY = "summary"
    const val KIND_FACT = "fact"
    const val KIND_ADVICE = "advice"
    const val KIND_READING_ORDER = "reading_order"
    const val KIND_REGION = "region"
    const val KIND_BUBBLE = "bubble"
    const val KIND_TRANSCRIPTION = "transcription"
    const val KIND_VOICE = "voice"
    const val KIND_NOTE = "note"

    const val AUTHOR_USER = "user"
    const val AUTHOR_WEB = "web"
    const val AUTHOR_AGENT = "agent"

    const val MAX_TEXT_CHARS = 6000

    /** Источник длиннее этого в промпт не идёт: ссылка должна быть читаемой. */
    const val MAX_SOURCE_CHARS = 300

    val KINDS: List<String> = listOf(
        KIND_SITE,
        KIND_SUMMARY,
        KIND_FACT,
        KIND_ADVICE,
        KIND_READING_ORDER,
        KIND_REGION,
        KIND_BUBBLE,
        KIND_TRANSCRIPTION,
        KIND_VOICE,
        KIND_NOTE,
    )

    private val ALIASES: Map<String, String> = mapOf(
        "site" to KIND_SITE,
        "url" to KIND_SITE,
        "где читать" to KIND_SITE,
        "сайт" to KIND_SITE,
        "summary" to KIND_SUMMARY,
        "сюжет" to KIND_SUMMARY,
        "о чём" to KIND_SUMMARY,
        "fact" to KIND_FACT,
        "факт" to KIND_FACT,
        "advice" to KIND_ADVICE,
        "совет" to KIND_ADVICE,
        "reading_order" to KIND_READING_ORDER,
        "reading order" to KIND_READING_ORDER,
        "порядок" to KIND_READING_ORDER,
        "порядок чтения" to KIND_READING_ORDER,
        "region" to KIND_REGION,
        "область" to KIND_REGION,
        "рамка" to KIND_REGION,
        "bubble" to KIND_BUBBLE,
        "бабл" to KIND_BUBBLE,
        "облачко" to KIND_BUBBLE,
        "transcription" to KIND_TRANSCRIPTION,
        "расшифровка" to KIND_TRANSCRIPTION,
        "voice" to KIND_VOICE,
        "голос" to KIND_VOICE,
        "роль" to KIND_VOICE,
        "note" to KIND_NOTE,
        "заметка" to KIND_NOTE,
    )

    /** Приводит вид правила к каноническому: неизвестное — обычная заметка. */
    fun normalizeKind(raw: String): String {
        val key = raw.trim().lowercase().replace('_', ' ').replace(Regex("\\s+"), " ")
        if (key.isBlank()) return KIND_NOTE
        ALIASES[key]?.let { return it }
        ALIASES[key.replace(' ', '_')]?.let { return it }
        return if (key in KINDS) key else KIND_NOTE
    }

    /** Человекочитаемое название вида правила — для UI и отчётов. */
    fun kindTitle(kind: String): String = when (normalizeKind(kind)) {
        KIND_SITE -> "Где читать"
        KIND_SUMMARY -> "О чём"
        KIND_FACT -> "Факт"
        KIND_ADVICE -> "Совет"
        KIND_READING_ORDER -> "Порядок чтения"
        KIND_REGION -> "Область"
        KIND_BUBBLE -> "Баллоны"
        KIND_TRANSCRIPTION -> "Расшифровка"
        KIND_VOICE -> "Голоса и роли"
        else -> "Заметка"
    }

    private val toolCallBlock = Regex("<[^>]*tool_call[^>]*>.*?</[^>]*tool_call>", RegexOption.DOT_MATCHES_ALL)
    private val toolCallLine = Regex("^@\\s*(?:tool\\s+)?[A-Za-z_][A-Za-z0-9_]*\\s*\\{")
    private val controlChars = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")

    /**
     * Очищает текст из внешнего источника: убирает управляющие символы,
     * вызовы инструментов (чужой текст не должен уметь вызывать @tool),
     * схлопывает пустые строки и обрезает по [maxChars].
     */
    fun sanitize(raw: String, maxChars: Int = MAX_TEXT_CHARS): String {
        val limit = maxChars.coerceIn(64, 40_000)
        val withoutCalls = toolCallBlock.replace(raw, " ")
        val lines = withoutCalls
            .split('\n')
            .map { controlChars.replace(it, " ").trim() }
            .filterNot { toolCallLine.containsMatchIn(it) }
        val kept = mutableListOf<String>()
        var pendingBreak = false
        for (line in lines) {
            if (line.isBlank()) {
                pendingBreak = kept.isNotEmpty()
                continue
            }
            if (pendingBreak) kept.add("")
            pendingBreak = false
            kept.add(line)
        }
        val text = kept.joinToString("\n")
        return if (text.length <= limit) text else text.take(limit).trimEnd() + "…"
    }

    private val bullet = Regex("^(?:(?:[-*•–—]|\\d{1,3}[.)])\\s*)+")

    /**
     * Разбирает присланный текст на правила. Список («- …», «1. …») становится
     * несколькими правилами, обычный текст — одним правилом целиком.
     */
    fun parseRules(
        raw: String,
        kind: String = KIND_NOTE,
        source: String = "",
        author: String = AUTHOR_AGENT,
        time: Long = System.currentTimeMillis(),
        maxRules: Int = 12,
    ): List<LearningRule> {
        val clean = sanitize(raw)
        if (clean.isBlank()) return emptyList()
        val normalized = normalizeKind(kind)
        val bullets = clean.lines().map { it.trim() }.filter { bullet.containsMatchIn(it) }
        val texts = if (bullets.isEmpty()) {
            listOf(clean)
        } else {
            bullets.map { it.replace(bullet, "").trim() }.filter { it.isNotEmpty() }
        }
        val perRule = (MAX_TEXT_CHARS / texts.size.coerceAtLeast(1)).coerceIn(200, MAX_TEXT_CHARS)
        return texts.take(maxRules.coerceIn(1, 40)).map {
            LearningRule(
                kind = normalized,
                text = sanitize(it, perRule),
                source = source,
                author = author,
                time = time,
            )
        }.filter { it.text.isNotBlank() }
    }

    /**
     * Порядок чтения из правила книги: правило может быть «сверху вниз»,
     * «справа налево», «vertical», «rtl»… Возвращает только известные движку
     * значения, иначе null — тогда остаётся порядок из пресета контента.
     */
    fun readingOrderOf(text: String): String? {
        val t = text.trim().lowercase()
        return when {
            t.isEmpty() -> null
            "vertical" in t || "сверху вниз" in t || "сверху-вниз" in t || " сверху" in t -> "vertical"
            "справа налево" in t || "right to left" in t || t.startsWith("rtl") -> "rtl"
            "слева направо" in t || "left to right" in t || t.startsWith("ltr") -> "ltr"
            else -> null
        }
    }

    /** Правило одного вида — последнее выученное (правила переопределяют друг друга). */
    fun lastOf(rules: List<LearningRule>, kind: String): LearningRule? {
        val target = normalizeKind(kind)
        return rules.lastOrNull { it.kind == target }
    }

    private val urlScheme = Regex("^(https?)://([^/\\s?#]+)", RegexOption.IGNORE_CASE)

    /**
     * Источник пригоден, если это обычная веб-ссылка. Агент не должен уводить
     * приложение читать `file://`, `content://` или `javascript:` — поэтому
     * проверка схемы и хоста здесь, а не внутри загрузчика.
     */
    fun isHttpUrl(raw: String): Boolean {
        val url = raw.trim()
        if (url.isEmpty() || url.length > MAX_SOURCE_CHARS * 8) return false
        val match = urlScheme.find(url) ?: return false
        val authority = match.groupValues[2]
        return authority.isNotBlank() && !authority.startsWith(':') && !authority.endsWith(':')
    }

    /**
     * Источник в том виде, в каком он попадёт в промпт: одна строка без
     * управляющих символов. Ссылка приходит извне, поэтому длинный «url» с
     * переводом строки и инструкцией внутри не должен ломать разметку блока
     * правил и не должен печататься целиком.
     */
    fun normalizeSource(raw: String): String =
        sanitize(raw, MAX_SOURCE_CHARS).replace(Regex("\\s+"), " ").trim()
}
