package eu.kanade.tachiyomi.ui.koma

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Склейка реплики: облачко из нескольких строк — ОДИН текст, порядок строк
 * сверху вниз, тонкая полоска-мусор не рвёт реплику, а колонка прозы не
 * превращается в одну реплику на полстраницы.
 *
 * Правила перенесены из веб-версии (1.9.49), где они проверены на реальных
 * страницах: до переноса в АПК реплика рвалась на два-три текста.
 */
class KomaBubblesTest {

    private fun texts(regions: List<OcrRegion>): List<String> =
        KomaReadingOrder.order(regions, rtl = true, onlyCyrillic = true).map { it.text }

    /** Строка облачка: одна и та же полоса по X, разная высота. */
    private fun line(text: String, top: Float, height: Float = 0.034f, left: Float = 0.30f, right: Float = 0.70f) =
        OcrRegion(text, left, top, right, top + height)

    @Test
    fun `облачко из трёх строк — одна реплика, строки сверху вниз`() {
        val regions = listOf(
            line("УЖЕ ПОРА", 0.10f),
            line("НАЧИНАТЬ ВСТРЕЧУ,", 0.14f),
            line("ТАК ЧТО ОНА", 0.18f),
        )
        texts(regions) shouldBe listOf("УЖЕ ПОРА НАЧИНАТЬ ВСТРЕЧУ, ТАК ЧТО ОНА")
    }

    @Test
    fun `тонкая полоска-мусор не рвёт реплику`() {
        // Детектор отдал рамку высотой 0,004 страницы поперёк картинки: текст из
        // неё — мусор, но её высота раньше портила «типичную высоту строки», и
        // честное облачко выходило двумя текстами.
        val regions = listOf(
            line("ПОВТОРИТЕ", 0.10f),
            line("ЭТО", 0.14f),
            line("ДВА-ТРИ РАЗА,", 0.18f),
            line("КОГДА СЛУШАЕТЕ.", 0.22f),
            OcrRegion("е ой", 0.30f, 0.31f, 0.60f, 0.314f),
        )
        texts(regions) shouldBe listOf("ПОВТОРИТЕ ЭТО ДВА-ТРИ РАЗА, КОГДА СЛУШАЕТЕ.")
    }

    @Test
    fun `колонка прозы не становится одной репликой на полстраницы`() {
        // Шесть плотных строк подряд — так выглядит газетная колонка. Раньше
        // они склеивались в блок высотой 0,30 страницы и читались одной
        // «репликой» на 75 слов.
        val regions = (0 until 6).map { index ->
            line("СТРОКА НОМЕР ${index + 1} ИЗ КОЛОНКИ", 0.05f + index * 0.05f)
        }
        val result = texts(regions)
        (result.size > 1) shouldBe true
    }

    @Test
    fun `обрывок другого кегля не втягивается в реплику`() {
        // Крупный заголовок (высота 0,05) и мелкая подпись (0,012) рядом:
        // склейка в один текст рисовала рамку по объединению двух шрифтов.
        val regions = listOf(
            OcrRegion("ГРОМКИЙ ЗАГОЛОВОК", 0.10f, 0.10f, 0.90f, 0.15f),
            OcrRegion("мелкая подпись", 0.30f, 0.16f, 0.60f, 0.172f),
        )
        texts(regions).size shouldBe 2
    }
}
