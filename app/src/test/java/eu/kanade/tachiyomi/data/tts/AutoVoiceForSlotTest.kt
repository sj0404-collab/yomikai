package eu.kanade.tachiyomi.data.tts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Автоподбор голоса персонажа по номеру слота.
 *
 * Смысл: персонажу не нужно вручную назначать голос. Слот у него постоянный,
 * значит и голос постоянный — а разные персонажи получают разные голоса.
 */
class AutoVoiceForSlotTest {

    private val localVoices = listOf(
        "ru-supertonic-ru_f1",
        "ru-supertonic-ru_f2",
        "ru-supertonic-m1",
        "ru-supertonic-m5",
    )

    private fun pick(slot: Int) = TtsSpeaker.pickSlotVoice(localVoices, slot)

    @Test
    fun `один и тот же слот всегда даёт один и тот же голос`() {
        assertEquals(pick(3), pick(3))
        assertEquals(pick(1), pick(1))
    }

    @Test
    fun `соседние слоты звучат разными голосами`() {
        assertNotEquals(pick(1), pick(2))
        assertNotEquals(pick(2), pick(3))
    }

    @Test
    fun `голоса ходят по кругу и не заканчиваются`() {
        // Слот 5 при четырёх голосах возвращается к началу списка, а не падает
        // в пустоту — озвучка продолжает работать и в длинных главах.
        assertEquals(localVoices[1], pick(5))
    }

    @Test
    fun `без голосов или без слота голос не выдумывается`() {
        assertEquals("", TtsSpeaker.pickSlotVoice(emptyList(), 2))
        // Слот 0 — это нарратор, ему голос назначается отдельно.
        assertEquals("", TtsSpeaker.pickSlotVoice(localVoices, 0))
    }

    @Test
    fun `каждому персонажу достаётся свой голос`() {
        val assigned = (1..4).map { pick(it) }.toSet()
        assertTrue(assigned.size > 1, "персонажи не должны звучать одним голосом: $assigned")
    }
}
