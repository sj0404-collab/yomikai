package eu.kanade.tachiyomi.data.tts

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.nulls.shouldBeNull
import org.junit.jupiter.api.Test

class VoiceIntonationsTest {

    private val rules = listOf(
        VoiceIntonationRule(pattern = "что?!", exact = true, pauseMs = 800, pitch = 1.4f, rate = 1.1f),
        VoiceIntonationRule(pattern = "шёпот", pauseMs = 400, pitch = 0.6f, rate = 0.7f),
        VoiceIntonationRule(pattern = "...", exact = true),
    )

    @Test
    fun `parse and toJson round trip keeps all fields`() {
        val json = VoiceIntonationDictionary.toJson(rules)
        val parsed = VoiceIntonationDictionary.parse(json)
        parsed shouldBe rules
    }

    @Test
    fun `parse skips empty patterns`() {
        val parsed = VoiceIntonationDictionary.parse("""[{"pattern":""},{"pattern":"эй"}]""")
        parsed.size shouldBe 1
        parsed.first().pattern shouldBe "эй"
    }

    @Test
    fun `exact rule matches the whole sentence only`() {
        VoiceIntonationDictionary.matchRule(rules, "Что?!") shouldNotBe null
        VoiceIntonationDictionary.matchRule(rules, "что?!") shouldNotBe null
        VoiceIntonationDictionary.matchRule(rules, "Что?! Ты серьёзно?").shouldBeNull()
        VoiceIntonationDictionary.matchRule(rules, "Так себе.").shouldBeNull()
    }

    @Test
    fun `substring rule matches inside a longer sentence`() {
        VoiceIntonationDictionary.matchRule(rules, "Я прошептал это шёпотом?")?.pattern shouldBe "шёпот"
    }

    @Test
    fun `first matching rule wins in order`() {
        val superset = rules + VoiceIntonationRule(pattern = "БАМ", pauseMs = 500)
        val ordered = listOf(
            VoiceIntonationRule(pattern = "БАМ", pauseMs = 999),
            superset.last(),
        )
        VoiceIntonationDictionary.matchRule(ordered, "БАМ!")?.pauseMs shouldBe 999
    }

    @Test
    fun `disabled rule is never matched`() {
        val disabled = rules.map { it.copy(enabled = false) }
        VoiceIntonationDictionary.matchRule(disabled, "Что?!").shouldBeNull()
    }
}