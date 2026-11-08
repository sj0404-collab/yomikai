package eu.kanade.tachiyomi.data.tts

import eu.kanade.tachiyomi.data.voice.VoicePlugins
import eu.kanade.tachiyomi.data.voice.VoicePlugins.VoicePlanReason
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Куда попадает голос, выбранный ИИ.
 *
 * Проблема, которую закрывает это правило: модель пишет голос в словарь, а
 * движок его потом не может произнести. Системный TTS умеет только то, что
 * установлено на устройстве, поэтому «красивый» голос из ответа модели мог
 * молча увести озвучку на другой движок — читатель слышал не то, что
 * настроили, и без единой ошибки.
 */
class AiVoicePlanTest {

    private val installed = listOf("ru-ru-x-sfg", "ru-ru-x-dfa-network", "google")

    @Test
    fun `online engine takes any voice the model picks`() {
        val plan = VoicePlugins.planAiVoice(
            targetEngine = TtsSpeaker.ENGINE_EDGE_TTS,
            aiVoice = "ru-RU-SvetlanaNeural",
            installedSystemVoices = emptyList(),
            online = true,
            aiAvailable = true,
        )
        plan.engineId shouldBe TtsSpeaker.ENGINE_EDGE_TTS
        plan.voiceId shouldBe "ru-RU-SvetlanaNeural"
        plan.aiAuthored shouldBe true
        plan.reason shouldBe VoicePlanReason.ONLINE_AUTHORED
    }

    @Test
    fun `system engine uses the model voice when it is really installed`() {
        val plan = VoicePlugins.planAiVoice(
            targetEngine = TtsSpeaker.ENGINE_SYSTEM,
            aiVoice = "ru-ru-x-dfa-network",
            installedSystemVoices = installed,
            online = false,
            aiAvailable = true,
        )
        plan.engineId shouldBe TtsSpeaker.ENGINE_SYSTEM
        plan.voiceId shouldBe "ru-ru-x-dfa-network"
        plan.aiAuthored shouldBe true
        plan.reason shouldBe VoicePlanReason.SYSTEM_AUTHORED_AVAILABLE
    }

    @Test
    fun `installed voice matches regardless of case`() {
        // Названия голосов приходят от модели в чужом регистре; сравнение не
        // должно молча отправлять чтение в сеть из-за «RU-RU-X-DFA».
        val plan = VoicePlugins.planAiVoice(
            targetEngine = TtsSpeaker.ENGINE_SYSTEM,
            aiVoice = "RU-RU-X-DFA-NETWORK",
            installedSystemVoices = installed,
            online = true,
            aiAvailable = true,
        )
        plan.reason shouldBe VoicePlanReason.SYSTEM_AUTHORED_AVAILABLE
        plan.engineId shouldBe TtsSpeaker.ENGINE_SYSTEM
    }

    @Test
    fun `system voice the device does not have falls back to online`() {
        val plan = VoicePlugins.planAiVoice(
            targetEngine = TtsSpeaker.ENGINE_SYSTEM,
            aiVoice = "ru-RU-SvetlanaNeural",
            installedSystemVoices = installed,
            online = true,
            aiAvailable = true,
        )
        plan.engineId shouldBe TtsSpeaker.ENGINE_EDGE_TTS
        plan.aiAuthored shouldBe true
        plan.reason shouldBe VoicePlanReason.SYSTEM_MISSING_FALLBACK_ONLINE
    }

    @Test
    fun `missing system voice without network stays on the system engine`() {
        // Сети нет: уходить на сетевой движок бессмысленно, там всё равно не
        // заговорит. Читаем системно, голос подберётся по полу.
        val plan = VoicePlugins.planAiVoice(
            targetEngine = TtsSpeaker.ENGINE_SYSTEM,
            aiVoice = "ru-RU-SvetlanaNeural",
            installedSystemVoices = installed,
            online = false,
            aiAvailable = true,
        )
        plan.engineId shouldBe TtsSpeaker.ENGINE_SYSTEM
        plan.voiceId shouldBe ""
        plan.aiAuthored shouldBe false
        plan.reason shouldBe VoicePlanReason.SYSTEM_MISSING_NO_NETWORK
    }

    @Test
    fun `online engine without network does not pretend to be configured`() {
        val plan = VoicePlugins.planAiVoice(
            targetEngine = TtsSpeaker.ENGINE_EDGE_TTS,
            aiVoice = "ru-RU-SvetlanaNeural",
            installedSystemVoices = emptyList(),
            online = false,
            aiAvailable = true,
        )
        plan.engineId shouldBe TtsSpeaker.ENGINE_SYSTEM
        plan.aiAuthored shouldBe false
        plan.reason shouldBe VoicePlanReason.UNAVAILABLE
    }

    @Test
    fun `without an AI key nothing is authored`() {
        val plan = VoicePlugins.planAiVoice(
            targetEngine = TtsSpeaker.ENGINE_SYSTEM,
            aiVoice = "ru-ru-x-sfg",
            installedSystemVoices = installed,
            online = true,
            aiAvailable = false,
        )
        plan.aiAuthored shouldBe false
        plan.voiceId shouldBe ""
        plan.reason shouldBe VoicePlanReason.UNAVAILABLE
    }

    @Test
    fun `empty model answer is not treated as a voice`() {
        val plan = VoicePlugins.planAiVoice(
            targetEngine = TtsSpeaker.ENGINE_SYSTEM,
            aiVoice = "   ",
            installedSystemVoices = installed,
            online = true,
            aiAvailable = true,
        )
        plan.aiAuthored shouldBe false
        plan.reason shouldBe VoicePlanReason.UNAVAILABLE
    }
}