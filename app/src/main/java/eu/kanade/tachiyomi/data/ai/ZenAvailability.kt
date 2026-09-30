package eu.kanade.tachiyomi.data.ai

/**
 * Что приложение знает о реальной доступности бесплатных моделей Zen.
 *
 * Раньше отчёт писал «Zen free: доступно» по одному наличию сети, ничего не
 * спрашивая у провайдера. Из-за этого агент, получив такой отчёт в системном
 * промпте, продолжал стучаться в закрытую дверь вместо того, чтобы уйти на
 * рабочий путь. Теперь доступность выводится из фактического ответа.
 */
object ZenAvailability {

    /** Что известно по последним попыткам. */
    enum class State {
        /** Ещё не пробовали — доказывать нечего. */
        UNKNOWN,

        /** Модель ответила: путь рабочий. */
        WORKING,

        /** Провайдер отказал именно по тарифу: клиент не OpenCode. */
        FREE_TIER_BLOCKED,
    }

    /**
     * Сколько живёт успешный ответ. Старый успех не должен вечно щадить Zen:
     * провайдер мог поменять политику, и через несколько часов тот же путь
     * окажется закрыт.
     */
    const val SUCCESS_VALID_MS = 30 * 60_000L

    /** Насколько верим успеху, если сеть пропала. */
    const val FAILURE_VALID_MS = 60 * 60_000L

    fun successState(lastSuccessAt: Long, now: Long): State =
        if (lastSuccessAt > 0L && now - lastSuccessAt < SUCCESS_VALID_MS) State.WORKING else State.UNKNOWN

    fun blockedState(lastBlockedAt: Long, now: Long): State =
        if (lastBlockedAt > 0L && now - lastBlockedAt < FAILURE_VALID_MS) State.FREE_TIER_BLOCKED else State.UNKNOWN

    /**
     * Доступен ли Zen для агента прямо сейчас.
     *
     * Свежий отказ важнее старого успеха: если провайдер только что отказал,
     * считать путь рабочим нельзя, иначе агент снова уйдёт в ту же дверь.
     */
    fun decide(lastSuccessAt: Long, lastBlockedAt: Long, now: Long): State {
        val blocked = blockedState(lastBlockedAt, now)
        if (blocked == State.FREE_TIER_BLOCKED) return blocked
        return successState(lastSuccessAt, now)
    }

    /**
     * Итоговая строка для отчёта о возможностях. Здесь важно не вводить в
     * заблуждение: «возможно» честнее, чем уверенное «доступно».
     */
    fun describe(hasNetwork: Boolean, state: State): Pair<Boolean, String> = when {
        !hasNetwork -> false to "Нет сети"
        state == State.FREE_TIER_BLOCKED ->
            false to "OpenCode отдаёт бесплатные модели только своему клиенту " +
                "(HTTP 403). Рабочий путь: OpenRouter «:free», локальный адрес " +
                "или свой opencode."
        state == State.WORKING -> true to "OK (отвечает)"
        // Не пробовали — не обещаем, но и не ругаем.
        else -> true to "OK (не проверено, нужен интернет)"
    }
}
