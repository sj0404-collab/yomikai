package eu.kanade.tachiyomi.ui.koma

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Порядок чтения страницы: панель за панелью, реплика целиком.
 *
 * Раскладка взята с реальной страницы (скриншоты 22:01–22:07): три панели в
 * верхней полосе, две широкие реплики внизу. Раньше строки соседних облачек
 * склеивались вперемешку, реплика рвалась на куски — «не находит и пропускает».
 */
class KomaReadingOrderTest {

    private fun page(vararg blocks: PageBlock): List<OcrRegion> =
        blocks.flatMap { it.regions() }

    private class PageBlock(val lines: List<String>, val left: Float, val right: Float, val top: Float, val lineHeight: Float) {
        fun regions(): List<OcrRegion> = lines.mapIndexed { index, text ->
            val y = top + index * lineHeight
            OcrRegion(text, left, y, right, y + lineHeight * 0.86f)
        }
    }

    private fun texts(regions: List<OcrRegion>): List<String> =
        KomaReadingOrder.order(regions, rtl = true, onlyCyrillic = true).map { it.text }

    @Test
    fun `реплики верхней полосы читаются справа налево и целиком`() {
        val regions = page(
            // правая панель
            PageBlock(
                listOf("ДУМАЮ, В НАШЕМ", "СЛУЧАЕ ПОМОЖЕТ", "ТОЛЬКО ЭКЗОРЦИЗМ"),
                left = 0.75f, right = 0.98f, top = 0.06f, lineHeight = 0.04f,
            ),
            // средняя панель
            PageBlock(
                listOf("У ЭТОГО МЕСТА", "ПРИЯТНАЯ АТМОС-", "ФЕРА"),
                left = 0.40f, right = 0.62f, top = 0.08f, lineHeight = 0.04f,
            ),
            // левая панель
            PageBlock(
                listOf("Я СЛОВНО", "ОКАЗАЛАСЬ", "В ФИЛЬМЕ", "УЖАСОВ"),
                left = 0.08f, right = 0.28f, top = 0.09f, lineHeight = 0.04f,
            ),
            // нижняя полоса: две реплики рядом
            PageBlock(
                listOf("ХОТЕЛА", "ВЫ ЭТО", "ПОСМО-", "ТРЕТЬ", "МОЙ", "СОСЕД", "ТРОЛЛЬ"),
                left = 0.78f, right = 0.97f, top = 0.55f, lineHeight = 0.04f,
            ),
            PageBlock(
                listOf("ПЕПЕЛЬНЫЕ", "ГРЕМЛИНЫ,", "ВЫХОДИТЕ!"),
                left = 0.60f, right = 0.78f, top = 0.86f, lineHeight = 0.04f,
            ),
            PageBlock(
                listOf("ДАВАЙТЕ,", "СКОРЕЕ", "ВЫХОДИТЕ!"),
                left = 0.40f, right = 0.58f, top = 0.87f, lineHeight = 0.04f,
            ),
        )

        assertEquals(
            listOf(
                "ДУМАЮ, В НАШЕМ СЛУЧАЕ ПОМОЖЕТ ТОЛЬКО ЭКЗОРЦИЗМ",
                "У ЭТОГО МЕСТА ПРИЯТНАЯ АТМОСФЕРА",
                "Я СЛОВНО ОКАЗАЛАСЬ В ФИЛЬМЕ УЖАСОВ",
                "ХОТЕЛА ВЫ ЭТО ПОСМОТРЕТЬ МОЙ СОСЕД ТРОЛЛЬ",
                "ПЕПЕЛЬНЫЕ ГРЕМЛИНЫ, ВЫХОДИТЕ!",
                "ДАВАЙТЕ, СКОРЕЕ ВЫХОДИТЕ!",
            ),
            texts(regions),
        )
    }

    @Test
    fun `обрывок буквы внутри рамки не становится отдельной репликой`() {
        val regions = listOf(
            OcrRegion("НЕБЫЛИЦА", 0.30f, 0.10f, 0.70f, 0.16f),
            OcrRegion("Я", 0.31f, 0.12f, 0.35f, 0.16f),
            OcrRegion("СЛУШАЙ", 0.30f, 0.16f, 0.70f, 0.22f),
        )
        assertEquals(listOf("Я НЕБЫЛИЦА СЛУШАЙ"), texts(regions))
    }

    @Test
    fun `пустая страница не даёт реплик`() {
        assertEquals(emptyList<String>(), texts(emptyList()))
    }
}
