package eu.kanade.tachiyomi.data.ai

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Асимметрия, найденная читателем: модели Zen работают как OCR и не работают
 * в чате, хотя ходят в один и тот же эндпоинт.
 *
 * Причина не в провайдере, а в коде: 403 FreeTierError трактовался как «весь
 * бесплатный Zen закрыт» и выход делался сразу, до следующей модели. Проверка
 * ниже фиксирует и сам признак блокировки, и то, что он не обязан означать
 * обвал всего каталога.
 */
class AiAssistantZenFreeTierTest {

    @Test
    fun `free tier block is recognised by body not only by code`() {
        val body = """{"error":{"message":"can only be used from within OpenCode","type":"FreeTierError"}}"""
        assertTrue(AiAssistant.isZenFreeTierBlocked(403, body))
        assertTrue(
            AiAssistant.isZenFreeTierBlocked(
                403,
                """{"error":{"type":"FreeTierError"}}""",
            ),
        )

        // 403 бывает и по другим причинам — это не блокировка free tier.
        assertFalse(AiAssistant.isZenFreeTierBlocked(403, """{"error":"forbidden"}"""))
        assertFalse(AiAssistant.isZenFreeTierBlocked(401, """{"error":{"type":"FreeTierError"}}"""))
    }

    @Test
    fun `a blocked model does not prove the whole catalog is dead`() {
        // OCR-движок в этом же приложении ходит в
        // https://opencode.ai/zen/v1/chat/completions с моделью
        // space-bunny-free и получает ответ — значит блокировка помодельная, и
        // исход FreeTierBlocked должен означать «пропусти эту модель».
        // Та же модель обязана быть и в каталоге чата, иначе исправленный
        // выход по кругу всё равно её не найдёт.
        assertTrue(
            AiAssistant.ZEN_MODELS.contains("space-bunny-free"),
            "рабочая OCR-модель space-bunny-free обязана быть в каталоге чата",
        )
    }

    @Test
    fun `rotation has a model to fall back on after a block`() {
        // Если каталог состоит из одной модели, 403 на ней и есть конец. С
        // несколькими — ротация обязана дойти до следующей, поэтому в списке
        // нужен запас.
        assertTrue(
            AiAssistant.ZEN_MODELS.size > 1,
            "при блокировке одной модели ротации некуда идти",
        )
    }
}
