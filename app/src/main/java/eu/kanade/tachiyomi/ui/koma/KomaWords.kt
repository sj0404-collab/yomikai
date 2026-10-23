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

    /** Готовая реплика: починка слов, дефисы, кавычки и регистр строки. */
    fun finish(text: String): String = normalizeCase(correctLine(text))

    fun correctLine(text: String): String {
        val fixed = WORD.replace(text) { correctWord(it.value) }
        return balanceQuotes(restoreHyphens(repairSplitWords(fixed)))
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
