package eu.kanade.tachiyomi.data.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Суб-агент «сюжет по мере чтения».
 *
 * Копит произнесённые реплики и просит модель пересказать прочитанное от
 * первого лица — так, как это пересказал бы человек, который там был: с
 * эмоциями, с оценкой, а не как протокол.
 *
 * Почему «по мере чтения», а не по итогам главы: пересказ берётся из того же
 * текста, что и озвучка, поэтому он появляется, пока читатель слушает, и не
 * ждёт конца. Честная оговорка — качество пересказа упирается в качество
 * распознавания: если в тексте «ХО-РОШО», то и в пересказе будет «ХО-РОШО».
 * Поэтому агент получает ровно то, что было произнесено, без «улучшений».
 *
 * Ничего не выдумывает на своей стороне: пустой ответ модели — это пустой
 * ответ, а не текст «похоже, глава про…».
 */
class ReadingPlotAgent(
    /**
     * Хранилище пересказа по книгам.
     *
     * Файл, а не настройка: ключ настройки должен быть известен на этапе
     * компиляции, а книга меняется. Кэш настроек для этого не годится.
     */
    private val store: PlotStore,
    /**
     * Есть ли ключ ИИ.
     *
     * Проверка ключа вне агента и через лямбду, а не прямой доступ к
     * настройкам: иначе агент нельзя было бы проверить тестом вовсе — в
     * app-модуле Injekt не поднят, а именно это требование (не выдумывать
     * пересказ без модели) важнее всего остального в классе.
     */
    private val aiKeyPresent: () -> Boolean,
) {

    /** Состояние вкладки «Сюжет». */
    data class State(
        /** Текст пересказа; пусто — пересказа ещё нет. */
        val text: String = "",
        /** Сколько реплик учтено. */
        val heard: Int = 0,
        /** Идёт ли запрос к модели. */
        val thinking: Boolean = false,
        /** Причина, по которой пересказа нет (нет ключа, нет текста, ошибка). */
        val reason: String? = null,
        /** Модель, которая ответила. */
        val model: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Реплики текущей главы; последние и уходят в модель. */
    private val heard = ArrayDeque<String>()

    /** Идёт ли пересказ — повторный запрос во время него не запускаем. */
    private var requesting = false

    /**
     * Текущий запрос пересказа.
     *
     * Держать его надо было, чтобы «Стоп» действительно отменял запрос: у
     * агента был только флаг `requesting`, который запрещал новый запрос, но
     * ничего не отменял — и пересказ дописывался после остановки чтения.
     */
    @Volatile
    private var recapJob: kotlinx.coroutines.Job? = null

    /**
     * Учесть произнесённую реплику и, когда набралось достаточно, обновить
     * пересказ.
     *
     * Обновление не на каждую реплику: это запрос к модели, и частота здесь
     * задаёт и стоимость, и то, насколько плавно выглядит текст. Пересказ
     * собирается заново из накопленного, поэтому он не «размазывается».
     */
    fun onSpoken(line: String, bookId: Long?) {
        val text = line.trim()
        if (text.isEmpty() || bookId == null) return
        synchronized(heard) {
            // Один и тот же бабл повторно не считается: страница может
            // вернуться в кадр, и пересказ рос бы от дублей.
            if (heard.isNotEmpty() && heard.last() == text) return
            heard.addLast(text)
            while (heard.size > MAX_LINES) heard.removeFirst()
            _state.value = _state.value.copy(heard = heard.size)
        }
        if (snapshot().size >= MIN_LINES_FOR_RECAP) requestRecap(bookId)
    }

    /**
     * Показать сохранённый пересказ книги.
     *
     * Читатель возвращается к книге — и видит то, что уже было написано, а не
     * пустую вкладку до первого запроса к модели.
     */
    fun loadFor(bookId: Long?) {
        val id = bookId ?: return
        val saved = store.read(id)
        if (saved.isNotBlank()) {
            _state.value = _state.value.copy(text = saved)
        }
    }

    /** Перезапуск для новой главы: пересказ старой книги не должен висеть. */
    fun reset() {
        synchronized(heard) { heard.clear() }
        recapJob?.cancel()
        recapJob = null
        requesting = false
        currentBookId = null
        _state.value = State()
    }

    /**
     * Остановить идущий запрос пересказа.
     *
     * Отдельный публичный метод, а не только сброс флага: иначе «Стоп» в
     * консоли останавливал агента и чтение, а пересказ тем временем дописывался
     * уже после остановки — и панель «Сюжет» менялась на живого читателя.
     *
     * @return true, если запрос был и действительно отменён.
     */
    fun cancel(): Boolean {
        val job = recapJob
        if (job == null || !job.isActive) return false
        job.cancel()
        _state.value = _state.value.copy(thinking = false, reason = "Остановлено")
        AiConsole.user("Сюжет: запрос пересказа отменён")
        return true
    }

    /** Запросить пересказ принудительно (кнопка «обновить»). */
    fun refresh(bookId: Long?) {
        if (bookId == null) {
            _state.value = _state.value.copy(reason = "Книга не открыта")
            return
        }
        if (snapshot().isEmpty()) {
            _state.value = _state.value.copy(reason = "Пока нечего пересказывать: не озвучено ни одной реплики")
            return
        }
        requestRecap(bookId)
    }

    private fun snapshot(): List<String> = synchronized(heard) { heard.toList() }

    /** Книга, для которой считается пересказ (задаётся при refresh). */
    @Volatile
    private var currentBookId: Long? = null

    private fun requestRecap(bookId: Long) {
        currentBookId = bookId
        val lines = snapshot()
        if (lines.isEmpty() || requesting) return
        if (!runCatching(aiKeyPresent).getOrDefault(false)) {
            _state.value = _state.value.copy(reason = "Нет ключа ИИ: пересказ пишет модель, а не приложение")
            return
        }
        requesting = true
        _state.value = _state.value.copy(thinking = true, reason = null)
        AiConsole.note(
            title = "Сюжет: запрошен пересказ",
            detail = "реплик в накопленном: ${lines.size} · книга $bookId",
        )
        recapJob = scope.launch {
            try {
                val reply = AiAssistant.chatFull(
                    userPrompt = prompt(lines),
                    systemPrompt = SYSTEM,
                    maxTokens = MAX_TOKENS,
                )
                val text = reply?.content?.trim().orEmpty()
                if (text.isEmpty()) {
                    // Модель не ответила содержательно — показываем честную
                    // причину, а не правдоподобный текст.
                    val onlyReasoning = reply?.reasoning?.isNotBlank() == true
                    _state.value = _state.value.copy(
                        thinking = false,
                        reason = if (onlyReasoning) {
                            "Модель вернула только размышления, без текста пересказа"
                        } else {
                            "Модель не ответила"
                        },
                    )
                    AiConsole.note(
                        title = "Сюжет: пересказ не получен",
                        detail = if (onlyReasoning) {
                            "модель вернула только размышления, без текста"
                        } else {
                            "модель не ответила"
                        },
                        level = AiConsole.Level.WARN,
                    )
                } else {
                    // Пересказ сразу пишется на диск: закрытие читалки не
                    // должна стирать работу, за которую заплатили запросом.
                    runCatching { store.write(currentBookId ?: 0L, text) }
                        .onFailure { logcat(LogPriority.WARN, it) { "Plot save failed" } }
                    _state.value = _state.value.copy(
                        text = text,
                        thinking = false,
                        reason = null,
                        model = reply?.model.orEmpty(),
                    )
                    AiConsole.note(
                        title = "Сюжет: пересказ готов",
                        detail = "модель ${reply?.model.orEmpty()} · символов ${text.length}",
                        level = AiConsole.Level.OK,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Reading plot recap failed" }
                _state.value = _state.value.copy(thinking = false, reason = "Ошибка: ${e.message}")
            } finally {
                requesting = false
            }
        }
    }

    companion object {
        /**
         * Промпт пересказа.
         *
         * Живёт в companion, а не в инстансе: он не читает настроек и не
         * держит состояния, а проверяться должен без Injekt и без «ключа» —
         * иначе главное требование к нему (не выдумывать сюжет) осталось бы
         * непроверяемым.
         */
        fun prompt(lines: List<String>): String = buildString {
    appendLine("Ниже реплики, произнесённые при чтении страниц манги/комикса по порядку.")
    appendLine("Перескажи это как человек, который там был: от первого лица, с эмоциями,")
    appendLine("с оценкой того, что происходило, и с тем, что чувствовали герои.")
    appendLine("Это пересказ для читателя, а не протокол и не список реплик.")
    appendLine("Опирайся ТОЛЬКО на эти строки: не додумывай сюжет, событий и персонажей,")
    appendLine("о которых в них нет. Орфографию и ошибки распознавания исправляй молча.")
    appendLine()
    appendLine("РЕПЛИКИ:")
    lines.forEach { appendLine("- $it") }
    appendLine()
    appendLine("Пиши связным текстом от первого лица, 3-6 абзацев. Без заголовков и списков.")
        }

        /** Сколько последних реплик уходит в пересказ. */
        private const val MAX_LINES = 120

        /** С какого числа реплик пересказ имеет смысл. */
        private const val MIN_LINES_FOR_RECAP = 6

        private const val MAX_TOKENS = 900

        private val SYSTEM =
            "Ты — читатель манги, который пересказывает услышанное другу. " +
                "Пишешь живым русским языком, от первого лица, с эмоциями. " +
                "Не выдумываешь того, чего в репликах нет."
    }
}