package eu.kanade.tachiyomi.data.tts

import mihon.domain.ocr.service.OcrPreferences

/**
 * Правило интонации: узор текста → произношение этого предложения.
 *
 * Позволяет задать паузы и модификаторы питча/темпа для характерных фраз
 * («Что?!», шёпот, крик, внутренние монологи). Правило применяется к
 * КОНКРЕТНОМУ предложению (после деления текста на предложения), поэтому
 * ударение из всех правил словаря не «залипает» на всю реплику.
 *
 * [exact] = true — узор должен совпадать со всем предложением целиком
 * (без учёта регистра), иначе достаточно вхождения подстроки.
 */
data class VoiceIntonationRule(
    val pattern: String,
    val exact: Boolean = false,
    val pauseMs: Int = 0,
    val pitch: Float = 1.0f,
    val rate: Float = 1.0f,
    val enabled: Boolean = true,
) {
    fun matches(sentence: String): Boolean {
        if (!enabled || pattern.isBlank()) return false
        return if (exact) {
            sentence.equals(pattern.trim(), ignoreCase = true)
        } else {
            sentence.contains(pattern.trim(), ignoreCase = true)
        }
    }
}

/**
 * Доступ к словарю интонаций: JSON в настройках
 * ([OcrPreferences.voiceIntonations]). Первое подходящее правило выигрывает;
 * порядок в списке = приоритет.
 */
object VoiceIntonationDictionary {

    /**
     * Разбор строки JSON-массива правил. Ручной разбор (org.json в юнит-тестах
     * app-модуля не замокан) — см. [VoiceRoleDictionary.parse].
     */
    fun parse(json: String): List<VoiceIntonationRule> {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return emptyList()
        val objs = if (trimmed.startsWith("[")) {
            VoiceJson.parseObjects(trimmed)
        } else {
            VoiceJson.parseObject(trimmed).run { listOf(this) }
        }
        return objs.mapNotNull { obj ->
            val pattern = obj["pattern"].orEmpty().trim()
            if (pattern.isBlank()) return@mapNotNull null
            VoiceIntonationRule(
                pattern = pattern,
                exact = obj["exact"]?.equals("true", ignoreCase = true) == true,
                pauseMs = obj["pauseMs"]?.toIntOrNull()?.coerceIn(0, 10000) ?: 0,
                pitch = obj["pitch"]?.toFloatOrNull()?.takeIf { it > 0f } ?: 1.0f,
                rate = obj["rate"]?.toFloatOrNull()?.takeIf { it > 0f } ?: 1.0f,
                enabled = obj["enabled"]?.let { it != "false" } ?: true,
            )
        }
    }

    /** Ручная JSON-сериализация массива правил. */
    fun toJson(rules: List<VoiceIntonationRule>): String {
        val sb = StringBuilder("[")
        rules.forEachIndexed { idx, rule ->
            if (idx > 0) sb.append(',')
            sb.append('{')
            sb.append("\"pattern\":\"").append(VoiceJson.jsonEscape(rule.pattern)).append('"')
            sb.append(",\"exact\":").append(rule.exact)
            sb.append(",\"pauseMs\":").append(rule.pauseMs)
            sb.append(",\"pitch\":").append(rule.pitch)
            sb.append(",\"rate\":").append(rule.rate)
            sb.append(",\"enabled\":").append(rule.enabled)
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    fun load(prefs: OcrPreferences): List<VoiceIntonationRule> =
        parse(prefs.voiceIntonations().get())

    fun save(prefs: OcrPreferences, rules: List<VoiceIntonationRule>) {
        prefs.voiceIntonations().set(toJson(rules))
    }

    /** Первое правило, подошедшее под предложение, или null. */
    fun matchRule(rules: List<VoiceIntonationRule>, sentence: String): VoiceIntonationRule? =
        rules.firstOrNull { it.matches(sentence) }

    fun matchRule(prefs: OcrPreferences, sentence: String): VoiceIntonationRule? =
        matchRule(load(prefs), sentence)
}