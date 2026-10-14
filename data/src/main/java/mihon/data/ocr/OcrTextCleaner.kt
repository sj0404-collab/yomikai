package mihon.data.ocr

/**
 * Постобработка текста после CTC-декодирования и онлайн-движков.
 *
 * 1. [fixLookalikesPerWord] — пословная правка: в словах С кириллицей
 *    латинские омоглифы и похожие цифры заменяются на кириллицу (N→Н, I→И,
 *    5→Б …), чтобы текст читался и TTS не диктовал буквы. Чистая латынь не
 *    транслитерируется автоматически: локальный CTC-модель часто выдаёт её
 *    как мусор на декоративных шрифтах, и «исправление» превращало ошибку в
 *    убедительную, но выдуманную русскую фразу;
 * 2. [joinLineHyphens] — переносы слов соединяются («пере-\nносится» →
 *    «переносится»);
 * 3. [looksLikeDictionaryRamp] — фильтр мусора «словарной лесенкой».
 */
/**
 * Счётчики чистильщиков, которые РЕАЛЬНО сработали за проход распознавания:
 * словарные восстановления слов и склейки пунктуации. Используются индикатором
 * сканирования и историей: пользователь просил видеть, применялся ли словарь.
 */
object OcrTextCleanerStats {
    @Volatile
    var wordDictHits: Int = 0

    @Volatile
    var userDictHits: Int = 0

    @Volatile
    var punctFixes: Int = 0

    @Volatile
    var splitFixes: Int = 0

    /** Сколько раз срезан мусорный токен на краю реплики. */
    @Volatile
    var edgeNoiseTrims: Int = 0

    /** Сколько одиночных букв выброшено как вставки распознавателя («Х»). */
    @Volatile
    var droppedStrayLetters: Int = 0

    /** Сколько целых результатов отброшено как нечленые по словарю («тій»). */
    @Volatile
    var dictionaryGarbage: Int = 0

    /** Сколько страниц отброшено целиком как заставки без речи. */
    @Volatile
    var speechlessPages: Int = 0

    fun reset() {
        wordDictHits = 0
        userDictHits = 0
        punctFixes = 0
        splitFixes = 0
        edgeNoiseTrims = 0
        droppedStrayLetters = 0
        dictionaryGarbage = 0
        speechlessPages = 0
    }
}

object OcrTextCleaner {

    private val HYPHEN_LINE_BREAK = Regex("([\\p{L}])-[ \\t]*\\n[ \\t]*([\\p{L}])")
    private val CYRILLIC_RUN = Regex("[А-Яа-яЁё]{2,}")

    /** Пробелы/табуляции ВНУТРИ строки; перевод строки не трогает. */
    private val HORIZONTAL_WHITESPACE = Regex("[ \\t\\u00A0\\u2007\\u202F]+")

    /** Несколько пустых строк подряд схлопываются в один перевод строки. */
    private val BLANK_LINES = Regex("\\n[ \\t]*\\n+")

    /**
     * Разделители токенов. Раньше эти [Regex] собирались заново на КАЖДЫЙ вызов
     * [dictionaryCoverage] / [userDictionaryCoverage], а их дёргает ранжирование
     * кандидатов по 2–3 раза на каждый кроп: до 36 компиляций регулярки на одну
     * реплику только на этом.
     */
    private val TOKEN_SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
    private val INLINE_SPACES = Regex("[ \\t]{2,}")
    private val WHITESPACE_RUN = Regex("\\s+")
    private val PUNCT_BEFORE_LETTER = Regex("([,!?;:])(?=[А-Яа-яЁё])")
    private val QUOTE_CLOSE_BEFORE_CAPS = Regex("([»”])(?=[А-ЯЁ])")
    private val QUOTE_OPEN_AFTER_LETTER = Regex("(?<=[А-ЯЁа-яё])([«„])")

    private val LATIN_WHITELIST = setOf(
        "sos", "bmw", "wi-fi", "ok", "tv", "dvd", "3d", "hp", "pc", "usb", "sim", "sd",
    )

    /**
     * Расширенная таблица омоглифов и визуальных замен для слов, в которых
     * уже есть кириллица. Собрана по реальным промахам модели на манге:
     * N→Н, I→И, L→Л, D→Д, G→Г, W/V→В, Z→З, J→Й, S→С, 5→Б, 0→О, 4→Ч…
     */
    private val EXT_LOOKALIKE_MAP = mapOf(
        'A' to 'А', 'a' to 'а',
        'B' to 'В',
        'C' to 'С', 'c' to 'с',
        'E' to 'Е', 'e' to 'е',
        'H' to 'Н',
        'K' to 'К', 'k' to 'к',
        'M' to 'М', 'm' to 'м',
        'O' to 'О', 'o' to 'о',
        'P' to 'Р', 'p' to 'р',
        'T' to 'Т', 't' to 'т',
        'X' to 'Х', 'x' to 'х',
        'y' to 'у',
        '3' to 'З', '6' to 'б',
        'I' to 'И', 'L' to 'Л', 'N' to 'Н', 'D' to 'Д', 'G' to 'Г',
        'W' to 'В', 'V' to 'В', 'Z' to 'З', 'J' to 'Й', 'S' to 'С',
        's' to 'с',
        '5' to 'Б', '0' to 'О', '4' to 'Ч',
    )

    fun joinLineHyphens(text: String): String {
        if (!text.contains('-')) return text
        return HYPHEN_LINE_BREAK.replace(text) { m ->
            m.groupValues[1] + m.groupValues[2]
        }
    }

