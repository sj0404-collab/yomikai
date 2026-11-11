package eu.kanade.tachiyomi.data.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Запасной список OpenRouter используется, когда живой каталог недоступен, и
 * как значение по умолчанию. Id из него обязаны существовать в каталоге
 * openrouter.ai/api/v1/models, иначе выбор по умолчанию всегда падает.
 */
class AiAssistantOpenRouterFallbackTest {

    @Test
    fun `fallback lists only free OpenRouter models`() {
        val list = AiAssistant.OPENROUTER_FREE_FALLBACK
        assertTrue(list.isNotEmpty())
        assertTrue(list.all { it.endsWith(":free") }, "в запасном списке должны быть только :free модели")
        assertEquals(list.size, list.toSet().size, "в запасном списке есть повторы")
    }

    @Test
    fun `fallback has no models that OpenRouter no longer lists`() {
        // Эти id не нашлись в каталоге OpenRouter при проверке: запрос по ним
        // возвращал «модель не найдена», а первый из них был значением по умолчанию.
        val dead = listOf(
            "nvidia/nemotron-3-nano-30b-a3b:free",
            "nvidia/nemotron-3-super-120b-a12b:free",
            "poolside/laguna-s-2.1:free",
            "z-ai/glm-5.2:free",
        )
        for (model in dead) {
            assertFalse(AiAssistant.OPENROUTER_FREE_FALLBACK.contains(model), "$model больше нет в OpenRouter")
        }
    }

    @Test
    fun `default openrouter model is the first fallback entry`() {
        // Выбор по умолчанию (пустой pref) берётся из первой записи, поэтому она
        // обязана быть живой бесплатной моделью, а не устаревшей.
        assertTrue(AiAssistant.OPENROUTER_FREE_FALLBACK.first().startsWith("nvidia/nemotron"))
    }
}
