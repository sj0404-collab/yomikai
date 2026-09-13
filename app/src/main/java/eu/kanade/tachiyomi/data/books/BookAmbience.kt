package eu.kanade.tachiyomi.data.books

import android.content.Context
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Процедурный фоновый «саундтрек-эмбиент» под настроение книги. Без внешних
 * аудио-файлов: трёхаккордовый пэд синтезируется в коде и пишется в кэш как
 * 16-bit PCM WAV-петля, которую читалка крутит MediaPlayer'ом на фоне озвучки.
 * Настроение определяется по жанру/заголовку книги. Вся генерация обёрнута в
 * runCatching — музыка никогда не роняет приложение.
 */
object BookAmbience {

    enum class Mood { NEUTRAL, CALM, DARK, BRIGHT }

    private const val SAMPLE_RATE = 22050
    private const val LOOP_SEC = 14
    private const val CHORD_SEC = 3.5
    private const val NOTE_GAIN = 0.045

    /** Определяет настроение по жанру/описанию книги. */
    fun moodFor(keywords: String?): Mood {
        if (keywords.isNullOrBlank()) return Mood.NEUTRAL
        val k = keywords.lowercase()
        val dark = listOf(
            "мистик", "детектив", "триллер", "ужас", "тьм", "мрак", "тень", "мёртв",
            "смерт", "кримина", "нуар", "тёмн", "страх", "кошмар", "вампир",
            "некром", "ритуал", "проклять", "кровь", "безум",
        )
        val bright = listOf(
            "сказ", "фэнтез", "приключ", "путешеств", "свет", "радуг", "весел",
            "дружб", "волшебн", "маг", "дракон", "рыцар", "детск", "мечт", "смех",
        )
        val calm = listOf(
            "роман", "любов", "нежн", "спокойн", "тих", "домашн", "семь", "мирн",
            "раздум", "мудр", "старо", "груст", "осен", "дождь", "море", "нозалг", "ностальг",
        )
        val d = dark.count { k.contains(it) }
        val b = bright.count { k.contains(it) }
        val c = calm.count { k.contains(it) }
        return when {
            d >= b && d >= c && d > 0 -> Mood.DARK
            b > c && b > 0 -> Mood.BRIGHT
            c > 0 -> Mood.CALM
            else -> Mood.NEUTRAL
        }
    }

    /** Возвращает WAV-файл петли для настроения (генерирует и кэширует). null при ошибке. */
    fun fileFor(context: Context, mood: Mood): File? {
        val dir = File(context.cacheDir, "book_ambience")
        if (!dir.exists()) runCatching { dir.mkdirs() }
        val f = File(dir, "amb_${mood.name.lowercase()}.wav")
        if (f.exists() && f.length() > 44L) return f
        return runCatching {
            if (!f.exists()) f.outputStream().use { it.write(generateWav(mood)) }
            f
        }.onFailure { e ->
            logcat(LogPriority.WARN, e) { "BookAmbience: generation failed" }
        }.getOrNull()
    }

    /** 16-bit mono PCM WAV, ~[LOOP_SEC] сек, с атакой/затуханием для мягкой петли. */
    fun generateWav(mood: Mood): ByteArray {
        val chords = CHORDS[mood] ?: CHORDS[Mood.NEUTRAL]!!
        val totalSamples = LOOP_SEC * SAMPLE_RATE
        val chordSamples = (CHORD_SEC * SAMPLE_RATE).toInt()
        val buf = DoubleArray(totalSamples)

        chords.forEachIndexed { ci, notes ->
            val start = ci * chordSamples
            for (f in notes) {
                for (i in 0 until chordSamples) {
                    val idx = start + i
                    if (idx >= totalSamples) break
                    val t = i.toDouble() / SAMPLE_RATE
                    var v = sin(2 * PI * f * t) + 0.3 * sin(2 * PI * f * 2 * t)
                    if (mood == Mood.DARK) v += 0.2 * sin(2 * PI * f * 0.5 * t)
                    buf[idx] += v * noteEnv(i, chordSamples) * NOTE_GAIN
                }
            }
        }

        // Медленный «дыхательный» LFO + глобальное затухание краёв петли.
        val fadeSamples = (1.3 * SAMPLE_RATE).toInt()
        var peak = 0.0
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / SAMPLE_RATE
            val lfo = 0.86 + 0.14 * sin(2 * PI * 0.09 * t)
            val fadeIn = i / fadeSamples.toDouble()
            val fadeOut = (totalSamples - 1 - i) / fadeSamples.toDouble()
            val env = minOf(1.0, fadeIn, fadeOut)
            buf[i] = buf[i] * lfo * env
            peak = max(peak, abs(buf[i]))
        }

