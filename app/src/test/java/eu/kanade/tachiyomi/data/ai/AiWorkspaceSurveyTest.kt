package eu.kanade.tachiyomi.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Обзор папки и чтение пачкой — то, на чём агент строит план работы с
 * workspace. Проверяем здесь не «красиво ли», а три вещи, которые ломали
 * работу по-настоящему: выход за пределы папки, молчаливое обрезание и
 * потерю порядка файлов.
 */
class AiWorkspaceSurveyTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun root(): File {
        val r = folder.newFolder("ws")
        File(r, "book").mkdirs()
        File(r, "book/1.md").writeText("глава один")
        File(r, "book/2.md").writeText("глава два")
        File(r, "note.txt").writeText("заметка")
        return r
    }

    @Test
    fun `дерево показывает все файлы и папки`() {
        val text = AiWorkspaceSurvey.renderTree(root())
        assertTrue(text, text.contains("book/1.md"))
        assertTrue(text, text.contains("book/2.md"))
        assertTrue(text, text.contains("note.txt"))
        // Папка помечена косой чертой, иначе агент не отличит её от файла.
        assertTrue(text, text.contains("book/"))
    }

    @Test
    fun `дерево пустой папки не выдаёт ошибку`() {
        val r = folder.newFolder("empty")
        val text = AiWorkspaceSurvey.renderTree(r)
        assertTrue(text, text.contains("пусто"))
    }

    @Test
    fun `несуществующий подкаталог честно помечен как не найденный`() {
        val text = AiWorkspaceSurvey.renderTree(root(), "нет-такой")
        assertTrue(text, text.contains("не найден"))
    }

    @Test
    fun `обрезка по лимиту строк говорит сколько не показано`() {
        val text = AiWorkspaceSurvey.renderTree(root(), limit = 1)
        assertTrue(text, text.contains("показано 1 из"))
    }

    @Test
    fun `чтение пачкой сохраняет порядок файлов`() {
        val text = AiWorkspaceSurvey.readMany(root(), listOf("book/1.md", "book/2.md"))
        val first = text.indexOf("глава один")
        val second = text.indexOf("глава два")
        assertTrue("порядок нарушен: $text", first in 0 until second)
    }

    @Test
    fun `бюджет чтения обрезает и говорит об этом`() {
        val r = root()
        File(r, "big.txt").writeText("я".repeat(50_000))
        val text = AiWorkspaceSurvey.readMany(r, listOf("big.txt"), budget = 100)
        assertTrue(text, text.contains("обрезан по бюджету"))
    }

    @Test
    fun `израсходованный бюджет перечисляет непрочитанные файлы`() {
        val r = root()
        val text = AiWorkspaceSurvey.readMany(r, listOf("note.txt", "book/1.md"), budget = 20)
        assertTrue(text, text.contains("Бюджет чтения исчерпан"))
    }

    @Test
    fun `путь за пределы workspace не читается`() {
        val secret = folder.newFile("secret.txt").apply { writeText("не workspace") }
        val text = AiWorkspaceSurvey.readMany(root(), listOf("../secret.txt"))
        assertTrue(text, text.contains("НЕ НАЙДЕН"))
        assertTrue(!text.contains("не workspace"))
    }

    @Test
    fun `изображение не читается как текст`() {
        val r = root()
        File(r, "pic.png").writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        val text = AiWorkspaceSurvey.readMany(r, listOf("pic.png"))
        assertTrue(text, text.contains("не вижу картинки"))
    }

    @Test
    fun `пустой список файлов не проходит молча`() {
        val text = AiWorkspaceSurvey.readMany(root(), emptyList())
        assertTrue(text, text.contains("ни одного файла"))
    }

    @Test
    fun `дерево укладывается в бюджет символов`() {
        val r = root()
        repeat(200) { File(r, "book/f$it.md").writeText("x") }
        val text = AiWorkspaceSurvey.renderTree(r, maxBudget = 500)
        assertTrue("бюджет превышен: ${text.length}", text.length <= 500)
        assertTrue("нет пометки об обрезке", text.contains("показано"))
    }
}
