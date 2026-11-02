package eu.kanade.tachiyomi.ui.koma

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ln
import kotlin.math.max

/**
 * Языковая починка распознанной реплики: словарь частот + правдоподобие букв.
 *
 * Модель распознавания путает похожие буквы («ПОШЕВЕАИТЬСЯ» вместо
 * «ПОШЕВЕЛИТЬСЯ», «СЮОДА» вместо «СЮДА», «ТРУ» вместо «ТРИ») и теряет дефисы
 * («ЧТОТО» вместо «ЧТО-ТО»). Масштаб и предобработка это не лечат — только
 * язык. Порт лабораторного `lab/correct.py`, проверенного на страницах 2, 4, 15,
 * 16 и A.
 *
 * Правим слово, только если:
 *  * его нет в чистом списке частых слов ([known]), И
 *  * выигрыш по правдоподобию заметный: иначе имена («ХАНЫ», «ДРЕГЕР»)
 *    превращались в случайные слова («РАНЫ», «ПРЕВЕР»).
 *
 * Словарь — `assets/koma/dict/ru_250k.txt.gz`: 250 000 слов по убыванию
 * частоты, номер строки и есть ранг. Слова с рангом до 50 000 считаются
 * «заведомо верными» — это замена чистому списку `ru_50k` из лаборатории.
 */
object KomaWords {

    private const val DELTA = 6.0          // насколько должно вырасти правдоподобие
    private const val TWO_EDITS_MIN = 6    // длина слова, с которой ищем две опечатки
    private const val SPLIT_MIN = 6        // длина, с которой ищем слипшиеся слова
    private const val SPLIT_DELTA = 8.0
    private const val COMMON_RANK = 100_000
    private const val BIG_GAIN = 15.0
    private const val KNOWN_RANK = 50_000  // слово верное, если оно из частых
    private const val TRIGRAM_MAX_RANK = 100_000

    private const val ALPHABET = "абвгдежзийклмнопрстуфхцчшщъыьэюяё"

    private val WORD = Regex("[А-Яа-яЁё]+")

    /** Частицы, которые в русском всегда пишутся через дефис. */
    private val TAILS = listOf("нибудь", "либо", "то")

    private val HEADS = setOf(
        "что", "кто", "где", "когда", "куда", "откуда", "почему", "отчего",
        "зачем", "как", "какой", "какая", "какое", "какие", "каков", "какова",
        "чей", "чья", "чьё", "чьи", "сколько", "чем", "кому", "кого", "кем",
    )

    private val ready = AtomicBoolean(false)
    private val lock = Any()

    private var dict: HashMap<String, Int> = HashMap()
    private var known: HashSet<String> = HashSet()
    private var trigram: HashMap<String, Double> = HashMap()
    private var total = 1.0
    private val floor = ln(1e-4)

    /** Словарь разобран и починка включена. */
    fun isReady(): Boolean = ready.get()

    /**
     * Загружает частотный список: слова по убыванию частоты, по одному в строке.
     * Вызывать один раз и не на главном потоке — 250 000 слов.
     */
    fun load(lines: Sequence<String>) {
        if (ready.get()) return
        synchronized(lock) {
            if (ready.get()) return
            val table = HashMap<String, Int>(1 shl 19)
            val common = HashSet<String>(1 shl 17)
            var rank = 0
            for (line in lines) {
                val word = line.trim().lowercase().replace('ё', 'е')
                if (word.isNotEmpty() && word.all { it in 'а'..'я' }) {
                    if (!table.containsKey(word)) {
                        table[word] = rank
                        if (rank < KNOWN_RANK) common.add(word)
                    }
                }
                rank++
            }
            if (table.isEmpty()) return
            val grams = HashMap<String, Double>(1 shl 20)
            for ((word, place) in table) {
                if (place >= TRIGRAM_MAX_RANK) continue
                val weight = 1.0 / (1.0 + place)
                val padded = "#$word#"
                for (i in 0..padded.length - 3) {
                    val gram = padded.substring(i, i + 3)
                    grams[gram] = (grams[gram] ?: 0.0) + weight
                }
            }
            var sum = 0.0
            for (value in grams.values) sum += value
            trigram = grams
            total = if (sum > 0.0) sum else 1.0
            known = common
            dict = table
            ready.set(true)
        }
    }