    private val PROMOTIONAL_SITE = Regex(
        // v1.9.135: водяники манга-сайтов попадали в OCR и ЗАЧИТЫВАЛИСЬ как
        // реплика («ЧИТАЙ НА САЙТЕ MANGABUFF…» получала номер реплики).
        "reman?ga(?:\\.org)?|readmanga|mintmanga|mangabuff(?:\\.ru|\\.me|\\.com)?" +
            "|mangapoisk|manga_?one|mangalib|acomics|usagi(?:\\.one)?|seimanga" +
            "|desu(?:\\.me|s)?(?!\\p{L})|mangarip|manga-?sama|new-manga",
        RegexOption.IGNORE_CASE,
    )
    private val PROMOTIONAL_SLOGAN = Regex(
        // слоганы «читай раньше/на сайте» и слепки с адресом; короткие формы
        // OCR (без точек/с опечатками «ситуатУка») ловим общими кусками.
        "читай\\s+раньше\\s+всех(?:\\s+на\\s+\\S+)?" +
            "|читай(те)?\\s+(?:новинки\\s+)?(?:бесплатно\\s+)?на\\s+сайте?(?:\\s+\\S+){0,3}" +
            "|читаем\\s+на\\s+сайте?|сайте?\\s+\\S{0,16}\\.\\s*(?:ru|com|net|club|me|fun)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Рекламные бары новелл-читалок («+ алмазы и промокоды в Telegram-канале
     * Manga… Перейти», «ГЛАВЫ БЫСТРО В ДРУГИХ») OCR склеивает с реальной
     * репликой через общую строку: целиком строку мы НЕ сносим — режем хвост
     * от первого маркера. Реплика остаётся, реклама пропадает.
     * «.» не переваривает \n — хвост режется ровно до конца строки.
     */
    private val PROMOTIONAL_ADBLOCK = Regex(
        "\\s*\\+?\\s*алмазы\\s+и\\s+промокод\\p{L}{0,2}.*" +
            "|\\s*\\+\\s*алмазы.*" +
            "|телеграм(-|\\s)канал.*|telegram(-|\\s)канал.*|tg(-|\\s)канал.*" +
            "|главы\\s+быстре?о?\\s+в\\s+других.*" +
            "|быстрее?,?\\s+качественнее.*" +
            "|читай(те)?\\s+на\\s+(?:сайте|сайте\\.\\S+).*" +
            "|поддержать\\s+автора.*",
        RegexOption.IGNORE_CASE,
    )

    /** Кнопки/навигация как САМОСТОЯТЕЛЬНАЯ строка — репликой не бывает. */
    private val STANDALONE_AD_BUTTONS = setOf(
        "перейти", "читать далее", "подробнее", "жми сюда", "далее",
        "назад", "вперёд", "вперед", "меню глав",
    )


    fun stripPromotionalText(text: String): String {
        if (text.isBlank()) return text
        return text.lineSequence()
            .map { line ->
                val cut = line
                    .replace(PROMOTIONAL_ADBLOCK, "")
                    .replace(PROMOTIONAL_SLOGAN, "")
                    .replace(PROMOTIONAL_SITE, "")
                    .replace(INLINE_SPACES, " ")
                    .trim()
                if (cut.lowercase() in STANDALONE_AD_BUTTONS) "" else cut

            }
            .filter(String::isNotBlank)
            .joinToString("\n")
    }

    fun isPromotionalText(text: String): Boolean {
        if (text.isBlank()) return false
        // v1.9.136: линюя-строка с водяником режется ЦЕЛИКОМ, даже если после
        // слогана стоит ещё слово («ЧИТАЙ НА САЙТЕ MANGABUFF… И ПРОЧЕЕ»): раньше
        // проверка требовала, чтобы strip обнулил строку дочиста, и любой
        // лишний хвост реабилитировал водяник. Промо-маркер внутри реплики не
        // бывает случайным: нормальный текст слов «readmanga/mangabuff» и
        // «читай на сайте» не содержит.
        return stripPromotionalText(text).isBlank() ||
            PROMOTIONAL_SITE.containsMatchIn(text) ||
            PROMOTIONAL_SLOGAN.containsMatchIn(text)

    }

    /**
     * Нормализует локально распознанную русскую подпись в правильном порядке.
     *
     * Сначала склеиваем переносы «ХО-\nРОШО», пока граница строки ещё
     * сохранена. Лишь затем восстанавливаем безопасные границы известных
     * слов. Раньше [restoreKnownCaptionWords] сворачивал `\n` в пробел до
     * [joinLineHyphens], и пользователь видел ложное «ХО- РОШО».
     */
    fun normalizeLocalCyrillicCaption(text: String): String {
        return restoreKnownCaptionWords(
            fixLookalikesPerWord(joinLineHyphens(text)),
        )
    }

    fun fixLookalikesPerWord(text: String): String {
        if (text.isEmpty()) return text
        return text.split(' ').joinToString(" ") { word ->
            val hasCyrillic = word.any { it.code in CYRILLIC_RANGE }
            val hasLatin = word.any { it.isLetter() && it.code < 0x80 }
            when {
                hasCyrillic -> mapChars(word, EXT_LOOKALIKE_MAP)
                hasLatin -> {
                    val bare = word.trimEnd('.', '!', ',', '?', '…').lowercase()
                    if (bare in LATIN_WHITELIST) word else word
                }
                else -> word
            }
        }
    }