        val norm = if (peak > 1e-9) 0.8 / peak else 1.0
        val data = ByteArray(totalSamples * 2)
        var di = 0
        for (i in 0 until totalSamples) {
            val s = (buf[i] * norm * 32767.0).roundToInt().coerceIn(-32768, 32767)
            data[di++] = (s and 0xFF).toByte()
            data[di++] = ((s shr 8) and 0xFF).toByte()
        }
        return wavHeader(totalSamples) + data
    }

    private fun noteEnv(i: Int, chordSamples: Int): Double {
        // Плавное начало и скромное затухание к концу аккорда (без щелчков).
        val attack = (0.4 * SAMPLE_RATE).toInt()
        val release = (0.8 * SAMPLE_RATE).toInt()
        val rise = if (i < attack) i.toDouble() / attack else 1.0
        val fall = if (i > chordSamples - release) {
            (chordSamples - 1 - i).toDouble() / release
        } else 1.0
        return minOf(rise, fall).coerceIn(0.0, 1.0)
    }

    private fun wavHeader(sampleCount: Int): ByteArray {
        val byteRate = SAMPLE_RATE * 2
        val dataSize = sampleCount * 2
        val out = ByteArray(44)
        fun putInt(off: Int, value: Int) {
            out[off] = (value and 0xFF).toByte()
            out[off + 1] = ((value shr 8) and 0xFF).toByte()
            out[off + 2] = ((value shr 16) and 0xFF).toByte()
            out[off + 3] = ((value shr 24) and 0xFF).toByte()
        }
        fun putShort(off: Int, value: Int) {
            out[off] = (value and 0xFF).toByte()
            out[off + 1] = ((value shr 8) and 0xFF).toByte()
        }
        out[0] = 'R'.code.toByte(); out[1] = 'I'.code.toByte()
        out[2] = 'F'.code.toByte(); out[3] = 'F'.code.toByte()
        putInt(4, 36 + dataSize)
        out[8] = 'W'.code.toByte(); out[9] = 'A'.code.toByte()
        out[10] = 'V'.code.toByte(); out[11] = 'E'.code.toByte()
        out[12] = 'f'.code.toByte(); out[13] = 'm'.code.toByte()
        out[14] = 't'.code.toByte(); out[15] = ' '.code.toByte()
        putInt(16, 16)
        putShort(20, 1)
        putShort(22, 1)
        putInt(24, SAMPLE_RATE)
        putInt(28, byteRate)
        putShort(32, 2)
        putShort(34, 16)
        out[36] = 'd'.code.toByte(); out[37] = 'a'.code.toByte()
        out[38] = 't'.code.toByte(); out[39] = 'a'.code.toByte()
        putInt(40, dataSize)
        return out
    }

    private val CHORDS: Map<Mood, Array<DoubleArray>> = mapOf(
        Mood.NEUTRAL to arrayOf(
            doubleArrayOf(220.0, 261.63, 329.63),
            doubleArrayOf(164.81, 196.0, 246.94),
            doubleArrayOf(261.63, 329.63, 392.0),
            doubleArrayOf(196.0, 246.94, 293.66),
        ),
        Mood.CALM to arrayOf(
            doubleArrayOf(220.0, 261.63, 329.63, 440.0),
            doubleArrayOf(174.61, 220.0, 261.63, 349.23),
            doubleArrayOf(261.63, 329.63, 392.0, 523.25),
            doubleArrayOf(196.0, 246.94, 293.66, 392.0),
        ),
        Mood.DARK to arrayOf(
            doubleArrayOf(146.83, 174.61, 220.0, 293.66),
            doubleArrayOf(116.54, 174.61, 233.08, 349.23),
            doubleArrayOf(174.61, 220.0, 261.63, 349.23),
            doubleArrayOf(220.0, 261.63, 329.63, 440.0),
        ),
        Mood.BRIGHT to arrayOf(
            doubleArrayOf(261.63, 329.63, 392.0, 523.25),
            doubleArrayOf(196.0, 246.94, 293.66, 392.0),
            doubleArrayOf(220.0, 261.63, 329.63, 440.0),
            doubleArrayOf(174.61, 220.0, 261.63, 349.23),
        ),
    )
}