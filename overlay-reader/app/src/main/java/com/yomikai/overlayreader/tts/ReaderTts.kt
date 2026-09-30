package com.yomikai.overlayreader.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.yomikai.overlayreader.Prefs
import com.yomikai.overlayreader.ocr.OcrRegion
import java.util.Locale

/**
 * Озвучка распознанного текста с «тоном и тембром» по типу реплики.
 *
 * Тон подбирается эвристиками по тексту и расположению:
 *  - восклицание/крик (все заглавные, «!») — выше тон, быстрее;
 *  - вопрос («?») — вопросительная интонация;
 *  - «…»/многоточие — медленнее и тише;
 *  - верх реплик vs низ кадра — циклический перебор голосов, чтобы разные
 *    персонажи звучали по-разному (разные «тембры»).
 *
 * Голосов несколько (per-Voice из Android TTS), если они установлены.
 */
class ReaderTts(private val context: Context) {

    interface ReadyCallback {
        fun onReady(ready: Boolean)
    }

    private var tts: TextToSpeech? = null
    private var voices: List<android.speech.tts.Voice> = emptyList()
    private var ready = false

    fun init(callback: ReadyCallback?) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                tts?.language = Locale("ru", "RU")
                voices = tts?.voices?.filter { it.locale.language == "ru" || it.locale.language == "en" }
                    ?.ifEmpty { tts?.voices?.toList() }
                    ?: emptyList()
                applyVoiceIndex(Prefs.ttsVoiceIndex())
            }
            callback?.onReady(ready)
        }
    }

    val isReady: Boolean get() = ready && tts != null

    fun availableVoiceCount(): Int = voices.size

    fun cycleVoice(): Int {
        if (voices.isEmpty()) return 0
        val current = Prefs.ttsVoiceIndex()
        val next = (current + 1) % voices.size
        Prefs.setTtsVoiceIndex(next)
        applyVoiceIndex(next)
        return next
    }

    private fun applyVoiceIndex(index: Int) {
        if (voices.isEmpty() || index < 0 || index >= voices.size) return
        tts?.voice = voices[index]
    }

    /** Озвучить весь результат: реплики по очереди, каждая в своём тоне/голосе. */
    fun speak(regions: List<OcrRegion>) {
        if (!ready || regions.isEmpty()) return
        if (regions.size == 1) {
            speakOne(regions[0], voiceOffset = 0, mode = TextToSpeech.QUEUE_FLUSH)
            return
        }
        // Несколько реплик: разные голоса (тембры) по очереди.
        for ((index, region) in regions.withIndex()) {
            val voiceOffset = if (voices.size > 1) index % voices.size else 0
            if (index == 0) {
                speakOne(region, voiceOffset, TextToSpeech.QUEUE_FLUSH)
            } else {
                speakOne(region, voiceOffset, TextToSpeech.QUEUE_ADD)
            }
        }
    }

    private fun speakOne(region: OcrRegion, voiceOffset: Int, mode: Int) {
        val t = tts ?: return
        val text = region.text.trim()
        if (text.isBlank()) return

        applyVoiceIndex((Prefs.ttsVoiceIndex() + voiceOffset) % voices.size.coerceAtLeast(1))

        val (pitch, rate) = toneFor(text)
        t.setPitch(pitch)
        t.setSpeechRate(rate)
        t.speak(text, mode, null, "region_${hashCode()}_${System.nanoTime()}")
    }

    /** Высота и скорость по типу реплики. */
    private fun toneFor(text: String): Pair<Float, Float> {
        val upperCount = text.count { it.isUpperCase() }
        val lowerCount = text.count { it.isLowerCase() }
        val allCaps = upperCount >= 3 && lowerCount < upperCount
        return when {
            text.contains('!') || allCaps -> 1.35f to 1.12f
            text.contains('?') -> 1.2f to 0.98f
            text.contains('…') || text.contains("...") -> 0.92f to 0.85f
            else -> 1.0f to 1.0f
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}