package eu.kanade.tachiyomi.ui.koma

/**
 * Одна точка сборки регулярных выражений читалки.
 *
 * Зачем: 24.10.2026 читалка падала на открытии с
 * `PatternSyntaxException: Syntax error in regexp pattern near index 35` — в
 * шаблоне `\{\s*(?:имя|name)\s*:\s*[^}]{1,40}}` осталась НЕЭКРАНИРОВАННАЯ `}`.
 * Android собирает регулярку через ICU, а ICU (в отличие от некоторых других
 * реализаций) считает одиночную `}` после квантификатора синтаксической
 * ошибкой и бросает исключение — в главном потоке это конец приложения.
 *
 * Правило теперь одно: шаблон собирается здесь и битым не бывает. Если шаблон
 * не собрался — возвращается `null`, и вызывающий код просто не применяет это
 * правило (подпись говорящего не распознана), но страница читается дальше.
 */
internal object KomaRegex {

    fun compileOrNull(pattern: String, vararg options: RegexOption): Regex? =
        runCatching { Regex(pattern, options.toSet()) }.getOrNull()

    /** Для тестов и проверок: шаблон собирается без исключения наружу. */
    fun isCompilable(pattern: String): Boolean = compileOrNull(pattern) != null
}
