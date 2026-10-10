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

    /**
     * Значение по ключу из плоского JSON-объекта: `{ "12": [...] }` → `[...]`.
     *
     * Ключи — числа (id книги), поэтому ищем `"<key>"` с двоеточием, а не как
     * обычное поле. null — ключа нет либо значение не строка/массив; вызывающий
     * трактует это как «для этой книги ролей нет».
     */
    fun extractArray(json: String, key: String): String? {
        val t = json.trim()
        if (!t.startsWith("{") || !t.endsWith("}")) return null
        val needle = "\"$key\""
        var i = 0
        while (i < t.length) {
            val at = t.indexOf(needle, i)
            if (at < 0) return null
            var m = at + needle.length
            while (m < t.length && t[m].isWhitespace()) m++
            if (m >= t.length || t[m] != ':') {
                i = at + needle.length
                continue
            }
            m++
            while (m < t.length && t[m].isWhitespace()) m++
            if (m >= t.length) return null
            return when (t[m]) {
                '[' -> {
                    var depth = 0
                    var inStr = false
                    var k = m
                    while (k < t.length) {
                        val c = t[k]
                        if (inStr) {
                            if (c == '\\') { k += 2; continue }
                            if (c == '"') inStr = false
                        } else {
                            when (c) {
                                '"' -> inStr = true
                                '[' -> depth++
                                ']' -> {
                                    depth--
                                    if (depth == 0) return t.substring(m, k + 1)
                                }
                            }
                        }
                        k++
                    }
                    null
                }
                '"' -> {
                    // Стартуем сразу ПОСЛЕ открывающей кавычки: раньше цикл
                    // натыкался на неё и выходил с inStr=false -> всегда null.
                    var k = m + 1
                    var closed = false
                    while (k < t.length) {
                        val c = t[k]
                        if (c == '\\') { k += 2; continue }
                        if (c == '"') { closed = true; break }
                        k++
                    }
                    if (closed) unquote(t.substring(m, k + 1)) else null
                }
                else -> null
            }
        }
        return null
    }

    /**
     * Заменить (или добавить) значение по ключу в плоском JSON-объекте.
     *
     * Пишется вручную и по верхнему уровню: чужие книги и их роли должны
     * пережить перезапись нетронутыми, а org.json здесь недоступен в тестах.
     */
    fun withArray(json: String, key: String, value: String): String {
        val t = json.trim()
        val entry = "\"$key\":$value"
        if (t.isEmpty() || !t.startsWith("{") || !t.endsWith("}")) return "{$entry}"
        val inner = t.removePrefix("{").removeSuffix("}").trim()
        if (inner.isEmpty()) return "{$entry}"
        val out = StringBuilder("{")
        var i = 0
        var replaced = false
        while (i < inner.length) {
            // Один элемент объекта: "key":value — value либо массив, либо строка.
            val keyStart = inner.indexOf('"', i)
            if (keyStart < 0) break
            var k = keyStart + 1
            var inStr = false
            while (k < inner.length) {
                val c = inner[k]
                if (c == '\\') { k += 2; continue }
                if (c == '"') { inStr = false; break }
                k++
            }
            if (inStr) break
            val name = inner.substring(keyStart + 1, k)
            var m = k + 1
            while (m < inner.length && inner[m].isWhitespace()) m++
            if (m >= inner.length || inner[m] != ':') break
            m++
            while (m < inner.length && inner[m].isWhitespace()) m++
            val valueEnd = valueEndOf(inner, m)
            if (valueEnd < 0) break
            val element = inner.substring(keyStart, valueEnd + 1)
            if (out.length > 1) out.append(',')
            if (name == key) {
                out.append(entry)
                replaced = true
            } else {
                out.append(element)
            }
            i = valueEnd + 1
        }
        if (!replaced) out.append(',').append(entry)
        out.append('}')
        return out.toString()
    }

    /** Индекс последнего символа значения, начавшегося в [start], или -1. */
    private fun valueEndOf(text: String, start: Int): Int {
        if (start >= text.length) return -1
        return when (text[start]) {
            '[' -> {
                var depth = 0
                var inStr = false
                var i = start
                while (i < text.length) {
                    val c = text[i]
                    if (inStr) {
                        if (c == '\\') { i += 2; continue }
                        if (c == '"') inStr = false
                    } else {
                        when (c) {
                            '"' -> inStr = true
                            '[' -> depth++
                            ']' -> {
                                depth--
                                if (depth == 0) return i
                            }
                        }
                    }
                    i++
                }
                -1
            }
            '"' -> {
                var i = start + 1
                while (i < text.length) {
                    val c = text[i]
                    if (c == '\\') { i += 2; continue }
                    if (c == '"') return i
                    i++
                }
                -1
            }
            else -> {
                var i = start
                while (i < text.length && text[i] != ',' && !text[i].isWhitespace()) i++
                if (i == start) -1 else i - 1
            }
        }
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