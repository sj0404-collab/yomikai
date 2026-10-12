package eu.kanade.tachiyomi.data.ai

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Журнал консоли ИИ: кольцо, порядок и то, что запись безопасна из потока.
 *
 * Журнал бьёт по читателю, а не по модели: важно, что он не съедает память на
 * часовом скане главы, что новые строки сверху (хвост истории не нужен) и что
 * длинные промпты обрезаются, а не рвут список.
 */
class AiConsoleTest {

    @AfterEach
    fun tearDown() {
        AiConsole.clear()
    }

    @Test
    fun `newest entry comes first`() {
        AiConsole.note("первая")
        AiConsole.note("вторая")
        val entries = AiConsole.entries.value
        assertEquals(2, entries.size)
        assertEquals("вторая", entries.first().title)
        assertEquals("первая", entries.last().title)
    }

    @Test
    fun `ring keeps only the limit and drops the oldest`() {
        repeat(AiConsole.LIMIT + 25) { AiConsole.note("строка $it") }
        val entries = AiConsole.entries.value
        assertEquals(AiConsole.LIMIT, entries.size)
        // Хвост (самое старое) обрезан, начало списка — самое новое.
        assertEquals("строка ${AiConsole.LIMIT + 24}", entries.first().title)
        assertEquals("строка 25", entries.last().title)
    }

    @Test
    fun `long prompt is truncated so one entry cannot flood the list`() {
        AiConsole.model("модель", "x".repeat(50_000))
        val entry = AiConsole.entries.value.single()
        assertTrue(entry.detail!!.length <= 4000, "подробности должны быть обрезаны")
    }

    @Test
    fun `blank detail is dropped rather than shown as an empty line`() {
        AiConsole.tool("@read_file", "   ")
        assertEquals(null, AiConsole.entries.value.single().detail)
    }

    @Test
    fun `kind and level survive the round trip`() {
        AiConsole.round("Раунд 1")
        AiConsole.error("инструмент упал", "HTTP 500")
        val entries = AiConsole.entries.value
        assertEquals(AiConsole.Kind.TOOL, entries.first().kind)
        assertEquals(AiConsole.Level.ERROR, entries.first().level)
        assertEquals(AiConsole.Kind.ROUND, entries.last().kind)
    }

    @Test
    fun `a chapter scan leaves a trace the reader can watch`() {
        // Регент на «событий: 0» при идущем скане: консоль открывают ради
        // скана, а движки OCR ходят в сеть мимо AiAssistant, из-за чего журнал
        // был пуст ровно тогда, когда он нужнее всего.
        AiConsole.ocr(
            title = "Скан главы «1. Глава 1» начат",
            detail = "страниц 2",
        )
        AiConsole.ocr(title = "Скан страницы 1 · Google Lens")
        val titles = AiConsole.entries.value.map { it.title }
        assertTrue(
            titles.any { it.startsWith("Скан главы") },
            "в журнале должно быть начало скана главы, а есть только: $titles",
        )
        assertTrue(
            titles.any { it.startsWith("Скан страницы") },
            "в журнале должен быть скан страницы, а есть только: $titles",
        )
    }

    @Test
    fun `ids are unique so the list keys do not collide after trimming`() {
        AiConsole.note("a")
        AiConsole.note("b")
        AiConsole.note("c")
        val ids = AiConsole.entries.value.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}