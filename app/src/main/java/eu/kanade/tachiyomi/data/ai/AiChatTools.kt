package eu.kanade.tachiyomi.data.ai

import android.content.Context
import eu.kanade.tachiyomi.data.tts.EdgeTts
import eu.kanade.tachiyomi.data.tts.TtsSpeaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.domain.ocr.service.OcrPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Инструменты AI-чата: генерация звука и работа с голосами озвучки.
 *
 * Тот же принцип, что у [AiReaderTools]: инструменты читают те же настройки,
 * которые видит пользователь (голос — `OcrPreferences.edgeVoice`), а файлы
 * создаются внутри workspace (`audio/`), поэтому результат сразу появляется
 * в чате чипом файла с размером и кнопкой «Отправить».
 */
object AiChatTools {

    const val TOOL_RENDER_AUDIO = "render_audio"
    const val TOOL_VOICE_LIST = "voice_list"
    const val TOOL_VOICE_PREVIEW = "voice_preview"
    const val TOOL_VOICE_SET = "voice_set"
    const val TOOL_GEN_VIDEO = "gen_video"

    val TOOL_NAMES = listOf(
        TOOL_RENDER_AUDIO,
        TOOL_VOICE_LIST,
        TOOL_VOICE_PREVIEW,
        TOOL_VOICE_SET,
        TOOL_GEN_VIDEO,
    )

    /** Документация инструментов для системного промпта агента. */
    val SYSTEM_PROMPT_LINES = listOf(
        "@tool render_audio {\"text\":\"текст\",\"voice\":\"ru-RU-SvetlanaNeural\",\"name\":\"имя.mp3\"} — озвучить текст голосом в файл MP3 (workspace/audio/), файл появится в чате готовым",
        "@tool voice_list {} — список голосов Edge TTS и какой сейчас выбран",
        "@tool voice_preview {\"voice\":\"ru-RU-DmitryNeural\"} — проиграть пробу голоса, чтобы пользователь послушал",
        "@tool voice_set {\"voice\":\"ru-RU-DmitryNeural\"} — сменить голос озвучки приложения на указанный",
        "@tool gen_video {\"text\":\"заголовок\",\"images\":[\"https://image.pollinations.ai/prompt/a%20cat\"],\"fps\":3,\"name\":\"видео.mp4\"} — собрать видео на GitHub-ранере (нужен PAT-токен в настройках вкладки AI): slideshow из images или текстовая анимация; файл появится в чате готовым",
    )

    /** Короткий каталог голосов Edge TTS для `voice_list` (без сети). */
    private val edgeCatalog = listOf(
        "ru-RU-SvetlanaNeural (рус, жен., тёплый)",
        "ru-RU-DmitryNeural (рус, муж.)",
        "ru-RU-DariyaNeural (рус, жен., спокойный)",
        "ru-RU-PavelNeural (рус, муж., спокойный)",
        "ru-RU-ElinaNeural (рус, жен.)",
        "ru-RU-DenisNeural (рус, муж.)",
        "ru-RU-IrinaNeural (рус, жен., повествование)",
        "ru-RU-MaximNeural (рус, муж., повествование)",
        "uk-UA-PolinaNeural (укр, жен.)",
        "uk-UA-OstapNeural (укр, муж.)",
        "en-US-JennyNeural (англ, жен.)",
        "en-US-GuyNeural (англ, муж.)",
    )

    private const val DEFAULT_VOICE = "ru-RU-SvetlanaNeural"

    data class RenderOutcome(val output: String, val file: File? = null)

    private fun prefs(): OcrPreferences = Injekt.get()

    /** Что озвучивается сейчас и какие голоса можно попробовать. */
    fun voiceList(context: Context): String {
        val current = prefs().edgeVoice().get().ifBlank { DEFAULT_VOICE }
        return buildString {
            appendLine("Текущий голос озвучки (Edge): «$current»")
            appendLine()
            appendLine("Каталог голосов Edge TTS:")
            edgeCatalog.forEach { appendLine("• $it") }
            append("Проба: voice_preview {\"voice\":\"имя\"}; смена: voice_set {\"voice\":\"имя\"}")
        }
    }

    /** Проиграть пробу голоса прямо сейчас (файл не создаётся). */
    fun previewVoice(context: Context, voice: String?): String {
        val v = voice?.takeIf { it.isNotBlank() } ?: prefs().edgeVoice().get().ifBlank { DEFAULT_VOICE }
        TtsSpeaker.speakEdgeVoiceTest(context, v)
        return "Играет проба голоса «$v»: «Привет! Это тест голоса $v.»"
    }

