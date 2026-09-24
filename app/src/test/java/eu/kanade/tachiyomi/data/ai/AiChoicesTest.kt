package eu.kanade.tachiyomi.data.ai

import eu.kanade.tachiyomi.data.ai.AiHistoryManager.Msg
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * Кнопки-варианты [[...]] из ответа модели: разбор в AiAgent и хранение/чтение
 * в истории AiHistoryManager. Проверяется чистый текст-контракт, без сети и
 * Android: модель пишет формат ``[[Вариант]]``, и приложение обязано
 * превратить его в кнопки и не показывать разметку в тексте ответа.
 */
class AiChoicesTest {

    @Test
    fun `parseChoices extracts up to four options`() {
        AiAgent.parseChoices(
            "Финальный ответ текстом.\n[[Прочитать главу]]\n[[Перевести реплику]]\n" +
                "[[Сменить пресет на манхву]]\n[[Нарисовать лого]]\n[[Лишний вариант]]",
        ) shouldContainExactly
            listOf("Прочитать главу", "Перевести реплику", "Сменить пресет на манхву", "Нарисовать лого")
    }

    @Test
    fun `parseChoices returns empty when there are no options`() {
        AiAgent.parseChoices("Обычный ответ без вариантов.") shouldBe emptyList()
        AiAgent.parseChoices("") shouldBe emptyList()
    }

    @Test
    fun `stripChoices removes the markup but keeps the answer text`() {
        AiAgent.stripChoices("Ответ.\n[[Вариант]]") shouldBe "Ответ."
        AiAgent.stripChoices("[[Один вариант]]") shouldBe ""
    }

    @Test
    fun `choices round-trip through history serialization`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val msg = Msg(
            role = "ai",
            text = "Готово.",
            time = 123L,
            choices = listOf("Сделать заметку", "Упаковать workspace"),
        )
        val decoded = json.decodeFromString<List<Msg>>(json.encodeToString(listOf(msg))).first()
        decoded.choices shouldBe listOf("Сделать заметку", "Упаковать workspace")
        decoded.text shouldBe "Готово."
    }

    @Test
    fun `older history without choices field still decodes`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = """[{"role":"ai","text":"Старый ответ","time":1}]"""
        val decoded = json.decodeFromString<List<Msg>>(old).first()
        decoded.choices shouldBe emptyList()
        decoded.text shouldBe "Старый ответ"
    }
}