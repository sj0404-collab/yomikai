package mihon.data.ocr.vlm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PpOcrDbTest {

    private fun map(w: Int, h: Int, blocks: List<IntArray>): FloatArray {
        val p = FloatArray(w * h)
        blocks.forEach { b -> for (y in b[1] until b[3]) for (x in b[0] until b[2]) p[y * w + x] = 0.9f }
        return p
    }

    @Test
    fun `находит два блока и считает их координаты`() {
        val p = map(20, 20, listOf(intArrayOf(2, 2, 8, 6), intArrayOf(12, 12, 18, 16)))
        val boxes = PpOcrDb.boxes(p, 20, 20)
        assertEquals(2, boxes.size)
        assertEquals(2, boxes[0].left)
        assertEquals(6, boxes[0].width)
    }

    @Test
    fun `слишком мелкий шум отбрасывается`() {
        val p = map(20, 20, listOf(intArrayOf(2, 2, 3, 3)))
        assertTrue(PpOcrDb.boxes(p, 20, 20).isEmpty())
    }

    @Test
    fun `диагонально наклонная надпись остаётся одним блоком`() {
        val p = FloatArray(20 * 20)
        for (i in 2 until 15) p[i * 20 + i] = 0.9f
        val boxes = PpOcrDb.boxes(p, 20, 20)
        assertEquals(1, boxes.size)
        assertEquals(13, boxes[0].width)
    }

    @Test
    fun `порядок чтения сверху вниз и слева направо`() {
        val boxes = listOf(
            PpOcrDb.Box(100, 5, 140, 15),   // верх, справа
            PpOcrDb.Box(10, 6, 50, 16),     // верх, слева
            PpOcrDb.Box(10, 100, 50, 110),  // низ
        )
        val order = PpOcrDb.readingOrder(boxes)
        assertEquals(10, order[0].left)
        assertEquals(100, order[1].left)
        assertEquals(100, order[2].top)
    }

    @Test
    fun `пустая карта даёт пустой список`() {
        assertTrue(PpOcrDb.boxes(FloatArray(0), 0, 0).isEmpty())
    }
}
