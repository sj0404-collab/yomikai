package eu.kanade.tachiyomi.data.books

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.tts.EdgeTts
import eu.kanade.tachiyomi.data.tts.VoiceHelper
import tachiyomi.core.common.util.system.logcat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.ocr.service.OcrPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Автосоздание аудиокниги по кнопке: прогоняет все главы книги через
 * НАСТРОЕННЫЙ движок озвучки (Edge TTS или выбранный системный TTS) с
 * ролевыми голосами из настроек ⚙ читалки (рассказчик + персонаж 1/2) и
 * складывает результат в папку «audiobooks/<Название>»:
 * по одному аудиофайлу на главу.
 *
 * Работает одной фоновой задачей; состояние доступно Compose-экрану
 * через [state] (snapshot state). Отмена — [cancel].
 */
object AudiobookMaker {

    data class State(
        val running: Boolean = false,
        val bookTitle: String = "",
        val chapter: Int = 0,
        val chaptersTotal: Int = 0,
        val done: Boolean = false,
        val cancelled: Boolean = false,
        val error: String? = null,
        val outDirLabel: String = "",
    )

    var state by mutableStateOf(State())
        private set

    private var job: Job? = null

    fun cancel() {
        job?.cancel()
        state = state.copy(running = false, cancelled = true)
    }

