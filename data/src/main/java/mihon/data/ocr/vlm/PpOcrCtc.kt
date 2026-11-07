package mihon.data.ocr.vlm

/**
 * CTC-разбор выхода распознавателя PP-OCRv5 (LiteRT).
 *
 * Чистая арифметика без Android: на вход — плоский массив логитов `steps × classes`
 * и словарь, на выход — строка и средний балл. Поэтому проверяется юнит-тестами
 * на подставных числах, без телефона и без модели.
 *
 * Правила ровно те, что в контракте PP-OCR (и в нашем KOMA-движке):
 *  • класс 0 — blank;
 *  • повторы одного класса подряд схлопываются (иначе «ПРИНЦЕССОЙ» станет «ППРРИИ…»);
 *  • последний класс словаря — пробел;
 *  • символы за пределами словаря отбрасываются, а не превращаются в мусор.
 */
internal object PpOcrCtc {

    /** Словарь из файла: blank(0) + строки + пробел. Пустые строки в конце отбрасываются. */
    fun loadCharset(raw: String): List<String> {
        val lines = raw.split('\n').map { it.trimEnd('\r') }
            .toMutableList()
        while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        return buildList {
            add("")              // blank
            addAll(lines)
            add(" ")             // пробел — последним классом
        }
    }

    data class Result(val text: String, val score: Float, val symbols: Int)

    /**
     * Жадный разбор. [minScore] — отсечка «уверенности» символа: слабые символы
     * не добавляются в текст, а не добавляются как случайная буква.
     */
    fun decode(
        logits: FloatArray,
        steps: Int,
        classes: Int,
        charset: List<String>,
        minScore: Float = 0f,
    ): Result {
        if (steps <= 0 || classes <= 1 || logits.size < steps * classes) return Result("", 0f, 0)
        val sb = StringBuilder()
        var prev = 0
        var sum = 0f
        var n = 0
        for (t in 0 until steps) {
            val base = t * classes
            var best = 0
            var bestV = logits[base]
            for (c in 1 until classes) {
                val v = logits[base + c]
                if (v > bestV) { bestV = v; best = c }
            }
            if (best != 0 && best != prev && bestV >= minScore) {
                if (best < charset.size) sb.append(charset[best])
                sum += bestV
                n++
            }
            prev = best
        }
        return Result(sb.toString(), if (n == 0) 0f else sum / n, n)
    }
}