    /** Запомнить выбранный голос в настройках озвучки. */
    fun voiceSet(voice: String): String {
        val v = voice.trim()
        when {
            v.isEmpty() -> return "ОШИБКА: пустое имя голоса (пример: ru-RU-DmitryNeural)"
            !v.contains("-") -> return "ОШИБКА: имя голоса должно содержать дефис (пример: ru-RU-DmitryNeural)"
            else -> {
                prefs().edgeVoice().set(v)
                return "Голос озвучки сменён на «$v». Проверка: voice_preview {\"voice\":\"$v\"}, статус: tts_status"
            }
        }
    }

    /** Озвучить текст в MP3-файл внутри workspace (`audio/`). Телефон сам не играет. */
    suspend fun renderAudio(
        context: Context,
        text: String,
        voice: String?,
        nameHint: String?,
    ): RenderOutcome = withContext(Dispatchers.IO) {
        val clean = text.trim()
        if (clean.isEmpty()) {
            return@withContext RenderOutcome("ОШИБКА: пустой text")
        }
        val v = voice?.takeIf { it.isNotBlank() } ?: prefs().edgeVoice().get().ifBlank { DEFAULT_VOICE }
        val synthesized = EdgeTts.synthesizeToFile(context, clean, v, useCache = false)
        if (synthesized == null) {
            return@withContext RenderOutcome("ОШИБКА: синтез не удался (нет сети или сервер Edge не ответил)")
        }
        if (synthesized.length() < 1024) {
            return@withContext RenderOutcome("ОШИБКА: файл получился пустым — проверьте текст и голос")
        }
        val base = (nameHint ?: "voice_${System.currentTimeMillis() / 1000}").let(::sanitizeName)
        val mp3 = if (base.endsWith(".mp3", true)) base else "$base.mp3"
        val dest = AiWorkspace.resolve(context, "audio/$mp3")
        if (dest == null) {
            return@withContext RenderOutcome("ОШИБКА: некорректное имя файла: $nameHint")
        }
        runCatching {
            dest.parentFile?.mkdirs()
            synthesized.copyTo(dest, overwrite = true)
        }.onFailure {
            return@withContext RenderOutcome("ОШИБКА: не удалось записать файл: ${it.message?.take(100)}")
        }
        RenderOutcome("Аудио готово: audio/$mp3 (${dest.length() / 1024} КБ, голос «$v»)", dest)
    }

    /**
     * Собрать видео на GitHub-ранере (video-runner.yml, нужен PAT-токен):
     * текстовая анимация или слайд-шоу из [images]-URL. Готовый mp4 кладётся
     * в workspace (`videos/`) и появляется в чате готовым.
     */
    suspend fun renderVideo(
        context: Context,
        images: List<String>,
        text: String,
        fps: Int,
        nameHint: String?,
        onStatus: (String) -> Unit = {},
    ): RenderOutcome = withContext(Dispatchers.IO) {
        val result = RunnerLlm.startVideo(
            context,
            RunnerLlm.VideoRequest(
                mode = if (images.isNotEmpty()) "slideshow" else "text",
                text = text,
                images = images,
                fps = fps,
            ),
            onStatus,
        )
        if (result == null) {
            return@withContext RenderOutcome("ОШИБКА: видео не создано — нужен GitHub-токен (⚙ вкладки AI) или сеть не дала связаться с ранером")
        }
        val base = (nameHint ?: "video_${System.currentTimeMillis() / 1000}").let(::sanitizeName)
        val mp4name = if (base.endsWith(".mp4", true)) base else "$base.mp4"
        val dest = AiWorkspace.resolve(context, "videos/$mp4name")
        if (dest == null) {
            return@withContext RenderOutcome("ОШИБКА: некорректное имя файла: $nameHint")
        }
        runCatching {
            dest.parentFile?.mkdirs()
            result.file.copyTo(dest, overwrite = true)
        }.onFailure {
            return@withContext RenderOutcome("ОШИБКА: не удалось записать файл: ${it.message?.take(100)}")
        }
        RenderOutcome("Видео готово: videos/$mp4name (${dest.length() / 1024} КБ)", dest)
    }

    /** Только безопасные для файловой системы символы. */
    private fun sanitizeName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .replace(Regex("[^A-Za-zА-Яа-яЁё0-9._-]"), "_")
            .trim('_', '.')
            .take(64)
            .ifBlank { "voice_${System.currentTimeMillis() / 1000}" }
}