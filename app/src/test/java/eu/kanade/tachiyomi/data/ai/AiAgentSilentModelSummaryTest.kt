package eu.kanade.tachiyomi.data.ai

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Что приложение показывает, когда модель вернула только вызовы инструментов.
 *
 * Скрин читателя: модель написала, что всё отправлено в workspace, и
 * приложение показало «Готово. Результаты — в карточках инструментов ниже и
 * в workspace». В workspace при этом было пусто, вкладки не появилось. Фраза
 * была жёстко зашита и не зависела от того, выполнено хоть что-нибудь или
 * нет, поэтому приложение отчиталось об успехе, которого не было.
 */
class AiAgentSilentModelSummaryTest {

    @Test
    fun `no tools and no text means nothing was done`() {
        val text = AiAgent.silentModelSummary(emptyList())
        assertTrue(text.contains("ничего не выполнено"), text)
        assertTrue(text.contains("не вызвала ни одного инструмента"), text)
        // Главное: никаких обещаний про workspace и результаты.
        assertFalse(text.contains("Готово"), text)
        assertFalse(text.contains("workspace"), text)
    }

    @Test
    fun `executed tools are listed with their status`() {
        val text = AiAgent.silentModelSummary(
            listOf(
                AiAgent.ToolResult(name = "write_file", output = "ok", fileProduced = File("/tmp/a.md")),
                AiAgent.ToolResult(name = "read_file", output = "boom", status = "error"),
            ),
        )
        assertTrue(text.contains("Выполнено инструментов: 2"), text)
        assertTrue(text.contains("из них с ошибкой: 1"), text)
        assertTrue(text.contains("a.md"), text)
        assertFalse(text.contains("Файлов не создано"), text)
    }

    @Test
    fun `tools without files say so instead of promising a workspace`() {
        val text = AiAgent.silentModelSummary(
            listOf(AiAgent.ToolResult(name = "list_ext", output = "ничего не найдено")),
        )
        assertTrue(text.contains("Файлов не создано"), text)
        assertFalse(text.contains("в workspace"), text)
    }
}
