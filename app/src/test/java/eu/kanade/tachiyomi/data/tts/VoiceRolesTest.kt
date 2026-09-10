package eu.kanade.tachiyomi.data.tts

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.nulls.shouldBeNull
import org.junit.jupiter.api.Test

class VoiceRolesTest {

    private val roles = listOf(
        VoiceRole(
            id = "aki",
            name = "Аки",
            gender = "female",
            age = "teen",
            voice = "ru-ru-x-dfa-network",
            pitch = 1.2f,
            rate = 1.0f,
            markers = listOf("Аки", "AKI"),
        ),
        VoiceRole(
            id = "oldman",
            name = "Старик",
            gender = "male",
            age = "elderly",
            voice = "",
            pitch = 0.7f,
            rate = 0.85f,
        ),
    )

    @Test
    fun `parse and toJson round trip keeps all fields`() {
        val json = VoiceRoleDictionary.toJson(roles)
        val parsed = VoiceRoleDictionary.parse(json)
        parsed shouldBe roles
    }

    @Test
    fun `parse ignores blank and malformed entries`() {
        val parsed = VoiceRoleDictionary.parse("""[{"id":"","name":""},{"name":"Аки","gender":"female"}]""")
        parsed.size shouldBe 1
        parsed.first().name shouldBe "Аки"
    }

    @Test
    fun `resolve picks role by speaker name first`() {
        val resolved = VoiceRoleDictionary.resolve(roles, speakerName = "АКИ", gender = null)
        resolved shouldNotBe null
        resolved!!.name shouldBe "Аки"
    }

    @Test
    fun `resolve matches marker alias case-insensitively`() {
        val resolved = VoiceRoleDictionary.resolve(roles, speakerName = "aki", gender = null)
        resolved?.name shouldBe "Аки"
    }

    @Test
    fun `resolve by explicit gender without a name`() {
        val resolved = VoiceRoleDictionary.resolve(roles, speakerName = null, gender = "male")
        resolved?.name shouldBe "Старик"
    }

    @Test
    fun `resolve returns null when nothing matches`() {
        VoiceRoleDictionary.resolve(roles, speakerName = "Кводе", gender = null).shouldBeNull()
        VoiceRoleDictionary.resolve(roles, speakerName = null, gender = null).shouldBeNull()
    }

    @Test
    fun `role with auto gender never matches explicit gender`() {
        val auto = roles + VoiceRole(id = "n", name = "Нейтрал", gender = "auto")
        VoiceRoleDictionary.resolve(auto, speakerName = null, gender = "male")?.name shouldBe "Старик"
    }
}