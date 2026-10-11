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

    @Test
    fun `вертикальная лесенка читается строго сверху вниз`() {
        val lines = listOf(
            line("низ", 0.10f, 0.60f, 0.45f, 0.67f),
            line("верх", 0.60f, 0.05f, 0.95f, 0.12f),
            line("середина", 0.35f, 0.32f, 0.70f, 0.39f),
        )
        assertEquals(listOf("верх", "середина", "низ"), texts("vertical", lines))
    }

    @Test
    fun `rtl лесенка со сдвигом по X не читается куском слева и куском справа`() {
        // Диагональ сверху-справа вниз-влево: раньше рекурсивный разрезатель
        // делил кадр на правую и левую группы и читал их раздельно, из-за чего
        // «лесенка» ломалась.
        val lines = listOf(
            line("D", 0.00f, 0.27f, 0.35f, 0.34f),
            line("B", 0.35f, 0.13f, 0.75f, 0.20f),
            line("A", 0.55f, 0.05f, 0.95f, 0.12f),
            line("C", 0.15f, 0.20f, 0.55f, 0.27f),
        )
        assertEquals(listOf("A", "B", "C", "D"), texts("rtl", lines))
    }

    @Test
    fun `панели манги читаются в порядке панелей, внутри панели — рядами`() {
        // Панели уже пришли в порядке чтения: правая, потом левая. Реплика из
        // левой панели стоит ВЫШЕ реплики из правой — но порядок всё равно
        // «правая панель целиком, потом левая».
        val panels = listOf(
            box(0.52f, 0.05f, 0.98f, 0.45f),
            box(0.02f, 0.05f, 0.48f, 0.45f),
        )
        val boxes = listOf(
            "низ правой" to box(0.55f, 0.30f, 0.90f, 0.42f),
            "верх правой" to box(0.55f, 0.08f, 0.90f, 0.20f),
            "верх левой" to box(0.05f, 0.08f, 0.40f, 0.20f),
        )
        val ordered = orderByPanels(boxes, panels, tachiyomi.core.common.util.system.ReadingDirection.RTL)
        assertEquals(listOf("верх правой", "низ правой", "верх левой"), ordered)
    }

    @Test
    fun `реплика без панели уходит в конец`() {
        val panels = listOf(
            box(0.52f, 0.05f, 0.98f, 0.45f),
            box(0.02f, 0.05f, 0.48f, 0.45f),
        )
        val boxes = listOf(
            "в панели" to box(0.55f, 0.08f, 0.90f, 0.20f),
            "в поле" to box(0.02f, 0.80f, 0.40f, 0.92f),
        )
        val ordered = orderByPanels(boxes, panels, tachiyomi.core.common.util.system.ReadingDirection.RTL)
        assertEquals(listOf("в панели", "в поле"), ordered)
    }

    @Test
    fun `без панелей порядок не меняется`() {
        val boxes = listOf(
            "A" to box(0.10f, 0.10f, 0.40f, 0.20f),
            "B" to box(0.55f, 0.08f, 0.90f, 0.18f),
        )
        val ordered = orderByPanels(boxes, emptyList(), tachiyomi.core.common.util.system.ReadingDirection.RTL)
        assertEquals(listOf("A", "B"), ordered)
    }

    @Test
    fun `ltr панели идут слева направо`() {
        // Западный комикс: детектор вернул панели в порядке чтения — левая,
        // потом правая. Реплики читаются по панелям в том же порядке.
        val panels = listOf(
            box(0.02f, 0.05f, 0.48f, 0.45f),
            box(0.52f, 0.05f, 0.98f, 0.45f),
        )
        val boxes = listOf(
            "правая" to box(0.55f, 0.08f, 0.90f, 0.20f),
            "левая" to box(0.05f, 0.08f, 0.40f, 0.20f),
        )
        val ordered = orderByPanels(boxes, panels, tachiyomi.core.common.util.system.ReadingDirection.LTR)
        assertEquals(listOf("левая", "правая"), ordered)
    }

    @Test
    fun `ltr внутри одной панели — рядами слева направо`() {
        // Панель на весь лист: порядок внутри неё всё равно применяется.
        val panels = listOf(box(0.02f, 0.02f, 0.98f, 0.45f))
        val boxes = listOf(
            "правый верх" to box(0.55f, 0.08f, 0.90f, 0.20f),
            "левый верх" to box(0.08f, 0.06f, 0.40f, 0.18f),
            "левый низ" to box(0.08f, 0.28f, 0.40f, 0.40f),
        )
        val ordered = orderByPanels(boxes, panels, tachiyomi.core.common.util.system.ReadingDirection.LTR)
        assertEquals(listOf("левый верх", "правый верх", "левый низ"), ordered)
    }

    @Test
    fun `orderLinesByPanels читает панель за панелью`() {
        val panels = listOf(
            box(0.52f, 0.05f, 0.98f, 0.45f),
            box(0.02f, 0.05f, 0.48f, 0.45f),
        )
        val lines = listOf(
            line("низ правой", 0.55f, 0.30f, 0.90f, 0.42f),
            line("верх правой", 0.55f, 0.08f, 0.90f, 0.20f),
            line("верх левой", 0.05f, 0.08f, 0.40f, 0.20f),
        )
        val ordered = AutoReadEngine.orderLinesByPanels(lines, "rtl", panels)
        assertEquals(listOf("верх правой", "низ правой", "верх левой"), ordered.map { it.text })
    }

    @Test
    fun `orderLinesByPanels без панелей = обычный порядок`() {
        val lines = listOf(
            line("BR", 0.60f, 0.55f, 0.95f, 0.70f),
            line("TR", 0.60f, 0.05f, 0.95f, 0.20f),
            line("BL", 0.05f, 0.55f, 0.40f, 0.70f),
            line("TL", 0.05f, 0.05f, 0.40f, 0.20f),
        )
        val ordered = AutoReadEngine.orderLinesByPanels(lines, "rtl", emptyList())
        assertEquals(listOf("TR", "TL", "BR", "BL"), ordered.map { it.text })
    }

    @Test
    fun `на русской странице иноязычные надписи выкидываются`() {
        val lines = listOf(
            line("Привет, как дела?", 0.1f, 0.1f, 0.5f, 0.2f),
            line("Это реплика героя", 0.1f, 0.3f, 0.5f, 0.4f),
            line("И снова реплика тут", 0.1f, 0.5f, 0.5f, 0.6f),
            // Японский корешок, распознанный в мусор латиницей — выкидываем.
            line("KOCRE 26 BUKU", 0.6f, 0.1f, 0.9f, 0.2f),
            // Смешанная строка с вывеской — тоже чужая.
            line("Quiz 26", 0.6f, 0.3f, 0.9f, 0.4f),
        )
        val kept = AutoReadEngine.dropForeignLanguageLines(lines).map { it.text }
        assertEquals(
            listOf("Привет, как дела?", "Это реплика героя", "И снова реплика тут"),
            kept,
        )
    }

    @Test
    fun `на некириллической странице ничего не выкидываем`() {
        val lines = listOf(
            line("Hello there", 0.1f, 0.1f, 0.5f, 0.2f),
            line("Another line", 0.1f, 0.3f, 0.5f, 0.4f),
            line("Sign text", 0.6f, 0.1f, 0.9f, 0.2f),
        )
        assertEquals(3, AutoReadEngine.dropForeignLanguageLines(lines).size)
    }

    @Test
    fun `cyrillicShare считает долю кириллицы по буквам`() {
        assertEquals(1f, AutoReadEngine.cyrillicShare("Привет"))
        assertEquals(0f, AutoReadEngine.cyrillicShare("Quiz"))
        // 6 кириллических из 9 букв (пробел в знаменатель не входит).
        assertEquals(2f / 3f, AutoReadEngine.cyrillicShare("Привет abc"), 0.001f)
        // Пунктуация и пробелы в знаменатель не входят.
        assertEquals(1f, AutoReadEngine.cyrillicShare("Да... что?"))
        assertEquals(0f, AutoReadEngine.cyrillicShare("...12345"))
    }

    private fun box(l: Float, t: Float, r: Float, b: Float) = OcrBoundingBox(l, t, r, b)

    /** Прогоняет рамки через панельную сортировку и возвращает их подписи. */
    private fun orderByPanels(
        boxes: List<Pair<String, OcrBoundingBox>>,
        panels: List<OcrBoundingBox>,
        direction: tachiyomi.core.common.util.system.ReadingDirection,
    ): List<String> {
        val result = AutoReadEngine.orderBoxesByPanels(boxes.map { it.second }, panels, direction)
        val names = boxes.associateBy { it.second }
        return result.map { names.getValue(it).first }
    }
}
