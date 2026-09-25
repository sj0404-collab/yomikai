package eu.kanade.tachiyomi.data.tts

import mihon.domain.ocr.model.OcrBoundingBox
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Юнит-тесты чистки OCR-мусора и порядка чтения для авточтения.
 *
 * Фидбек с устройства: одиночные слова («Я», «но», «И») не читались, а знак
 * «!!» — читался; порядок на манге шёл «лесенкой». Ниже фиксируем исправленное
 * поведение чистыми функциями (без Android), которые гоняет CI.
 */
class AutoReadEngineCleanTest {

    @Test
    fun `russian single-letter words are meaningful`() {
        assertTrue(AutoReadEngine.isMeaningful("Я", "ru"))
        assertTrue(AutoReadEngine.isMeaningful("и", "ru"))
        assertTrue(AutoReadEngine.isMeaningful("но", "ru"))
        assertTrue(AutoReadEngine.isMeaningful("не", "ru"))
    }

    @Test
    fun `punctuation only is not meaningful`() {
        assertFalse(AutoReadEngine.isMeaningful("!!", "ru"))
        assertFalse(AutoReadEngine.isMeaningful("...", "ru"))
        assertFalse(AutoReadEngine.isMeaningful("?", "ru"))
    }

    @Test
    fun `garbage is removed but short words survive`() {
        assertEquals("Я", AutoReadEngine.cleanOcrGarbage("Я", "ru"))
        assertEquals("но", AutoReadEngine.cleanOcrGarbage("но", "ru"))
        assertEquals("", AutoReadEngine.cleanOcrGarbage("!!", "ru"))
        // Строка из одних палок/стрелок — целиком мусор.
        assertEquals("", AutoReadEngine.cleanOcrGarbage("| | > |", "ru"))
        // Осмысленная реплика не кромсается.
        assertEquals("МЕНЯ НА РУЧКИ", AutoReadEngine.cleanOcrGarbage("МЕНЯ НА РУЧКИ", "ru"))
    }

    @Test
    fun `short russian word helper`() {
        assertTrue(AutoReadEngine.isShortRussianWord("Я"))
        assertTrue(AutoReadEngine.isShortRussianWord("но"))
        assertTrue(AutoReadEngine.isShortRussianWord("и"))
        assertFalse(AutoReadEngine.isShortRussianWord("например"))
        assertFalse(AutoReadEngine.isShortRussianWord(""))
    }

    @Test
    fun `orderRegions groups by row and sorts ltr`() {
        val lines = listOf(
            AutoReadEngine.Line("A", OcrBoundingBox(0.1f, 0.05f, 0.4f, 0.20f)),
            AutoReadEngine.Line("B", OcrBoundingBox(0.6f, 0.05f, 0.9f, 0.20f)),
            AutoReadEngine.Line("C", OcrBoundingBox(0.1f, 0.55f, 0.5f, 0.70f)),
        )
        val out = AutoReadEngine.orderRegions(lines, "ltr")
        assertEquals(listOf("A", "B", "C"), out.map { it.text })
    }

    @Test
    fun `orderRegions sorts rtl within row`() {
        val lines = listOf(
            AutoReadEngine.Line("A", OcrBoundingBox(0.1f, 0.05f, 0.4f, 0.20f)),
            AutoReadEngine.Line("B", OcrBoundingBox(0.6f, 0.05f, 0.9f, 0.20f)),
            AutoReadEngine.Line("C", OcrBoundingBox(0.1f, 0.55f, 0.5f, 0.70f)),
        )
        val out = AutoReadEngine.orderRegions(lines, "rtl")
        assertEquals(listOf("B", "A", "C"), out.map { it.text })
    }

    @Test
    fun `orderRegions vertical sorts top to bottom`() {
        val lines = listOf(
            AutoReadEngine.Line("C", OcrBoundingBox(0.1f, 0.55f, 0.5f, 0.70f)),
            AutoReadEngine.Line("A", OcrBoundingBox(0.1f, 0.05f, 0.4f, 0.20f)),
        )
        val out = AutoReadEngine.orderRegions(lines, "vertical")
        assertEquals(listOf("A", "C"), out.map { it.text })
    }

    @Test
    fun `orderRegions rtl keeps right bubble first when left one sits slightly higher`() {
        val lines = listOf(
            AutoReadEngine.Line("LEFT", OcrBoundingBox(0.08f, 0.049f, 0.38f, 0.20f)),
            AutoReadEngine.Line("RIGHT", OcrBoundingBox(0.60f, 0.050f, 0.92f, 0.21f)),
        )
        val out = AutoReadEngine.orderRegions(lines, "rtl")
        assertEquals(listOf("RIGHT", "LEFT"), out.map { it.text })
    }

