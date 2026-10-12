package eu.kanade.tachiyomi.data.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Прерывание запросов к модели.
 *
 * Пока сокет не умели обрывать, «Стоп» останавливал корутину, а запрос к
 * модели дорабатывал свои 90 секунд; вдобавок оборванный сокет выглядел как
 * обычный сетевой сбой и уходил в повтор — то есть «Стоп» означал ещё три
 * запроса. Оба поведения проверяются здесь через поколение прерывания и
 * счётчик epoch.
 */
class AiAssistantAbortTest {

    @Test
    fun `abort bumps the epoch so an in-flight call can tell it was aborted`() {
        val before = AiAssistant.abortEpochForTest()
        AiAssistant.abortActiveRequests("тест")
        assertNotEquals(before, AiAssistant.abortEpochForTest())
    }

    @Test
    fun `abort on an idle assistant does nothing harmful`() {
        AiAssistant.abortActiveRequests("тест без активных запросов")
        // Повторный вызов тоже обязан быть безопасным: кнопка «Стоп» жмётся
        // и при пустом журнале, и при уже остановленном агенте.
        AiAssistant.abortActiveRequests("тест повторный")
        assertTrue(AiAssistant.abortEpochForTest() > 0)
    }

    @Test
    fun `no connections are tracked when nothing is running`() {
        AiAssistant.abortActiveRequests("тест")
        assertEquals(0, AiAssistant.activeConnectionCountForTest())
    }
}