    /**
     * Принимает только осмысленный результат русского локального движка.
     * Пунктуация, корейские SFX и CTC-мусор с остаточной латиницей не должны
     * попадать в переводчик как «русский» текст. Допускаются короткие
     * общеупотребимые латинские токены из белого списка (`SOS`, `Wi-Fi`, `3D`).
     */
    /**
     * Пословный salvage распознанной строки.
     *
     * Раньше [isAcceptableCyrillicOcrText] требовала, чтобы ВСЕ слова строки
     * были чистыми: один латинский мусорный токен (`Tele'axect.E`) обнулял всю
     * подпись, и пользователь получал «Нет результатов» вместо готовой фразы.
     * Именно так на device-проверке пропала белая подпись
     * «ПО СЛОВАМ «ОХОТНИЧЬЕГО ПСА»…».
     *
     * Правила намеренно консервативны:
     *  * строка без единой кириллической буквы возвращается как есть — это
     *    латинская надпись или звукоподражание, терять её нельзя;
     *  * если в строке осталось смешанное или сомнительное кириллическое
     *    слово, возвращается вся строка целиком: частичный salvage мог бы
     *    собрать фразу, которой в оригинале не было;
     *  * иначе отбрасываются только токены без кириллицы, не входящие в
     *    [LATIN_WHITELIST] (`SOS`, `Wi-Fi`, `3D` сохраняются).
     *
     * Это не словарная коррекция: ничего не придумывается и не заменяется.
     */
    fun filterGarbageTokens(text: String): String {
        if (text.isBlank()) return text
        val tokens = text.split(' ').filter(String::isNotEmpty)
        if (tokens.isEmpty()) return text
        val hasCyrillic = tokens.any { token -> token.any { it.code in CYRILLIC_RANGE } }
        if (!hasCyrillic) return text
        val suspicious = tokens.any { token ->
            token.any { it.code in CYRILLIC_RANGE } &&
                token.any { it.isLetter() && it.code < 0x80 }
        }
        if (suspicious) return text
        val kept = tokens.filter { token ->
            val lexical = token.filter(Char::isLetter)
            // Одиночная буква, которой нет ни в словаре, ни среди служебных
            // предлогов, — вставка распознавателя: в скриншоте пользователя
            // так попадало «Х» внутрь «ПОЗВАТЬ Х ВАШУ ГОРНИЧНУЮ». Настоящие
            // «В», «К», «С», «У» словами являются и остаются на месте.
            val strayLetter = lexical.length == 1 &&
                lexical.any { it.code in CYRILLIC_RANGE } &&
                !knownWord(lexical) &&
                token !in SINGLE_LETTER_SOUNDS
            if (strayLetter) {
                OcrTextCleanerStats.droppedStrayLetters++
                false
            } else {
                token.any { it.code in CYRILLIC_RANGE } ||
                    token.trimEnd('.', '!', ',', '?', '…').lowercase() in LATIN_WHITELIST
            }
        }
        return if (kept.isEmpty()) "" else kept.joinToString(" ")
    }

    /**
     * Распознанный текст — звуковой эффект («БАХ», «ШАСТ», …), а не речь.
     * Такие куски не читаются голосом: они уходят в плеер скрытным звуком,
     * и «бах» больше не произносится «бэ-а-ха».
     */
    private val SOUND_EFFECT_LEXICON = setOf(
        "БАХ", "БУХ", "БАБАХ", "БАХБАХ", "БУМ", "БОМ", "ХЛОП", "ХРЯСЬ",
        "ЧМЮК", "ЧМЯК", "ЧМОК", "ТСС", "ТССТСС",
        "ВЖУХ", "ВЖИХ", "ВЖЖ", "ЖЖЖ", "СВИСТ", "ФЬЮ", "ФИУ", "ШШШ", "ШШ",
        "ШШШШ", "ШАСТ", "ШУХ", "ХРЯП", "ХРЯС", "ПЛЮХ", "ПЛЮС", "БАМ",
        "БАМБАМ", "КРАШ", "КРАК", "КЛАЦ", "ХРУСТ", "СКВИРК", "КВАРК",
        "АХ", "ОХ", "ОЙ", "ОЙОЙ", "ХАХА", "ХИХИ", "АХА",
        "ААА", "АААА", "ААААА", "ООО", "ЭЭЭ", "УУУ", "ИИИ",
        "БРРР", "БРРРР", "БЗЗ", "БЗЗЗ", "РРР", "ТРРР",
        "ГРРР", "ГРР", "МРРР", "ЖЖЖЖ", "ЖРЖР", "ЖРРР",
    )

    fun isSoundEffect(rawText: String): Boolean {
        val candidates = rawText.trim()
            .split(WHITESPACE_RUN)
            .map { it.trim().trim('!', '?', '…', '.', ':', ',', ';', '-') }
            .filter { it.isNotBlank() }
        if (candidates.size != 1) return false
        val canon = candidates[0].uppercase().replace(WHITESPACE_RUN, "")
        if (canon in SOUND_EFFECT_LEXICON) return true
        // Одна буква («А», «О», «Э») — междометие, а не слово.
        if (canon.length == 1) return canon in SINGLE_LETTER_SOUNDS
        // Две буквы — уже нормальное слово («он», «на»), не звук.
        if (canon.length == 2) return false
        // «ААА», «ШШШ» — одна буква повторена 3+ раз.
        // «БРР», «ТРРР» — в слове 3+ букв, где одна повторена 2+ раз.
        val groups = canon.groupingBy { it }.eachCount()
        val maxRep = groups.values.maxOrNull() ?: 0
        if (maxRep >= 3 && groups.size <= 2) return true
        if (maxRep >= 2 && groups.size <= 2 && canon.length <= 3) return true
        // «АААА», «БРРРР» — одна буква доминирует в слове до 6 букв.
        if (maxRep >= 3 && canon.length <= 6) return true
        return false
    }

