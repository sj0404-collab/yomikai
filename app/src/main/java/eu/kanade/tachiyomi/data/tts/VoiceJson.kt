package eu.kanade.tachiyomi.data.tts

/**
 * Лёгкий ручной JSON-разбор/сериализация словарей голосов и интонаций.
 *
 * org.json живёт только внутри Android, а в юнит-тестах app-модуля он не
 * замокан и бросает при любом обращении. Словари должны читаться и в тестах,
 * поэтому формат (массив объектов) разбирается этим хелпером независимо.
 */
internal object VoiceJson {

    /** Клоч JSON-массива: `[{"k":"v"},{"k":"v"}]` → список карт. */
    fun parseObjects(json: String): List<Map<String, String>> {
        val trimmed = json.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return parseObject(trimmed).let { if (it.isEmpty()) emptyList() else listOf(it) }
        val inner = trimmed.removePrefix("[").removeSuffix("]").trim()
        if (inner.isEmpty()) return emptyList()
        val result = mutableListOf<Map<String, String>>()
        var i = 0
        while (i < inner.length) {
            val objStart = inner.indexOf('{', i)
            if (objStart < 0) break
            var depth = 0
            var inStr = false
            var m = objStart
            var end = objStart
            while (m < inner.length) {
                val c = inner[m]
                if (inStr) {
                    if (c == '\\') { m += 2; continue }
                    if (c == '"') inStr = false
                } else {
                    when (c) {
                        '"' -> inStr = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) { end = m; break }
                        }
                    }
                }
                m++
            }
            val objText = inner.substring(objStart, end + 1)
            result.add(parseObject(objText))
            i = end + 1
        }
        return result
    }

    internal fun parseObject(text: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val t = text.trim()
        if (!t.startsWith("{") || !t.endsWith("}")) return map
        val inner = t.removePrefix("{").removeSuffix("}").trim()
        if (inner.isEmpty()) return map
        for (pair in splitTopLevel(inner)) {
            val colon = pair.indexOf(':')
            if (colon <= 0) continue
            val key = unquote(pair.substring(0, colon).trim())
            val value = unquote(pair.substring(colon + 1).trim())
            map[key] = value
        }
        return map
    }

    /** Разбирает массив `["a","b"]` в список токенов. */
    fun parseArrayTokens(s: String): List<String> {
        val t = s.trim()
        if (!t.startsWith("[") || !t.endsWith("]")) {
            // Единичная метка без скобок.
            return if (t.isNotBlank()) listOf(unquote(t)) else emptyList()
        }
        val inner = t.removePrefix("[").removeSuffix("]").trim()
        if (inner.isEmpty()) return emptyList()
        return splitTopLevel(inner).map(::unquote).filter { it.isNotBlank() }
    }

    /** Разбивает содержимое по запятым нулевого уровня (не в строке и не в скобках). */
    internal fun splitTopLevel(text: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var inStr = false
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inStr) {
                if (c == '\\') { i += 2; continue }
                if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{', '[', '(' -> depth++
                    '}', ']', ')' -> depth--
                    ',' -> if (depth == 0) { result.add(text.substring(start, i)); start = i + 1 }
                }
            }
            i++
        }
        if (start < text.length) result.add(text.substring(start))
        return result
    }

    /** Снимает кавычки со строки, если они есть. */
    internal fun unquote(s: String): String {
        val t = s.trim()
        if (t.length >= 2 && t.first() == '"' && t.last() == '"') {
            return t.substring(1, t.length - 1).replace("\\\"", "\"")
        }
        return t
    }

    fun jsonEscape(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c.coerceAtLeast(' '))
            }
        }
    }
}