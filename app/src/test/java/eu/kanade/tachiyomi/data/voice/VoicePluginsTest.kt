package eu.kanade.tachiyomi.data.voice

import eu.kanade.tachiyomi.data.tts.TtsSpeaker
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Реестр голосовых плагинов.
 *
 * Проверяется только декларативная часть (id, бэкенд, требования, доступность):
 * реальная озвучка живёт в `TtsSpeaker`/`VoiceHelper` и требует Android.
 */
class VoicePluginsTest {

    @Test
    fun `every plugin has a unique id matching its backend`() {
        VoicePlugins.ALL.map { it.id }.distinct().size shouldBe VoicePlugins.ALL.size
        VoicePlugins.ALL.forEach { plugin ->
            plugin.id shouldBe plugin.backend.id
            VoicePlugins.byId(plugin.id) shouldBe plugin
            VoicePlugins.byBackend(plugin.backend) shouldBe plugin
        }
    }

    @Test
    fun `backend ids match the values stored in pref_voice_engine`() {
        VoiceBackend.fromId("system_tts") shouldBe VoiceBackend.SYSTEM_TTS
        VoiceBackend.fromId("google_web") shouldBe VoiceBackend.GOOGLE_WEB
        VoiceBackend.fromId("eleven_api") shouldBe VoiceBackend.ELEVEN_API
        // Значение, которое пишет UI озвучки (TtsSpeaker.ENGINE_REMOTE).
        VoiceBackend.fromId("remote_tts") shouldBe VoiceBackend.REMOTE_TTS
        // Наследие сборок, где нейроголоса жили в APK: "onnx_tts" и "onnx"
        // обязаны вести на серверный движок, иначе пользователь потерял бы выбор.
        VoiceBackend.fromId("onnx_tts") shouldBe VoiceBackend.REMOTE_TTS
        VoiceBackend.fromId("onnx") shouldBe VoiceBackend.REMOTE_TTS
        // Пустое или неизвестное значение читается как системный TTS.
        VoiceBackend.fromId("") shouldBe VoiceBackend.SYSTEM_TTS
        VoiceBackend.fromId(null) shouldBe VoiceBackend.SYSTEM_TTS
        VoiceBackend.fromId("что-то-новое") shouldBe VoiceBackend.SYSTEM_TTS
    }

    @Test
    fun `availability follows the declared requirements`() {
        VoicePlugins.available(
            networkAvailable = false,
            systemEnginePresent = true,
        ) shouldContainExactly listOf(VoicePlugins.SYSTEM_TTS)

        VoicePlugins.available(
            networkAvailable = true,
            systemEnginePresent = false,
        ) shouldContainExactly listOf(VoicePlugins.GOOGLE_WEB, VoicePlugins.EDGE_TTS)

        VoicePlugins.available(
            networkAvailable = true,
            systemEnginePresent = true,
            hasApiKey = { it.backend == VoiceBackend.ELEVEN_API },
        ) shouldContainExactly listOf(
            VoicePlugins.SYSTEM_TTS,
            VoicePlugins.GOOGLE_WEB,
            VoicePlugins.EDGE_TTS,
            VoicePlugins.ELEVEN_API,
        )

        // Серверный движок предлагается только когда задан адрес сервера.
        VoicePlugins.available(
            networkAvailable = false,
            systemEnginePresent = true,
            hasServerAddress = { true },
        ) shouldContainExactly listOf(VoicePlugins.SYSTEM_TTS, VoicePlugins.REMOTE_TTS)

        VoicePlugins.available(
            networkAvailable = false,
            systemEnginePresent = true,
            hasServerAddress = { false },
        ) shouldContainExactly listOf(VoicePlugins.SYSTEM_TTS)
    }

    @Test
    fun `offline flags are declared for the engines that work without network`() {
        VoicePlugins.ALL.filter { it.offline }.map { it.id } shouldContainExactly
            listOf(VoiceBackend.SYSTEM_TTS.id)
        VoicePlugins.ALL.filterNot { it.offline }.map { it.id } shouldContainExactly
            listOf(
                VoiceBackend.GOOGLE_WEB.id,
                VoiceBackend.EDGE_TTS.id,
                VoiceBackend.ELEVEN_API.id,
                VoiceBackend.REMOTE_TTS.id,
            )
    }

    @Test
    fun `gender aware engines are marked so auto-voicing can use them`() {
        VoicePlugins.ALL.filter { it.supportsGender }.map { it.id } shouldContainExactly
            listOf(VoiceBackend.SYSTEM_TTS.id, VoiceBackend.REMOTE_TTS.id)
    }

    @Test
    fun `remote engine declares the server address requirement`() {
        VoicePlugins.REMOTE_TTS.backend shouldBe VoiceBackend.REMOTE_TTS
        VoicePlugins.REMOTE_TTS.requirements.contains(VoiceRequirement.SERVER_ADDRESS) shouldBe true
        // Каталог серверных голосов не пустой и совпадает с Piper-набором.
        VoicePlugins.REMOTE_VOICE_CATALOG_IDS shouldBe listOf("irina", "dmitri", "ruslan")
    }

    @Test
    fun `backend ids are exactly the values TtsSpeaker routes on`() {
        // Единственный источник истины для pref_voice_engine: если id реестра
        // разойдётся с константами маршрутизатора, озвучка молча уйдёт в
        // системный TTS.
        VoiceBackend.SYSTEM_TTS.id shouldBe TtsSpeaker.ENGINE_SYSTEM
        VoiceBackend.GOOGLE_WEB.id shouldBe TtsSpeaker.ENGINE_GOOGLE_WEB
        VoiceBackend.ELEVEN_API.id shouldBe TtsSpeaker.ENGINE_ELEVENLABS
        VoiceBackend.REMOTE_TTS.id shouldBe TtsSpeaker.ENGINE_REMOTE
    }

