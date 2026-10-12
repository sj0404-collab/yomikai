package eu.kanade.tachiyomi.data.tts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Эмоциональные голоса: реплика распознаётся по словам и пунктуации, чтобы
 * голос вживался в роль (страх читается выше и быстрее, утешение ниже и
 * медленнее), а не зачитывал ровно.
 */
class AutoReadEngineEmotionTest {

    private fun emotion(text: String) = AutoReadEngine.guessEmotion(text)

    @Test
    fun `страх распознаётся по словам страха`() {
        assertEquals(AutoReadEngine.Emotion.FEAR, emotion("Боюсь…"))
        assertEquals(AutoReadEngine.Emotion.FEAR, emotion("Мне страшно."))
        assertEquals(AutoReadEngine.Emotion.FEAR, emotion("Помогите! Он убьёт её!"))
        assertEquals(AutoReadEngine.Emotion.FEAR, emotion("Мы в опасности!"))
    }

    @Test
    fun `утешение перебивает страх`() {
        assertEquals(AutoReadEngine.Emotion.COMFORT, emotion("Не бойся, я рядом."))
        assertEquals(AutoReadEngine.Emotion.COMFORT, emotion("Спокойно, всё хорошо."))
        assertEquals(AutoReadEngine.Emotion.COMFORT, emotion("Успокойся, ничего страшного."))
        assertEquals(AutoReadEngine.Emotion.COMFORT, emotion("Ты не одна, я с тобой."))
    }

    @Test
    fun `гнев и шёпот распознаются отдельно от радости`() {
        assertEquals(AutoReadEngine.Emotion.ANGER, emotion("Как ты смеешь! Замолчи!!!"))
        assertEquals(AutoReadEngine.Emotion.ANGER, emotion("Прекрати, бесит!"))
        assertEquals(AutoReadEngine.Emotion.WHISPER, emotion("Тихо… шшш, скажу секрет."))
    }

    @Test
    fun `грусть и радость по словам и пунктуации`() {
        assertEquals(AutoReadEngine.Emotion.SADNESS, emotion("Мне одиноко…"))
        assertEquals(AutoReadEngine.Emotion.SADNESS, emotion("Прощай, это больно."))
        assertEquals(AutoReadEngine.Emotion.JOY, emotion("Ура! Мы справились!"))
        assertEquals(AutoReadEngine.Emotion.JOY, emotion("Ха-ха, как весело!"))
    }

    @Test
    fun `удивление по вопросительному восклицанию`() {
        assertEquals(AutoReadEngine.Emotion.SURPRISE, emotion("Что?! Ты серьёзно?"))
        assertEquals(AutoReadEngine.Emotion.SURPRISE, emotion("Не может быть!!!"))
        assertEquals(AutoReadEngine.Emotion.SURPRISE, emotion("Как?! Он же был здесь!"))
    }

    @Test
    fun `спокойные реплики звучат ровно`() {
        assertEquals(AutoReadEngine.Emotion.NEUTRAL, emotion("Спасибо за чай."))
        assertEquals(AutoReadEngine.Emotion.NEUTRAL, emotion("Иди домой."))
        assertEquals(AutoReadEngine.Emotion.NEUTRAL, emotion("а"))
    }

    @Test
    fun `просодия эмоции задаёт множители высоты и темпа`() {
        assertEquals(AutoReadEngine.Emotion.FEAR.pitch, 1.3f)
        assertEquals(AutoReadEngine.Emotion.FEAR.rate, 1.4f)
        assertEquals(AutoReadEngine.Emotion.COMFORT.pitch, 0.85f)
        assertEquals(AutoReadEngine.Emotion.COMFORT.rate, 0.82f)
        assertEquals(AutoReadEngine.Emotion.NEUTRAL.pitch, 1f)
        assertEquals(AutoReadEngine.Emotion.NEUTRAL.rate, 1f)
    }
}