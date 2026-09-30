package eu.kanade.tachiyomi.data.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Отчёт о возможностях не должен обещать то, что не проверено.
 *
 * Претензия читателя: приложение отвечало «Zen Free доступен», хотя запросы
 * получали 403. Отчёт смотрел только на наличие сети. Теперь доступность
 * выводится из фактического ответа провайдера.
 */
class ZenAvailabilityTest {

    private val now = 1_000_000_000L

    @Test
    fun `nothing is claimed before the first attempt`() {
        // Не пробовали — нельзя ни хвалить, ни ругать.
        assertEquals(ZenAvailability.State.UNKNOWN, ZenAvailability.decide(0, 0, now))
    }

    @Test
    fun `a fresh success means the path works`() {
        assertEquals(
            ZenAvailability.State.WORKING,
            ZenAvailability.decide(lastSuccessAt = now - 1_000, lastBlockedAt = 0, now = now),
        )
    }

    @Test
    fun `a fresh block wins over an old success`() {
        // Главный случай: путь работал, потом провайдер закрыл его. Старый
        // успех не должен щадить закрытый путь, иначе агент снова в него войдёт.
        assertEquals(
            ZenAvailability.State.FREE_TIER_BLOCKED,
            ZenAvailability.decide(
                lastSuccessAt = now - 10_000,
                lastBlockedAt = now - 1_000,
                now = now,
            ),
        )
    }

    @Test
    fun `old verdicts stop counting`() {
        // И успех, и отказ протухают: провайдер вправе поменять политику.
        assertEquals(
            ZenAvailability.State.UNKNOWN,
            ZenAvailability.decide(
                lastSuccessAt = now - ZenAvailability.SUCCESS_VALID_MS - 1,
                lastBlockedAt = 0,
                now = now,
            ),
        )
        assertEquals(
            ZenAvailability.State.UNKNOWN,
            ZenAvailability.decide(
                lastSuccessAt = 0,
                lastBlockedAt = now - ZenAvailability.FAILURE_VALID_MS - 1,
                now = now,
            ),
        )
    }

    @Test
    fun `report says blocked out loud instead of promising`() {
        val (available, reason) = ZenAvailability.describe(
            hasNetwork = true,
            state = ZenAvailability.State.FREE_TIER_BLOCKED,
        )
        assertFalse(available)
        // Причина обязана называть рабочую замену, иначе агент и читатель
        // остаются с тупиком.
        assertTrue(reason.contains("403"))
        assertTrue(reason.contains("OpenRouter"))
    }

    @Test
    fun `no network is never available`() {
        for (state in ZenAvailability.State.entries) {
            val (available, _) = ZenAvailability.describe(hasNetwork = false, state = state)
            assertFalse(available, "без сети доступным не может быть $state")
        }
    }

    @Test
    fun `unverified path is reported as unverified`() {
        val (available, reason) = ZenAvailability.describe(true, ZenAvailability.State.UNKNOWN)
        assertTrue(available)
        assertTrue(
            reason.contains("не проверено"),
            "непроверенный путь должен быть назван непроверенным, а не «OK»",
        )
    }
}