    /** Однобуквенные междометия, которые не являются словами. */
    private val SINGLE_LETTER_SOUNDS = setOf("А", "О", "Э", "У", "И", "Ы")

    fun isAcceptableCyrillicOcrText(text: String): Boolean {
        val words = text.split(WHITESPACE_RUN).filter(String::isNotBlank)
        if (words.isEmpty()) return false

        return words.all { word ->
            val lexical = word.filter(Char::isLetterOrDigit)
            if (lexical.isEmpty()) return@all false

            val cyrillicCount = lexical.count { it.code in CYRILLIC_RANGE }
            val latinCount = lexical.count { it.isLetter() && it.code < 0x80 }
            if (cyrillicCount > 0) {
                latinCount == 0
            } else {
                word.trimEnd('.', '!', ',', '?', '…').lowercase() in LATIN_WHITELIST
            }
        }
    }

    /**
     * Безопасная очистка одной строки Google ML Kit: переносы слов
     * объединяются, явно мусорные строки отбрасываются, а распознанные
     * кириллические слова и смешанные токены сохраняются без догадочной
     * замены букв.
     */
    fun cleanMlKitLine(text: String): String {
        val raw = text.trim()
        if (raw.isBlank()) return ""
        if (looksLikeDictionaryRamp(raw)) return ""
        val cleaned = joinLineHyphens(raw)
        val letters = cleaned.count(Char::isLetter)
        if (letters == 0) return ""
        if (cleaned.none { it.code in CYRILLIC_RANGE }) {
            val latin = cleaned.count { it.isLetter() && it.code < 0x80 }
            if (latin.toFloat() / letters < 0.9f) return ""
        }
        if (letters.toFloat() / cleaned.length < 0.4f) return ""
        return cleaned
    }

    /**
     * Итоговый гейт для строки локального распознавания.
     *
     * [filterGarbageTokens] отбрасывает мусорные токены только из строк, где
     * осталась кириллица; чистая латынь («v i m» вместо «ШУМ») проходит через
     * него как есть, и русский TTS читал бы её по буквам. Здесь результат
     * после salvage возвращается, лишь если он целиком читаем: кириллица или
     * токен из [LATIN_WHITELIST] (`SOS`, `Wi-Fi`).
     *
     * Это не словарная корректировка: текст не придумывается и не заменяется.
     */
    fun acceptableAfterSalvage(text: String): String {
        val salvaged = filterGarbageTokens(text)
        return if (isAcceptableCyrillicOcrText(salvaged)) salvaged else ""
    }

    /**
     * Доля букв, которые уже можно безопасно читать как кириллицу. Нужна не
     * для словарной подмены, а для выбора между двумя визуальными моделями.
     */
    fun cyrillicFitness(text: String): Float {
        val repaired = fixLookalikesPerWord(text)
        val letters = repaired.filter(Char::isLetter)
        if (letters.isEmpty()) return 0f
        return letters.count { it.code in CYRILLIC_RANGE }.toFloat() / letters.length
    }

    /**
     * PP-OCR recognizers correctly read most glyphs in compact manga captions,
     * but their CTC vocabulary has no explicit space token. Restore a boundary
     * only when **every** resulting fragment is in a small offline caption
     * lexicon. This is deliberately not spell correction and never substitutes
     * a different word; an unknown run is left unchanged.
     */
    fun restoreKnownCaptionWords(text: String): String {
        if (text.isBlank()) return text
        var hits = 0
        val restored = CYRILLIC_RUN.replace(text) { match ->
            val r = restoreRun(match.value)
            if (r != match.value) hits++
            r
        }
        // Счётчики реальных срабатываний словарей: их видит индикатор
        // сканирования и (следом) история распознанных страниц.
        OcrTextCleanerStats.wordDictHits += hits
        OcrTextCleanerStats.punctFixes +=
            PUNCT_BEFORE_LETTER.findAll(restored).count()
return restored
            .replace(QUOTE_CLOSE_BEFORE_CAPS, "$1 ")
            .replace(QUOTE_OPEN_AFTER_LETTER, " $1")
            // Пробел после знака препинания перед буквой: модель теряла его
            // на плотной вёрстке («Я,НАКОНЕЦ,» со скриншота).
            .replace(PUNCT_BEFORE_LETTER, "$1 ")
            // Границы реплик — это переводы строк, и раньше они здесь
            // сворачивались в пробелы: все облачка страницы становились одной
            // простынёй текста («давай поиграем… Одному играть… я плохо
            // разбираюсь…»). Схлопываем только горизонтальные пробелы внутри
            // строки, а перевод строк сохраняем и приводим к одному.
            .replace(HORIZONTAL_WHITESPACE, " ")
            .replace(BLANK_LINES, "\n")
            .trim()
    }

