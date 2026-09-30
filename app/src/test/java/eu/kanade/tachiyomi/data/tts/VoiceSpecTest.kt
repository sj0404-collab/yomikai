package eu.kanade.tachiyomi.data.tts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Спецификация голоса «пакет::имя» — единственный носитель того, КАКИМ
 * движком озвучивать. Раньше `speakWithVoice` разбирал её через
 * `substringBefore("::")`, а он для строки без разделителя возвращает
 * строку целиком: локальный голос `ru-ru-x-dfc-local` уезжал в
 * `TextToSpeech(app, …, "ru-ru-x-dfc-local")`, такого пакета нет, движок не
 * поднимался, и голос из списка молча не звучал.
 */
class VoiceSpecTest {

    @Test
    fun `a local voice without a package does not become a package name`() {
        val (pkg, name) = TtsSpeaker.parseVoiceSpec("ru-ru-x-dfc-local")
        assertEquals("", pkg, "пустой пакет = движок по умолчанию, а не имя голоса")
        assertEquals("ru-ru-x-dfc-local", name)
    }

    @Test
    fun `a network voice name is not treated as a package either`() {
        val (pkg, name) = TtsSpeaker.parseVoiceSpec("ru-RU-DmitryNeural")
        assertEquals("", pkg)
        assertEquals("ru-RU-DmitryNeural", name)
    }

    @Test
    fun `an engine prefixed spec is split into package and voice`() {
        val (pkg, name) = TtsSpeaker.parseVoiceSpec("com.github.massivemadness.ubiquity_rhvoice::Russian")
        assertEquals("com.github.massivemadness.ubiquity_rhvoice", pkg)
        assertEquals("Russian", name)
    }

    @Test
    fun `the edge prefix survives parsing`() {
        val (pkg, name) = TtsSpeaker.parseVoiceSpec("edge::ru-RU-SvetlanaNeural")
        assertEquals("edge", pkg)
        assertEquals("ru-RU-SvetlanaNeural", name)
    }

    @Test
    fun `a spec with an empty voice keeps the package`() {
        val (pkg, name) = TtsSpeaker.parseVoiceSpec("com.svox.picovoice::")
        assertEquals("com.svox.picovoice", pkg)
        assertEquals("", name, "пустое имя = голос движка по умолчанию")
    }

    @Test
    fun `a leading separator is not a package`() {
        val (pkg, name) = TtsSpeaker.parseVoiceSpec("::ru-ru-x-nmt-local")
        assertEquals("", pkg)
        assertEquals("ru-ru-x-nmt-local", name)
    }

    @Test
    fun `blank specs are empty on both sides`() {
        assertEquals("" to "", TtsSpeaker.parseVoiceSpec(null))
        assertEquals("" to "", TtsSpeaker.parseVoiceSpec("   "))
    }
}
