package eu.kohesive.tachiyomi.data.ai

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Каталог моделей Zen сверяется с документацией opencode, а не с памятью.
 *
 * Модели в Zen появляются и уходят: ушедшие модели не «падают хуже», а
 * занимают место в ротации и тратят время на заведомо мёртвый запрос. Такие
 * имена поэтому запрещены явным списком.
 */
class AiAssistantModelListTest {

    @Test
    fun `no dead models are offered`() {
        // Этих моделей в Zen больше нет. Пока они стояли в списке, ротация
        // упиралась в них и не доходила до рабочих.
        val dead = listOf(
            "laguna-s-2.1-free",
            "deepseek-v4-flash-free",
            "hy3-free",
        )
        for (model in dead) {
            assertFalse(
                AiAssistant.ZEN_MODELS.contains(model),
                "модель $model больше не существует в Zen",
            )
        }
    }

    @Test
    fun `current free models are present`() {
        // Бесплатные модели из документации Zen: без них читатель не получает
        // рабочий путь вообще.
        val current = listOf(
            "mimo-v2.6-flash-free",
            "mimo-v2.5-free",
            "nemotron-3.5-lightning-free",
            "nemotron-3-ultra-free",
            "big-pickle",
            "space-bunny-free",
            "ling-3.0-flash-fin-free",
        )
        for (model in current) {
            assertTrue(
                AiAssistant.ZEN_MODELS.contains(model),
                "модель $model есть в Zen, но её нет в списке",
            )
        }
    }

    @Test
    fun `catalog has no duplicates and starts with a fast model`() {
        val list = AiAssistant.ZEN_MODELS
        assertTrue(list.isNotEmpty())
        assertTrue(list.size == list.toSet().size, "в каталоге есть повторы")

        // Первой должна идти быстрая модель без тяжёлого reasoning: с неё
        // начинается ротация, и читатель получает ответ быстро.
        assertFalse(list.first().contains("reasoning"))
    }

    @Test
    fun `every catalog model is routed to a known endpoint`() {
        // Если у модели неизвестное семейство, она молча уйдёт в
        // /chat/completions и не ответит — значит, её нельзя добавлять молча.
        for (model in AiAssistant.ZEN_MODELS) {
            assertTrue(
                ZenProtocol.familyOf(model) != ZenProtocol.Family.SYSTEM_ONE,
                "$model не чат-модель, её нельзя крутить в ротации чата",
            )
        }
    }
}
