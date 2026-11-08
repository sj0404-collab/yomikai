package eu.kanade.tachiyomi.data.tts

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Выбор голоса по умолчанию: локальные голоса установленного синтезатора
 * (RHVoice, Tera/«supertonic», Асапела) должны побеждать сетевые. Сетевой
 * голос выбирается только вручную — сам по себе он в списке оказывался
 * первым и уводил озвучку в интернет.
 */
class VoiceLocalFirstTest {

    /**
     * Повторяет ключевую сортировку списка голосов: сначала локальные, потом
     * сетевые, внутри группы — по имени.
     */
    private fun ranked(names: List<Pair<String, Boolean>>): List<String> =
        names.sortedWith(compareBy<Pair<String, Boolean>>({ it.second }, { it.first }))
            .map { it.first }

    @Test
    fun `локальные голоса идут раньше сетевых`() {
        val order = ranked(
            listOf(
                "ru-ru-x-dfa-network" to true,
                "ru-supertonic-ru_f1" to false,
                "ru-ru-x-rusc-local" to false,
                "ru-supertonic-m5" to false,
                "ru-ru-x-dfc-network" to true,
            ),
        )
        val firstNetwork = order.indexOfFirst { it.endsWith("-network") }
        val lastLocal = order.indexOfLast { !it.endsWith("-network") }
        assertTrue(firstNetwork > lastLocal, "сетевой голос не должен идти раньше локальных: $order")
    }

    @Test
    fun `внутри группы порядок стабильный по имени`() {
        val order = ranked(
            listOf(
                "ru-supertonic-st3_F2" to false,
                "ru-supertonic-ru_f1" to false,
                "ru-supertonic-m5" to false,
            ),
        )
        assertTrue(
            order == listOf("ru-supertonic-m5", "ru-supertonic-ru_f1", "ru-supertonic-st3_F2"),
            "ожидалась сортировка по имени: $order",
        )
    }
}