    @Test
    fun `orderRegions rtl reads row by row, not column by column`() {
        val lines = listOf(
            AutoReadEngine.Line("TOP_RIGHT", OcrBoundingBox(0.55f, 0.05f, 0.92f, 0.18f)),
            AutoReadEngine.Line("TOP_LEFT", OcrBoundingBox(0.08f, 0.05f, 0.45f, 0.18f)),
            AutoReadEngine.Line("BOTTOM_RIGHT", OcrBoundingBox(0.55f, 0.55f, 0.92f, 0.68f)),
            AutoReadEngine.Line("BOTTOM_LEFT", OcrBoundingBox(0.08f, 0.55f, 0.45f, 0.68f)),
        )
        val out = AutoReadEngine.orderRegions(lines, "rtl")
        assertEquals(
            listOf("TOP_RIGHT", "TOP_LEFT", "BOTTOM_RIGHT", "BOTTOM_LEFT"),
            out.map { it.text },
        )
    }

    @Test
    fun `orderRegions ltr keeps left bubble first when right one sits slightly higher`() {
        val lines = listOf(
            AutoReadEngine.Line("RIGHT", OcrBoundingBox(0.60f, 0.050f, 0.92f, 0.21f)),
            AutoReadEngine.Line("LEFT", OcrBoundingBox(0.08f, 0.049f, 0.38f, 0.20f)),
        )
        val out = AutoReadEngine.orderRegions(lines, "ltr")
        assertEquals(listOf("LEFT", "RIGHT"), out.map { it.text })
    }

    @Test
    fun `orderRegions keeps a single row in document order for vertical webtoon`() {
        val lines = listOf(
            AutoReadEngine.Line("LOWER", OcrBoundingBox(0.08f, 0.40f, 0.90f, 0.46f)),
            AutoReadEngine.Line("UPPER", OcrBoundingBox(0.08f, 0.10f, 0.90f, 0.16f)),
        )
        val out = AutoReadEngine.orderRegions(lines, "vertical")
        assertEquals(listOf("UPPER", "LOWER"), out.map { it.text })
    }

    @Test
    fun `short russian reactions with punctuation survive cleanup`() {
        assertEquals("А!", AutoReadEngine.cleanOcrGarbage("А!", "ru"))
        assertEquals("А-А?", AutoReadEngine.cleanOcrGarbage("А-А?", "ru"))
        assertTrue(AutoReadEngine.isMeaningful("А!", "ru"))
        assertTrue(AutoReadEngine.isMeaningful("А-А?", "ru"))
    }

    @Test
    fun `tts timeout scales with selected speech rate`() {
        val normal = AutoReadEngine.ttsTimeoutMs(200, 1f)
        val slow = AutoReadEngine.ttsTimeoutMs(200, 0.5f)
        val fast = AutoReadEngine.ttsTimeoutMs(200, 2f)

        assertTrue(slow > normal)
        assertTrue(normal > fast)
    }

    @Test
    fun `fictional text is meaningful and watermark is not`() {
        assertTrue(AutoReadEngine.isMeaningful("Столичный город Арзия", "ru"))
        assertFalse(AutoReadEngine.isMeaningful("REMANGA.ORG ЧИТАЙ РАНЬШЕ ВСЕХ", "ru"))
    }

    @Test
    fun `an unstarted line stops blocking auto read after a short grace`() {
        // Регрессия: страница из одной реплики, которую движок не смог произнести,
        // ждала полный таймаут (минимум 8 с) и выглядела как остановка.
        val fullTimeout = AutoReadEngine.ttsTimeoutMs(0, 1f)
        assertTrue(
            "полный таймаут реплики должен быть заметно больше терпения к старту",
            fullTimeout > AutoReadEngine.TTS_START_GRACE_MS * 2,
        )

        // На старте реплика ещё не пошла — ждать имело смысл.
        assertFalse(AutoReadEngine.ttsStartedOrGiveUp(started = false, elapsedMs = 0))
        assertFalse(
            AutoReadEngine.ttsStartedOrGiveUp(
                started = false,
                elapsedMs = AutoReadEngine.TTS_START_GRACE_MS - 1,
            ),
        )

        // Как только терпение вышло — ждать больше нечего, идём к следующей
        // реплике вместо того, чтобы висеть до конца таймаута.
        assertTrue(
            AutoReadEngine.ttsStartedOrGiveUp(
                started = false,
                elapsedMs = AutoReadEngine.TTS_START_GRACE_MS,
            ),
        )
    }

    @Test
    fun `a started line is never given up on`() {
        // Реплика, которая пошла, должна доигрываться: терпение к старту на неё
        // не действует, сколько бы времени она ни звучала.
        assertTrue(AutoReadEngine.ttsStartedOrGiveUp(started = true, elapsedMs = 0))
        assertTrue(AutoReadEngine.ttsStartedOrGiveUp(started = true, elapsedMs = 60_000))
    }
}
