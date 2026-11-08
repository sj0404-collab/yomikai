package eu.kanade.tachiyomi.ui.koma

import mihon.data.ocr.OcrPlugins
import mihon.domain.ocr.model.OcrModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Выбор движка распознавания в читалке KOMA.
 *
 * Жалоба, из которой это выросло: «в основной читалке мгновенно, а в KOMA нет —
 * и движок не сменился на тот, что их 5». Причина была в коде: читалка знала
 * ТОЛЬКО встроенный PP-OCR, а список движков из настроек на неё не влиял.
 *
 * Здесь проверяется то, что можно проверить без устройства: разметка
 * «офлайн/онлайн», откат неизвестного значения и перевод выбора в модель.
 */
class KomaEngineChoiceTest {

    @Test
    fun `встроенный KOMA первый и помечен офлайн`() {
        val options = KomaEngineChoice.options(null)
        assertEquals(KomaEngineChoice.LOCAL_ID, options.first().id)
        assertFalse(options.first().online)
        assertTrue(options.first().readiness.startsWith("офлайн"))
    }

    @Test
    fun `все движки из настроек попадают в список читалки`() {
        val ids = KomaEngineChoice.options(null).map { it.id }.toSet()
        OcrPlugins.ALL.forEach { plugin ->
            assertTrue(plugin.id in ids, "в списке читалки нет движка ${plugin.id}")
        }
    }

    @Test
    fun `онлайн-движки идут после офлайн и помечены честно`() {
        val options = KomaEngineChoice.options(null)
        val firstOnline = options.indexOfFirst { it.online }
        val lastOffline = options.indexOfLast { !it.online }
        assertTrue(firstOnline > lastOffline, "онлайн-движок оказался выше офлайн")
        options.filter { it.online }.forEach {
            assertTrue(it.readiness.contains("ещё не доделан"), "нет пометки у ${it.title}")
        }
    }

    @Test
    fun `требования движка объясняются словами`() {
        assertEquals("офлайн", KomaEngineChoice.requirementText(emptyList()))
        assertEquals("нужна сеть", KomaEngineChoice.requirementText(listOf("NETWORK")))
        assertEquals("нужен ключ", KomaEngineChoice.requirementText(listOf("NETWORK", "API_KEY")))
        assertEquals("нужен свой сервер", KomaEngineChoice.requirementText(listOf("NETWORK", "SERVER_ADDRESS")))
        assertEquals("нужен пакет моделей", KomaEngineChoice.requirementText(listOf("MODEL_PACK", "LITERT")))
    }

    @Test
    fun `неизвестное сохранённое значение читается встроенным KOMA`() {
        assertEquals(KomaEngineChoice.LOCAL_ID, KomaEngineChoice.resolveId(null))
        assertEquals(KomaEngineChoice.LOCAL_ID, KomaEngineChoice.resolveId(""))
        assertEquals(KomaEngineChoice.LOCAL_ID, KomaEngineChoice.resolveId("движок-из-старой-сборки"))
    }

    @Test
    fun `известное значение сохраняется, и выбор даёт модель`() {
        val plugin = OcrPlugins.ALL.first { it.online }
        assertEquals(plugin.id, KomaEngineChoice.resolveId(plugin.id))
        assertEquals(plugin.model, KomaEngineChoice.modelOf(plugin.id))
        // Встроенный движок моделью не описывается: его читает KomaOcrEngine.
        assertNull(KomaEngineChoice.modelOf(KomaEngineChoice.LOCAL_ID))
        assertNotNull(KomaEngineChoice.modelOf(OcrModel.MLKIT.name.lowercase()))
    }

    @Test
    fun `недоступный движок виден, но не пропадает из списка`() {
        val options = KomaEngineChoice.options(availableIds = emptySet())
        assertTrue(options.isNotEmpty())
        assertTrue(options.drop(1).all { !it.available })
        // Неизвестная доступность — не повод писать «недоступен».
        assertTrue(KomaEngineChoice.options(null).drop(1).all { it.available })
    }
}