    @Test
    fun `a legacy engine value still resolves to the remote plugin`() {
        // На устройствах с прежней сборкой в pref_voice_engine лежит
        // "onnx" или "onnx_tts". Без legacy-разбора пользователь потерял бы
        // выбранный движок после обновления.
        VoicePlugins.byId("onnx") shouldBe VoicePlugins.REMOTE_TTS
        VoicePlugins.byId("onnx_tts") shouldBe VoicePlugins.REMOTE_TTS
        VoicePlugins.byId("  onnx_tts  ") shouldBe VoicePlugins.REMOTE_TTS
        // Мусор не должен молча превращаться в системный плагин.
        VoicePlugins.byId("несуществующий") shouldBe null
        VoicePlugins.byId("") shouldBe null
        VoicePlugins.byId(null) shouldBe null
    }

    @Test
    fun `unknown and legacy engine values fall back to the system engine`() {
        VoiceBackend.fromId("onnx_tts") shouldBe VoiceBackend.REMOTE_TTS
        VoiceBackend.fromId("  onnx_tts  ") shouldBe VoiceBackend.REMOTE_TTS
        VoiceBackend.fromId("ONNX_TTS") shouldBe VoiceBackend.SYSTEM_TTS
        VoiceBackend.fromId("plugin:my-voice") shouldBe VoiceBackend.SYSTEM_TTS
    }

    @Test
    fun `phone only reading keeps an explicitly chosen network engine`() {
        // Телефонный режим отменяет АВТОМАТИЧЕСКИЙ сетевой маршрут, но читатель,
        // выбравший онлайн-голос вручную, ждёт именно его. Раньше выбор молча
        // уходил в системный движок, и чтение требовало «установите TTS» для
        // голоса, которого на устройстве нет и быть не должно.
        resolvePlugin(engine = "google_web", phoneOnly = true) shouldBe VoicePlugins.GOOGLE_WEB
        resolvePlugin(engine = "edge_tts", phoneOnly = true) shouldBe VoicePlugins.EDGE_TTS
        resolvePlugin(engine = "eleven_api", phoneOnly = true) shouldBe VoicePlugins.ELEVEN_API
        // Автовыбор и системный движок остаются голосом телефона.
        resolvePlugin(engine = "auto", phoneOnly = true) shouldBe VoicePlugins.SYSTEM_TTS
        resolvePlugin(engine = "system_tts", phoneOnly = true) shouldBe VoicePlugins.SYSTEM_TTS
    }

    @Test
    fun `an explicit network engine wins over phone only for synthesis`() {
        val engine = { id: String, online: Boolean ->
            VoicePlugins.resolveSpeakEngine(engineId = id, phoneOnly = true, online = online)
        }
        engine("google_web", true) shouldBe TtsSpeaker.ENGINE_GOOGLE_WEB
        engine("edge_tts", true) shouldBe TtsSpeaker.ENGINE_EDGE_TTS
        engine("eleven_api", true) shouldBe TtsSpeaker.ENGINE_ELEVENLABS
        // Без сети сетевой движок всё равно недоступен — читаем системным.
        engine("edge_tts", false) shouldBe TtsSpeaker.ENGINE_SYSTEM
        // Автовыбор и системный движок телефонного режима не трогаем.
        engine("auto", true) shouldBe TtsSpeaker.ENGINE_SYSTEM
        engine("system_tts", true) shouldBe TtsSpeaker.ENGINE_SYSTEM
        engine("onnx", true) shouldBe TtsSpeaker.ENGINE_SYSTEM
    }

    @Test
    fun `a network engine is used when phone only reading is off`() {
        resolveSpeak("auto", online = true) shouldBe TtsSpeaker.ENGINE_GOOGLE_WEB
        resolveSpeak("auto", online = false) shouldBe TtsSpeaker.ENGINE_SYSTEM
        resolveSpeak("google_web", online = true) shouldBe TtsSpeaker.ENGINE_GOOGLE_WEB
        resolveSpeak("edge_tts", online = true) shouldBe TtsSpeaker.ENGINE_EDGE_TTS
        resolveSpeak("eleven_api", online = true) shouldBe TtsSpeaker.ENGINE_ELEVENLABS
        resolveSpeak("system_tts", online = true) shouldBe TtsSpeaker.ENGINE_SYSTEM
        resolveSpeak("несуществующий", online = true) shouldBe TtsSpeaker.ENGINE_SYSTEM
    }

    @Test
    fun `a network plugin is used when phone only reading is off`() {
        resolvePlugin(engine = "google_web", phoneOnly = false) shouldBe VoicePlugins.GOOGLE_WEB
        resolvePlugin(engine = "eleven_api", phoneOnly = false) shouldBe VoicePlugins.ELEVEN_API
        resolvePlugin(engine = "onnx", phoneOnly = false) shouldBe VoicePlugins.REMOTE_TTS
        // Мусор в настройках всё равно читается как системный голос.
        resolvePlugin(engine = "несуществующий", phoneOnly = false) shouldBe VoicePlugins.SYSTEM_TTS
    }

    private fun resolvePlugin(engine: String, phoneOnly: Boolean): VoicePluginDescriptor =
        VoicePlugins.resolvePlugin(engineId = engine, phoneOnly = phoneOnly)

    private fun resolveSpeak(engine: String, online: Boolean): String =
        VoicePlugins.resolveSpeakEngine(engineId = engine, phoneOnly = false, online = online)
}
