package eu.kanade.tachiyomi.data.tts

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Разбор ответа модели, проверяющей страницу.
 *
 * Главное здесь — что при негодном ответе возвращается честный отказ, а не
 * правдоподобная половина: раньше подсветка и порядок чтения опирались на
 * локальный разбор, и подмешивание «частично разобранного» ответа сделало бы
 * отладку невозможной.
 */
class PageRoleVerifierTest {

    @Test
    fun `reads roles thoughts and dropped entries`() {
        val answer = """
            {"pages":[{"page":"001.png","lines":[
              {"role":"Аки","text":"Я так и знал.","thought":false,"kept":true},
              {"role":"Аки","text":"Надо же...","thought":true,"kept":true}
            ],"dropped":["Глава 12","Перевод: someone"]}]}
        """.trimIndent()

        val check = PageRoleVerifier.parse(answer, listOf("Я так и знал.", "Надо же"))

        check.checked shouldBe true
        check.lines.size shouldBe 2
        check.lines[0].role shouldBe "Аки"
        check.lines[0].thought shouldBe false
        check.lines[1].thought shouldBe true
        // Пропущенное возвращается списком, а не выбрасывается молча: иначе
        // пропажу нельзя отличить от опечатки модели.
        check.dropped shouldBe listOf("Глава 12", "Перевод: someone")
    }

    @Test
    fun `a line added by the model is marked uncertain`() {
        // OCR пропустил реплику, модель её вернула: draft_text пустой, значит
        // правки не было. Такой строке нельзя верить наравне с проверенными.
        val answer = """
            {"pages":[{"page":"001.png","lines":[
              {"role":"Аки","text":"Это не было сказано.","thought":false,"kept":true,"draft_text":""},
              {"role":"Аки","text":"Я видел это","thought":false,"kept":true,"draft_text":"я видл это"}
            ]}]}
        """.trimIndent()

        val check = PageRoleVerifier.parse(answer, listOf("я видл это"))
        check.lines[0].uncertain shouldBe false
        // Строка, которой в черновике не было, — её модель сочинила или
        // прочитала лучше OCR. Помечаем как спорную.
        check.lines[1].uncertain shouldBe true
    }

    @Test
    fun `dropped title survives a missing dropped array`() {
        val answer = """{"pages":[{"page":"001.png","lines":[{"role":"Аки","text":"Привет.","thought":false,"kept":true}]}]}"""
        val check = PageRoleVerifier.parse(answer, listOf("Привет."))
        check.checked shouldBe true
        check.dropped shouldBe emptyList()
    }

    @Test
    fun `markdown fences do not break parsing`() {
        val answer = """
            ```json
            {"pages":[{"page":"001.png","lines":[{"role":"Аки","text":"Да.","thought":false,"kept":true}]}]}
            ```
        """.trimIndent()
        val check = PageRoleVerifier.parse(answer, listOf("Да."))
        check.checked shouldBe true
        check.lines.first().text shouldBe "Да."
    }

    @Test
    fun `missing role becomes the unknown marker`() {
        val answer = """{"pages":[{"page":"001.png","lines":[{"text":"Кто я?","thought":false,"kept":true}]}]}"""
        val check = PageRoleVerifier.parse(answer, listOf("Кто я?"))
        // Роль обязана быть строкой: пустая строка ломала бы сравнение по имени.
        check.lines.first().role shouldBe PageRoleVerifier.UNKNOWN_ROLE
    }

    @Test
    fun `garbage answer is a refusal not a partial result`() {
        // Каждый плохой ответ обязан дать checked = false, иначе локальный
        // разбор смешается с выдумкой модели.
        for (bad in listOf("", "просто текст", "{}", """{"pages":[]}""", """{"pages":[{"page":"1"}]}""")) {
            val check = PageRoleVerifier.parse(bad, listOf("строка"))
            check.checked shouldBe false
            check.lines shouldBe emptyList()
            (check.reason != null) shouldBe true
        }
    }

    @Test
    fun `page name is echoed verbatim`() {
        // Имя файла — из входа, а не из головы модели: страница потом ищется
        // по этому имени, и выдуманное имя тихо потеряло бы результат.
        val prompt = PageRoleVerifier.prompt("chapter_007.png", listOf("Ау"))
        prompt.contains("\"chapter_007.png\"") shouldBe true
        prompt.contains("не выдумывай имя файла") shouldBe true
    }

    @Test
    fun `prompt forbids inventing text and enumerates bubble shapes`() {
        val prompt = PageRoleVerifier.prompt("001.png", listOf("1. Текст"))
        // То, что уже портило текст в прошлых правках.
        prompt.contains("не додумывай") shouldBe true
        // Формы реплик: без них thought ставился не туда.
        prompt.contains("thought: true") shouldBe true
        prompt.contains("narrator") shouldBe true
        // Четыре проверки из задания.
        prompt.contains("АТРИБУЦИЯ") shouldBe true
        prompt.contains("ПОЛНОТА") shouldBe true
        prompt.contains("ТОЧНОСТЬ ТЕКСТА") shouldBe true
        prompt.contains("dropped") shouldBe true
    }

    @Test
    fun `prompt is genre neutral`() {
        // Промпт не должен заточиваться под конкретную мангу или язык: в
        // задании требовалось работать с любыми жанрами и стилями.
        val prompt = PageRoleVerifier.prompt("001.png", emptyList()).lowercase()
        prompt.contains("жанр, стиль и язык любые") shouldBe true
        listOf("миэруко", "сёнен", "романтика", "кодмена").forEach {
            prompt.contains(it) shouldBe false
        }
    }
}