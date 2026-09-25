package eu.kanade.tachiyomi.data.ai

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Бесплатные модели Zen отдаются OpenCode только его собственному клиенту.
 * Отказ приходит как HTTP 403 с `FreeTierError`, и раньше он уезжал в
 * «фатальную» ошибку: ротация перебирала весь каталог, каждой модели вешался
 * cooldown на 5 минут, а пользователю показывали «проверьте прокси» — то есть
 * отправляли чинить то, что сломано не у него.
 *
 * Проверяется чистая часть: как отличается отказ провайдера от лимита, сбоя
 * сети и обычной ошибки.
 */
class AiZenFreeTierTest {

    private val blocked = """{"type":"error","error":{"type":"FreeTierError",""" +
        """"message":"Error from provider (Console): OpenCode's free tier can only be used from within OpenCode"}}"""

    private val rateLimited = """{"type":"error","error":{"type":"FreeUsageLimitError",""" +
        """"message":"Free usage limit exceeded"}}"""

    @Test
    fun `zen free tier refusal is recognized`() {
        AiAssistant.isZenFreeTierBlocked(403, blocked) shouldBe true
        // Регистр и формулировка могут меняться, а суть — нет.
        AiAssistant.isZenFreeTierBlocked(403, blocked.uppercase()) shouldBe true
    }

    @Test
    fun `real rate limit is not the free tier refusal`() {
        // 429 остаётся настоящим лимитом: его лечит ротация, а не смена бэкенда.
        AiAssistant.isZenFreeTierBlocked(429, rateLimited) shouldBe false
    }

    @Test
    fun `other 403 reasons are not the free tier refusal`() {
        // 403 бывает и по другим причинам (доступ, ключ) — это не повод
        // объявлять бесплатный уровень закрытым.
        AiAssistant.isZenFreeTierBlocked(403, """{"error":"forbidden"}""") shouldBe false
        AiAssistant.isZenFreeTierBlocked(401, blocked) shouldBe false
    }

    @Test
    fun `explanation names the real cause and a working alternative`() {
        val message = AiAssistant.FREE_TIER_BLOCKED_MESSAGE
        (message.contains("OpenCode")) shouldBe true
        (message.contains("OpenRouter")) shouldBe true
        (message.contains("локальная LLM")) shouldBe true
    }
}
