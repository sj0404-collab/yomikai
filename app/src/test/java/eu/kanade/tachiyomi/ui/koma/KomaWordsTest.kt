package eu.kanade.tachiyomi.ui.koma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Языковая починка без словаря: правила дефисов, кавычек и регистра не должны
 * зависеть от загрузки частотного списка — они работают всегда.
 *
 * Проверки со словарём (ЭТАШТУКА → ЭТА ШТУКА, РАССЧИ ТВВАЮ → РАССЧИТЫВАЮ)
 * сделаны в лаборатории на страницах 2, 4, 15, 16 и A: для них нужен файл
 * `assets/koma/dict/ru_250k.txt.gz`, а он в юнит-тестах недоступен.
 */
class KomaWordsTest {

    @Test
    fun `потерянный дефис возвращается`() {
        assertEquals("ЧТО-ТО", KomaWords.restoreHyphens("ЧТОТО"))
        assertEquals("В КАКОЕ-НИБУДЬ МЕСТО", KomaWords.restoreHyphens("В КАКОЕНИБУДЬ МЕСТО"))
        assertEquals("ГДЕ-ТО ДАЛЕКО", KomaWords.restoreHyphens("ГДЕТО ДАЛЕКО"))
        // «ЗАТО» — не «ЗА-ТО»: основа не из списка местоимений и наречий.
        assertEquals("ЗАТО КРАСИВО", KomaWords.restoreHyphens("ЗАТО КРАСИВО"))
    }

    @Test
    fun `незакрытая кавычка закрывается`() {
        assertEquals("С \"БОЖЕСТВЕННЫМ АБСОЛЮТОМ\"", KomaWords.balanceQuotes("С \"БОЖЕСТВЕННЫМ АБСОЛЮТОМ"))
        assertEquals("\"ОТРОК\".", KomaWords.balanceQuotes("\"ОТРОК\"."))
    }

    @Test
    fun `строка с половиной заглавных приводится к заглавным`() {
        // Раньше порог был 75 %, и «господи БОЖЕ МОЙ!» оставалась так.
        assertEquals("ГОСПОДИ БОЖЕ МОЙ!", KomaWords.normalizeCase("господи БОЖЕ МОЙ!"))
        assertEquals("Она сказала тихо", KomaWords.normalizeCase("Она сказала тихо"))
    }

    @Test
    fun `без словаря слова не правятся`() {
        assertFalse(KomaWords.isReady())
        assertEquals("ЭТАШТУКА ОТЦЕПИЛАСЬ", KomaWords.correctLine("ЭТАШТУКА ОТЦЕПИЛАСЬ"))
    }
}
