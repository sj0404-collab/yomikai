package eu.kanade.tachiyomi.data.ai

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Разделение моделей по ролям: OCR, чат, оркестратор.
 *
 * Проверяется чистая часть — что роль следует за чатом, пока свой выбор не
 * задан, и что свой выбор действительно перекрывает чат.
 */
class AiModelRolesTest {

    private val chat = "Zen • mimo-v2.5-free"

    private val localState = AiBackendState(
        networkAvailable = false,
        hasOpenRouterKey = false,
        hasGithubPat = false,
        runnerAllowed = false,
        localModelId = "qwen25_05b",
        localModelInstalled = true,
        localModelSizeMb = 1200,
        runnerSessionAlive = false,
    )

    @Test
    fun `empty orchestrator settings follow the chat`() {
        val target = AiModelRoles.orchestratorTarget(
            chatBackend = "online",
            chatProvider = "zen",
            chatModel = chat,
            orchestratorBackend = "",
            orchestratorModel = "",
        )
        target.backendId shouldBe "online"
        target.model shouldBe chat
        target.modelOverride.shouldBeNull()
        target.own shouldBe false
    }

    @Test
    fun `own orchestrator model overrides the chat model`() {
        val target = AiModelRoles.orchestratorTarget(
            chatBackend = "online",
            chatProvider = "zen",
            chatModel = chat,
            orchestratorBackend = "",
            orchestratorModel = "glm-4.6",
        )
        target.model shouldBe "glm-4.6"
        target.modelOverride shouldBe "glm-4.6"
        target.own shouldBe true
        // Бэкенд остался чатовым — сменилась только модель.
        target.backendId shouldBe "online"
    }

    @Test
    fun `own orchestrator backend overrides the chat backend`() {
        val target = AiModelRoles.orchestratorTarget(
            chatBackend = "online",
            chatProvider = "zen",
            chatModel = chat,
            orchestratorBackend = "local",
            orchestratorModel = "",
        )
        target.backendId shouldBe "local"
        target.backendTitle shouldBe AiBackends.LOCAL.title
        target.model shouldBe chat
        target.modelOverride.shouldBeNull()
        target.own shouldBe true
    }

    @Test
    fun `unknown orchestrator backend falls back to the chat one`() {
        val target = AiModelRoles.orchestratorTarget(
            chatBackend = "local",
            chatProvider = "zen",
            chatModel = chat,
            orchestratorBackend = "какая-то новизна",
            orchestratorModel = "",
        )
        target.backendId shouldBe AiBackends.LOCAL.id
        target.own shouldBe false
    }

    @Test
    fun `local backend ignores the orchestrator model and shows its own`() {
        // Локальная LLM работает на той модели, что выбрана в её настройках:
        // роль может выбрать бэкенд, но не модель — и статус обязан это сказать.
        val target = AiModelRoles.orchestratorTarget(
            chatBackend = "online",
            chatProvider = "zen",
            chatModel = chat,
            orchestratorBackend = "local",
            orchestratorModel = "какая-то модель",
            backendState = localState,
        )
        target.backendId shouldBe AiBackends.LOCAL.id
        target.model shouldBe "qwen25_05b • 1200 МБ • установлена"
        target.modelOverride.shouldBeNull()
        target.modelHonoured shouldBe false
        target.own shouldBe true
        AiModelRoles.statusLines(target) shouldContainExactly listOf(
            "Оркестратор: Локальная LLM · zen · qwen25_05b • 1200 МБ • установлена · модель выбирает сам бэкенд",
        )
    }

    @Test
    fun `status shows all three roles`() {
        val lines = AiModelRoles.statusLines(
            AiModelRoles.ocrTarget("CYRILLIC", "Cyrillic PP-OCR (офлайн)"),
            AiModelRoles.chatTarget("online", "zen", chat),
            AiModelRoles.orchestratorTarget("online", "zen", chat, "", ""),
        )
        lines.size shouldBe 3
        lines[0] shouldBe "OCR: Cyrillic PP-OCR (офлайн)"
        lines[1] shouldBe "Чат: Онлайн (Zen / OpenRouter) · zen · Zen • mimo-v2.5-free"
        lines[2] shouldBe "Оркестратор: Онлайн (Zen / OpenRouter) · zen · Zen • mimo-v2.5-free · как у чата"
    }

    @Test
    fun `own orchestrator choice is visible in status`() {
        val lines = AiModelRoles.statusLines(
            AiModelRoles.orchestratorTarget(
                chatBackend = "online",
                chatProvider = "zen",
                chatModel = chat,
                orchestratorBackend = "runner",
                orchestratorModel = "",
                backendState = localState,
            ),
        )
        lines shouldContainExactly listOf(
            "Оркестратор: Полу-онлайн (GitHub Runner) · zen · " +
                "${AiBackends.statusOf(AiBackends.RUNNER, localState).detail} · модель выбирает сам бэкенд",
        )
    }
}