    /** Падежные окончания: по ним словоформа узнаётся, даже если её нет в словаре. */
    private val ENDINGS = listOf(
        "ами", "ями", "ого", "его", "ому", "ему", "ыми", "ими", "ах", "ях", "ов", "ев",
        "ой", "ей", "ий", "ый", "ая", "ое", "ые", "ие", "ом", "ем", "ам", "ям", "мь", "ью",
        "ы", "и", "а", "е", "о", "у", "ю", "ь", "я", "й", "м",
    )

    /**
     * Латинские, греческие и цифровые двойники кириллицы.
     *
     * «cecmpoй mиpaнa» — это «сестрой тирана»: шрифт рисует «т» как латинскую
     * «m», «а», «е», «с», «о», «й» — тоже похоже. Замена делается только тогда,
     * когда получившееся слово есть в словаре, поэтому настоящая латиница
     * (звуки «BANG», имена) не трогается. «Ë» в манга-шрифте — это «Ё»: без неё
     * строка «TE EË.» («ТЕ ЕЁ.») не опознавалась и выбрасывалась целиком —
     * из-за этого реплика обрывалась на «КОГДА СЛУША» (скриншот 21:20).
     */
    private val HOMOGLYPH = mapOf(
        'a' to 'а', 'b' to 'в', 'c' to 'с', 'e' to 'е', 'h' to 'н', 'k' to 'к',
        'm' to 'т', 'n' to 'п', 'o' to 'о', 'p' to 'р', 't' to 'т', 'x' to 'х',
        'y' to 'у', 'u' to 'и', 'i' to 'и', 'j' to 'ј', 'r' to 'г',
        'A' to 'А', 'B' to 'В', 'C' to 'С', 'E' to 'Е', 'H' to 'Н', 'K' to 'К',
        'M' to 'М', 'O' to 'О', 'P' to 'Р', 'T' to 'Т', 'X' to 'Х', 'Y' to 'У',
        'R' to 'Г', 'I' to 'І', 'J' to 'Ј',
        'Ë' to 'Ё', 'ë' to 'ё', 'Î' to 'Й', 'î' to 'й', 'Ä' to 'Д', 'ä' to 'д',
    )

    private fun isLatin(ch: Char) = ch in 'a'..'z' || ch in 'A'..'Z'

    /** Слово есть в частотном словаре (регистр и «ё» не важны). */
    fun isKnown(word: String): Boolean {
        if (!ready.get()) return false
        return inDict(word.trim().lowercase())
    }

    /**
     * Слово выглядит настоящим: есть в словаре ИЛИ это его словоформа
     * (ВЫСОЧЕСТВОМ → ВЫСОЧЕСТВ + ОМ). Без этой проверки «правка» превращала
     * нормальное слово во что-то другое, а склейка переноса — в мусор.
     */
    fun isPlausible(word: String): Boolean {
        if (!ready.get()) return true
        val low = word.trim().lowercase().replace('ё', 'е')
        if (inDict(low)) return true
        if (low.length < 5) return false
        for (end in ENDINGS) {
            if (!low.endsWith(end)) continue
            val stem = low.dropLast(end.length)
            if (stem.length < 4) continue
            val rank = dict[stem] ?: continue
            if (rank <= COMMON_RANK) return true
        }
        return false
    }

    private fun inDict(low: String): Boolean {
        val key = low.replace('ё', 'е')
        return key.isNotEmpty() && dict.containsKey(key)
    }

