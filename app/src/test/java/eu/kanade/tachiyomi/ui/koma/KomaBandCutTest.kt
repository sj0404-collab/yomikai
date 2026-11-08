package eu.kanade.tachiyomi.ui.koma

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Жалоба: «он снова делит рамку на два текста» — облачко на стыке полос
 * читалось двумя репликами («УЖЕ ПОРА НА» и остаток). Полоса, обрезавшая
 * реплику, теперь переносит её на следующую.
 */
class KomaBandCutTest {

    private fun line(text: String, top: Float, bottom: Float) =
        KomaReadingOrder.Line(text = text, left = 0.1f, top = top, right = 0.8f, bottom = bottom)

    @Test
    fun `line cut by the band bottom is carried over`() {
        val band = KomaBand(0, 0f, 0.5f)
        KomaReadingOrder.cutAtBandBottom(line("УЖЕ ПОРА НА-", 0.42f, 0.49f), band) shouldBe true
    }

    @Test
    fun `finished line is not carried`() {
        val band = KomaBand(0, 0f, 0.5f)
        KomaReadingOrder.cutAtBandBottom(line("ПРИБЫЛА.", 0.42f, 0.49f), band) shouldBe false
        KomaReadingOrder.cutAtBandBottom(line("КТО ЗДЕСЬ?", 0.42f, 0.49f), band) shouldBe false
        KomaReadingOrder.cutAtBandBottom(line("СКАЗАЛ «ДА»", 0.42f, 0.49f), band) shouldBe false
    }

    @Test
    fun `line well above the band bottom is not carried`() {
        val band = KomaBand(0, 0f, 0.5f)
        KomaReadingOrder.cutAtBandBottom(line("ОН ИДЁТ", 0.10f, 0.20f), band) shouldBe false
    }

    @Test
    fun `whole band never cuts a line`() {
        KomaReadingOrder.cutAtBandBottom(line("ОН ИДЁТ", 0.90f, 0.99f), KomaBand.Whole) shouldBe false
    }

    @Test
    fun `hyphen join glues the word without a space`() {
        KomaReadingOrder.joinCarry("УЖЕ ПОРА НА-", "ЧИНАТЬ ВСТРЕЧУ") shouldBe "УЖЕ ПОРА НАЧИНАТЬ ВСТРЕЧУ"
    }

    @Test
    fun `plain join keeps a space between fragments`() {
        KomaReadingOrder.joinCarry("ДОЛЖНО", "БЫТЬ, ПРИБЫЛА.") shouldBe "ДОЛЖНО БЫТЬ, ПРИБЫЛА."
    }

    @Test
    fun `empty parts do not produce stray spaces`() {
        KomaReadingOrder.joinCarry("", "ПРИБЫЛА.") shouldBe "ПРИБЫЛА."
        KomaReadingOrder.joinCarry("ОН ИДЁТ", "  ") shouldBe "ОН ИДЁТ"
    }
}