    // Свой scope, чтобы генерация не умирала при свайпе «назад» с экрана книги.
    private val makerScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.Default,
    )

    /** Одновременно генерится одна аудиокнига; повторный запуск игнорируется. */
    fun start(context: Context, book: UniFile, title: String) {
        if (state.running) return
        val appContext = context.applicationContext
        state = State(running = true, bookTitle = title)
        job = makerScope.launch {
            try {
                generate(appContext, book, title)
            } catch (e: CancellationException) {
                state = state.copy(running = false, cancelled = true)
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "AudiobookMaker: сбой генерации" }
                state = state.copy(
                    running = false,
                    error = e.message ?: "Неизвестная ошибка",
                )
            }
        }
    }

    // ---------- реализация ----------

    /** Убираем вставки «⟦иллюстрация…⟧», чтобы они не озвучивались. */
    private val markerRegex = Regex("⟦[^⟧]*⟧")

    private const val WAV_HEADER = 44

    private suspend fun generate(context: Context, book: UniFile, title: String) {
        val prefs = runCatching { Injekt.get<OcrPreferences>() }.getOrNull()

        val parsed = withContext(Dispatchers.IO) {
            BookParser.parse(book, book.uri.toString(), context)
        }
        val textChapters = parsed.chapters.filter { !it.isPageBased && it.resolvedText.isNotBlank() }
        if (textChapters.isEmpty()) {
            throw IllegalStateException("В книге нет текстовых глав для озвучки")
        }

        val isEdge = prefs?.bookTtsEngine()?.get() == "edge"
        val roleVoices = prefs?.bookRoleVoices()?.get() ?: true
        val speechRate = prefs?.bookSpeechRate()?.get() ?: 1.0f
        val pitch = prefs?.bookSpeechPitch()?.get() ?: 1.0f
        val narratorEdge = if (isEdge) {
            prefs?.bookVoiceSpec()?.get().orEmpty().takeIf { it.isNotBlank() }
                ?: EdgeTts.DEFAULT_VOICE
        } else {
            ""
        }
        val char1Spec = prefs?.bookVoiceChar1()?.get().orEmpty()
        val char2Spec = prefs?.bookVoiceChar2()?.get().orEmpty()
        val systemPkg = prefs?.systemTtsEngine()?.get().orEmpty()
        val systemVoiceSpec = prefs?.bookVoiceSpec()?.get().orEmpty()

        val outDir = withContext(Dispatchers.IO) { outputDir(context, title) }
            ?: throw IllegalStateException("Не удалось создать папку audiobooks")
        state = state.copy(
            chaptersTotal = textChapters.size,
            outDirLabel = outDirName(context, title),
        )

        // Системный TTS (если выбран) инициализируем заранее на главном потоке.
        val systemTts = if (!isEdge) initSystemTts(context, systemPkg, systemVoiceSpec) else null
        if (!isEdge && systemTts == null) {
            throw IllegalStateException(
                "Системный TTS недоступен. Выберите движок «Edge TTS» в ⚙ читалки.",
            )
        }

        var consecutiveErrors = 0
        try {
            textChapters.forEachIndexed { idx, chapter ->
                if (job?.isActive == false) throw CancellationException("отменено пользователем")
                state = state.copy(chapter = idx + 1)
                val segments = BookTtsScript.split(chapter.resolvedText)
                    .map { it.copy(text = markerRegex.replace(it.text, "").trim()) }
                    .filter { it.text.isNotBlank() }
                if (segments.isEmpty()) return@forEachIndexed

                val chapterBytes = java.io.ByteArrayOutputStream()
                var firstWavHeader = true
                for (seg in segments) {
                    if (job?.isActive == false) throw CancellationException("отменено пользователем")
                    if (isEdge) {
                        val voice = pickEdgeVoice(seg, roleVoices, narratorEdge, char1Spec, char2Spec)
                        val ratePercent = ((speechRate - 1.0f) * 100).toInt()
                        val baseHz = ((pitch - 1.0f) * 100).toInt()
                        val roleHz = if (roleVoices) {
                            BookTtsScript.edgePitchHzFor(seg.role, seg.speaker)
                        } else {
                            0
                        }
                        val file = runCatching {
                            EdgeTts.synthesizeToFile(context, seg.text, voice, ratePercent, baseHz + roleHz)
                        }.getOrNull()
                        if (file == null) {
                            consecutiveErrors++
                            if (consecutiveErrors >= 5) {
                                throw IllegalStateException(
                                    "Edge TTS не отвечает (5 ошибок подряд — проверьте сеть)",
                                )
                            }
                        } else {
                            consecutiveErrors = 0
                            chapterBytes.write(file.readBytes())
                            file.delete()
                        }
                    } else {
                        val tts = systemTts!!
                        val factor = if (roleVoices) {
                            BookTtsScript.systemPitchFactorFor(seg.role, seg.speaker)
                        } else {
                            1.0f
                        }
                        val bytes = synthesizeWav(context, tts, seg.text, pitch * factor)
                        if (bytes != null) {
                            if (firstWavHeader) {
                                chapterBytes.write(bytes)
                                firstWavHeader = false
                            } else {
                                val skip = WAV_HEADER.coerceAtMost(bytes.size)
                                chapterBytes.write(bytes, skip, bytes.size - skip)
                            }
                        }
                    }
                }

                val ext = if (isEdge) "mp3" else "wav"
                val fileName = "chapter-%03d.%s".format(idx + 1, ext)
                val written = withContext(Dispatchers.IO) {
                    outDir.findFile(fileName)?.delete()
                    val outFile = outDir.createFile(fileName)
                    outFile?.openOutputStream()?.use { out ->
                        chapterBytes.writeTo(out)
                    } != null
                }
                if (!written) {
                    logcat(LogPriority.WARN) { "AudiobookMaker: не удалось записать $fileName" }
                }
            }
        } finally {
            systemTts?.shutdown()
        }

        state = state.copy(running = false, done = true)
    }

    private fun pickEdgeVoice(
        seg: BookSpeechSegment,
        roleVoices: Boolean,
        narrator: String,
        char1: String,
        char2: String,
    ): String {
        if (!roleVoices || seg.role != BookSpeechRole.CHARACTER) return narrator
        val picked = if (seg.speaker % 2 == 0) char1 else char2
        return picked.takeIf { it.isNotBlank() && !it.contains("::") }
            ?: BookTtsScript.edgeVoiceFor(seg.role, seg.speaker, narrator)
    }

    /** Синтез одного сегмента системным TTS в WAV-байты (ждём onDone). */
    private suspend fun synthesizeWav(
        context: Context,
        tts: TextToSpeech,
        text: String,
        pitchFactor: Float,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "abmk_${System.nanoTime()}.wav")
        val utteranceId = "abmk_${System.nanoTime()}"
        val done = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            runCatching {
                tts.setPitch(pitchFactor.coerceIn(0.5f, 2.0f))
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    @Deprecated("deprecated")
                    override fun onError(id: String?) {
                        done.complete(false)
                    }
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(id: String?) {
                        if (id == utteranceId) done.complete(true)
                    }
                })
                tts.synthesizeToFile(text, Bundle(), tmp, utteranceId)
            }.onFailure { done.complete(false) }
        }
        val ok = done.await()
        if (!ok || !tmp.isFile) {
            tmp.delete()
            return@withContext null
        }
        val bytes = tmp.readBytes()
        tmp.delete()
        bytes.takeIf { it.size > WAV_HEADER }
    }

    private suspend fun initSystemTts(
        context: Context,
        pkg: String,
        voiceSpec: String,
    ): TextToSpeech? = withContext(Dispatchers.Main) {
        val ready = CompletableDeferred<Boolean>()
        val tts = if (pkg.isNotBlank()) {
            TextToSpeech(context, { ready.complete(it == TextToSpeech.SUCCESS) }, pkg)
        } else {
            TextToSpeech(context) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
        }
        val ok = ready.await()
        if (!ok) {
            tts.shutdown()
            return@withContext null
        }
        runCatching {
            VoiceHelper.prepareForLanguage(tts, "ru")
            val voiceName = voiceSpec.substringAfter("::").takeIf { it.isNotBlank() }
            if (voiceName != null) {
                tts.voices?.firstOrNull { it.name == voiceName }?.let { tts.voice = it }
            }
        }
        tts
    }

    private fun outDirName(context: Context, title: String): String =
        "audiobooks/${sanitize(title)}"

    private fun sanitize(title: String): String =
        title.replace(Regex("[/\\\\:<>|?*\"]"), "_").take(80).ifBlank { "audiobook" }

    /** Папка «audiobooks» внутри каталога книг; внутри — подпапка с названием книги. */
    private fun outputDir(context: Context, title: String): UniFile? {
        return runCatching {
            val books = BooksStore.booksDirectory(context) ?: return@runCatching null
            val root = books.findFile("audiobooks") ?: books.createDirectory("audiobooks")
            root?.let { r ->
                r.findFile(sanitize(title)) ?: r.createDirectory(sanitize(title))
            }
        }.getOrNull() ?: run {
            val f = File(context.filesDir, "audiobooks/${sanitize(title)}")
            if (!f.exists()) f.mkdirs()
            UniFile.fromFile(f)
        }
    }
}