    private fun restoreRun(source: String): String {
        val upper = source.uppercase()
        // v1.9.139: настоящие слова НЕ дробим. DP-разбиение с лексиконом
        // коротких слов резало частотную лексику на «слоги»:
        // СВОИХ→«С ВО ИХ», НЕУЖЕЛИ→«НЕ УЖ ЕЛИ», ВЫГЛЯДИТ→«ВЫ ГЛЯДИТ»,
        // МИРА→«МИР А», ПРИБЫЛИ→«ПРИ БЫЛИ», ВДРУГ→«В ДРУГ» — и TTS читал
        // реплики по кускам с паузами («не-уж-ели»).
        // v1.9.141: гейт «короче 9 букв не режем вовсе» снят: он же оставлял
        // слипшимися РОВНО те короткие пары, которые пользователь видел на
        // device-скриншотах («ИЗНИХ», «ОНБЫЛ», «КАКЖЕ», «СДЕМОНОМ»,
        // «СМОГУПО»). Вместо длины прогона границу разрешает
        // [boundaryIsJustified]: целое словарное слово по-прежнему
        // неприкосновенно (СВОИХ/НЕУЖЕЛИ/ВЫГЛЯДИТ/МИРА/ПРИБЫЛИ/ВДРУГ лежат в
        // [RuWordList] и вообще не доходят до разбиения), а у остальных
        // прогонов граница должна быть лингвистически оправдана.
        val solid = upper in CAPTION_WORDS || knownWord(upper)
        var segments = if (solid) null else splitKnownWords(upper)
        if (segments == null && !solid) {

            // Слиплись слова без пробелов: разбиваем по частотному
            // словарю словоформ. Только вставка пробелов.
            val glued = splitGluedRun(upper)
            if (glued != null) {
                OcrTextCleanerStats.splitFixes++
                segments = glued
            }
        }
        val normalized = (segments ?: listOf(upper)).map(::normalizeCaptionWord)
        val joined = normalized.joinToString(" ")
        val letters = source.filter(Char::isLetter)
        return when {
            letters.all(Char::isUpperCase) -> joined
            source.firstOrNull()?.isUpperCase() == true -> joined.lowercase().replaceFirstChar(Char::uppercaseChar)
            else -> joined.lowercase()
        }
    }

    private fun splitKnownWords(run: String): List<String>? {
        if (run.length < 4) return null
        val best = arrayOfNulls<List<String>>(run.length + 1)
        best[0] = emptyList()
        for (start in run.indices) {
            val prefix = best[start] ?: continue
            for (end in start + 1..run.length) {
                val word = run.substring(start, end)
                if (word !in CAPTION_WORDS) continue
                val candidate = prefix + word
                val current = best[end]
                // Минимум сегментов, а не максимум: реальное слово («ПАРАД»)
                // должно побеждать рваньё («ПАР»+«АД»), а не наоборот.
                if (current == null || candidate.size < current.size) best[end] = candidate
            }
        }
        return best[run.length]?.takeIf { it.size > 1 }
    }

    /**
     * DP-разбиение сплошного прогона капсом на слова из [RuWordList] и
     * пользовательского словаря [OcrVocabulary]: минимум сегментов при полном
     * покрытии, как в [splitKnownWords]. Словарик пользователя подключается
     * через [knownWord], поэтому слипшиеся имена персонажей и редкие термины
     * тоже делятся на слова.
     */
    private fun splitGluedRun(run: String): List<String>? {
        // v1.9.141: собственный порог длины снят — решение принимает
        // [boundaryIsJustified], общий для обоих DP-разбиений. Порог 9 букв
        // (v1.9.139) защищал от «ПРИ БЫЛИ», но заодно оставлял слипшимися
        // короткие пары со скриншотов пользователя.
        if (run.length < MIN_SPLIT_RUN) return null

        val best = arrayOfNulls<List<String>>(run.length + 1)
        best[0] = emptyList()
        for (start in run.indices) {
            val prefix = best[start] ?: continue
            val maxEnd = minOf(run.length, start + 24)
            for (end in start + 2..maxEnd) {
                val word = run.substring(start, end)
                if (!knownWord(word)) continue
                val candidate = prefix + word
                val current = best[end]
                if (current == null || candidate.size < current.size) best[end] = candidate
            }
        }
        val split = best[run.length]?.takeIf { it.size > 1 } ?: return null
        return split.takeIf { boundaryIsJustified(split) }
    }

    /**
     * Оправдана ли вставка границ в сплошном прогоне по частотному словарю.
     *
     * Словарь только РАЗРЕШАЕТ границу, но не всякая граница разрешена: DP
     * умеет нарезать настоящее слово на куски из служебных слов — ПРОХОДЕ →
     * «ПРО ХОДЕ», МЕЛКО → «МЕЛ КО», СОЛОНКИ → «СО ЛОНКИ». Поэтому разбиение
     * принимается по одному из двух правил:
     *
     *  1. **все куски — полноценные слова** ([MIN_FULL_SEGMENT] букв и
     *     больше). Тогда разрез не может быть обрезком: он режет ровно те
     *     прогоны, где слиплись два нормальных слова («МНОЖЕСТВОКНИГ»,
     *     «КОНЧИКАВОЛОС»), и не трогает «СТОЛИЧНЫЙ» (СТО+ЛИЧНЫЙ — кусок
     *     в три буквы);
     *  2. **каждый стык упирается в служебное слово** ([RuMorph.isFunctionWord]:
     *     предлог, союз, частица, местоимение), а остальные куски — не короче
     *     [MIN_SEGMENT_LETTERS]. Это ровно тот дефект, который даёт CTC-декодер
     *     без токена пробела: он теряет пробел рядом с предлогом или частицей
     *     («ИЗ НИХ» → «ИЗНИХ», «СМОГУ ПО» → «СМОГУПО», «КАК ЖЕ» → «КАКЖЕ»).
     *     Требование «на КАЖДОМ стыке» отсекает синтетические слитки вроде
     *     «НЕИЗВЕСТНОЕСЛОВО» (второй стык ИЗВЕСТНОЕ|СЛОВО служебным словом
     *     не оправдан).
     *
     * Это по-прежнему НЕ словарная коррекция: текст не заменяется, вставляются
     * только пробелы, а неразобранный прогон остаётся нетронутым.
     */
    private fun boundaryIsJustified(segments: List<String>): Boolean {
        if (segments.all { it.length >= MIN_FULL_SEGMENT }) return true
        val stub = segments.any { it.length < MIN_SEGMENT_LETTERS && !RuMorph.isFunctionWord(it) }
        if (stub) return false
        return segments.zipWithNext().all { (left, right) ->
            RuMorph.isFunctionWord(left) || RuMorph.isFunctionWord(right)
        }

    }

