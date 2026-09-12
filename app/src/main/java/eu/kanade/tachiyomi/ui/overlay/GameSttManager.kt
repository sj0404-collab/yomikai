package eu.kanade.tachiyomi.ui.overlay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import eu.kanade.tachiyomi.data.tts.AutoReadEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mihon.data.ocr.MangaTranslatorService
import mihon.domain.ocr.service.OcrPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * STT для игр в реальном времени: английская речь (только голоса, без
 * текста) → текст → перевод → русские голоса.
 *
 * Системный [SpeechRecognizer] с частичными результатами (partial — сразу
 * в бабл, final — перевод через [MangaTranslatorService] и озвучка через
 * [AutoReadEngine], тот же движок, что в читалке). Слушает непрерывно:
 * после конца фразы/ошибки перезапускается, пока не вызовут [stop].
 * Требует RECORD_AUDIO (запрашивается из настроек).
 */
class GameSttManager(
    private val appContext: Context,
    private val prefs: OcrPreferences = Injekt.get(),
    private val readEngine: AutoReadEngine,
) {

    /** Частичный результат — показать в бабле, не озвучивать. */
    var onPartial: ((String) -> Unit)? = null

    /** Финальный текст (уже переведённый, если перевод включён). */
    var onFinal: ((String) -> Unit)? = null

    var onError: ((String) -> Unit)? = null
    var onListeningChanged: ((Boolean) -> Unit)? = null

    val isListening: Boolean get() = listening

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var wantListening = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun start() {
        if (wantListening) return
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onError?.invoke("Распознавание речи недоступно на устройстве")
            return
        }
        wantListening = true
        ensureRecognizer()
        listenOnce()
    }

    fun stop() {
        wantListening = false
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel() }
        setListening(false)
    }

    fun destroy() {
        stop()
        scope.cancel()
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun ensureRecognizer() {
        if (recognizer != null) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
            setRecognitionListener(listener)
        }
    }

    private fun listenOnce() {
        if (!wantListening) return
        val recognizer = recognizer ?: run {
            ensureRecognizer()
            recognizer ?: return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, prefs.sttSourceLang().get().ifBlank { "en-US" })
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }
        runCatching { recognizer.startListening(intent) }.onFailure {
            scheduleRestart(800)
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!wantListening) return
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({ if (wantListening) listenOnce() }, delayMs)
    }

    private fun setListening(value: Boolean) {
        if (listening == value) return
        listening = value
        onListeningChanged?.invoke(value)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            setListening(true)
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            setListening(false)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (text.isNotBlank()) onPartial?.invoke(text)
        }

        override fun onResults(results: Bundle?) {
            setListening(false)
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (text.isBlank()) {
                scheduleRestart(300)
                return
            }
            scope.launch {
                val spoken = runCatching {
                    if (prefs.sttTranslate().get()) {
                        val src = prefs.sttSourceLang().get().substringBefore('-').ifBlank { "auto" }
                        MangaTranslatorService.translate(text, targetLang = "ru", sourceLang = src)
                            .takeIf { it.isNotBlank() } ?: text
                    } else {
                        text
                    }
                }.getOrDefault(text)
                val gender = prefs.gameVoiceGender().get().takeIf { it != "auto" }
                readEngine.speakSingle(spoken, gender)
                onFinal?.invoke(spoken)
                scheduleRestart(300)
            }
        }

        override fun onError(error: Int) {
            setListening(false)
            when (error) {
                android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    wantListening = false
                    onError?.invoke("Нет разрешения на микрофон")
                    return
                }
                android.speech.SpeechRecognizer.ERROR_CLIENT,
                android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                -> {
                    // Пересоздаём распознаватель — иначе виснет в BUSY.
                    runCatching { recognizer?.destroy() }
                    recognizer = null
                    ensureRecognizer()
                }
                else -> Unit
            }
            scheduleRestart(600)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
