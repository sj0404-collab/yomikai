package eu.kanade.tachiyomi.data.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.*
import kotlin.math.sin
import kotlin.random.Random

/**
 * Генеративная фоновая музыка для погружения в чтение.
 * Создаёт мелодии программно (PCM через AudioTrack).
 */
class GenerativeMusic {
    
    enum class Genre {
        ACTION, ROMANCE, MYSTERY, COMEDY, DRAMA, AMBIENT, DEFAULT
    }
    
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private var isPlaying = false
    private var currentVolume = 0.3f
    private var targetVolume = 0.3f
    private val sampleRate = 44100
    
    fun start(genre: Genre) {
        stop()
        isPlaying = true
        
        playbackJob = CoroutineScope(Dispatchers.IO).launch {
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            
            audioTrack?.play()
            
            while (isPlaying && isActive) {
                val samples = generateMusic(genre, durationSeconds = 8)
                audioTrack?.write(samples, 0, samples.size)
                smoothVolumeTransition()
            }
        }
    }
    
    fun stop() {
        isPlaying = false
        playbackJob?.cancel()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }
    
    fun setVolume(volume: Float) {
        targetVolume = volume.coerceIn(0f, 1f)
    }
    
    fun duck() {
        targetVolume = 0.1f
    }
    
    fun unduck() {
        targetVolume = 0.3f
    }
    
    private fun generateMusic(genre: Genre, durationSeconds: Int): ShortArray {
        val numSamples = sampleRate * durationSeconds
        val samples = ShortArray(numSamples)
        
        val tempo = when (genre) {
            Genre.ACTION -> 140
            Genre.ROMANCE -> 70
            Genre.MYSTERY -> 90
            Genre.COMEDY -> 130
            Genre.DRAMA -> 100
            Genre.AMBIENT -> 60
            Genre.DEFAULT -> 100
        }
        
        val beatDuration = 60.0 / tempo
        val samplesPerBeat = (sampleRate * beatDuration).toInt()
        
        val chords = when (genre) {
            Genre.ACTION -> listOf(
                listOf(220.0, 261.63, 329.63),
                listOf(196.0, 246.94, 293.66),
                listOf(174.61, 220.0, 261.63),
                listOf(164.81, 207.65, 246.94)
            )
            Genre.ROMANCE -> listOf(
                listOf(261.63, 329.63, 392.0),
                listOf(293.66, 369.99, 440.0),
                listOf(246.94, 311.13, 369.99),
                listOf(220.0, 277.18, 329.63)
            )
            else -> listOf(
                listOf(261.63, 329.63, 392.0),
                listOf(220.0, 277.18, 329.63),
                listOf(246.94, 311.13, 369.99),
                listOf(196.0, 246.94, 293.66)
            )
        }
        
        var sampleIndex = 0
        var chordIndex = 0
        
        while (sampleIndex < numSamples) {
            val chord = chords[chordIndex % chords.size]
            chordIndex++
            
            for (beat in 0 until 4) {
                for (i in 0 until samplesPerBeat) {
                    if (sampleIndex >= numSamples) break
                    
                    val t = (sampleIndex.toDouble() / sampleRate)
                    
                    var sample = 0.0
                    for (freq in chord) {
                        sample += sin(2.0 * Math.PI * freq * t) * 0.2
                    }
                    
                    val bassFreq = chord[0] / 2
                    sample += sin(2.0 * Math.PI * bassFreq * t) * 0.3
                    
                    if (beat % 2 == 0 && i < samplesPerBeat / 4) {
                        sample += Random.nextDouble(-0.1, 0.1)
                    }
                    
                    val envelope = if (i < samplesPerBeat / 10) {
                        i.toDouble() / (samplesPerBeat / 10)
                    } else if (i > samplesPerBeat * 9 / 10) {
                        (samplesPerBeat - i).toDouble() / (samplesPerBeat / 10)
                    } else {
                        1.0
                    }
                    
                    sample *= envelope * currentVolume
                    samples[sampleIndex++] = (sample * Short.MAX_VALUE).toInt().toShort()
                }
            }
        }
        
        return samples
    }
    
    private fun smoothVolumeTransition() {
        val step = 0.01f
        if (currentVolume < targetVolume) {
            currentVolume = (currentVolume + step).coerceAtMost(targetVolume)
        } else if (currentVolume > targetVolume) {
            currentVolume = (currentVolume - step).coerceAtLeast(targetVolume)
        }
    }
}
