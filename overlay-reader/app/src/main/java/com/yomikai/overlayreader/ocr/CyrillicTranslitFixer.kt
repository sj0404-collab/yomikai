package com.yomikai.overlayreader.ocr

/**
 * Преобразует латинские омоглифы/транслитерацию обратно в кириллицу.
 *
 * Порт из yomikai (CyrillicTranslitFixer). Локальный template-движок
 * частотные путает похожие начертания (X→Х, A→А, H→Н); online-движки иногда
 * отдают русский текст латиницей. Этот класс приводит такие гипотезы к
 * русскому виду, не выдумывая несуществующие слова.
 */
object CyrillicTranslitFixer {

    private val LOOKALIKE_MAP = mapOf(
        'A' to 'А', 'a' to 'а',
        'B' to 'В',
        'C' to 'С', 'c' to 'с',
        'E' to 'Е', 'e' to 'е',
        'H' to 'Н',
        'K' to 'К', 'k' to 'к',
        'M' to 'М',
        'O' to 'О', 'o' to 'о',
        'P' to 'Р', 'p' to 'р',
        'T' to 'Т',
        'X' to 'Х', 'x' to 'х',
        'y' to 'у',
        '3' to 'З',
        '6' to 'б',
    )

    private val TRANSLIT_MULTI_MAP = listOf(
        "shch" to "щ", "SHCH" to "Щ", "Shch" to "Щ",
        "ch" to "ч", "CH" to "Ч", "Ch" to "Ч",
        "sh" to "ш", "SH" to "Ш", "Sh" to "Ш",
        "zh" to "ж", "ZH" to "Ж", "Zh" to "Ж",
        "yo" to "ё", "YO" to "Ё", "Yo" to "Ё", "jo" to "ё",
        "ya" to "я", "YA" to "Я", "Ya" to "Я", "ja" to "я",
        "yu" to "ю", "YU" to "Ю", "Yu" to "Ю", "ju" to "ю",
        "ye" to "е", "YE" to "Е", "Ye" to "Е",
        "ts" to "ц", "TS" to "Ц", "Ts" to "Ц",
        "kh" to "х", "KH" to "Х", "Kh" to "Х",
    )

    private val TRANSLIT_SINGLE_MAP = mapOf(
        'a' to "а", 'b' to "б", 'v' to "в", 'g' to "г", 'd' to "д",
        'e' to "е", 'z' to "з", 'i' to "и", 'k' to "к", 'l' to "л",
        'm' to "м", 'n' to "н", 'o' to "о", 'p' to "п", 'r' to "р",
        's' to "с", 't' to "т", 'u' to "у", 'f' to "ф", 'h' to "х",
        'y' to "ы", 'j' to "й", 'w' to "в", 'q' to "к", 'x' to "кс",
        'A' to "А", 'B' to "Б", 'V' to "В", 'G' to "Г", 'D' to "Д",
        'E' to "Е", 'Z' to "З", 'I' to "И", 'K' to "К", 'L' to "Л",
        'M' to "М", 'N' to "Н", 'O' to "О", 'P' to "П", 'R' to "Р",
        'S' to "С", 'T' to "Т", 'U' to "У", 'F' to "Ф", 'H' to "Х",
        'Y' to "Ы", 'J' to "Й", 'W' to "В", 'Q' to "К",
    )

    /** Омоглифная подмена: X→Х, A→А, B→В, H→Н и т.п. */
    fun fixLookalikes(text: String): String {
        val sb = StringBuilder(text.length)
        for (char in text) {
            sb.append(LOOKALIKE_MAP[char] ?: char)
        }
        return sb.toString()
    }

    /** Полная транслитерация латиницы в кириллицу (запасной путь). */
    fun translitToCyrillic(text: String): String {
        var result = text
        for ((latin, cyrillic) in TRANSLIT_MULTI_MAP) {
            result = result.replace(latin, cyrillic)
        }
        val sb = StringBuilder(result.length * 2)
        for (char in result) {
            val mapped = TRANSLIT_SINGLE_MAP[char]
            sb.append(mapped ?: char)
        }
        return sb.toString()
    }

    /**
     * Приоритет — омоглифы, а не транслитерация. Если после подмены латиницы
     * не осталось — строка и была кириллицей латинскими омоглифами. Настоящую
     * латиницу (английская надпись) не трогаем.
     */
    fun autoFixCyrillic(text: String): String {
        if (text.isBlank()) return text

        val latinCount = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        val cyrillicCount = text.count { it in '\u0400'..'\u04FF' }

        return when {
            latinCount > 0 && cyrillicCount > 0 -> fixLookalikes(text)
            latinCount > 0 -> {
                val lookalike = fixLookalikes(text)
                val leftoverLatin = lookalike.count { it in 'a'..'z' || it in 'A'..'Z' }
                when {
                    leftoverLatin == 0 -> lookalike
                    !text.any { it == ' ' || it == '\n' || it == '\t' } &&
                        translitToCyrillic(text).none { it in 'a'..'z' || it in 'A'..'Z' } ->
                        translitToCyrillic(text)
                    lookalike.count { it in '\u0400'..'\u04FF' } > 0 -> text
                    else -> translitToCyrillic(text)
                }
            }
            else -> text
        }
    }
}