    /**
     * Правда, если слово есть во встроенном частотном словаре [RuWordList]
     * ИЛИ в пользовательском словаре OCR ([OcrVocabulary]). Словарь только
     * РАЗРЕШАЕТ вставить границу — сам текст при этом никогда не заменяется.
     * Словарное слово пользователя дополнительно считается в статистику
     * [OcrTextCleanerStats.userDictHits], чтобы индикатор сканирования не
     * «врал», что словарь отработал.
     */
    fun knownWord(word: String): Boolean {
        if (word.isEmpty()) return false
        val up = word.uppercase()
        // Пользовательский словарь проверяется ПЕРВЫМ. Раньше он шёл вторым, и
        // слово, добавленное пользователем, но уже есть во встроенном
        // словаре, не попадало в userDictHits — индикатор сканирования молчал.
        // Приоритет пользовательского словаря отражает его смысл: это слова,
        // которые он задал сам и которым хочет доверять.
        if (OcrVocabulary.isUserWord(up)) {
            OcrTextCleanerStats.userDictHits++
            return true
        }
        return RuWordList.containsUpper(up)
    }

    fun userDictionaryCoverage(text: String): Float {
        if (text.isBlank()) return 0f
        var letters = 0
        var covered = 0
        for (token in text.split(TOKEN_SEPARATORS)) {
            val lexical = token.filter(Char::isLetter)
            if (lexical.count { it.code in CYRILLIC_RANGE } == 0) continue
            letters += lexical.length
            if (OcrVocabulary.isUserWord(lexical)) covered += lexical.length
        }
        if (letters == 0) return 0f
        return covered.toFloat() / letters
    }

    /**
     * Срезает шум, которым детектор зацепил край облачка.
     *
     * Реплика из манги почти всегда окружена контуром пузыря или следами
     * соседнего рисунка. Если такой кусок попадает в кроп, PP-OCR стабильно
     * пишет его ОДНИМ токеном на начале или на конце строки — пользователь
     * видел это как «рандомные буквы спереди и сзади».
     *
     * Токен считается шумом только если выполнены ВСЕ условия:
     *  * он не словарное слово (иначе это первое слово реплики);
     *  * он короткий — реальное слово на 5+ букв шумом не бывает;
     *  * его удаление ПОВЫШАЕТ покрытие словарём по всей реплике.
     *
     * Последнее условие — главное: оно отличает мусор от настоящего слова.
     * Реплика «Я плохо разбираюсь в военном искусстве» с прилипшим «ПШЛ»
     * после среза покрывается словарём лучше, а реплика «ЭЙ слышишь» после
     * среза «ЭЙ» — хуже, и потому остаётся целой.
     *
     * Ничего не заменяется и не додумывается: режется только крайний токен,
     * максимум один с каждой стороны.
     */
    fun trimEdgeNoise(text: String): String {
        if (text.isBlank()) return text
        val tokens = text.split(WHITESPACE_RUN).filter(String::isNotBlank)
        if (tokens.size < 2) return text

        var start = 0
        var end = tokens.size
        if (isEdgeNoise(tokens, start, end - start)) start++
        if (end - start >= 2 && isEdgeNoise(tokens, end - 1, end - start)) end--
        if (start == 0 && end == tokens.size) return text

        val trimmed = tokens.subList(start, end).joinToString(" ")
        // Срезать нельзя, если от реплики ничего не осталось или осталось не
        // то, что похоже на текст.
        if (trimmed.isBlank() || trimmed.none { it.code in CYRILLIC_RANGE }) return text
        OcrTextCleanerStats.edgeNoiseTrims += tokens.size - (end - start)
        return trimmed
    }

    private fun isEdgeNoise(tokens: List<String>, index: Int, count: Int): Boolean {
        if (count < 2) return false
        val candidate = tokens[index].trimEnd('.', '!', ',', '?', '…', ':', ';', '—', '-')
        if (candidate.isEmpty()) return false
        // isKnownWord: проверка ничего не меняет в тексте,
        // но knownWord увеличивает счётчики пользовательского словаря, а это
        // служебный прогон на отбрасываемый токен.
        if (OcrVocabulary.isKnownWord(candidate)) return false
        if (candidate.count(Char::isLetter) > 4) return false
        // Имена собственные («Том», «Алекс») и заголовки начинаются
        // с заглавной. Но защиту от среза применяем только для-known слов:
        // «ПШЛ» не в словаре → его можно срезать, «Том» — защищаем.
        if (candidate.isNotEmpty() && candidate[0].isUpperCase() && OcrVocabulary.isKnownWord(candidate)) return false

        val without = tokens.toMutableList().apply { removeAt(index) }.joinToString(" ")
        if (without.isBlank()) return false
        return dictionaryCoverage(without) > dictionaryCoverage(tokens.joinToString(" "))
    }

