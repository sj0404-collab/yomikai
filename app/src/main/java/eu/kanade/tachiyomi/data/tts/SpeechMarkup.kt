package eu.kanade.tachiyomi.data.tts

/**
 * Разметка реплик для озвучки.
 *
 * На экране у каждой реплики есть служебные пометки — номер по порядку
 * чтения, кто говорит, пауза. Их должно быть ВИДНО, но TTS не должен их
 * произносить. Поэтому текст для показа и текст для синтеза расходятся:
 * [strip] снимает разметку перед отправкой в движок.
 *
 * Поддерживаемые пометки:
 *
 * | Запись        | Значение                                   | Читается |
 * |---------------|--------------------------------------------|----------|
 * | `{1}`         | номер реплики в порядке чтения             | нет      |
 * | `{ж}` `{м}`   | пол говорящего (женский / мужской)         | нет      |
 * | `{ж2}`        | вторая женщина в сцене (свой голос)        | нет      |
 * | `{имя:Аки}`   | имя говорящего                             | нет      |
 * | `{пауза}`     | дополнительная пауза перед репликой        | нет      |
 * | `{...}`       | любая другая служебная пометка             | нет      |
 * | `÷`           | разделитель частей реплики (короткая пауза)| нет      |
 *
 * Всё, что не в фигурных скобках, читается как обычно.
 *
 * Имя говорящего живёт в метке `{имя:Аки}`, но авточтение получает текст из
 * OCR, где меток нет. Поэтому есть и обратный путь: [speakerNameFromText]
 * распознаёт подпись персонажа в самой реплике («АКИ: …», «Аки — …»), а
 * [withSpeakerName] прикрепляет метку обратно. Метка снимается в [strip] и на
 * экране, и в синтезе, поэтому имя не начинает произноситься дважды.
 */
object SpeechMarkup {

    // ВАЖНО: фигурные скобки внутри класса символов обязаны быть экранированы.
    // На JVM/ART без экрана работает и без экрана, но ICU-движок регулярок
    // (Itel, Infinix и др. Android 13+) бросает PatternSyntaxException прямо в
    // <clinit>, из-за чего ЛЮБОЕ обращение к TTS роняло приложение
    // (ExceptionInInitializerError).
    private val TAG = Regex("""\{[^\{\}]{0,40}\}""")
    private val DIVIDER = '÷'

    /**
     * Подпись персонажа в начале реплики капсом: «АКИ: …», «АКИ — …»,
     * «КИМ ЧЕН: …». В комиксах и вебтунах имя набрано прописными, а сама
     * реплика — строчными, поэтому такой префикс надёжно отличает имя от
     * обычного слова, даже если OCR не сохранил шрифт.
     */
    private val CAPS_NAME = Regex(
        """^\s*([A-ZА-ЯЁ][A-ZА-ЯЁ'\-]{1,23}(?:\s+[A-ZА-ЯЁ][A-ZА-ЯЁ'\-]{1,23})?)\s*[:—–]\s*(\S.*)$""",
    )

    /**
     * Подпись персонажа с одной заглавной буквы: «Аки: …», «Аки — …».
     * Разрешаем не короче трёх букв: «Нет: я не знаю» и «Помоги — где ты?»
     * превратились бы в «нового персонажа» с собственным голосом.
     */
    private val TITLE_NAME = Regex(
        """^\s*([А-ЯЁA-Z][а-яёa-z]{2,23}(?:\s+[А-ЯЁA-Z][а-яёa-z]{2,23})?)\s*[:—–]\s*(\S.*)$""",
    )

    /**
     * Слова, с которых реплика не может начинаться как с имени. Список
     * короткий и явный: угадывать «имя» по одной заглавной букве нельзя, иначе
     * любая короткая реплика («Он ушёл», «Помоги — где ты?») завела бы себе
     * отдельный голос, а роль из словаря получала бы чужое имя.
     */
    private val NOT_A_NAME = setOf(
        "э", "эй", "ах", "хм", "ну", "о", "а", "и", "у", "не", "нет", "да",
        "ты", "вы", "он", "она", "мы", "все", "вот", "это", "так", "затем",
        "потом", "потому", "поэтому", "значит", "вдруг", "опять", "снова",
        "если", "когда", "чтобы", "ладно", "слушай", "смотри", "боже",
        "господи", "чёрт", "кто", "что", "где", "куда", "зачем", "привет",
        "здравствуй", "пока", "прости", "извини", "спасибо", "глава", "том",
        "помоги", "попробуй", "подожди", "пожалуйста", "иди", "стой", "беги",
        "неужели", "правда", "точно", "хорошо", "отлично", "увы", "помощь",
    )

