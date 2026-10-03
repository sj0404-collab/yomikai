package eu.kanade.tachiyomi.data.ocr

import eu.kanade.tachiyomi.data.tts.PageRoleVerifier
import io.kotest.matchers.shouldBe
import mihon.domain.ocr.model.OcrBoundingBox
import mihon.domain.ocr.model.OcrRegion
import mihon.domain.ocr.model.OcrTextOrientation
import org.junit.jupiter.api.Test

/**
 * Что проверка моделью реально меняет в странице.
 *
 * Проверяется только то, что можно сделать честно: правка текста распознавания
 * и отсев не-реплик. Строки, которых не было в черновике, НЕ добавляются —
 * у них нет рамки, а выдуманная рамка означала бы подсветку и тап в пустоте.
 */
class OcrChapterAiVerifierApplyTest {

    private fun region(order: Int, text: String) = OcrRegion(
        order = order,
        text = text,
        boundingBox = OcrBoundingBox(0.1f * order, 0.1f, 0.1f * order + 0.2f, 0.3f),
        textOrientation = OcrTextOrientation.Horizontal,
    )

    private val ordered = listOf(
        region(0, "Я так и знал"),
        region(1, "Глава 12"),
        region(2, "Куда ты идёшь"),
    )

    @Test
    fun `model spelling replaces the recognized one`() {
        val check = PageRoleVerifier.parse(
            """{"pages":[{"page":"p","lines":[
              {"role":"Аки","text":"Я так и знал","thought":false,"kept":true},
              {"role":"Аки","text":"Куда ты идёшь","thought":false,"kept":true}
            ]}]}""",
            emptyList(),
        )
        // Правки нет — страница остаётся как есть, null = кэш не трогаем зря.
        OcrChapterAiVerifier.applyToRegions(check, ordered) shouldBe null
    }

    @Test
    fun `changed text is written back`() {
        val check = PageRoleVerifier.parse(
            """{"pages":[{"page":"p","lines":[
              {"role":"Аки","text":"Я так и знал.","thought":false,"kept":true},
              {"role":"Аки","text":"Куда ты идёшь?","thought":false,"kept":true}
            ]}]}""",
            emptyList(),
        )
        val out = OcrChapterAiVerifier.applyToRegions(check, ordered)!!
        out.map { it.text } shouldBe listOf("Я так и знал.", "Глава 12", "Куда ты идёшь?")
    }

    @Test
    fun `a non-reply region is dropped`() {
        val check = PageRoleVerifier.parse(
            """{"pages":[{"page":"p","lines":[
              {"role":"Аки","text":"Я так и знал","thought":false,"kept":true},
              {"role":"narrator","text":"Глава 12","thought":false,"kept":false},
              {"role":"Аки","text":"Куда ты идёшь","thought":false,"kept":true}
            ]}]}""",
            emptyList(),
        )
        val out = OcrChapterAiVerifier.applyToRegions(check, ordered)!!
        // Титул уходит из чтения, порядок пересобирается без дырок.
        out.map { it.text } shouldBe listOf("Я так и знал", "Куда ты идёшь")
        out.map { it.order } shouldBe listOf(0, 1)
    }

    @Test
    fun `a line the model invented is never inserted`() {
        val check = PageRoleVerifier.parse(
            """{"pages":[{"page":"p","lines":[
              {"role":"Аки","text":"Я так и знал","thought":false,"kept":true},
              {"role":"Аки","text":"Этого не было в черновике.","thought":false,"kept":true,"draft_text":""},
              {"role":"Аки","text":"Куда ты идёшь","thought":false,"kept":true}
            ]}]}""",
            emptyList(),
        )
        // Строки без рамки в страницу не попадают: иначе подсветка уехала бы в
        // пустоту, а тап по ней открыл бы несуществующую область. Изменений
        // тоже нет, поэтому результат null.
        OcrChapterAiVerifier.applyToRegions(check, ordered) shouldBe null
    }

    @Test
    fun `region boxes are never invented`() {
        val check = PageRoleVerifier.parse(
            """{"pages":[{"page":"p","lines":[
              {"role":"Аки","text":"Я так и знал.","thought":false,"kept":true},
              {"role":"Аки","text":"Куда ты идёшь","thought":false,"kept":true}
            ]}]}""",
            emptyList(),
        )
        val out = OcrChapterAiVerifier.applyToRegions(check, ordered)!!
        // Правка меняет только текст: рамки остаются исходными.
        out.map { it.boundingBox } shouldBe ordered.map { it.boundingBox }
    }
}