    /**
     * Одно слово: латиница/смесь → кириллица, но только когда это даёт слово из
     * словаря. «cecmpoй» → «сестрой», «mиpaнa» → «тирана»; «BANG!!» остаётся.
     */
    fun fixAlphabet(word: String): String {
        if (!ready.get() || word.none { isLatin(it) }) return word
        val start = word.indexOfFirst { it.isLetter() }
        val end = word.indexOfLast { it.isLetter() }
        if (start < 0 || end < start) return word
        val core = word.substring(start, end + 1)
        val cyr = buildString { for (ch in core) append(HOMOGLYPH[ch] ?: ch) }
        if (!cyr.equals(core, ignoreCase = true) && inDict(cyr.lowercase())) {
            return word.replaceFirst(core, cyr)
        }
        // Латинский кусок перед кириллицей: «TE EË.» («ТЕ ЕЁ.»), «mиpaнa».
        val pre = Regex("^([A-Za-zËë]+)([А-Яа-яЁё]+)").find(core)
        if (pre != null) {
            val fixed = buildString { for (ch in pre.groupValues[1]) append(HOMOGLYPH[ch] ?: ch) } +
                pre.groupValues[2]
            if (inDict(fixed.lowercase())) return word.replaceFirst(core, fixed)
        }
        // Короткое слово целиком на латинице: «TE», «EË» — тоже буквы алфавита,
        // если после замены получается слово из словаря.
        if (core.length <= 3 && core.all { isLatin(it) }) {
            if (!cyr.equals(core, ignoreCase = true) && inDict(cyr.lowercase())) {
                return word.replaceFirst(core, cyr)
            }
        }
        // Кириллица с прилипшим латинским хвостом («СЛУШАЙТЕЕУ.owb»): кириллицу
        // оставляем, хвост выбрасываем, лишние буквы на стыке снимаем.
        val mix = Regex("([А-Яа-яЁё]{4,})[^А-Яа-яЁёA-Za-z0-9]*([A-Za-z]{2,})").find(word)
        if (mix != null) {
            val head = mix.groupValues[1]
            val base = head.lowercase()
            if (inDict(base)) return head
            for (cut in 1..2) {
                if (base.length - cut < 4) break
                if (inDict(base.dropLast(cut))) return head.dropLast(cut)
            }
        }
        return word
    }

    /**
     * Буква вместо цифры: «2-З РАЗА» → «2-3 РАЗА». Правило узкое и проверяемое:
     * буква стоит ВПЛОТНУЮ к цифре (в том же слове через дефис или точку).
     */
    fun fixDigits(word: String): String {
        if (word.none { it in '0'..'9' } || word.none { it in "ЗзОо" }) return word
        return word
            .replace(Regex("(\\d)\\s*[-\u2013\u2014]\\s*[Зз]"), "$1-3")
            .replace(Regex("[Зз]\\s*[-\u2013\u2014]\\s*(\\d)"), "3-$1")
            .replace(Regex("(\\d)[Оо]"), "$10")
            .replace(Regex("[Оо](\\d)"), "0$1")
    }

    /** Готовая реплика: починка слов, дефисы, кавычки и регистр строки. */
    fun finish(text: String): String {
        var t = text
        if (ready.get()) {
            // Латиница, которая на самом деле кириллица: «cecmpoй mиpaнa» →
            // «сестрой тирана». Делается ДО словарной правки, иначе слово не
            // находится в словаре и уходит в мусор.
            t = t.split(' ').joinToString(" ") { fixAlphabet(it) }
            // Латинский шум чтеца внутри русской реплики («КОГДА СЛУШАЕТЕ
            // ЕЁ.owb»). Убираем слово целиком на латинице, только если в реплике
            // есть кириллица — иначе пропали бы настоящие английские звуки.
            if (t.any { KomaText.isRussianLetter(it) }) {
                t = t.split(' ').filter { word ->
                    if (word.none { isLatin(it) }) return@filter true
                    if (word.any { KomaText.isRussianLetter(it) }) return@filter true
                    word.count { it.isLetter() } < 3
                }.joinToString(" ")
            }
            // Буква вместо цифры: «2-З РАЗА» → «2-3 РАЗА».
            t = t.split(' ').joinToString(" ") { fixDigits(it) }
        }
        return normalizeCase(correctLine(t))
    }

