package eu.kohesive.tachiyomi.data.tts

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Мост озвучки: плагин забирает только непроизводимые звуки, и только когда
 * читатель его включил и скачал. Главное свойство — плагин выключен не
 * меняет ничего: звуки идут в голос ровно как раньше.
 */
class TtsPluginBridgeTest {

    private fun decide(
        isSound: Boolean = true,
        variant: TtsPluginBridge.Variant? = TtsPluginBridge.Variant.AI_CACHED,
        downloaded: Boolean = true,
        enabled: Boolean = true,
    ) = TtsPluginBridge.decide(isSound, variant, downloaded, enabled)

    @Test
    fun `a real sound goes to the plugin when it is ready`() {
        assertEquals(TtsPluginBridge.Action.PLUGIN, decide(isSound = true))
    }

    @Test
    fun `plugin off keeps everything on the voice`() {
        // Плагин выключен — поведение ровно то, что было до плагина.
        assertEquals(TtsPluginBridge.Action.VOICE, decide(enabled = false))
        assertEquals(
            TtsPluginBridge.Action.VOICE,
            decide(isSound = false, enabled = false),
        )
    }

    @Test
    fun `plugin not downloaded yet keeps everything on the voice`() {
        // «Сначала скачай, потом включай»: без пакета включение бессмысленно,
        // но и не должно ломать озвучку.
        assertEquals(TtsPluginBridge.Action.VOICE, decide(downloaded = false))
        assertEquals(
            TtsPluginBridge.Action.VOICE,
            decide(downloaded = false, enabled = false),
        )
    }

    @Test
    fun `no chosen variant means no plugin`() {
        assertEquals(TtsPluginBridge.Action.VOICE, decide(variant = null))
    }

    @Test
    fun `text and interjections never go to the plugin`() {
        // Плагин озвучивает звуки, а не читает книгу: обычный текст и
        // междометия остаются на голосе, даже когда плагин включён.
        assertEquals(TtsPluginBridge.Action.VOICE, decide(isSound = false))
    }

    @Test
    fun `active variant is null until it is both enabled and downloaded`() {
        val id = TtsPluginBridge.Variant.AUDIO_PACK.id
        assertEquals(
            TtsPluginBridge.Variant.AUDIO_PACK,
            TtsPluginBridge.activeVariant(id, downloaded = true, enabled = true),
        )
        assertNull(TtsPluginBridge.activeVariant(id, downloaded = false, enabled = true))
        assertNull(TtsPluginBridge.activeVariant(id, downloaded = true, enabled = false))
        assertNull(TtsPluginBridge.activeVariant(null, downloaded = true, enabled = true))
    }

    @Test
    fun `cycling through variants wraps around`() {
        val order = TtsPluginBridge.Variant.entries.map { it.id }

        // Из выключенного состояния первый щелчок включает первый вариант.
        assertEquals(order[0], TtsPluginBridge.nextVariant(null, enabled = false))

        // Дальше по кругу, с последнего — на первый.
        assertEquals(order[1], TtsPluginBridge.nextVariant(order[0], enabled = true))
        assertEquals(order[2], TtsPluginBridge.nextVariant(order[1], enabled = true))
        assertEquals(order[0], TtsPluginBridge.nextVariant(order[2], enabled = true))

        // Неизвестный/пустой текущий вариант не должен ломать перебор.
        assertEquals(order[0], TtsPluginBridge.nextVariant("что-то", enabled = true))
        assertEquals(order[0], TtsPluginBridge.nextVariant(null, enabled = true))
    }

    @Test
    fun `every variant has an id and a title`() {
        // Варианты показываются в настройках и уходят в настройки как id,
        // поэтому идентификатор и название обязаны быть у каждого.
        val ids = TtsPluginBridge.Variant.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(TtsPluginBridge.Variant.entries.all { it.title.isNotBlank() })
        for (v in TtsPluginBridge.Variant.entries) {
            assertEquals(v, TtsPluginBridge.Variant.of(v.id))
        }
        assertNull(TtsPluginBridge.Variant.of("нет_такого"))
    }
}
