package eu.kanade.tachiyomi.data.tts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Закрепление голоса за персонажем без подписи.
 *
 * Подписей «АКИ: …» в манге часто нет, и раньше такие герои теряли голос при
 * каждом перезапуске. Теперь безымянный персонаж привязывается к отпечатку
 * своей речи — но только если реплика достаточно длинная.
 */
class UnnamedSpeakerKeyTest {

    private fun fingerprint(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    private fun key(name: String?, text: String) =
        AutoReadEngine.persistentSpeakerKey(name, fingerprint(text))

    @Test
    fun `безымянный персонаж получает стабильный ключ по своей речи`() {
        val first = key(null, "Ты понимаешь, о чём я сейчас скажу")
        val again = key(null, "Ты понимаешь, о чём я сейчас скажу")
        assertEquals(first, again)
    }

    @Test
    fun `у разных персонажей ключи разные`() {
        val a = key(null, "Ну что, снова драться пойдём")
        val b = key(null, "Подожди, я ещё не закончила говорить")
        assertNotEquals(a, b)
    }

    @Test
    fun `короткая реплика ключа не получает`() {
        // «Да!» есть у всех — такой ключ склеил бы весь диалог в одного героя.
        assertNull(key(null, "Да!"))
        assertNull(key(null, "Нет"))
    }

    @Test
    fun `подпись важнее отпечатка речи`() {
        // Один и тот же герой в разных репликах обязан иметь один ключ.
        assertEquals(key("Аки", "Эй, ты меня слышишь вообще"), key("Аки", "Я же сказал ждать"))
        // Регистр и пробелы не влияют.
        assertEquals(key("АКИ", "Привет"), key("аки", "Привет"))
    }
}
