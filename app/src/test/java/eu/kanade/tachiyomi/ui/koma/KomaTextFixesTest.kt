package eu.kanade.tachiyomi.ui.koma

import org.junit.Assert.assertEquals
import org.junit.Test

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
        assertEquals("СЛУШАЙТЕ ТЕ ЕЁ.", KomaText.fixLookalikes("СЛУШАЙТЕ TE EË."))
        assertEquals("ЙОД", KomaText.fixLookalikes("ÎOД"))
    }

    @Test
    fun `буква вместо цифры в диапазоне`() {
        assertEquals("2-3", KomaWords.fixDigits("2-З"))
        assertEquals("3-4", KomaWords.fixDigits("З-4"))
        assertEquals("100", KomaWords.fixDigits("1О0"))
    }

    @Test
    fun `цифры в реплике не выбрасываются`() {
        // «ПОВТОРИТЕ 2-3 РАЗА» — цифры часть реплики, а не мусор.
        assertEquals("ПОВТОРИТЕ 2-3 РАЗА", KomaWords.fixDigits("ПОВТОРИТЕ 2-З РАЗА"))
    }
}
