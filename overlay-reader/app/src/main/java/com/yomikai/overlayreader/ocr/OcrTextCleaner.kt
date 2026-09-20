package com.yomikai.overlayreader.ocr

/**
 * Постобработка распознанного текста: склейка переносов, сжатие пробелов,
 * удаление мусорных символов, автофикс кириллицы.
 */
object OcrTextCleaner {

    private val KEEP = Regex("[\\p{L}\\p{N}\\s.,!?;:'\"«»()\\-—…]")
    private val ALLOWED = Regex("[\\p{L}\\p{N}]")

    /**
     * Склейка дефиса на конце строки с началом следующей: «ХО- \nРОШО» → «ХОРОШО».
     */
    fun joinLineHyphens(text: String): String {
        val lines = text.split("\n")
        val sb = StringBuilder()
        for (i in lines.indices) {
            val line = lines[i].trimEnd()
            if (i > 0 && lines[i - 1].trimEnd().endsWith("-")) {
                sb.replace(sb.length - 1, sb.length, "")
            } else if (i > 0 && !sb.isEmpty()) {
                sb.append(' ')
            }
            val candidate = line
            if (candidate.endsWith("-") && i < lines.size - 1) {
                sb.append(candidate.dropLast(1))
            } else {
                sb.append(candidate)
            }
        }
        return sb.toString()
    }

    /**
     * Консервативная очистка: убираем то, что не похоже на текст (служебные
     * символы, одиночные несмысловые токены), сохраняем пробелы и пунктуацию.
     */
    fun clean(text: String): String {
        val kept = text.map { if (KEEP.matches(it.toString())) it else ' ' }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
        return kept
    }

    /**
     * Полный цикл: очистка → автофикс кириллицы → склейка переносов.
     */
    fun postprocess(text: String): String {
        val cleaned = clean(text).let { CyrillicTranslitFixer.autoFixCyrillic(it) }
        return joinLineHyphens(cleaned).trim()
    }

    /** Число «настоящих» букв (первые 50 буквенных символов, для эвристик голоса). */
    fun letterRatio(text: String): Float {
        if (text.isBlank()) return 0f
        val letters = text.count { ALLOWED.matches(it.toString()) }
        return letters.toFloat() / text.length
    }
}