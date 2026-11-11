package eu.kanade.tachiyomi.data.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Фоновая музыка для авточтения. Не скачивается и не стримится: каждый жанр
 * синтезируется кодом один раз в WAV (16 бит, моно, 22050 Гц) и потом
 * зацикливается через [MediaPlayer]. Файлы лежат в `filesDir/music/`, поэтому
 * повторный запуск читалки сразу проигрывает готовый трек.
 *
 * Громкость управляется плавно: [duck] приглушает музыку под голос озвучки,
 * [unduck] возвращает её к базовому уровню. Переход занимает около секунды,
 * чтобы не было щелчков.
 *
 * Все вызовы [start]/[stop]/[duck]/[unduck] — с главного потока. Тяжёлая
 * генерация WAV идёт на IO и отменяется вместе с [stop].
 */
class GenerativeMusic(private val context: Context) {

    /** Трезвучие: смещение корня в полутонах от тоники жанра и его тип. */
    data class Chord(val rootSemitones: Int, val minor: Boolean)

    /** Тип ударных: без них, мягкие хай-хэты или хай-хэты с бочкой. */
    enum class Percussion { NONE, SOFT, DRIVE }

    enum class Genre(
        val label: String,
        val tempo: Int,
        /** Частота тоники (Гц). От неё считаются аккорды, бас и арпеджио. */
        val keyHz: Double,
        /** Прогрессия из четырёх тактов; цикл повторяется до конца трека. */
        val progression: List<Chord>,
        /** Нот арпеджио на долю: 2 — восьмые, 4 — шестнадцатые. */
        val arpPerBeat: Int,
        val padLevel: Double,
        val bassLevel: Double,
        val arpLevel: Double,
        val percussion: Percussion,
    ) {
        ACTION(
            label = "Экшн", tempo = 140, keyHz = 110.0,
            progression = listOf(Chord(0, true), Chord(8, false), Chord(3, false), Chord(10, false)),
            arpPerBeat = 4, padLevel = 0.10, bassLevel = 0.22, arpLevel = 0.12, percussion = Percussion.DRIVE,
        ),
        ROMANCE(
            label = "Романтика", tempo = 72, keyHz = 130.81,
            progression = listOf(Chord(0, false), Chord(7, false), Chord(9, true), Chord(5, false)),
            arpPerBeat = 2, padLevel = 0.16, bassLevel = 0.12, arpLevel = 0.10, percussion = Percussion.NONE,
        ),
        MYSTERY(
            label = "Детектив", tempo = 90, keyHz = 146.83,
            progression = listOf(Chord(0, true), Chord(8, false), Chord(10, false), Chord(0, true)),
            arpPerBeat = 2, padLevel = 0.14, bassLevel = 0.15, arpLevel = 0.08, percussion = Percussion.NONE,
        ),
        COMEDY(
            label = "Комедия", tempo = 128, keyHz = 261.63,
            progression = listOf(Chord(0, false), Chord(5, false), Chord(7, false), Chord(0, false)),
            arpPerBeat = 4, padLevel = 0.10, bassLevel = 0.18, arpLevel = 0.14, percussion = Percussion.SOFT,
        ),
        DRAMA(
            label = "Драма", tempo = 96, keyHz = 164.81,
            progression = listOf(Chord(0, true), Chord(5, true), Chord(7, true), Chord(0, true)),
            arpPerBeat = 2, padLevel = 0.16, bassLevel = 0.14, arpLevel = 0.07, percussion = Percussion.NONE,
        ),
        AMBIENT(
            label = "Атмосфера", tempo = 60, keyHz = 174.61,
            progression = listOf(Chord(0, false), Chord(9, true), Chord(4, true), Chord(7, false)),
            arpPerBeat = 2, padLevel = 0.20, bassLevel = 0.08, arpLevel = 0.05, percussion = Percussion.NONE,
        ),
        DEFAULT(
            label = "Обычная", tempo = 100, keyHz = 196.0,
            progression = listOf(Chord(0, false), Chord(4, true), Chord(5, false), Chord(7, false)),
            arpPerBeat = 2, padLevel = 0.14, bassLevel = 0.14, arpLevel = 0.10, percussion = Percussion.SOFT,
        ),
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var startJob: Job? = null
    private var fadeJob: Job? = null
    private var player: MediaPlayer? = null

    private var baseVolume = DEFAULT_VOLUME
    private var ducked = false
    private var currentVolume = 0f

    val isRunning: Boolean get() = player != null || startJob?.isActive == true

    /** Запускает цикл жанра. Повторный вызов заменяет текущий трек. */
    fun start(genre: Genre, volume: Float = DEFAULT_VOLUME) {
        stop()
        baseVolume = volume.coerceIn(0f, 1f)
        startJob = scope.launch {
            val file = withContext(Dispatchers.IO) { ensureTrack(genre) } ?: return@launch
            if (!isActive) return@launch
            val mp = MediaPlayer()
            val ready = runCatching {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                mp.setDataSource(file.absolutePath)
                mp.isLooping = true
                mp.setVolume(0f, 0f)
                mp.prepare()
                mp.start()
            }.isSuccess
            if (!ready || !isActive) {
                runCatching { mp.release() }
                return@launch
            }
            player = mp
            currentVolume = 0f
            startFade()
        }
    }

    /** Останавливает музыку и освобождает плеер. Безопасно вызывать повторно. */
    fun stop() {
        startJob?.cancel()
        startJob = null
        fadeJob?.cancel()
        fadeJob = null
        player?.let { mp ->
            runCatching { mp.stop() }
            runCatching { mp.release() }
        }
        player = null
        ducked = false
        currentVolume = 0f
    }

    /** Базовая громкость (0..1), к ней возвращается [unduck]. */
    fun setVolume(volume: Float) {
        baseVolume = volume.coerceIn(0f, 1f)
    }

    /** Приглушить под голос озвучки. */
    fun duck() {
        ducked = true
    }

    /** Вернуть базовую громкость после реплики. */
    fun unduck() {
        ducked = false
    }

    private fun startFade() {
        fadeJob?.cancel()
        fadeJob = scope.launch {
            // Шаг 0.03 каждые 50 мс: полный переход от тишины до 0.3 занимает около полсекунды.
            while (isActive) {
                val target = if (ducked) baseVolume * DUCK_FACTOR else baseVolume
                val diff = target - currentVolume
                currentVolume += diff.coerceIn(-FADE_STEP, FADE_STEP)
                player?.setVolume(currentVolume, currentVolume)
                delay(FADE_TICK_MS)
            }
        }
    }

    /** Файл трека жанра: синтез при первом обращении, дальше — из кеша. */
    private fun ensureTrack(genre: Genre): File? = runCatching {
        val dir = File(context.filesDir, "music").apply { mkdirs() }
        val target = File(dir, "${genre.name.lowercase()}_$ALGO_VERSION.wav")
        if (target.isFile && target.length() > WAV_HEADER_BYTES) return@runCatching target
        val pcm = synthesize(genre)
        val tmp = File(dir, target.name + ".tmp")
        writeWav(tmp, pcm)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        target
    }.getOrNull()

    companion object {
        /** Версия алгоритма: при смене синтеза имя файла меняется, старые WAV не используются. */
        private const val ALGO_VERSION = "v1"
        const val SAMPLE_RATE = 22050
        private const val LOOP_SECONDS = 48.0
        private const val DEFAULT_VOLUME = 0.3f
        private const val DUCK_FACTOR = 0.25f
        private const val FADE_STEP = 0.03f
        private const val FADE_TICK_MS = 50L
        private const val WAV_HEADER_BYTES = 44

        /**
         * Жанр по тегам ComicInfo (`<Genre>` и т.п.). Порядок проверок важен:
         * «action» стоит раньше «drama», чтобы «Action, Drama» дал экшн.
         * Ничего не нашли — [Genre.DEFAULT].
         */
        fun genreFromTags(tags: Collection<String>?): Genre {
            val text = tags.orEmpty().joinToString(" ").lowercase()
            if (text.isBlank()) return Genre.DEFAULT
            fun has(vararg words: String) = words.any { text.contains(it) }
            return when {
                has("action", "fighting", "martial", "shounen", "war", "mecha", "superhero") -> Genre.ACTION
                has("mystery", "horror", "thriller", "suspense", "crime", "detective") -> Genre.MYSTERY
                has("romance", "shoujo", "love") -> Genre.ROMANCE
                has("comedy", "gag", "parody", "funny") -> Genre.COMEDY
                has("drama", "tragedy", "psychological", "historical") -> Genre.DRAMA
                has("slice of life", "healing", "iyashikei", "nature") -> Genre.AMBIENT
                else -> Genre.DEFAULT
            }
        }

        /** Настройка «auto» — по тегам, иначе имя жанра из настроек. */
        fun resolve(pref: String, tags: Collection<String>?): Genre {
            if (pref == "auto") return genreFromTags(tags)
            return Genre.entries.firstOrNull { it.name == pref } ?: Genre.DEFAULT
        }

        /** Синтез цикла жанра. Чистая функция, без Android-API. */
        internal fun synthesize(genre: Genre): ShortArray {
            val sr = SAMPLE_RATE.toDouble()
            val barSamples = (sr * 4.0 * 60.0 / genre.tempo).toInt()
            val beatSamples = barSamples / 4
            val barsPerCycle = genre.progression.size
            val cycleSeconds = barsPerCycle * barSamples / sr
            val cycles = max(1, Math.round(LOOP_SECONDS / cycleSeconds).toInt())
            val totalBars = cycles * barsPerCycle
            val total = totalBars * barSamples
            val mix = DoubleArray(total)
            val rnd = Random(genre.ordinal * 7919 + 1)

            for (bar in 0 until totalBars) {
                val chord = genre.progression[bar % barsPerCycle]
                val start = bar * barSamples
                val third = if (chord.minor) 3 else 4
                val root = genre.keyHz * 2.0.pow(chord.rootSemitones / 12.0)
                val tones = doubleArrayOf(
                    root,
                    root * 2.0.pow(third / 12.0),
                    root * 2.0.pow(7 / 12.0),
                )

                // Пэд: мягкие трезвучия на весь такт с плавным входом и выходом.
                val fade = (0.25 * sr).toInt()
                for (n in 0 until barSamples) {
                    val t = (start + n) / sr
                    val env = min(1.0, n.toDouble() / fade) * min(1.0, (barSamples - n).toDouble() / fade)
                    var s = 0.0
                    for (f in tones) s += sin(2 * PI * f * t) + 0.3 * sin(4 * PI * f * t)
                    mix[start + n] += s * env * genre.padLevel * 0.35
                }

                // Бас: четвертные ноты на октаву ниже корня, плавно затухают.
                for (beat in 0 until 4) {
                    val bStart = start + beat * beatSamples
                    val f = root / 2.0
                    for (n in 0 until beatSamples) {
                        val t = (bStart + n) / sr
                        val env = exp(-n / (sr * 0.35))
                        mix[bStart + n] += sin(2 * PI * f * t) * env * genre.bassLevel
                    }
                }

                // Арпеджио: короткие плавные ноты по тонам трезвучия; через такт порядок разворачивается.
                val notesPerBar = 4 * genre.arpPerBeat
                val noteSamples = beatSamples / genre.arpPerBeat
                val reversed = (bar / barsPerCycle) % 2 == 1
                for (k in 0 until notesPerBar) {
                    val idx = if (reversed) notesPerBar - 1 - k else k
                    val pitch = tones[idx % 3] * (if ((idx / 3) % 2 == 0) 1.0 else 2.0)
                    val nStart = start + k * noteSamples
                    for (n in 0 until noteSamples) {
                        val t = (nStart + n) / sr
                        val env = exp(-n / (sr * 0.12))
                        mix[nStart + n] += sin(2 * PI * pitch * t) * env * genre.arpLevel * 0.5
                    }
                }

                // Ударные: хай-хэт на каждой восьмой (мягко или громче), бочка на 1 и 3 для DRIVE.
                when (genre.percussion) {
                    Percussion.NONE -> Unit
                    Percussion.SOFT, Percussion.DRIVE -> {
                        val hatLen = (sr * 0.03).toInt()
                        val hatLevel = if (genre.percussion == Percussion.DRIVE) 0.10 else 0.05
                        for (eighth in 0 until 8) {
                            val hStart = start + eighth * (beatSamples / 2)
                            for (n in 0 until hatLen) {
                                if (hStart + n >= total) break
                                val env = exp(-n / (sr * 0.012))
                                mix[hStart + n] += (rnd.nextDouble() * 2 - 1) * env * hatLevel
                            }
                        }
                    }
                }
                if (genre.percussion == Percussion.DRIVE) {
                    val kickLen = (sr * 0.25).toInt()
                    for (beat in listOf(0, 2)) {
                        val kStart = start + beat * beatSamples
                        var phase = 0.0
                        for (n in 0 until kickLen) {
                            if (kStart + n >= total) break
                            val f = 110.0 - 65.0 * min(1.0, n / (sr * 0.2))
                            phase += 2 * PI * f / sr
                            val env = exp(-n / (sr * 0.10))
                            mix[kStart + n] += sin(phase) * env * 0.35
                        }
                    }
                }
            }

            // Края трека в тишину, мягкое ограничение и перевод в 16 бит.
            val edge = (sr * 0.03).toInt()
            val out = ShortArray(total)
            for (i in 0 until total) {
                val fadeIn = min(1.0, i.toDouble() / edge)
                val fadeOut = min(1.0, (total - i).toDouble() / edge)
                val v = tanh(mix[i] * 1.4) * 0.85 * fadeIn * fadeOut
                out[i] = (v * Short.MAX_VALUE).toInt().toShort()
            }
            return out
        }

        private fun writeWav(file: File, pcm: ShortArray) {
            val dataBytes = pcm.size * 2
            val buf = ByteBuffer.allocate(WAV_HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.put("RIFF".toByteArray(Charsets.US_ASCII))
            buf.putInt(36 + dataBytes)
            buf.put("WAVE".toByteArray(Charsets.US_ASCII))
            buf.put("fmt ".toByteArray(Charsets.US_ASCII))
            buf.putInt(16)
            buf.putShort(1) // PCM
            buf.putShort(1) // моно
            buf.putInt(SAMPLE_RATE)
            buf.putInt(SAMPLE_RATE * 2)
            buf.putShort(2)
            buf.putShort(16)
            buf.put("data".toByteArray(Charsets.US_ASCII))
            buf.putInt(dataBytes)
            for (s in pcm) buf.putShort(s)
            file.writeBytes(buf.array())
        }
    }
}
