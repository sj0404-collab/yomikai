package com.yomikai.overlayreader.ocr

/**
 * Постобработка распознанного текста: склейка переносов, сжатие пробелов,
 * удаление мусорных символов, автофикс кириллицы.
 */
object OcrTextCleaner {

    private val KEEP = Regex("[\\p{L}\\p{N}\\s.,!?;:'\"«»()\\-—…]")
    private val ALLOWED = Regex("[\\p{L}\\p{N}]")

    /**
     * Склейка дефиса на конце строки с началом следующей: «ХО-\nРОШО» → «ХОРОШО».
     * Обычные переводы строк не трогаем — их в пробел сворачивает `clean()`.
     */
    fun joinLineHyphens(text: String): String =
        text.replace(Regex("-\\s*\r?\n\\s*"), "")

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
     * Полный цикл: склейка переносов → очистка → автофикс кириллицы.
     *
     * Склейка ДО чистки: `clean()` сворачивает переводы строк в пробел, и
     * информация о переносе теряется.
     */
    fun postprocess(text: String): String {
        val joined = joinLineHyphens(text)
        val cleaned = clean(joined)
        return CyrillicTranslitFixer.autoFixCyrillic(cleaned).trim()
    }

    /** Число «настоящих» букв (первые 50 буквенных символов, для эвристик голоса). */
    fun letterRatio(text: String): Float {
        if (text.isBlank()) return 0f
        val letters = text.count { ALLOWED.matches(it.toString()) }
        return letters.toFloat() / text.length
    }
}