    fun correctLine(text: String): String {
        val fixed = WORD.replace(text) { correctWord(it.value) }
        // Висячий дефис в конце реплики («ПОШЕВЕ-») — это разрыв строки в
        // облачке, а не дефис в слове: синтезатор читал его как паузу и
        // получалось «пошеве… литься». Склеивать уже нечего — убираем.
        val cleaned = fixed.replace(Regex("(?<=\\p{L})-\\s*$"), "")
        return balanceQuotes(restoreHyphens(repairSplitWords(cleaned)))
    }

    /** Как часто такое буквосочетание встречается в языке (лог-правдоподобие). */
    private fun plausibility(word: String): Double {
        val padded = "#$word#"
        var score = 0.0
        for (i in 0..padded.length - 3) {
            val gram = padded.substring(i, i + 3)
            score += ln(max(trigram[gram] ?: 0.0, 1e-4) / total) - floor
        }
        return score
    }

    private fun edits(word: String): Sequence<String> = sequence {
        for (i in 0..word.length) {
            val left = word.substring(0, i)
            val right = word.substring(i)
            if (right.isNotEmpty()) yield(left + right.substring(1))
            if (right.length > 1) {
                yield(left + right[1] + right[0] + right.substring(2))
            }
            for (ch in ALPHABET) {
                if (right.isNotEmpty()) yield(left + ch + right.substring(1))
                yield(left + ch + right)
            }
        }
    }

    private fun knownEdits(word: String): Sequence<String> = edits(word).filter { dict.containsKey(it) }

    private fun matchCase(source: String, target: String): String = when {
        source.all { !it.isLetter() || it.isUpperCase() } -> target.uppercase()
        source.first().isUpperCase() -> target.replaceFirstChar { it.uppercase() }
        else -> target
    }

    fun correctWord(word: String): String {
        if (!ready.get() || word.length < 3) return word
        val low = word.lowercase().replace('ё', 'е')
        // «ЬЫТЬ» со скриншота 21:20: мягкий знак прочитан вместо «Б». Ни одно
        // настоящее слово не начинается с «ь»/«ъ», поэтому замена проверяемая.
        if (low.startsWith('ь') || low.startsWith('ъ')) {
            val withB = "б" + low.drop(1)
            if (withB in dict) return matchCase(word, withB)
            val withV = "в" + low.drop(1)
            if (withV in dict) return matchCase(word, withV)
        }
        if (low in known) return word
        val base = plausibility(low)
        // Кандидат сравнивается парой (ранг, выигрыш): берём самое частое слово
        // из прошедших проверку, а не самое правдоподобное.
        var bestKey: Pair<Int, Double>? = null
        var best: String? = null

        fun offer(candidate: String, rank: Int, gain: Double) {
            val key = -rank to gain
            val current = bestKey
            if (current == null || key.first > current.first ||
                (key.first == current.first && key.second > current.second)
            ) {
                bestKey = key
                best = candidate
            }
        }

        // Слипшиеся слова проверяем ПЕРВЫМИ: выигрыш от разделения обычно больше,
        // чем от правки одной буквы, иначе «ИЗПОКОЛЕНИЯ» чинилось во что-то
        // постороннее одной заменой.
        if (low.length >= SPLIT_MIN) {
            for (cut in 2 until low.length - 1) {
                val first = low.substring(0, cut)
                val second = low.substring(cut)
                if (first !in known || second !in known) continue
                val firstRank = dict[first] ?: continue
                val secondRank = dict[second] ?: continue
                val gain = plausibility(first) + plausibility(second) - base
                val close = gain > 6.0 && firstRank < 20000 && secondRank < 20000
                if (gain > SPLIT_DELTA || close) {
                    offer("$first $second", firstRank + secondRank, gain)
                }
            }
            best?.let { return matchCase(word, it) }
        }

        for (candidate in knownEdits(low)) {
            val rank = dict[candidate] ?: continue
            val gain = plausibility(candidate) - base
            // Обычную ошибку берём, только если слово-замена само частое; редкие,
            // но очень правдоподобные — по большому выигрышу; «ТРУ» → «ТРИ»
            // ловится третьим правилом (кандидат из первых четырёхсот).
            if ((gain > DELTA && rank < COMMON_RANK) || gain > BIG_GAIN || rank < 400) {
                offer(candidate, rank, gain)
            }
        }

        if (best == null && low.length >= TWO_EDITS_MIN) {
            val second = HashSet<String>()
            for (one in knownEdits(low)) knownEdits(one).forEach { second.add(it) }
            for (candidate in second) {
                val rank = dict[candidate] ?: continue
                val gain = plausibility(candidate) - base
                if (gain > BIG_GAIN && rank < COMMON_RANK) offer(candidate, rank, gain)
            }
        }

        val found = best ?: return word
        return matchCase(word, found)
    }