    /**
     * Доля букв кириллического текста, попавших в известные словарям слова
     * (встроенный [RuWordList] + пользовательский [OcrVocabulary]).
     *
     * Нужна [CyrillicOcrEngine] для ранжирования кандидатов v3/v5: из двух
     * гипотез с близкой уверенностью предпочтительнее та, чьи слова реально
     * существуют у пользователя. Чистая латынь и звукоподражания не считаются.
     */
    fun dictionaryCoverage(text: String): Float {
        if (text.isBlank()) return 0f
        var letters = 0
        var covered = 0
        for (token in text.split(TOKEN_SEPARATORS)) {
            val lexical = token.filter(Char::isLetter)
            val cyrillicCount = lexical.count { it.code in CYRILLIC_RANGE }
            if (cyrillicCount == 0) continue
            letters += lexical.length
            if (knownWord(lexical)) covered += lexical.length
        }
        if (letters == 0) return 0f
        return covered.toFloat() / letters
    }

    /** Гласные русского языка. «тій» без них — не русское слово, а мусор OCR. */
    private val RUSSIAN_VOWELS = "аеёиоуыэюя".toSet()

    /** Сколько всего кириллических букв в тексте. */
    private fun cyrillicLetterCount(text: String): Int =
        text.count { it.code in CYRILLIC_RANGE }

    /**
     * Весь результат — мусор, а не речь.
     *
     * Поймано два наблюдения с реальной страницы:
     *  * «тій» — три буквы, ни одной в словаре, и ни одной русской гласной
     *    (там украинская «і»): такого слова в русском тексте не бывает;
     *  * «Небниня» — распознанная «Небожития» из готического логотипа-заставки:
     *    ни одного слова из словаря.
     *
     * Правила deliberately строгие, иначе потеряются редкие имена:
     *  1. ни одного известного слова, И
     *  2. при этом либо непустая часть токенов без русской гласной,
     *     либо всего три кириллические буквы на весь результат.
     *
     * Имена вроде «Артур» длиннее трёх букв и с гласными правилом 2 не
     * ловятся — ради них порог и поднят. Слова здесь не ЗАМЕНЯются, а целиком
     * отбрасываются: словарная коррекция в проекте запрещена.
     */
    fun isDictionaryGarbage(text: String): Boolean {
        if (text.isBlank()) return false
        val tokens = text.split(TOKEN_SEPARATORS)
            .map { it.filter(Char::isLetter) }
            .filter { it.isNotEmpty() && it.any { ch -> ch.code in CYRILLIC_RANGE } }
        if (tokens.isEmpty()) return false
        if (tokens.any { knownWord(it) }) return false

        val noVowelTokens = tokens.count { token ->
            token.none { it.lowercaseChar() in RUSSIAN_VOWELS }
        }
        if (noVowelTokens > 0) return true

        // Короткий результат из одних неизвестных букв — надёжный признак шума.
        return cyrillicLetterCount(text) <= 3
    }

    /**
     * Страница без речи: всё, что распозналось, — мусор из картинки.
     *
     * Наблюдение со скриншотов: на титульном листе с готическим логотипом
     * движок выдавал «Небниня», и страница уезжала в озвучку. Разбор по
     * одному результату бессилен — «Небниня» лексически неотличима от
     * имени «Артур». А по странице решение надёжное: если на листе всего
     * пара коротких незнакомых слов, речи там нет.
     *
     * Намеренно консервативно, чтобы не съесть редкие имена:
     *  * не больше [MAX_GARBAGE_REGIONS] регионов на странице;
     *  * ни одного известного слова суммарно;
     *  * очень мало кириллицы — у настоящей реплики её больше.
     */
    fun isSpeechlessPage(texts: List<String>): Boolean {
        if (texts.isEmpty() || texts.size > MAX_GARBAGE_REGIONS) return false
        val joined = texts.filter { it.isNotBlank() }
        if (joined.isEmpty()) return false
        if (cyrillicLetterCount(joined.joinToString(" ")) > MAX_GARBAGE_LETTERS) return false
        return joined.none { dictionaryCoverage(it) > 0f }
    }

    /** Больше регионов — значит это уже сцена со словами, а не заставка. */
    private const val MAX_GARBAGE_REGIONS = 2

    /** Столько букв на всей «реплики» — предел для страницы-заставки. */
    private const val MAX_GARBAGE_LETTERS = 24

    private fun normalizeCaptionWord(word: String): String = CAPTION_NORMALIZATIONS[word] ?: word

    private fun mapChars(word: String, map: Map<Char, Char>): String {
        val sb = StringBuilder(word.length)
        for (char in word) sb.append(map[char] ?: char)
        return sb.toString()
    }

    /**
     * Правда, если в тексте есть «лесенка» из 6+ символов подряд по порядку
     * кодов (цифры/латиница/кириллица) — верный признак того, что декодер
     * дрейфовал по словарю, а не читал надпись.
     */
    fun looksLikeDictionaryRamp(text: String): Boolean {
        var run = 1
        var previous = -2
        for (char in text) {
            val code = char.code
            run = if (code == previous + 1) run + 1 else 1
            if (run >= 6) return true
            previous = code
        }
        return false
    }

    private val CYRILLIC_RANGE = 0x0400..0x052F

    /** Минимальная длина прогона, который вообще имеет смысл делить на слова. */
    private const val MIN_SPLIT_RUN = 4

    /** Длина куска, с которой он считается полноценным словом, а не обрезком. */
    private const val MIN_FULL_SEGMENT = 4

    /** Кусок короче этого — только служебное слово (см. [boundaryIsJustified]). */
    private const val MIN_SEGMENT_LETTERS = 3