    /** Допустимые символы имени: без фигурных скобок, иначе метка не распарсится. */
    private val NAME_NOISE = Regex("""[^\p{L}\p{N} ._'’\-]""")

    /** Пол, объявленный разметкой: "female" | "male" | null. */
    fun genderOf(text: String): String? {
        val m = TAG.findAll(text).map { it.value.trim('{', '}').lowercase() }
        m.forEach {
            when {
                it.startsWith("ж") || it.startsWith("f") -> return "female"
                it.startsWith("м") || it.startsWith("m") -> return "male"
            }
        }
        return null
    }

    /**
     * Номер говорящего в сцене: `{ж2}` -> 1 (второй женский слот),
     * `{ж}`/`{м}` -> 0. Нужен, чтобы два персонажа одного пола звучали
     * разными голосами.
     */
    fun speakerSlot(text: String): Int {
        TAG.findAll(text).forEach { match ->
            val body = match.value.trim('{', '}').lowercase()
            if (body.startsWith("ж") || body.startsWith("м") ||
                body.startsWith("f") || body.startsWith("m")
            ) {
                val digits = body.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }
                if (digits.isNotEmpty()) return (digits.toIntOrNull() ?: 1).minus(1).coerceAtLeast(0)
                return 0
            }
        }
        return 0
    }

    /** Есть ли запрос на дополнительную паузу перед репликой. */
    fun hasPause(text: String): Boolean =
        TAG.findAll(text).any { it.value.trim('{', '}').lowercase().startsWith("пауза") }

    /** Имя говорящего из `{имя:Аки}`, если задано. */
    fun speakerName(text: String): String? {
        TAG.findAll(text).forEach { match ->
            val body = match.value.trim('{', '}')
            val lower = body.lowercase()
            if (lower.startsWith("имя:") || lower.startsWith("name:")) {
                return body.substringAfter(':').trim().ifBlank { null }
            }
        }
        return null
    }

    /**
     * Имя говорящего из САМОГО текста реплики («АКИ: …», «Аки — …»).
     *
     * Зачем это нужно. Метку `{имя:…}` авточтение раньше не собирало, поэтому
     * словарь голосовых ролей не мог сработать на OCR-чтении вообще: оставался
     * «только пол», а настроенные роли молча игнорировались. Второе применение
     * — слот голоса персонажа (см. AutoReadEngine.voiceSlotFor): без имени
     * третью реплику того же героя не отличить от нового персонажа, и голос
     * менялся на каждой реплике.
     */
    fun speakerNameFromText(text: String): String? {
        val line = text.trim()
        if (line.isEmpty()) return null
        CAPS_NAME.find(line)?.let { match ->
            val name = cleanName(match.groupValues[1])
            // Подпись без реплики («АКИ —») — это заголовок панели, а не реплика.
            if (name != null && match.groupValues[2].isNotBlank()) return name
        }
        TITLE_NAME.find(line)?.let { match ->
            val name = cleanName(match.groupValues[1])
            if (name != null) return name
        }
        return null
    }

    /** Имя реплики: из разметки, иначе из текста. Порядок важен — разметка точнее. */
    fun speakerNameOrGuess(text: String): String? =
        speakerName(text) ?: speakerNameFromText(SpeechMarkup.strip(text))

    /**
     * Прикрепляет метку `{имя:…}` к реплике, если имени ещё нет. Само слово
     * имени при этом НЕ вырезается: читатель слышит подпись так же, как видит
     * её на кадре, а служебная метка снимается в [strip] и не произносится.
     */
    fun withSpeakerName(text: String, name: String?): String {
        if (speakerName(text) != null) return text
        val clean = cleanName(name) ?: return text
        return "{имя:$clean} $text"
    }

    /**
     * Имя приводится к безопасному виду: без фигурных скобок (иначе метка
     * распадётся и будет произнесена), без переносов и не длиннее 24 символов
     * (длина метки в [TAG] ограничена 40 символами вместе со скобками).
     */
    private fun cleanName(raw: String?): String? {
        val cleaned = raw.orEmpty()
            .replace('\n', ' ')
            .replace(NAME_NOISE, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (cleaned.length < 2 || cleaned.lowercase() in NOT_A_NAME) return null
        return cleaned.take(24)
    }

    /**
     * Текст для синтеза: разметка убрана, `÷` заменён на запятую (короткая
     * пауза), лишние пробелы схлопнуты.
     */
    fun strip(text: String): String {
        var out = normalizeSpeechPunctuation(text)
        out = TAG.replace(out, " ")
        out = out.replace(DIVIDER, ',')
        out = out.replace(Regex("[~≈]+"), "") // тильду не озвучивать словом «тильда»
        out = out.replace(Regex("""\s+"""), " ")
        out = out.replace(Regex("""\s+([,.!?;:])"""), "$1")
        out = out.replace(Regex(""",{2,}"""), ",")
        return out.trim().trim(',').trim()
    }

    fun forSpeech(text: String): String {
        var out = text.replace(Regex("\\.{2,}"), "…")
        out = out.replace(Regex("!{2,}"), "!!")
        out = out.replace(Regex("\\?{2,}"), "??")
        out = out.replace(Regex("([!?]{2})[!?]+"), "$1")
        out = out.replace(Regex("(?:;;|:){2,}"), ";")
        out = out.replace(Regex("\\s+([,!?;:…])"), "$1")
        out = out.replace(Regex("(^|\\s)[.,!?;:·•*#|/^_=+<>]+(\\s|$)"), " ")
        out = out.replace(Regex(",{2,}"), ",")
        out = out.replace(Regex("\\s{2,}"), " ")
        return out.trim().trim(',').trim()
    }

    /**
     * Нормализация «японской» и полноширинной пунктуации перед синтезом.
     *
     * Google Lens и сканлатеры часто отдают реплики с полноширинными знаками
     * (＂！＂, ＂？＂, ＂：＂, ＂。＂, CJK-скобки), а синтезатор читает такие знаки
     * ВСЛУХ словами («восклицательный знак», «двоеточие», «правая скобка»)
     * вместо паузы и интонации. Приводим их к обычным ASCII/типографским
     * аналогам — движок даёт им эмоцию и паузу, как задумано. Сами буквы и
     * слова любых языков не трогаем: меняются только служебные знаки.
     */
    private fun normalizeSpeechPunctuation(text: String): String = buildString(text.length) {
        for (c in text) {
            when {
                // Полноширинные ASCII-формы U+FF01..FF5E (! ~ 0-9 A-Z a-z) — обычные.
                c in '！'..'～' -> append(c - 0xFEE0)
                else -> when (c) {
                    '　' -> append(' ') // идеографический пробел
                    '。' -> append('.')
                    '、' -> append(',')
                    // CJK-скобки: голос говорил «скобка». Рамку цитаты озвучивать
                    // нечем — просто снимаем её (остаётся сама реплика).
                    '「', '」', '『', '』', '【', '】', '〈', '〉', '《', '》', '〔', '〕' -> append(' ')
                    '・', '･' -> append(' ')
                    // Долгота каны и волнистые черты звука не имеют —
                    // проговариваться словами тоже не должны.
                    'ー', '〜', '∼' -> Unit
                    else -> append(c)
                }
            }
        }
    }

    /** Добавляет номер реплики в начало, если его там ещё нет. */
    fun withIndex(text: String, index: Int): String =
        if (TAG.containsMatchIn(text) && text.trimStart().startsWith("{$index}")) {
            text
        } else {
            "{$index} $text"
        }

    /** Собирает пометки для показа: `{3}{ж2}` перед текстом. */
    fun tagsOf(text: String): String =
        TAG.findAll(text).joinToString("") { it.value }
}
