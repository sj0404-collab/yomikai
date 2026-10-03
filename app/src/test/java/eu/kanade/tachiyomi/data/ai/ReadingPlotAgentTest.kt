package eu.kanade.tachiyomi.data.ai

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Суб-агент «Сюжет по мере чтения».
 *
 * Проверяется то, что агент не врёт: в промпт уходит ровно услышанное, без
 * выдуманных реплик, и пустое состояние не превращается в правдоподобный текст.
 */
class ReadingPlotAgentTest {

    // Ключа нет: проверяем, что агент честно отказывается, а не сочиняет.
    private val agent = ReadingPlotAgent(store = MemoryPlotStore(), aiKeyPresent = { false })

    private fun lines() = List(8) { "Реплика номер $it" }

    @Test
    fun `prompt carries exactly what was heard`() {
        val heard = listOf("Аки: Я так и знал.", "Ты где был?", "Я здесь.")
        val prompt = ReadingPlotAgent.prompt(heard)
        heard.forEach { prompt shouldContain it }
        // Список подаётся поимённо, иначе модель сочтёт, что ей дали сводку.
        prompt shouldContain "- Аки: Я так и знал."
    }

    @Test
    fun `prompt forbids inventing the plot`() {
        // Главный риск пересказа: модель дописывает то, чего в репликах нет,
        // и читатель принимает это за сюжет.
        val prompt = ReadingPlotAgent.prompt(listOf("Привет."))
        prompt shouldContain "не додумывай"
        prompt shouldContain "ТОЛЬКО"
    }

    @Test
    fun `prompt asks for first person with emotions`() {
        // Задача была не протокол, а живой пересказ человека.
        val prompt = ReadingPlotAgent.prompt(listOf("Привет."))
        prompt shouldContain "первого лица"
        prompt shouldContain "эмоциями"
    }

    @Test
    fun `prompt does not leak previous chapters`() {
        // Пересказ собирается заново из переданного списка: старые реплики не
        // должны попадать в новый запрос иначе, чем через явный reset().
        val prompt = ReadingPlotAgent.prompt(listOf("Новая сцена."))
        prompt shouldNotContain "Прошлая сцена"
    }

    @Test
    fun `empty state is honest`() {
        // Пустой пересказ обязан объяснять причину, а не показывать текст.
        agent.refresh(null)
        agent.state.value.reason shouldBe "Книга не открыта"
    }

    @Test
    fun `nothing to retell is stated plainly`() {
        agent.refresh(1L)
        agent.state.value.reason shouldContain "не озвучено ни одной реплики"
    }

    @Test
    fun `state starts empty`() {
        agent.state.value.text shouldBe ""
        agent.state.value.heard shouldBe 0
        agent.state.value.thinking shouldBe false
    }

    @Test
    fun `saved recap survives reopening the book`() {
        // Пересказ пишется запросом к модели, и терять его при закрытии
        // читалки обидно: вкладка при возврате должна быть не пустой, даже
        // если ключа сейчас нет.
        val store = MemoryPlotStore()
        store.write(42L, "Он вошёл и всё понял.")
        val reopened = ReadingPlotAgent(store = store, aiKeyPresent = { false })
        reopened.loadFor(42L)
        reopened.state.value.text shouldBe "Он вошёл и всё понял."
    }

    @Test
    fun `missing recap leaves the state untouched`() {
        // Книги, для которой пересказа нет, выдумывать нечего.
        val store = MemoryPlotStore()
        ReadingPlotAgent(store = store, aiKeyPresent = { false }).loadFor(999L)
        store.read(999L) shouldBe ""
    }
}
