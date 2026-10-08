package eu.kanade.tachiyomi.data.books

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.tts.EdgeTts
import eu.kanade.tachiyomi.data.tts.VoiceHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.ocr.service.OcrPreferences
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Автосоздание аудиокниги.
 *
 * • Генерация задаётся ДИАПАЗОНОМ: «с главы A, предложения X — до главы B,
 *   предложения Y» (можно пропустить оглавление книги).
 * • Можно сразу склеить все главы диапазона в один файл full.mp3/wav.
 * • Состояние [state] всегда говорит КТО озвучивает (движок + голос +
 *   ролевой состав), какой этап (глава M из N, предложение K из S),
 *   сколько времени заняла каждая глава и какой у неё получился
 *   аудиохронометраж.
 * • Отмена — [cancel]; готовые файлы (listFiles) можно слушать в любой
 *   момент, пока генерация идёт или уже завершена.
 */
object AudiobookMaker {

    data class ChapterRate(val chapter: Long, val genSeconds: Long, val audioSeconds: Long)

    data class State(
        val running: Boolean = false,
        val bookTitle: String = "",
        // кто озвучивает
        val engineLabel: String = "",
        val voiceLabel: String = "",
        val rolesLine: String = "",
        // этап/цикл
        val chapter: Int = 0,
        val chaptersTotal: Int = 0,
        val segmentInChapter: Int = 0,
        val segmentsInChapter: Int = 0,
        val rangeLabel: String = "вся книга",
        // хронометраж
        val elapsedSeconds: Long = 0,
        val chapterRates: List<ChapterRate> = emptyList(),
        val logLines: List<String> = emptyList(),
        // результат
        val done: Boolean = false,
        val cancelled: Boolean = false,
        val error: String? = null,
        val outDirLabel: String = "",
        val mergedFileName: String? = null,
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

    /**
     * Запуск. Диапазон 1-базный, включительный; -1 = «с начала»/«до конца».
     * mergeOneFile=true — после каждой главы те же байты дописываются в
     * full.mp3 (полная склейка выбранного диапазона одним файлом).
     */
    fun start(
        context: Context,
        book: UniFile,
        title: String,
        fromChapter: Int = -1,
        fromSentence: Int = -1,
        toChapter: Int = -1,
        toSentence: Int = -1,
        mergeOneFile: Boolean = true,
    ) {
        if (state.running) return
        val appContext = context.applicationContext
        state = State(running = true, bookTitle = title)
        job = makerScope.launch {
            try {
                generate(
                    appContext, book, title,
                    fromChapter, fromSentence, toChapter, toSentence, mergeOneFile,
                )
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

    /** Готовые аудиофайлы книги в audiobooks/<title>/ (для прослушивания). */
    fun listFiles(context: Context, title: String): List<UniFile>? =
        runCatching { outputDir(context, title)?.listFiles()?.toList() }.getOrNull()
            ?.filter { it.isFile() && (it.name.orEmpty().endsWith(".mp3") || it.name.orEmpty().endsWith(".wav")) }
            ?.sortedBy { it.name.orEmpty() }

    /** Реальная длительность аудиофайла (секунды) через системный декодер. */
    fun audioSecondsOf(context: Context, file: UniFile): Long = runCatching {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, file.uri)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLong()?.div(1000) ?: 0L
        } finally {
            mmr.release()
        }
    }.getOrDefault(0L)

    // ---------- реализация ----------

    /** Убираем вставки «⟦иллюстрация…⟧», чтобы они не озвучивались. */
    private val markerRegex = Regex("⟦[^⟧]*⟧")

    private const val WAV_HEADER = 44

    private fun fmtTime(s: Long): String {
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private suspend fun generate(
        context: Context,
        book: UniFile,
        title: String,
        fromChapter: Int,
        fromSentence: Int,
        toChapter: Int,
        toSentence: Int,
        mergeOneFile: Boolean,
    ) {
        val prefs = runCatching { Injekt.get<OcrPreferences>() }.getOrNull()

        val parsed = withContext(Dispatchers.IO) {
            BookParser.parse(book, book.uri.toString(), context)
        }
        val textChapters = parsed.chapters.filter { !it.isPageBased && it.resolvedText.isNotBlank() }
        if (textChapters.isEmpty()) {
            throw IllegalStateException("В книге нет текстовых глав для озвучки")
        }

        // Нормализация диапазона (1-базный включительно; -1 = граница книги).
        val firstCh = if (fromChapter > 0) fromChapter else 1
        val lastCh = if (toChapter > 0) toChapter else textChapters.size
        if (firstCh > lastCh) {
            throw IllegalStateException("Диапазон пуст: «с» больше «по»")
        }
        val chFrom = (firstCh - 1).coerceAtLeast(0)
        val chTo = (lastCh - 1).coerceAtMost(textChapters.size - 1)
        val rangeLabel = if (fromChapter < 0 && toChapter < 0 && fromSentence < 0) {
            "вся книга"
        } else {
            buildString {
                append("с главы $firstCh")
                if (fromSentence > 0) append(", пред. $fromSentence")
                append(" до главы $lastCh")
                if (toSentence > 0) append(", пред. $toSentence")
            }
        }
        val selected = textChapters.subList(chFrom, chTo + 1)

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

        val engineLabel = if (isEdge) {
            "Edge TTS (онлайн, Microsoft)"
        } else {
            "Системный TTS${systemPkg.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}"
        }
        val voiceLabel = prefs?.bookVoiceLabel()?.get().orEmpty()
            .ifBlank { narratorEdge.ifBlank { "по умолчанию" } }
        val rolesLine = if (!roleVoices) {
            "ролевые голоса: выкл — один голос"
        } else {
            val c1 = prefs?.bookVoiceChar1()?.get().orEmpty().substringAfterLast("::")
                .ifBlank { "палитра" }
            val c2 = prefs?.bookVoiceChar2()?.get().orEmpty().substringAfterLast("::")
                .ifBlank { "палитра" }
            "рассказчик: $voiceLabel · персонаж 1: $c1 · персонаж 2: $c2"
        }

        val outDir = withContext(Dispatchers.IO) { outputDir(context, title) }
            ?: throw IllegalStateException("Не удалось создать папку audiobooks")
        state = state.copy(
            engineLabel = engineLabel,
            voiceLabel = voiceLabel,
            rolesLine = rolesLine,
            chaptersTotal = selected.size,
            outDirLabel = outDirName(title),
            rangeLabel = rangeLabel,
        )

        // Системный TTS (если выбран) инициализируем заранее на главном потоке.
        val systemTts = if (!isEdge) initSystemTts(context, systemPkg, systemVoiceSpec) else null
        if (!isEdge && systemTts == null) {
            throw IllegalStateException(
                "Системный TTS недоступен. Выберите движок «Edge TTS» в ⚙ читалки.",
            )
        }

        // Файл склейки, если просили «все главы одним аудио».
        val ext = if (isEdge) "mp3" else "wav"
        val mergedName = if (mergeOneFile) "full.$ext" else null
        var mergedOut: java.io.OutputStream? = null
        var mergedHeaderDone = isEdge // для wav первый заголовок пишем один раз
        if (mergedName != null) {
            mergedOut = withContext(Dispatchers.IO) {
                outDir.findFile(mergedName)?.delete()
                outDir.createFile(mergedName)?.openOutputStream()
            }
        }

        var consecutiveErrors = 0
        var firstWavOverall = true
        val startMillis = android.os.SystemClock.elapsedRealtime()
        val rates = mutableListOf<ChapterRate>()
        val logs = mutableListOf<String>()

        fun pushLog(line: String) {
            logs.add(line)
            state = state.copy(logLines = logs.takeLast(40), elapsedSeconds = (android.os.SystemClock.elapsedRealtime() - startMillis) / 1000)
        }

        try {
            selected.forEachIndexed { idx, chapter ->
                if (job?.isActive == false) throw CancellationException("отменено пользователем")
                val chapterNo = chFrom + idx + 1
                state = state.copy(chapter = idx + 1)
                val chapterStart = android.os.SystemClock.elapsedRealtime()

                var segments = BookTtsScript.split(chapter.resolvedText)
                    .map { it.copy(text = markerRegex.replace(it.text, "").trim()) }
                    .filter { it.text.isNotBlank() }
                // Граничные предложения только для первой/последней главы диапазона.
                if (idx == 0 && fromSentence > 1) {
                    segments = segments.drop((fromSentence - 1).coerceAtMost(segments.size))
                }
                if (idx == selected.size - 1 && toSentence > 0) {
                    segments = segments.take(toSentence)
                }
                state = state.copy(segmentInChapter = 0, segmentsInChapter = segments.size)
                if (segments.isEmpty()) {
                    pushLog("Глава $chapterNo: пропущена (пустые сегменты)")
                    return@forEachIndexed
                }

                val chapterBytes = java.io.ByteArrayOutputStream()
                var firstWavHeader = true
                segments.forEachIndexed { segIdx, seg ->
                    if (job?.isActive == false) throw CancellationException("отменено пользователем")
                    state = state.copy(segmentInChapter = segIdx + 1)
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

                val fileName = "chapter-%03d.%s".format(chapterNo, ext)
                val saved = withContext(Dispatchers.IO) {
                    outDir.findFile(fileName)?.delete()
                    val outFile = outDir.createFile(fileName)
                    outFile?.openOutputStream()?.use { out ->
                        chapterBytes.writeTo(out)
                        outFile
                    }
                }
                // Те же байты — в общий файл, если включена склейка.
                if (mergedOut != null && saved != null) {
                    withContext(Dispatchers.IO) {
                        if (isEdge || mergedHeaderDone) {
                            val skip = if (!isEdge) WAV_HEADER else 0
                            if (!isEdge) {
                                val raw = chapterBytes.toByteArray()
                                mergedOut!!.write(raw, skip.coerceAtMost(raw.size), raw.size - skip.coerceAtMost(raw.size))
                            } else {
                                chapterBytes.writeTo(mergedOut!!)
                            }
                        } else {
                            chapterBytes.writeTo(mergedOut!!)
                            mergedHeaderDone = true
                        }
                        mergedOut!!.flush()
                    }
                }

                // Реальный аудиохронометраж главы + время генерации.
                val audioSec = if (saved != null) {
                    withContext(Dispatchers.IO) { audioSecondsOf(context, saved) }
                } else {
                    0L
                }
                val genSec = (android.os.SystemClock.elapsedRealtime() - chapterStart) / 1000
                rates.add(ChapterRate(chapterNo.toLong(), genSec, audioSec))
                state = state.copy(chapterRates = rates.toList())
                pushLog(
                    "Глава $chapterNo: ${segments.size} сегм. · генерация ${fmtTime(genSec)}" +
                        " · аудио ${fmtTime(audioSec)}",
                )
                if (!firstWavOverall) Unit else firstWavOverall = false
            }
        } finally {
            systemTts?.shutdown()
            runCatching { mergedOut?.close() }
        }

        state = state.copy(
            running = false,
            done = true,
            mergedFileName = mergedName,
            elapsedSeconds = (android.os.SystemClock.elapsedRealtime() - startMillis) / 1000,
        )
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

    private fun outDirName(title: String): String =
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
