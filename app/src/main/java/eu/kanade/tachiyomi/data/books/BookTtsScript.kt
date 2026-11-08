package eu.kanade.tachiyomi.data.books

/**
 * Ролевая разметка текста для озвучки книг.
 *
 * Нарратив читается основным голосом (рассказчик), реплики персонажей внутри
 * кавычек — отдельными голосами. Для Edge TTS голоса меняются «по-настоящему»
 * (SSML: разные <voice> на каждый сегмент); для системного движка движка,
 * который умеет лишь один голос, персонажи различаются сдвигом высоты.
 */
enum class BookSpeechRole { NARRATOR, CHARACTER }

/** Один фрагмент для озвучки. [speaker] — номер персонажа (0..), для нарратива 0. */
data class BookSpeechSegment(
    val role: BookSpeechRole,
    val speaker: Int,
    val text: String,
)

object BookTtsScript {

    private val OPEN_QUOTES = setOf('«', '„', '"', '“')
    private val CLOSE_QUOTES = setOf('»', '“', '"', '”')

    /** Слова-атрибуции: короткая связка «— сказал он.» означает, что реплику
     *  продолжает тот же персонаж, а не появляется новый. */
    private val ATTRIBUTION_PATTERN = Regex(
        """(сказал|сказала|ответил|ответила|спросил|спросила|промолвил|произнёс|произнес|""" +
            """крикнул|крикнула|шепнул|шепнула|заметил|заметила|подумал|подумала|начал|начала|""" +
            """продолжил|продолжила|повторил|повторила|перебил|перебила|усмехнулся|улыбнулся|""" +
            """рассмеялся|вздохнул|согласился|возразил|предложил|предложила|воскликнул|воскликнула|""" +
            """невозмутимо|тихо|громко|вслух|прошептал|прошептала)""",
        RegexOption.IGNORE_CASE,
    )

    /** Русские голоса Edge, среди которых выбираются голоса персонажей. */
    private val CHARACTER_VOICES = listOf("ru-RU-DmitryNeural", "ru-RU-DariyaNeural")

    /**
     * Разбивает текст на сегменты «нарратив / реплика».
     * Реплики — фрагменты внутри кавычек «», „“, “”, "". Незакрытая кавычка
     * безопасно читается как нарратив. Соседние реплики без длинного нарратива
     * между ними считаются репликами одного персонажа; после длинной связки
     * (новая сцена/абзац) начинает говорить следующий персонаж.
     */
    fun split(text: String): List<BookSpeechSegment> {
        if (text.isBlank()) return emptyList()
        val segments = mutableListOf<BookSpeechSegment>()
        var pos = 0
        var speaker = 0
        var quoteStreak = false

        fun emitNarrator(part: String) {
            val trimmed = part.trim()
            if (trimmed.isBlank()) return
            segments += BookSpeechSegment(BookSpeechRole.NARRATOR, 0, trimmed)
        }

        while (pos < text.length) {
            var open = -1
            for (i in pos until text.length) {
                if (text[i] in OPEN_QUOTES) {
                    open = i
                    break
                }
            }
            if (open < 0) {
                emitNarrator(text.substring(pos))
                return segments
            }

            val before = text.substring(pos, open)
            val advanceSpeaker = quoteStreak && !isAttribution(before)
            emitNarrator(before)
            if (advanceSpeaker) speaker = (speaker + 1) % MAX_SPEAKERS

            val close = findClosingQuote(text, open)
            if (close < 0) {
                emitNarrator(text.substring(open))
                return segments
            }
            val quote = text.substring(open + 1, close).trim()
            if (quote.isNotBlank()) {
                segments += BookSpeechSegment(BookSpeechRole.CHARACTER, speaker, quote)
                quoteStreak = true
            }
            pos = close + 1
        }
        return segments
    }

    private fun findClosingQuote(text: String, open: Int): Int {
        for (i in open + 1 until text.length) {
            if (text[i] in CLOSE_QUOTES) return i
        }
        return -1
    }

    private fun isAttribution(before: String): Boolean {
        val t = before.trim()
        return t.isNotEmpty() &&
            t.length <= 48 &&
            ATTRIBUTION_PATTERN.containsMatchIn(t)
    }

    /**
     * Голос для сегмента Edge TTS. Рассказчик — выбранный пользователем голос;
     * персонажи берутся из палитры (без повтора голоса рассказчика).
     */
    fun edgeVoiceFor(role: BookSpeechRole, speaker: Int, narratorVoice: String): String {
        if (role == BookSpeechRole.NARRATOR) return narratorVoice
        val narrator = narratorVoice.ifBlank { "ru-RU-SvetlanaNeural" }
        val palette = if (CHARACTER_VOICES.size > 1) {
            CHARACTER_VOICES.filterNot { it == narrator }
        } else {
            CHARACTER_VOICES
        }
        val voices = palette.ifEmpty { CHARACTER_VOICES }
        return voices[speaker.mod(voices.size)]
    }

    /** Сдвиг высоты для Edge (Hz) — дифференцирует персонажей. */
    fun edgePitchHzFor(role: BookSpeechRole, speaker: Int): Int {
        if (role == BookSpeechRole.NARRATOR) return 0
        return if (speaker % 2 == 0) -8 else 10
    }

    /** Множитель высоты для системного TTS (у движка один голос). */
    fun systemPitchFactorFor(role: BookSpeechRole, speaker: Int): Float {
        if (role == BookSpeechRole.NARRATOR) return 1.0f
        return if (speaker % 2 == 0) 0.93f else 1.08f
    }

    private const val MAX_SPEAKERS = 64
}