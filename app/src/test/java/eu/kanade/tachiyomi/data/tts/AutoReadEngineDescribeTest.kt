package eu.kanade.tachiyomi.data.tts

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Описание разворота без текста: отчёт «текста на странице нет» описанием
 * не считается — иначе авточтение зачитывало бы вместо картинки отказ.
 */
class AutoReadEngineDescribeTest {

    @Test
    fun `отказ «текста нет» не считается описанием`() {
        assertTrue(AutoReadEngine.containsNoTextMarker("На этой странице нет текста"))
        assertTrue(AutoReadEngine.containsNoTextMarker("текст отсутствует"))
        assertTrue(AutoReadEngine.containsNoTextMarker("No text on this page"))
        assertTrue(AutoReadEngine.containsNoTextMarker("ТЕКСТА НЕТ"))
    }

    @Test
    fun `нормальное описание проходит`() {
        assertFalse(AutoReadEngine.containsNoTextMarker("Двое идут по пустынному городу, над ними висят облака."))
        // Слово «текст» есть, но это не отказ, а часть описания.
        assertFalse(AutoReadEngine.containsNoTextMarker("На вывеске текст «Кафе», дверь наполовину открыта."))
    }
}
