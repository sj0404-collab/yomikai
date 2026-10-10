package eu.kanade.tachiyomi.data.tts

import mihon.domain.ocr.model.OcrBoundingBox
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Порядок чтения реплик кадра:
 *  • RTL-манга рядами баблов остаётся рядовой (TR, TL, BR, BL);
 *  • лента/махва с абзацами-колонками читается колонка за колонкой;
 *  • «vertical» читается сверху вниз одной лентой.
 */
class AutoReadEngineOrderTest {

    private fun line(text: String, l: Float, t: Float, r: Float, b: Float) =
        AutoReadEngine.Line(text, OcrBoundingBox(l, t, r, b))

    private fun texts(order: String, lines: List<AutoReadEngine.Line>) =
        AutoReadEngine.orderRegions(lines, order).map { it.text }

    @Test
    fun `мангa рядами баблов читается сверху вниз по рядам, внутри ряда справа налево`() {
        val lines = listOf(
            line("BR", 0.60f, 0.55f, 0.95f, 0.70f),
            line("TR", 0.60f, 0.05f, 0.95f, 0.20f),
            line("BL", 0.05f, 0.55f, 0.40f, 0.70f),
            line("TL", 0.05f, 0.05f, 0.40f, 0.20f),
        )
        assertEquals(listOf("TR", "TL", "BR", "BL"), texts("rtl", lines))
    }

    @Test
    fun `колонки абзацев читаются колонка за колонкой справа налево`() {
        // Правая узкая колонка абзаца + левая широкая: сериализованная лента.
        val lines = listOf(
            line("P4", 0.05f, 0.55f, 0.45f, 0.62f),
            line("P5", 0.05f, 0.65f, 0.45f, 0.72f),
            line("P1", 0.55f, 0.02f, 0.70f, 0.09f),
            line("P2", 0.55f, 0.10f, 0.70f, 0.17f),
            line("P3", 0.55f, 0.18f, 0.70f, 0.25f),
        )
        assertEquals(listOf("P1", "P2", "P3", "P4", "P5"), texts("rtl", lines))
    }

    @Test
    fun `колонки абзацев ltr читаются слева направо`() {
        val lines = listOf(
            line("L1", 0.05f, 0.02f, 0.35f, 0.09f),
            line("L2", 0.05f, 0.10f, 0.35f, 0.17f),
            line("L3", 0.05f, 0.18f, 0.35f, 0.25f),
            line("R1", 0.55f, 0.02f, 0.90f, 0.09f),
            line("R2", 0.55f, 0.10f, 0.90f, 0.17f),
            line("R3", 0.55f, 0.18f, 0.90f, 0.25f),
        )
        assertEquals(listOf("L1", "L2", "L3", "R1", "R2", "R3"), texts("ltr", lines))
    }

    @Test
    fun `vertical читается сверху вниз одной лентой`() {
        val lines = listOf(
            line("низ", 0.05f, 0.80f, 0.45f, 0.87f),
            line("верх", 0.55f, 0.02f, 0.95f, 0.09f),
            line("середина", 0.05f, 0.40f, 0.45f, 0.47f),
        )
        assertEquals(listOf("верх", "середина", "низ"), texts("vertical", lines))
    }

    @Test
    fun `одна реплика возвращается как есть`() {
        val lines = listOf(line("один", 0.1f, 0.1f, 0.2f, 0.2f))
        assertEquals(listOf("один"), texts("rtl", lines))
    }
}
