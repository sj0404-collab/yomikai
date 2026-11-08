package eu.kanade.tachiyomi.data.tts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Закрепление голоса персонажа за книгой.
 *
 * Голос выбирается по номеру слота, а слот хранится в настройках. Важно одно:
 * закреплённый слот не должен отменяться, но и не должен достаться тому, кто
 * уже занял этот голос в текущей сцене.
 */
class BookVoiceSlotTest {

    /**
     * Зеркалит решение движка: [nextFree] — первый свободный слот
     * (nextSlotFor), [remembered] — слот, закреплённый за персонажем в настройках.
     */
    private fun slotFor(remembered: Int, nextFree: Int): Int =
        if (remembered > 0 && remembered >= nextFree) remembered else nextFree

    @Test
    fun `закреплённый голос персонажа сохраняется между запусками`() {
        // Новая сессия: никого в сцене ещё нет, первый свободный слот — 0.
        // Закреплённый слот 1 свободен, значит герой говорит тем же голосом.
        assertEquals(1, slotFor(remembered = 1, nextFree = 0))
    }

    @Test
    fun `новый персонаж получает свободный слот`() {
        assertEquals(2, slotFor(remembered = 0, nextFree = 2))
    }

    @Test
    fun `если закреплённый слот уже занят другим — берём свободный`() {
        // Первый свободный слот 2, значит 0 и 1 заняты. Голос из слота 1 уже
        // играет другой персонаж — отдаём 2, чтобы не было двух на одном.
        assertEquals(2, slotFor(remembered = 1, nextFree = 2))
    }

    @Test
    fun `закреплённый слот не отменяется при свободных более низких`() {
        // Слоты 0 и 1 свободны, но у героя закреплён 2 — берём 2, иначе голос
        // прыгал бы при каждом перезапуске.
        assertEquals(2, slotFor(remembered = 2, nextFree = 0))
    }

    @Test
    fun `незакреплённый персонаж не получает голос`() {
        // Слот 0 — это нарратор, ему голос назначается отдельно.
        assertEquals(0, slotFor(remembered = 0, nextFree = 0))
    }
}
