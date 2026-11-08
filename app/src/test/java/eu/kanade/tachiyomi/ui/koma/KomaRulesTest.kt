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
    fun `multi-font defaults keep one-threshold behaviour until asked`() {
        // inkTileRows > 1 уже включён (несколько шрифтов в одном боксе), а второй
        // уровень «чернил» по умолчанию выключен: он добавляет и шум.
        KomaRules.DEFAULT.inkTileRows shouldBe 4
        KomaRules.DEFAULT.inkLevels shouldBe 1
    }

    @Test
    fun `multi-font options survive the file and are clamped`() {
        val parsed = KomaRules.fromJson("""{"inkTileRows":99,"inkLevels":7,"speechRate":9,"autoScrollStep":-1,"semiManual":true}""")
        parsed shouldNotBe null
        parsed!!.inkTileRows shouldBe 16
        parsed.inkLevels shouldBe 2
        near(parsed.speechRate, 3f)
        near(parsed.autoScrollStep, 0.05f)
        parsed.semiManual shouldBe true
    }

    @Test
    fun `band rules come from the file`() {
        val parsed = KomaRules.fromJson("""{"bandOverlap":0.30,"bandsMax":5,"wholeThreshold":0.9}""")!!
        val bands = KomaBands.of(viewportFrac = 0.25f, rules = parsed)
        near(parsed.bandOverlap, 0.3f)
        bands.size shouldBe 5
        // Страница, видимая на 95%, при пороге 0.9 читается целиком.
        KomaBands.of(viewportFrac = 0.95f, rules = parsed).size shouldBe 1
    }

    @Test
    fun `word fixes follow the file and keep the register`() {
        val fixes = mapOf("Пвтор" to "Автор", "Форирование" to "Формирование")
        KomaReadingOrder.applyWordFixes("Пвтор оригинала", fixes) shouldBe "Автор оригинала"
        // Капс остаётся капсом, обычный регистр — обычным.
        KomaReadingOrder.applyWordFixes("ПВТОР ОРИГИНАЛА", fixes) shouldBe "АВТОР ОРИГИНАЛА"
        KomaReadingOrder.applyWordFixes("Форирование прочной основы", fixes) shouldBe "Формирование прочной основы"
        // Слово не из словаря не трогаем.
        KomaReadingOrder.applyWordFixes("мачехи", fixes) shouldBe "мачехи"
    }

    @Test
    fun `word fixes work even without the frequency dictionary`() {
        // Именно так «Пвтор» и дожил до озвучки: словарь не был загружен.
        KomaReadingOrder.applyWordFixes("Пвтор", mapOf("пвтор" to "Автор")) shouldBe "Автор"
    }

    @Test
    fun `semi manual waits for the scroll to settle`() {
        KomaRules.DEFAULT.semiManualSettleMs shouldBe 700
        KomaRules.fromJson("""{"semiManualSettleMs":-500}""")!!.semiManualSettleMs shouldBe 0
        KomaRules.fromJson("""{"semiManualSettleMs":99999}""")!!.semiManualSettleMs shouldBe 5000
    }

    @Test
    fun `lines of very different height are not merged into one bubble`() {
        KomaRules.DEFAULT.mergeHeightRatio shouldBe 1.8f
        KomaRules.fromJson("""{"mergeHeightRatio":0.1}""")!!.mergeHeightRatio shouldBe 1.05f
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
