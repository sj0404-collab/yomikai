package eu.kanade.tachiyomi.data.ocr

import eu.kanade.tachiyomi.data.tts.PageRoleVerifier
import eu.kanade.tachiyomi.data.tts.VoiceRole
import eu.kanade.tachiyomi.data.tts.VoiceRoleDictionary
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

    @Test
    fun `manual voice assignment survives a repeated ai commit`() {
        // Книжные роли пишутся на каждом скане главы. Если бы commit()
        // перезаписывал словарь целиком, то голос, который читатель назначил
        // руками Аки, стирался бы при каждом повторном скане — и возвращался
        // уже с другим. Ручная настройка должна выживать.
        val store = """{"12":[{"name":"Аки","gender":"auto","age":"adult","voice":"ru-ru-x-dfa-network","pitch":1.0,"rate":1.0,"markers":["Аки"]}]}"""
        val existing = VoiceRoleDictionary.parseBookRoles(store, 12L)
        existing.single().voice shouldBe "ru-ru-x-dfa-network"

        // Модель предлагает Аки роль с ПУСТЫМ голосом: ручная должна выжить.
        val aiRole = VoiceRole(
            id = "аки",
            name = "Аки",
            gender = "auto",
            age = "adult",
            voice = "",
            markers = listOf("Аки"),
        )
        val merged = OcrChapterAiVerifier.mergeRoles(existing, listOf(aiRole))
        merged.single { it.name == "Аки" }.voice shouldBe "ru-ru-x-dfa-network"

        // Новый персонаж от модели при этом добавляется.
        val withNew = OcrChapterAiVerifier.mergeRoles(
            existing,
            listOf(aiRole, VoiceRole(id = "бо", name = "Бо", gender = "auto", age = "adult", voice = "", markers = listOf("Бо"))),
        )
        withNew.map { it.name } shouldBe listOf("Аки", "Бо")
    }
}
