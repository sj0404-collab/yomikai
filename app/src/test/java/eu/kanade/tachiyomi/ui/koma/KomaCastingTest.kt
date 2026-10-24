package eu.kanade.tachiyomi.ui.koma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Разбор «кто говорит» из текста реплики.
 *
 * v2.9.19 падала на старте авточтения: Android компилирует regexp через ICU, а
 * шаблон разметки заканчивался одиночной `}` после квантификатора `{1,40}` —
 * PatternSyntaxException. Здесь проверяется и поведение, и то, что шаблоны
 * вообще компилируются (на JVM и в ICU-совместимом виде — скобка экранирована).
 */
class KomaCastingTest {

    @Test
    fun `имя из разметки`() {
        assertEquals("Аки", KomaCasting.speakerName("{имя:Аки} Привет!"))
        assertEquals("Kim", KomaCasting.speakerName("{name:Kim} Hi"))
    }

    @Test
    fun `имя из подписи`() {
        assertEquals("Аки", KomaCasting.speakerName("Аки: Привет!"))
        assertEquals("Аки", KomaCasting.speakerName("Аки — привет"))
        assertNull(KomaCasting.speakerName("просто реплика без подписи"))
        // Короткие служебные слова именем не считаются.
        assertNull(KomaCasting.speakerName("НЕ: так"))
    }

    @Test
    fun `подпись убирается из озвучки`() {
        assertEquals("Привет!", KomaCasting.stripSpeaker("{имя:Аки} Привет!"))
        assertEquals("Привет!", KomaCasting.stripSpeaker("Аки: Привет!"))
        assertEquals("текст без подписи", KomaCasting.stripSpeaker("текст без подписи"))
    }

    @Test
    fun `голос отличается по полу и возрасту`() {
        val man = KomaCasting.voice(KomaCasting.Kind.MAN)
        val boy = KomaCasting.voice(KomaCasting.Kind.BOY)
        val woman = KomaCasting.voice(KomaCasting.Kind.WOMAN)
        assert(man.pitch < woman.pitch) { "мужской голос должен звучать ниже" }
        assert(boy.pitch > man.pitch) { "ребёнок должен звучать выше взрослого" }
        assertEquals("Мужчина", man.title)
    }
}
