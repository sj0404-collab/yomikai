package mihon.data.ocr

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Разбор кропа на строки по проекции строк.
 *
 * Проверяется ровно то, из-за чего появился разбор: детектор склеивает в один
 * бокс не только строку, но и колонку манхвы из пяти строк, и такой кроп
 * сжимается распознавателем в 320×48. При этом одиночная строка (обычный
 * случай) разбираться не должна — лишний проход там ничего не даёт, а ложное
 * разбиение разрезает глифы пополам.
 *
 * Геометрия тестов соответствует порогу движка: межстрочный зазор больше
 * `lineSplitMaxGapFactor` (0.45 высоты строки) — строки разные; зазор меньше —
 * это интервал внутри строки, и полосы обязаны слиться.
 */
class OcrLineBandsTest {

    /** Проекция строк: по [bands] подряд идущих строк с чернилами. */
    private fun profile(height: Int, vararg bands: IntRange): FloatArray {
        val ink = FloatArray(height)
        for (band in bands) {
            for (row in band) ink[row] = 0.4f
        }
        return ink
    }

    private fun bands(
        ink: FloatArray,
        minBands: Int = 2,
    ) = ocrLineBands(
        rowInk = ink,
        minInkRatio = 0.02f,
        maxGapFactor = 0.45f,
        minBandHeight = 4,
        minBands = minBands,
    )

    @Test
    fun `single line is never split`() {
        bands(profile(40, 8..24)) shouldBe emptyList()
    }

    @Test
    fun `single line touching the edges is never split`() {
        // Текст, обрезанный рамкой баллона: полоса от края до края.
        bands(profile(30, 0..29)) shouldBe emptyList()
    }

    @Test
    fun `column of five lines splits into five bands`() {
        // Высота строки 17, зазор 12 — зазор больше 0.45 высоты, строки разные.
        val found = bands(profile(140, 2..18, 31..47, 60..76, 89..105, 118..134))
        found.size shouldBe 5
        found.first() shouldBe 2..18
        found.last() shouldBe 118..134
    }

    @Test
    fun `close lines merge and stay whole`() {
        // Зазор в одну-две строки — это интервал внутри строки (диакритика,
        // висячие элементы букв). Полосы обязаны слиться, иначе глифы режутся.
        bands(profile(80, 10..30, 32..52, 54..74)) shouldBe emptyList()
    }

    @Test
    fun `two lines are returned but the engine needs three`() {
        val ink = profile(60, 4..24, 36..56)
        // Чистая функция честно отдаёт найденное...
        bands(ink).size shouldBe 2
        // ...а движок режет колонки только от трёх строк: подпись из двух
        // строк дёшево прочитать целиком.
        bands(ink, minBands = 3) shouldBe emptyList()
    }

    @Test
    fun `too short profile yields nothing`() {
        bands(profile(1, 0..0)) shouldBe emptyList()
    }

    @Test
    fun `blank profile yields nothing`() {
        bands(profile(50)) shouldBe emptyList()
    }

    @Test
    fun `faint noise below threshold is not a line`() {
        // Водяной знак оставляет меньше 2% чернил в строке: резать нечего.
        bands(FloatArray(60) { 0.01f }) shouldBe emptyList()
    }

    @Test
    fun `lines thinner than the minimum height are dropped`() {
        // Три полосы, но средняя — ореол рамки: остаётся две, и разбор отменяется.
        bands(profile(60, 4..24, 28..29, 36..56)) shouldBe emptyList()
    }

    @Test
    fun `a faint line does not disappear between strong lines`() {
        // Реплика читателя: на кропе из трёх мелких блоков средний блок из двух
        // слов даёт меньше чернил в строке, чем minInkRatio, и раньше выпадал
        // из разбора целиком — текст блока терялся. Теперь промежуток с
        // чернилами втягивается в полосу, и ни одна строка не пропадает.
        val ink = profile(120, 4..20, 52..68, 100..116)
        // Средняя «строка» — 40..48, чернил ниже порога, но строка не пустая.
        for (row in 40..48) ink[row] = 0.005f
        val found = bands(ink, minBands = 3)
        found.size shouldBe 3
        // Полоса доходит до последней строки с чернилами перед второй полосой.
        found[0].first shouldBe 4
        found[0].last shouldBe 48
        // Сильные полосы не изменились.
        found[1] shouldBe 52..68
        found[2] shouldBe 100..116
    }

    @Test
    fun `band edges reach the outermost faint ink`() {
        // Слабые чернила сверху и снизу относятся к первой и последней полосам.
        val ink = profile(100, 10..26, 40..56, 70..86)
        for (row in 0..3) ink[row] = 0.004f
        for (row in 92..95) ink[row] = 0.004f
        val found = bands(ink, minBands = 3)
        found.first().first shouldBe 0
        found.last().last shouldBe 95
    }

    @Test
    fun `blank gaps between strong lines stay gaps`() {
        // Пустой промежуток не должен раздуваться: полосы остаются узкими,
        // иначе соседние строки склеились бы в один кроп.
        val found = bands(profile(140, 2..18, 31..47, 60..76, 89..105, 118..134), minBands = 3)
        found.size shouldBe 5
        found shouldBe listOf(2..18, 31..47, 60..76, 89..105, 118..134)
    }
}
