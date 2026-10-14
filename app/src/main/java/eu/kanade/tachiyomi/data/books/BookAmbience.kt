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

    /** Сдвиг частоты на [semis] полутонов вверх (вниз при отрицательном). */
    private fun transpose(freq: Double, semis: Int): Double =
        freq * Math.pow(2.0, semis / 12.0)

    /**
     * Огибающая одного такта, сведённая с огибающей всей петли.
     * Такт приходит и уходит плавно, а края петли гаснут — иначе зацикливание
     * даёт щелчок на стыке.
     */
    private fun slotEnv(
        iInSlot: Int,
        slotSamples: Int,
        totalSamples: Int,
        i: Int,
    ): Double {
        val cross = (0.6 * SAMPLE_RATE).toInt().coerceAtLeast(1)
        val slotAttack = (iInSlot.toDouble() / minOf(cross, slotSamples)).coerceAtMost(1.0)
        val slotRelease = ((slotSamples - 1 - iInSlot).toDouble() / minOf(cross, slotSamples))
            .coerceAtMost(1.0)
        val edge = (1.3 * SAMPLE_RATE).toInt().coerceAtLeast(1)
        val loopIn = (i.toDouble() / edge).coerceAtMost(1.0)
        val loopOut = ((totalSamples - 1 - i).toDouble() / edge).coerceAtMost(1.0)
        return minOf(slotAttack, slotRelease, loopIn, loopOut).coerceIn(0.0, 1.0)
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

    // --- 15 дополнительных типов эмбиента для разных ситуаций ---

    enum class AmbienceType {
        NEUTRAL, CALM, DARK, BRIGHT,
        RAIN, FIREPLACE, NIGHT, WIND, OCEAN, FOREST,
        CITY, COFFEE_SHOP, THUNDERSTORM, SNOW, LIBRARY,
        TEMPLE, BATTLE, ROMANCE, MYSTERY,
    }

    /** Человекочитаемое имя типа эмбиента. */
    fun AmbienceType.label(): String = when (this) {
        AmbienceType.NEUTRAL -> "Нейтральный"
        AmbienceType.CALM -> "Спокойный"
        AmbienceType.DARK -> "Мрачный"
        AmbienceType.BRIGHT -> "Светлый"
        AmbienceType.RAIN -> "Дождь"
        AmbienceType.FIREPLACE -> "Камин"
        AmbienceType.NIGHT -> "Ночь"
        AmbienceType.WIND -> "Ветер"
        AmbienceType.OCEAN -> "Океан"
        AmbienceType.FOREST -> "Лес"
        AmbienceType.CITY -> "Город"
        AmbienceType.COFFEE_SHOP -> "Кофейня"
        AmbienceType.THUNDERSTORM -> "Гроза"
        AmbienceType.SNOW -> "Снег"
        AmbienceType.LIBRARY -> "Библиотека"
        AmbienceType.TEMPLE -> "Храм"
        AmbienceType.BATTLE -> "Битва"
        AmbienceType.ROMANCE -> "Романтика"
        AmbienceType.MYSTERY -> "Тайна"
    }

    /**
     * Параметры генерации для каждого типа.
     *
     * [progression] — полутоны относительно базового аккорда, по одному на
     * такт петли. Без него звучал один застывший аккорд все [loopSec]
     * секунд, и фон шёл ровным гудящим тоном без единой мелодии.
     */
    private data class AmbienceParams(
        val chordNotes: List<Double>,
        val progression: IntArray,
        val lfoRate: Double,
        val lfoDepth: Double,
        val noiseLevel: Double,
        val attackSec: Double,
        val releaseSec: Double,
        val loopSec: Int,
    )

    private fun paramsFor(type: AmbienceType): AmbienceParams = when (type) {
        // Прогрессии записаны полутонами от базового аккорда.
        // Светлые — мажорные (I-V-vi-IV), мрачные — натуральный минор
        // (i-VI-III-VII), так что вместо ровного гула получается смена аккордов.
        AmbienceType.NEUTRAL -> AmbienceParams(listOf(220.0, 261.63, 329.63), intArrayOf(0, -5, -9, -7), 0.09, 0.14, 0.0, 0.4, 0.8, 14)
        AmbienceType.CALM -> AmbienceParams(listOf(220.0, 261.63, 329.63, 440.0), intArrayOf(0, -7, -12, -7), 0.07, 0.12, 0.0, 0.6, 1.0, 16)
        AmbienceType.DARK -> AmbienceParams(listOf(146.83, 174.61, 220.0, 293.66), intArrayOf(0, 8, 3, 10), 0.05, 0.18, 0.0, 0.8, 1.2, 18)
        AmbienceType.BRIGHT -> AmbienceParams(listOf(261.63, 329.63, 392.0, 523.25), intArrayOf(0, -5, -9, -7), 0.12, 0.10, 0.0, 0.3, 0.6, 12)
        AmbienceType.RAIN -> AmbienceParams(listOf(180.0, 220.0, 270.0), intArrayOf(0, 3, 8, 3), 0.15, 0.20, 0.08, 0.1, 0.3, 10)
        AmbienceType.FIREPLACE -> AmbienceParams(listOf(120.0, 150.0, 180.0), intArrayOf(0, 3, 5, 3), 0.08, 0.25, 0.12, 0.2, 0.5, 12)
        AmbienceType.NIGHT -> AmbienceParams(listOf(100.0, 125.0, 150.0), intArrayOf(0, 10, 8, 10), 0.04, 0.15, 0.0, 1.0, 1.5, 20)
        AmbienceType.WIND -> AmbienceParams(listOf(160.0, 200.0, 240.0), intArrayOf(0, 5, 7, 2), 0.20, 0.30, 0.06, 0.5, 0.8, 14)
        AmbienceType.OCEAN -> AmbienceParams(listOf(140.0, 175.0, 210.0), intArrayOf(0, 8, 5, 8), 0.06, 0.22, 0.10, 0.8, 1.2, 16)
        AmbienceType.FOREST -> AmbienceParams(listOf(200.0, 250.0, 300.0), intArrayOf(0, 2, 5, 2), 0.10, 0.18, 0.04, 0.4, 0.7, 14)
        AmbienceType.CITY -> AmbienceParams(listOf(220.0, 275.0, 330.0), intArrayOf(0, -5, 3, -2), 0.12, 0.15, 0.05, 0.3, 0.5, 12)
        AmbienceType.COFFEE_SHOP -> AmbienceParams(listOf(240.0, 300.0, 360.0), intArrayOf(0, 3, 5, 7), 0.10, 0.12, 0.03, 0.4, 0.6, 12)
        AmbienceType.THUNDERSTORM -> AmbienceParams(listOf(90.0, 110.0, 135.0), intArrayOf(0, 1, 8, 1), 0.03, 0.35, 0.15, 1.5, 2.0, 20)
        AmbienceType.SNOW -> AmbienceParams(listOf(280.0, 350.0, 420.0), intArrayOf(0, -7, -12, -5), 0.05, 0.10, 0.02, 0.8, 1.0, 16)
        AmbienceType.LIBRARY -> AmbienceParams(listOf(180.0, 225.0, 270.0), intArrayOf(0, -5, 3, -2), 0.06, 0.08, 0.0, 0.6, 0.9, 14)
        AmbienceType.TEMPLE -> AmbienceParams(listOf(160.0, 200.0, 240.0, 300.0), intArrayOf(0, 7, 12, 7), 0.04, 0.12, 0.0, 1.2, 1.8, 22)
        AmbienceType.BATTLE -> AmbienceParams(listOf(110.0, 138.0, 165.0), intArrayOf(0, -2, 3, -2), 0.18, 0.25, 0.10, 0.2, 0.4, 10)
        AmbienceType.ROMANCE -> AmbienceParams(listOf(261.63, 329.63, 392.0, 493.88), intArrayOf(0, -5, -9, -7), 0.08, 0.10, 0.0, 0.7, 1.1, 16)
        AmbienceType.MYSTERY -> AmbienceParams(listOf(130.0, 162.0, 195.0), intArrayOf(0, 8, 3, 7), 0.06, 0.20, 0.04, 0.9, 1.3, 18)
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

    /** Громкость фона, когда говорит голос: доля от базовой. */
    private const val DUCK_LEVEL = 0.34f

    /**
     * Фоновая музыка, посыпаемая в AudioTrack напрямую.
     *
     * Раньше петля писалась WAV на диск и крутилась MediaPlayer'ом — громкость
     * была зашита на весь файл, и приглушить музыку на время речи было нечем.
     * Здесь синтез идёт по сэмплам, поэтому [voiceActive] опускает фон
     * мгновенно и поднимает обратно: голос всегда остаётся главным.
     *
     * Петля зациклена на уровне сэмплов (позиция берётся по модулю длины),
     * поэтому ни стыка, ни щелчка на переходе.
     */
    class Streamer(val type: AmbienceType) {

        /** Базовая громкость из настроек. Голос не перекрывает: см. [DUCK_LEVEL]. */
        @Volatile
        var baseVolume: Float = 0.15f

        /** Идёт ли сейчас речь. true — фон уходит вниз. */
        @Volatile
        var voiceActive: Boolean = false

        private val p = paramsFor(type)
        private val totalSamples = p.loopSec * SAMPLE_RATE
        private val slots = p.progression.size.coerceAtLeast(1)
        private val slotSamples = totalSamples / slots
        private val noiseRnd = java.util.Random(type.ordinal.toLong())

        @Volatile
        private var running = false
        private var track: android.media.AudioTrack? = null
        private var worker: Thread? = null

        // Состояние синтеза между вызовами: позиция в петле, фильтр, огибающая.
        private var pos = 0
        private var filterState = 0.0
        private var gain = 0.0
        private var lpCutoff = 0.0

        private val bufferSize = 2048
        private val buffer = ShortArray(bufferSize)

        fun start() {
            if (running) return
            val minBuf = android.media.AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) return
            val at = runCatching {
                android.media.AudioTrack.Builder()
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    .setAudioFormat(
                        android.media.AudioFormat.Builder()
                            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(minBuf, bufferSize * 2 * 4))
                    .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull() ?: return

            track = at
            running = true
            runCatching { at.play() }
            worker = Thread({ pump(at) }, "ambience-${type.name}").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
                start()
            }
        }

        fun stop() {
            running = false
            worker?.interrupt()
            worker = null
            val at = track
            track = null
            runCatching {
                at?.pause()
                at?.flush()
                at?.release()
            }
        }

        private fun pump(at: android.media.AudioTrack) {
            while (running) {
                render()
                val wrote = runCatching { at.write(buffer, 0, buffer.size) }.getOrDefault(-1)
                if (wrote < 0) break
            }
        }

        private fun render() {
            for (n in 0 until bufferSize) {
                val slot = pos / slotSamples
                val semis = p.progression[slot.coerceIn(0, slots - 1)]
                val tSlot = (pos % slotSamples).toDouble() / SAMPLE_RATE
                val tLoop = pos.toDouble() / SAMPLE_RATE

                // Аккорд текущего такта + второй обертон тише, чем был.
                var v = 0.0
                for ((idx, base) in p.chordNotes.withIndex()) {
                    val f = base * Math.pow(2.0, semis / 12.0)
                    val weight = 1.0 / (idx + 1)
                    v += sin(2 * PI * f * tSlot) * weight
                    v += 0.25 * sin(2 * PI * f * 2 * tSlot) * weight
                }

                // Шум дождя/ветра/костра — тот же, что у WAV-версии.
                if (p.noiseLevel > 0) {
                    v += (noiseRnd.nextDouble() * 2 - 1) * p.noiseLevel
                }

                // Медленно ползущий срез: петля перестаёт звучать застывшей.
                val targetCut = 1400.0 + 900.0 * (0.5 + 0.5 * sin(2 * PI * 0.035 * tLoop))
                lpCutoff += (targetCut - lpCutoff) * 0.00012
                val a = 1.0 - Math.exp(-2.0 * PI * lpCutoff / SAMPLE_RATE)
                filterState += (v * 0.045 - filterState) * a
                var s = filterState

                // Утилизация: быстро вниз на речи, плавно вверх в паузе.
                val target = baseVolume * if (voiceActive) DUCK_LEVEL else 1f
                val coef = if (target < gain) 0.0009 else 0.00006
                gain += (target - gain) * coef
                s *= gain

                // Края петли гаснут — стык должен быть бесшумным.
                val edge = (1.3 * SAMPLE_RATE).toInt().coerceAtLeast(1)
                s *= min(min(pos.toDouble() / edge, (totalSamples - 1 - pos).toDouble() / edge), 1.0)

                buffer[n] = (s * 32767.0).roundToInt().coerceIn(-32768, 32767).toShort()
                pos = (pos + 1) % totalSamples
            }
        }
    }
}