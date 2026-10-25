package eu.kanade.tachiyomi.ui.koma

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.math.abs
import org.junit.jupiter.api.Test

/**
 * Правила чтения — файл, который правит человек. Опечатка в нём не должна
 * ронять чтение: значения зажимаются, незнакомые поля игнорируются, мусор
 * даёт встроенные правила.
 */
class KomaRulesTest {

    /** Сравнение float без зависимости от точности разбора JSON. */
    private fun near(actual: Float, expected: Float, eps: Float = 0.001f) {
        (abs(actual - expected) < eps) shouldBe true
    }

    @Test
    fun `built-in rules keep the values that fixed the grey text`() {
        val r = KomaRules.DEFAULT
        near(r.inkCoefficient, 0.45f)
        near(r.inkLowPercentile, 0.02f)
        near(r.inkHighPercentile, 0.98f)
        near(r.contrastGain, 2.2f)
        near(r.cutMargin, 0.06f)
    }

    @Test
    fun `json round trip keeps every field`() {
        val custom = KomaRules(version = "test-1", inkCoefficient = 0.62f, cutMargin = 0.09f, bubbleGap = 0.5f)
        val back = KomaRules.fromJson(custom.toJson())
        back shouldNotBe null
        back!!.version shouldBe "test-1"
        near(back.inkCoefficient, 0.62f)
        near(back.cutMargin, 0.09f)
        near(back.bubbleGap, 0.5f)
    }

    @Test
    fun `unknown fields are ignored so older app reads newer files`() {
        val parsed = KomaRules.fromJson("""{"version":"future","inkCoefficient":0.7,"somethingNew":123,"nested":{"a":1}}""")
        parsed shouldNotBe null
        parsed!!.version shouldBe "future"
        near(parsed.inkCoefficient, 0.7f)
    }

    @Test
    fun `out of range values are clamped instead of breaking reading`() {
        val parsed = KomaRules.fromJson(
            """{"inkCoefficient":45,"inkMin":300,"inkMax":10,"cutMargin":-3,"bandsMax":0,"bubbleGap":99}""",
        )
        parsed shouldNotBe null
        parsed!!
        near(parsed.inkCoefficient, 0.95f)
        parsed.inkMin shouldBe 254
        parsed.inkMax shouldBe 255
        near(parsed.cutMargin, 0f)
        parsed.bandsMax shouldBe 1
        near(parsed.bubbleGap, 3f)
    }

    @Test
    fun `broken file falls back to built-in rules`() {
        KomaRules.fromJson("{ это не json") shouldBe null
        KomaRules.fromJson("") shouldBe null
    }

    @Test
    fun `missing fields fall back to defaults`() {
        val parsed = KomaRules.fromJson("""{"version":"short"}""")
        parsed shouldNotBe null
        near(parsed!!.inkCoefficient, KomaRules.DEFAULT.inkCoefficient)
        near(parsed.pageSide, KomaRules.DEFAULT.pageSide, 0.01f)
    }

    @Test
    fun `buffered line converts to a region with the same box`() {
        val line = BufferedLine(
            book = "b1", page = 3, band = 1, text = "УЖЕ ПОРА НА-",
            left = 0.1f, top = 0.2f, right = 0.8f, bottom = 0.3f,
            confidence = 0.9f, rulesVersion = "test",
        )
        val region = line.toRegion()
        region.text shouldBe "УЖЕ ПОРА НА-"
        near(region.left, 0.1f)
        near(region.bottom, 0.3f)
        line.toLine().text shouldBe "УЖЕ ПОРА НА-"
    }
}
