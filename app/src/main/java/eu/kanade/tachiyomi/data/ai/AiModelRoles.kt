package eu.kanade.tachiyomi.data.ai

/**
 * Разделение моделей по ролям: OCR, обычный чат и агент-оркестратор.
 *
 * Раньше все три молча делили один и тот же выбор (`pref_ai_backend` +
 * `pref_ai_provider` + модель провайдера), поэтому «какая модель отвечает» не
 *льзя было ни объяснить, ни поменять по отдельности. Теперь у ролей свои
 * настройки, а если роль ничего не задала — она честно следует за чатом, и
 * это видно в статусе.
 */
enum class AiRole(val title: String) {
    OCR("OCR"),
    CHAT("Чат"),
    ORCHESTRATOR("Оркестратор"),
}

data class AiRoleTarget(
    val role: AiRole,
    /** Бэкенд, который реально обслуживает роль. */
    val backendId: String,
    val backendTitle: String,
    val provider: String,
    /**
     * Модель, которую роль показывает как рабочую. Если бэкенд выбирает
     * модель сам (локальная LLM, ранер), сюда попадает модель чата, а
     * [modelOverride] остаётся null — см. [modelHonoured].
     */
    val model: String,
    /** Модель, заданная именно для этой роли; null = берётся из чата. */
    val modelOverride: String? = null,
    /** Отдельный ли выбор у роли (false = следует за чатом). */
    val own: Boolean = false,
    /**
     * Применяется ли [modelOverride]. Локальная LLM и ранер работают на той
     * модели, которую выбрали в их настройках, поэтому роль может выбрать
     * только бэкенд; иначе статус врал бы пользователю.
     */
    val modelHonoured: Boolean = true,
)

object AiModelRoles {

    private fun backendTitle(id: String): String = AiBackends.byId(id).title

    /**
     * Роль OCR — это движок из настроек распознавания. Он не AI-провайдер, но
     * показывать его пользователю нужно в том же списке, иначе «три модели»
     * остаётся двумя.
     */
    fun ocrTarget(engineId: String, engineTitle: String): AiRoleTarget = AiRoleTarget(
        role = AiRole.OCR,
        backendId = "",
        backendTitle = "",
        provider = "",
        model = engineTitle.ifBlank { engineId },
    )

    fun chatTarget(chatBackend: String?, chatProvider: String, chatModel: String): AiRoleTarget =
        AiRoleTarget(
            role = AiRole.CHAT,
            backendId = AiBackends.byId(chatBackend).id,
            backendTitle = backendTitle(chatBackend.orEmpty()),
            provider = chatProvider,
            model = chatModel,
        )

    /**
     * Оркестратор — агент читалки: он вызывает инструменты и выводит правила
     * книги. Пустые настройки означают «как у чата»: так поведение не
     * меняется у тех, кто ничего не выбирал.
     */
    fun orchestratorTarget(
        chatBackend: String?,
        chatProvider: String,
        chatModel: String,
        orchestratorBackend: String?,
        orchestratorModel: String?,
        /**
         * Состояние устройства: нужно ролям, чей бэкенд сам выбирает модель
         * (локальная LLM, ранер). Тогда статус показывает реальную модель
         * этого бэкенда, а не догадку. null — если состояние не собирали.
         */
        backendState: AiBackendState? = null,
    ): AiRoleTarget {
        // Неизвестный идентификатор бэкенда тоже считаем «своим выбор
        // отсутствует»: иначе роль молча уехала бы на онлайн вместо чата.
        val backendId = orchestratorBackend
            ?.takeIf { it.isNotBlank() }
            ?.let { raw -> AiBackends.ALL.firstOrNull { it.id == raw }?.id }
            ?: AiBackends.byId(chatBackend).id
        val backend = AiBackends.byId(backendId)
        val requested = orchestratorModel?.takeIf { it.isNotBlank() }
        val honoured = backend.supportsModelChoice
        val own = backendId != AiBackends.byId(chatBackend).id || requested != null
        val backendModel = if (honoured || backendState == null) {
            null
        } else {
            AiBackends.statusOf(backend, backendState, chatProvider).detail
        }
        return AiRoleTarget(
            role = AiRole.ORCHESTRATOR,
            backendId = backendId,
            backendTitle = backendTitle(backendId),
            provider = chatProvider,
            model = when {
                honoured -> requested ?: chatModel
                else -> backendModel?.takeIf { it.isNotBlank() } ?: chatModel
            },
            modelOverride = if (honoured) requested else null,
            own = own,
            modelHonoured = honoured,
        )
    }

    /** Строки статуса для UI: одна на роль, с пометкой «как у чата». */
    fun statusLines(vararg targets: AiRoleTarget): List<String> = targets.map { target ->
        val model = target.model.ifBlank { "не выбрана" }
        val tail = when {
            !target.own -> " · как у чата"
            !target.modelHonoured -> " · модель выбирает сам бэкенд"
            else -> ""
        }
        if (target.role == AiRole.OCR) {
            "${target.role.title}: $model"
        } else {
            "${target.role.title}: ${target.backendTitle} · ${target.provider} · $model$tail"
        }
    }
}
