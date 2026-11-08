package eu.kanade.tachiyomi.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Агент спрашивает vision-модель про файлы из workspace. Проверяем расчёт
 * уменьшения: ошибка здесь стоит OutOfMemory на телефоне — фото с камеры
 * 12 000×9000 при полной декодировке это 108 мегапикселей, и телефон это не
 * переживёт.
 */
class AiImageVisionTest {

    @Test
    fun `маленькая картинка не уменьшается`() {
        assertEquals(1, AiAgent.sampleSizeFor(800, 600, 1600))
    }

    @Test
    fun `картинка вдвое больше лимита уменьшается вдвое`() {
        assertEquals(2, AiAgent.sampleSizeFor(3200, 2400, 1600))
    }

    @Test
    fun `фото с камеры берётся сразу с нужным размером`() {
        // 12000x9000 -> sample 8 -> 1500x1125, что влезает в лимит 1600.
        val sample = AiAgent.sampleSizeFor(12_000, 9_000, 1600)
        assertTrue("слишком мелкий sample: $sample", sample >= 4)
        assertTrue(12_000 / sample <= 1600 && 9_000 / sample <= 1600)
    }

    @Test
    fun `степень уменьшения не бесконечна`() {
        // Огромная панорама: без ограничения цикл ушёл бы в ноль.
        val sample = AiAgent.sampleSizeFor(60_000, 40_000, 1600)
        assertTrue("sample не ограничен: $sample", sample in 1..16)
    }

    @Test
    fun `вырожденный размер не ломает расчёт`() {
        assertEquals(1, AiAgent.sampleSizeFor(1, 1, 1600))
        assertEquals(1, AiAgent.sampleSizeFor(0, 0, 1600))
    }

    @Test
    fun `расширения картинок совпадают с тем что умеет спрашивать движок`() {
        assertTrue(AiAgent.IMAGE_EXTENSIONS.contains("png"))
        assertTrue(AiAgent.IMAGE_EXTENSIONS.contains("jpg"))
        assertTrue(AiAgent.IMAGE_EXTENSIONS.contains("webp"))
        // Документы движок не примет, и агент должен получить внятный отказ.
        assertTrue(!AiAgent.IMAGE_EXTENSIONS.contains("pdf"))
        assertTrue(!AiAgent.IMAGE_EXTENSIONS.contains("txt"))
    }
}