    /**
     * Common closed-class words plus the proper nouns and caption vocabulary
     * demonstrated in verified device regressions. The set only authorizes
     * boundary insertion; it is not used to replace unknown OCR output.
     */
    private val CAPTION_WORDS = setOf(
        "А", "В", "ВО", "И", "К", "НА", "НЕ", "НО", "О", "ОБ", "ОН", "ОТ", "ПО", "С", "СО", "У",
        "БЫЛ", "БЫЛА", "БЫЛИ", "БЫТЬ", "ВСЕ", "ГДЕ", "ДЕМОНОМ", "ДОМА", "ЕГО", "ЕЙ", "ЕСТЬ",
        "ИЗ", "КАК", "КОТОРЫЙ", "ЛОЖНО", "ЛЮДИ", "МЫ", "НЕТ", "ОБВИНЕН", "ОБВИНЁН",
        "ОТЦУ", "ОХОТНИЧИЙ", "ОХОТНИЧЬЕГО", "ПАЛ", "ПЕС", "ПЁС", "ПОД", "ПОСВЯТИЛ", "ПРИНЯЛ",
        "РЕШЕНИЕ", "СГОВОРЕ", "СЕБЯ", "СЕМЬЕ", "СЛОВАМ", "ТЕЛЕ", "ТЯЖЕСТЬ", "ШУМНО",
        "ЛЕЗВИЕМ", "ГИЛЬОТИНЫ", "ПСА", "ВИКИР", "ВАН", "БАСКЕРВИЛЕЙ", "БАСКЕРВИЛЬ",
        // Частотные слова русских подписей: без них слипшиеся строки вроде
        // «СЕГОДНЯЯНЕ СМОГКОСНУТЬСЯ» (скриншот пользователя) не восстанавли-
        // вались. Словарь по-прежнему только РАЗРЕШАЕТ вставить границу:
        // неизвестное сплошное слово остаётся нетронутым.
    "БАНАРА", "БЕГИ", "БИТВА", "БОЙ", "БОЮСЬ", "БРАТ", "БРАТА", "ВРАГ", "ВРЕМЯ",
    "ВЕРЮ", "ВЕТЕР", "ВИЖУ", "ВОДА", "ВОЙНА", "ВОЛЯ", "ВОПРОС", "ГОЛОВА", "ГОРА", "ГНЕВ",
    "ГЕРОЙ", "ГЛАЗА", "ГОРОД", "ДВЕРЬ", "ДЕНЬ", "ДЕТИ", "ДОРОГА", "ДУША", "ДУШОЙ", "ДРУГ",
    "ДРУЗЬЯ", "ЖАРКО", "ЖДИ", "ЖИЗНЬ", "ЗАМАНИТЬ", "ЗДЕСЬ", "ИМЯ", "ИДУ", "КОГДА", "КОРОЛЬ",
    "КОСНУТЬСЯ", "КОНЧИКА", "КРОВЬ", "ВОЛОС", "ВОЛОСЫ", "ЛЕС", "ЛЮБЛЮ", "МАГИЯ", "МАМА", "МЕЧ", "МИР",
    "МОГУ", "МОРЕ", "МЕСТО", "НАКОНЕЦ", "НАШЁЛ", "НАЧАЛО", "НЕБО", "НЕНАВИСТЬ", "НОЧЬ",
    "ОГОНЬ", "ОКОНЧЕНА", "ОКНО", "ОТВЕТ", "ОЧЕНЬ", "ПАРЕНЬ", "ПАРНЯ", "ПАПА", "ПЕЧАЛЬ", "ПИСЬМО",
    "ПОБЕДА", "ПОБЕДИТЬ", "ПОМОГУ", "ПОМНЮ", "ПОТЕРЯЛ", "ПОТОМ", "ПРАВДА", "ПРЕЖДЕ", "ПРИНЦ", "ПРОКЛЯТИЕ",
    "ПУТЬ", "РАДОСТЬ", "РУКА", "РУКАМИ", "РУКИ", "РЫЦАРЬ", "СВОИМИ", "СЕЙЧАС", "СЕМЬЯ",
    "СЕРДЦЕ", "СИЛА", "СЕСТРА", "СЛОВО", "СЛЫШУ", "СМЕЛО", "СМОГ", "СМОГУ", "СМЕРТЬ",
    "СНИМЕТ", "СТАРИК", "СТОЙ", "СТРАХ", "СТОЛ", "СЧАСТЬЕ", "СЫН", "СЕГОДНЯ", "СРАЗИТЬ", "ТАМ",
    "ТЕЛО", "ТЬМА", "ТВОЕГО", "ТРЕНИРОВКА", "УБЬЮ", "УЛИЦА", "УМРУ", "УТРО", "УФ", "УШЁЛ",
    "ВЕЧЕР", "ХРАБРЫЙ", "ЧАС", "ЧЕЛОВЕК", "ЧЕМ", "ЩИТ", "ЭТОГО", "ЯРКИЙ", "ЖЕ",
    // Местоимения: без «Я» не разбивалось «СЕГОДНЯЯНЕ» (тест со скриншота).
    "Я", "ТЫ", "МЫ", "ВЫ", "ОНИ", "ОНА", "ОНО", "ЕЁ", "МНЕ", "ТЕБЕ", "НАС", "ВАС", "ИХ",
    )

    /** Короткие слова словаря: ими движок штрафует перерезанные куски. */
    val SMALL_WORDS: Set<String> = CAPTION_WORDS.filter { it.length <= 2 }.toSet()

    private val CAPTION_NORMALIZATIONS = mapOf(
        "ОБВИНЕН" to "ОБВИНЁН",
        "ПЕС" to "ПЁС",
    )
}
