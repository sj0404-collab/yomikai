package eu.kanade.tachiyomi.ui.koma

/**
 * Починка распознанного текста — общая для чтения и для движка распознавания.
 *
 * Модель регулярно пишет русские буквы латиницей, греческими заглавными и
 * цифрами: «HETΛOXO», «BECEΛO», «3ВУ4ИТ», «MECTO». Без починки такая строка не
 * проходит фильтр языка и пропадает из реплики целиком — в лаборатории из-за
 * этого терялись целые слова и целые реплики.
 */
object KomaText {
    private val lookalikes = mapOf(
        'a' to 'а', 'e' to 'е', 'o' to 'о', 'c' to 'с', 'p' to 'р', 'x' to 'х',
        'y' to 'у', 'k' to 'к', 'm' to 'м', 't' to 'т', 'b' to 'в', 'h' to 'н',
        'u' to 'и', 'n' to 'п',
        'A' to 'А', 'E' to 'Е', 'O' to 'О', 'C' to 'С', 'P' to 'Р', 'X' to 'Х',
        'Y' to 'У', 'K' to 'К', 'M' to 'М', 'T' to 'Т', 'B' to 'В', 'H' to 'Н',
        'U' to 'И',
        // Греческие заглавные.
        'Λ' to 'Л', 'Δ' to 'Д', 'Α' to 'А', 'Β' to 'В', 'Ε' to 'Е', 'Ζ' to 'З',
        'Η' to 'Н', 'Ι' to 'И', 'Κ' to 'К', 'Μ' to 'М', 'Ν' to 'Н', 'Ο' to 'О',
        'Ρ' to 'Р', 'Τ' to 'Т', 'Υ' to 'У', 'Χ' to 'Х',
        // Цифры-двойники: «3ВУ4ИТ» вместо «ЗВУЧИТ».
        '3' to 'з', '4' to 'ч', '0' to 'о', '6' to 'б', '8' to 'в',
    )

    private val greek = setOf('Λ', 'Δ', 'Α', 'Β', 'Ε', 'Ζ', 'Η', 'Ι', 'Κ', 'Μ', 'Ν', 'Ο', 'Ρ', 'Τ', 'Υ', 'Χ')

    fun isRussianLetter(ch: Char) = ch in 'а'..'я' || ch in 'А'..'Я' || ch == 'ё' || ch == 'Ё'

    /** Строка считается русской, если русских букв не меньше половины. */
    fun isRussianLine(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        val russian = text.count { isRussianLetter(it) }
        return russian > 0 && russian * 2 >= letters.length
    }

    fun fixLookalikes(raw: String): String {
        // Решение по СТРОКЕ, а не по слову: если в строке есть русские буквы или
        // греческие двойники, то и слова из одних латинских/греческих символов
        // переводятся в кириллицу.
        val lineIsRussian = raw.any { isRussianLetter(it) || it in greek }
        return raw.split(' ').joinToString(" ") { word ->
            val letters = word.filter { it.isLetterOrDigit() }
            val convertible = letters.isNotEmpty() && letters.all { it in lookalikes }
            val wordHasRussian = word.any { isRussianLetter(it) }
            if (lineIsRussian && (wordHasRussian || convertible)) {
                word.map { lookalikes[it] ?: it }.joinToString("")
            } else {
                word
            }
        }
    }

    fun cleanSpeech(raw: String): String {
        var text = raw
            .replace(Regex("(?<=\\p{L})\\s*-\\s*(?=\\p{L})"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        text = fixLookalikes(text)
        // Склеиваем цепочки одиночных букв: «в д а ль» → «вдаль».
        repeat(3) {
            text = text.replace(Regex("(?<!\\p{L})\\p{L}(\\s+\\p{L}){1,4}(?!\\p{L})")) { match ->
                match.value.replace(" ", "")
            }
        }
        return text.trim()
    }
}
