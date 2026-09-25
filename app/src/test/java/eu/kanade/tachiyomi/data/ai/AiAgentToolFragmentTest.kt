package eu.kanade.tachiyomi.data.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Мусорный синтаксис вызовов инструментов в ответе.
 *
 * Скрин читателя: модель написала «@tool page_text {» без закрывающей скобки.
 * Такой вызов не разбирался, и весь блок с @tool и JSON-аргументами уезжал
 * в «Размышления» как обычный текст. Список известных инструментов не
 * помогал: see_page и page_count — это действия внутри reader_do, известными
 * именами они не считаются.
 */
class AiAgentToolFragmentTest {

    @Test
    fun `unclosed tool call is removed with its json arguments`() {
        val raw = """
            @tool page_count {}
            @tool see_page
            {"action":"see_page","question":"Что изображено на этой странице? Опиши панели."}
            @tool page_text {
        """.trimIndent()

        val cleaned = AiAgent.stripToolFragments(raw)
        assertFalse(cleaned.contains("@tool"), "в ответе остался @tool")
        assertFalse(cleaned.contains("see_page"), "в ответе остался вызов инструмента")
        assertEquals("", cleaned)
    }

    @Test
    fun `ordinary text survives`() {
        val answer = "На странице изображена девушка.\nВсего панелей шесть."
        assertEquals(answer, AiAgent.stripToolFragments(answer))
    }

    @Test
    fun `text before and after a tool call is kept`() {
        val raw = """
            Смотрю страницу.
            @tool page_count {}
            Итог: страниц восемь.
        """.trimIndent()

        val cleaned = AiAgent.stripToolFragments(raw)
        assertTrue(cleaned.contains("Смотрю страницу."))
        assertTrue(cleaned.contains("Итог: страниц восемь."))
        assertFalse(cleaned.contains("@tool"))
    }

    @Test
    fun `json line without tool header is dropped`() {
        // Модель иногда пишет аргументы отдельной строкой без заголовка.
        val cleaned = AiAgent.stripToolFragments("""{"action":"turn_page","to":"next"}""")
        assertEquals("", cleaned)
    }
}
