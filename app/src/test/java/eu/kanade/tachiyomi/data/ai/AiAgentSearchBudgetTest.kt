package eu.kanade.tachiyomi.data.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Лимит поиска в сети.
 *
 * Скрин читателя: агент сделал три `web_search` (один — с ошибкой), потом
 * написал «Поиск в сети уже выполнялся 3 раза… если нужна другая страница,
 * назови её точно, и я сделаю один прямой запрос через web_fetch» — и
 * `web_fetch` не вызвал уже никогда.
 *
 * Причина была не в модели, а в коде: при исчерпании лимита цикл
 * агента делал `break` и подставлял в ответ готовый текст, не дав модели
 * сделать ход. Обещание прямого запроса было невыполнимым по построению.
 *
 * Здесь проверяется чистое правило, на котором стоял тот баг.
 */
class AiAgentSearchBudgetTest {

    @Test
    fun `search is allowed while under both limits`() {
        assertTrue(AiAgent.searchAllowed(searchesUsed = 0, searchAttempts = 0))
        assertTrue(AiAgent.searchAllowed(searchesUsed = 2, searchAttempts = 2))
    }

    @Test
    fun `successful searches are what the budget counts`() {
        // Три удачных поиска — лимит выбран.
        assertFalse(AiAgent.searchAllowed(searchesUsed = 3, searchAttempts = 3))
        assertFalse(AiAgent.searchAllowed(searchesUsed = 4, searchAttempts = 4))
    }

    @Test
    fun `failed attempts do not exhaust the result budget but do stop the retries`() {
        // Упавший поиск не должен выбирать лимит: у читателя на руках
        // два результата из трёх попыток, и третий ещё можно повторить.
        assertTrue(AiAgent.searchAllowed(searchesUsed = 0, searchAttempts = 3))
        assertTrue(AiAgent.searchAllowed(searchesUsed = 2, searchAttempts = 3))

        // Но и крутить бесконечно нельзя: иначе на заблокированном поиске
        // модель перебирает формулировки кругами и жжёт токены.
        assertFalse(
            AiAgent.searchAllowed(
                searchesUsed = 0,
                searchAttempts = AiAgent.SEARCH_ATTEMPT_CAP,
            ),
        )
    }

    @Test
    fun `attempt cap is higher than the result budget`() {
        // Иначе отдельного потолка попыток не было бы смысла вводить.
        assertTrue(AiAgent.SEARCH_ATTEMPT_CAP > AiAgent.SEARCH_BUDGET)
    }

    @Test
    fun `durations under a second are shown in milliseconds`() {
        // Инструменты быстрее секунды раньше показывались как «0 с».
        assertEquals("0 мс", AiAgent.humanMs(0))
        assertEquals("223 мс", AiAgent.humanMs(223))
        assertEquals("999 мс", AiAgent.humanMs(999))
        assertEquals("1 с", AiAgent.humanMs(1_000))
        assertEquals("2 с", AiAgent.humanMs(2_450))
        assertEquals("1 мин 30 с", AiAgent.humanMs(90_000))
    }
}
