package eu.kanade.tachiyomi.data.tts

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Описание разворота без текста: отчёт «текста на странице нет» описанием
 * не считается — иначе авточтение зачитывало бы вместо картинки отказ.
 */
class AutoReadEngineEmptyPageTest {

    @Test
    fun `пустой кадр не тратит время на добор облачков и скриншот`() {
        // На кадре нет ни одной буквы — второй проход OCR впустую.
        assertTrue(
            AutoReadEngine.skipEmptyPageWork(
                enabled = true,
                previousFrameWasEmpty = false,
                hasAnyLetters = false,
            ),
        )
    }

    @Test
    fun `после пустого кадра проходы пропускаются до появления текста`() {
        // Разворот-иллюстрация тянется на несколько страниц.
        assertTrue(
            AutoReadEngine.skipEmptyPageWork(
                enabled = true,
                previousFrameWasEmpty = true,
                hasAnyLetters = true,
            ),
        )
    }

    @Test
    fun `читаемая реплика возвращает обычную работу`() {
        // Появился текст — скан оживает: проходы нужны снова.
        assertFalse(
            AutoReadEngine.skipEmptyPageWork(
                enabled = true,
                previousFrameWasEmpty = false,
                hasAnyLetters = true,
            ),
        )
    }

    @Test
    fun `выключенная настройка ничего не меняет`() {
        assertFalse(
            AutoReadEngine.skipEmptyPageWork(
                enabled = false,
                previousFrameWasEmpty = true,
                hasAnyLetters = false,
            ),
        )
    }
}