    /** «ЧТОТО» → «ЧТО-ТО», «КАКОЕНИБУДЬ» → «КАКОЕ-НИБУДЬ», «ГДЕТО» → «ГДЕ-ТО». */
    fun restoreHyphens(text: String): String = WORD.replace(text) { match ->
        val word = match.value
        val low = word.lowercase()
        val tail = TAILS.firstOrNull { low.length >= 5 && low.endsWith(it) }
        if (low.contains('-') || tail == null) {
            word
        } else {
            val head = low.dropLast(tail.length)
            // Частотный список сам содержит склейки («чтото»), поэтому на словарь
            // здесь не опираемся — только на закрытый список основ.
            if (head in HEADS) word.substring(0, head.length) + "-" + word.substring(head.length) else word
        }
    }

    /**
     * Склеивает слово, разорванное на две строки бокса: «РАССЧИ ТВВАЮ» →
     * «РАССЧИТЫВАЮ». Детектор режет облачко по проекции, и одно слово может
     * оказаться в двух кусках.
     */
    fun repairSplitWords(text: String): String {
        if (!ready.get()) return text
        val words = text.split(' ')
        if (words.size < 2) return text
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            if (i + 1 < words.size) {
                val first = words[i].trim { it in ".,!?…—\"-" }
                val second = words[i + 1].trim { it in ".,!?…—\"-" }
                if (first.isNotEmpty() && second.isNotEmpty() &&
                    first.length > 2 && second.length > 2 &&
                    first.lowercase() !in known && second.lowercase() !in known
                ) {
                    val merged = first.lowercase() + second.lowercase()
                    val fixed = correctWord(merged)
                    if (fixed.lowercase() != merged && ' ' !in fixed && fixed.lowercase() in known) {
                        val tail = words[i + 1].substring(second.length)
                        val word = if (first.all { !it.isLetter() || it.isUpperCase() }) fixed.uppercase() else fixed
                        out.add(word + tail)
                        i += 2
                        continue
                    }
                }
            }
            out.add(words[i])
            i++
        }
        return out.joinToString(" ")
    }

    /** Нечётное число кавычек — реплика обрезана краем кадра: закрываем. */
    fun balanceQuotes(text: String): String {
        var count = 0
        for (ch in text) if (ch == '"') count++
        return if (count % 2 == 1) text.trimEnd() + "\"" else text
    }

    /**
     * Реплики в манге набраны заглавными, но модель выдаёт «СЕБЯ» и «СЕбЯ».
     * Верхний регистр, если заглавных не меньше половины букв: строка
     * «господи БОЖЕ МОЙ!» раньше оставалась со строчной буквой.
     */
    fun normalizeCase(text: String): String {
        var letters = 0
        var upper = 0
        for (ch in text) {
            if (ch.isLetter()) {
                letters++
                if (ch.isUpperCase()) upper++
            }
        }
        if (letters == 0) return text
        return if (upper * 2 >= letters) text.uppercase() else text
    }
}
