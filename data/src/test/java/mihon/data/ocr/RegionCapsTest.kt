package mihon.data.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionCapsTest {
    @Test
    fun `короткий бокс не трогаем`() {
        assertEquals(0.10f to 0.25f, RegionCaps.capHeight(0.10f, 0.25f))
    }

    @Test
    fun `бокс на полстраницы режется до 22 процентов`() {
        val (top, bottom) = RegionCaps.capHeight(0.05f, 0.65f)
        assertEquals(0.05f, top, 1e-6f)
        assertEquals(0.22f, bottom - top, 1e-6f)
    }

    @Test
    fun `верхняя граница сохраняется`() {
        val (top, _) = RegionCaps.capHeight(0.70f, 1.0f)
        assertEquals(0.70f, top, 1e-6f)
    }

    @Test
    fun `у нижнего края страницы не выходим за единицу`() {
        val (top, bottom) = RegionCaps.capHeight(0.95f, 1.0f)
        assertEquals(1.0f, bottom, 1e-6f)
        assertTrue(bottom - top <= 0.22f + 1e-6f)
    }

    @Test
    fun `инвертированный бокс остаётся пустым`() {
        val (top, bottom) = RegionCaps.capHeight(0.6f, 0.4f)
        assertTrue(bottom <= top)
    }
}
