package mihon.data.ocr

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Разбор кропа на строки по проекции строк.
 *
 * Проверяется ровно то, из-за чего появился разбор: детектор склеивает в один
 * бокс не только строку, но и колонку манхвы из пяти строк, и такой кроп
 * сжимается распознавателем в 320×48. При этом одиночная строка (обычный
 * случай) и мелкий текст разбираться не должны — лишний проход там ничего
 * не даёт, а ложное разбиение разрезает глифы пополам.
 */
class OcrLineBandsTest {

    /** Проекция строк: по [rows] подряд идущих строк с чернилами. */
    private fun profile(height: Int, vararg bands: IntRange): FloatArray {
        val ink = FloatArray(height)
        for (band in bands) {
            for (row in band) ink[row] = 0.4f
        }
        return ink
    }

    @Test
    fun `single line is never split`() {
        ocrLineBands(
            rowInk = profile(40, 8..24),
            minInkRatio = 0.02f,
            maxGapFactor = 0.45f,
            minBandHeight = 4,
        ) shouldBe emptyList()
    }

    @Test
    fun `single line touching the edges is never split`() {
        // Текст, обрезанный рамкой баллона: полоса от края до края.
        ocrLineBands(
            rowInk = profile(30, 0..29),
            minInkRatio = 0.02f,
            maxGapFactor = 0.45f,
            minBandHeight = 4,
        ) shouldBe emptyList()
    }

    @Test
    fun `column of five lines splits into five bands`() {
        val bands = ocrLineBands(
            rowInk = profile(100, 2..18, 22..38, 42..58, 62..78, 82..98),
            minInkRatio = 0.02f,
            maxGapFactor = 0.45f,
            minBandHeight = 4,
        )
        bands.size shouldBe 5
        bands.first() shouldBe 2..18
        bands.last() shouldBe 82..98
    }

    @Test
    fun `two lines are left to the whole-line recognizer`() {
        // Подпись из двух строк: дёшево прочитать целиком, а разбиение
        // добавит проход и риск разрезать глифы.
        ocrLineBands(
            rowInk = profile(60, 4..24, 30..50),
            minInkRatio = 0.02f,
            maxGapFactor = 0.45f,
            minBandHeight = 4,
        ) shouldBe emptyList()
    }

    @Test
    fun `close lines merge into one band`() {
        // Диакритика и висячие элементы букв не должны разрывать строку.
        val bands = ocrLineBands(
            rowInk = profile(80, 10..30, 32..52, 54..74),
            minInkRatio = 0.02f,
            maxGapFactor = 0.45f,
            minBandHeight = 4,
        )
        bands shouldBe listOf(10..74)
    }

    @Test
    fun `too short profile yields nothing`() {
        ocrLineBands(
            rowInk = profile(1, 0..0),
            minInkRatio = 0.02f,
            maxGapFactor = 0.45f,
            minBandHeight = 4,
        ) shouldBe emptyList()
    }

    @Test
    fun `blank profile yields nothing`() {
        ocrLineBands(
            rowInk = profile(50),
            minInkRatio = 0.02f,
            maxGapFactor = 0.45f,
            minBandHeight = 4,
        ) shouldBe emptyList()
    }

    @Test
    fun `faint noise below threshold is not a line`() {
        // Водяной знак оставляет меньше 2% чернил в строке: резать нечего.
        val ink = FloatArray(60) { 0.01f }
        ocrLineBands(
            rowInk = ink,
            minInkRatio = 0.02f,
            maxGapFactor = 0.45f,
            minBandHeight = 4,
        ) shouldBe emptyList()
    }
}
