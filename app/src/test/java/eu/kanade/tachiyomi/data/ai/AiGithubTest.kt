package eu.kanade.tachiyomi.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Форматирование событий ранера и ответов GitHub. Проверяется без сети:
 * именно эти строки читатель видит в чате вместо сырого JSON, и ошибка
 * перевода здесь выглядит как «приложение врёт, что делал ранер».
 */
class AiGithubTest {

    private fun run(
        id: Long = 42,
        status: String = "completed",
        conclusion: String? = "success",
    ) = AiGithub.Run(
        id = id,
        name = "LLM runner",
        status = status,
        conclusion = conclusion,
        createdAt = "2026-09-26T10:00:00Z",
        url = "https://github.com/o/r/actions/runs/42",
    )

    @Test
    fun `успешный прогон назван успешным`() {
        assertEquals("успешно", run().stateRu)
    }

    @Test
    fun `незавершённый прогон не выдаётся за успешный`() {
        assertEquals("идёт", run(status = "in_progress", conclusion = null).stateRu)
    }

    @Test
    fun `упавший прогон назван упавшим`() {
        assertEquals("упало", run(conclusion = "failure").stateRu)
    }

    @Test
    fun `список прогонов содержит номер и состояние`() {
        val text = AiGithub.renderRuns(listOf(run(), run(id = 43, conclusion = "failure")))
        assertTrue(text, text.contains("#42"))
        assertTrue(text, text.contains("успешно"))
        assertTrue(text, text.contains("#43"))
        assertTrue(text, text.contains("упало"))
    }

    @Test
    fun `пустой список прогонов не путается с ошибкой`() {
        assertEquals("Прогонов не найдено", AiGithub.renderRuns(emptyList()))
    }

    @Test
    fun `лента шагов показывает каждый шаг и его исход`() {
        val jobs = listOf(
            AiGithub.Job(
                name = "build",
                status = "completed",
                conclusion = "failure",
                steps = listOf(
                    AiGithub.Step(1, "checkout", "completed", "success"),
                    AiGithub.Step(2, "скачать модель", "completed", "success"),
                    AiGithub.Step(3, "serve", "completed", "failure"),
                ),
            ),
        )
        val text = AiGithub.renderJobs(jobs)
        assertTrue(text, text.contains("Задача: build"))
        assertTrue(text, text.contains("1. checkout — ок"))
        assertTrue(text, text.contains("3. serve — ошибка"))
    }

    @Test
    fun `шаг в процессе не назван ошибкой`() {
        val jobs = listOf(
            AiGithub.Job(
                name = "build",
                status = "in_progress",
                conclusion = null,
                steps = listOf(AiGithub.Step(1, "serve", "in_progress", null)),
            ),
        )
        val text = AiGithub.renderJobs(jobs)
        assertTrue(text, text.contains("1. serve — идёт"))
        assertTrue("ошибки быть не должно: $text", !text.contains("ошибка"))
    }

    @Test
    fun `прогон без задач не выглядит как пустой список`() {
        assertTrue(AiGithub.renderJobs(emptyList()).contains("возможно"))
    }

    @Test
    fun `пустые PR так и написаны`() {
        assertEquals("Открытых PR нет", AiGithub.renderPulls(emptyList()))
    }

    @Test
    fun `PR показан с номером и ссылкой`() {
        val text = AiGithub.renderPulls(listOf("#7 Починить OCR" to "https://github.com/o/r/pull/7"))
        assertTrue(text, text.contains("#7 Починить OCR"))
        assertTrue(text, text.contains("https://github.com/o/r/pull/7"))
    }

    @Test
    fun `ответ API различает успех и ошибку`() {
        assertTrue(AiGithub.Response(200, "{}").ok)
        assertTrue(AiGithub.Response(404, "{}").ok.not())
        assertTrue(AiGithub.Response(0, "сеть").ok.not())
    }
}
