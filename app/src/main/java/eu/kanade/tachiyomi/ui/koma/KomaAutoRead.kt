package eu.kanade.tachiyomi.ui.koma

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * Голос для авточтения KOMA-читалки.
 *
 * Взят тот же системный синтезатор, что озвучивал страницы в прежней читалке,
 * но без её машинерии: здесь нужен один вызов «сказать строку и дождаться
 * конца». Пока строка звучит, читалка готовит следующую страницу — ожидание
 * идёт по колбэку UtteranceProgressListener, а не через sleep с угаданной
 * длиной.
 *
 * Если русский голос в системе не установлен, [speak] просто возвращается
 * сразу: авточтение тогда листает страницы молча, а не зависает навсегда.
 */
class KomaTts(context: Context) {

    /** Готов ли синтезатор и есть ли русский голос. */
    @Volatile
    var available: Boolean = false
        private set

    @Volatile
    var languageNote: String? = null
        private set

    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val ready = CompletableDeferred<Boolean>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            runCatching {
                val result = tts.setLanguage(Locale("ru", "RU"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    languageNote = "Русский голос не установлен — страницы будут листаться молча"
                } else {
                    available = true
                }
            }
        } else {
            languageNote = "Синтезатор речи недоступен на этом устройстве"
        }
        ready.complete(available)
    }.apply {
        setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    utteranceId?.let { waiting.remove(it)?.complete(Unit) }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    utteranceId?.let { waiting.remove(it)?.complete(Unit) }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    utteranceId?.let { waiting.remove(it)?.complete(Unit) }
                }
            },
        )
    }

    /** Скорость речи: 1.0 — обычная. */
    fun setRate(rate: Float) {
        runCatching { tts.setSpeechRate(rate.coerceIn(0.5f, 2.0f)) }
    }

    /**
     * Произносит строку и ждёт окончания. Таймаут страхует от синтезатора,
     * который «начал и замолчал»: без него авточтение вставало навсегда.
     */
    suspend fun speak(text: String, timeoutMs: Long = 30_000): Unit = withContext(Dispatchers.IO) {
        val line = text.trim()
        if (line.isEmpty()) return@withContext
        if (!ready.await() || !available) return@withContext
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        waiting[id] = done
        val queued = runCatching {
            tts.speak(line, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        }.getOrDefault(TextToSpeech.ERROR)
        if (queued != TextToSpeech.SUCCESS) {
            waiting.remove(id)
            return@withContext
        }
        withTimeoutOrNull(max(5_000, timeoutMs)) { done.await() }
        waiting.remove(id)
        Unit
    }

    /** Ждёт инициализации — чтобы кнопка «читать» не молчала при первом нажатии. */
    suspend fun ensureReady(timeoutMs: Long = 8_000): Boolean =
        withTimeoutOrNull(timeoutMs) { ready.await() } ?: false

    fun stop() {
        runCatching { tts.stop() }
        waiting.values.forEach { it.complete(Unit) }
        waiting.clear()
    }

    fun shutdown() {
        stop()
        runCatching { tts.shutdown() }
    }
}

/**
 * Разбивает распознанный текст страницы на реплики для озвучки.
 *
 * Строки OCR уже идут в порядке чтения, но в одном регионе может оказаться
 * несколько предложений, а дефис в конце строки — это перенос слова
 * («ВЫСО-\nЧЕСТВО»), а не тире: склеиваем, иначе голос читает «высо-чество»
 * с паузой посередине слова.
 */
fun ocrLinesForSpeech(lines: List<String>): List<String> {
    val merged = mutableListOf<String>()
    for (raw in lines) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        val previous = merged.lastOrNull()
        if (previous != null && (previous.endsWith("-") || previous.endsWith("\u00AD"))) {
            merged[merged.lastIndex] = previous.dropLast(1) + line
        } else {
            merged += line
        }
    }
    return merged
}
