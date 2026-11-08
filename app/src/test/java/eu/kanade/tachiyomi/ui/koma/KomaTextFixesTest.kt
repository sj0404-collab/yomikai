package eu.kanade.tachiyomi.ui.koma

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Починка букв, из-за которых реплика обрывалась.
 *
 * Скриншот 21:20: вторая реплика заканчивалась на «КОГДА СЛУША», потому что
 * хвост «TE EË.» («ТЕ ЕЁ.») чтец отдал латиницей с точками, строка целиком не
 * прошла проверку языка и была выброшена. Эти правила проверены в веб-версии
 * 1.9.49 на той же странице.
 */
class KomaTextFixesTest {

    @Test
    fun `латинские двойники ё и й возвращаются в кириллицу`() {
        // Хвост прилип к русской реплике — именно так он и приходит с листа.
        KomaText.fixLookalikes("СЛУШАЙТЕ TE EË.") shouldBe "СЛУШАЙТЕ ТЕ ЕЁ."
        KomaText.fixLookalikes("ÎOД") shouldBe "ЙОД"
    }

    @Test
    fun `буква вместо цифры в диапазоне`() {
        KomaWords.fixDigits("2-З") shouldBe "2-3"
        KomaWords.fixDigits("З-4") shouldBe "3-4"
        KomaWords.fixDigits("1О0") shouldBe "100"
    }

    @Test
    fun `цифры в реплике не выбрасываются`() {
        // «ПОВТОРИТЕ 2-3 РАЗА» — цифры часть реплики, а не мусор.
        KomaWords.fixDigits("ПОВТОРИТЕ 2-З РАЗА") shouldBe "ПОВТОРИТЕ 2-3 РАЗА"
    }
}
