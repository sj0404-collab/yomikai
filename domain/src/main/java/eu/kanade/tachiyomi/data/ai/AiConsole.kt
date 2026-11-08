package eu.kanade.tachiyomi.data.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * Живой журнал всей работы ИИ в приложении: раунды агента, запросы к модели,
 * каждый инструмент с временем и итогом, стадии OCR и авточтения.
 *
 * Зачем он, когда уже есть три журнала по отдельности:
 *  • `AiAssistant.log()` — только запросы к модели, кольцо на 40 записей, и
 *    видно его лишь в настройках озвучки, причём только если включён
 *    `aiGenderVoices`;
 *  • `OcrHistoryStore` — только OCR и озвучка, ИИ-вызовов агента там нет;
 *  • ход агента пишется в сообщение чата, но там он живёт вместе с диалогом и
 *    теряется при прокрутке.
 *
 * То есть посмотреть «что сейчас делает ИИ» было негде: журнал модели — в
 * настройках, журнал чтения — в другом экране, а раунды агента — внутри ответа
 * чата. Здесь всё это в одном списке, который обновляется на каждый чих.
 *
 * Поток общий для процесса, записи — из любых потоков (`update` атомарен).
 * Кольцо ограничено [LIMIT], поэтому часовой скан главы не съедает память.
 */
object AiConsole {

    /** Что за строка в журнале. */
    enum class Kind {
        /** Раунд агентского цикла: начался. */
        ROUND,

        /** Запрос к модели: промпт, ответ, время. */
        MODEL,

        /** Инструмент агента: что вызвали, что вернуло, за сколько. */
        TOOL,

        /** Решение человека: остановить, сменить провайдера. */
        USER,

        /** Стадия распознавания/озвучки. */
        OCR,

        /** Заметка о состоянии системы. */
        NOTE,
    }

    /** Насколько строго читать строку. */
    enum class Level { INFO, OK, WARN, ERROR }

    /**
     * Одна строка журнала.
     *
     * @param id порядковый номер: список живёт в StateFlow, и без него ключ
     *   строки перескакивал бы при обрезке кольца.
     * @param title короткая строка — что произошло.
     * @param detail подробности: промпт, вывод инструмента, причина ошибки.
     * @param ms сколько заняло, если это запрос или инструмент.
     */
    data class Entry(
        val id: Long,
        val timeMs: Long,
        val kind: Kind,
        val level: Level = Level.INFO,
        val title: String,
        val detail: String? = null,
        val ms: Long? = null,
    )

    private val counter = AtomicLong(0)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    /** Журнал: новые записи сверху. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** Сколько строк держим. Скан главы даёт сотни, но не десятки тысяч. */
    const val LIMIT = 400

    fun round(
        title: String,
        detail: String? = null,
        level: Level = Level.INFO,
    ) = add(Kind.ROUND, level, title, detail)

    fun model(
        title: String,
        detail: String? = null,
        ms: Long? = null,
        level: Level = Level.INFO,
    ) = add(Kind.MODEL, level, title, detail, ms)

    fun tool(
        title: String,
        detail: String? = null,
        ms: Long? = null,
        level: Level = Level.INFO,
    ) = add(Kind.TOOL, level, title, detail, ms)

    fun user(title: String, detail: String? = null) = add(Kind.USER, Level.OK, title, detail)

    fun ocr(
        title: String,
        detail: String? = null,
        level: Level = Level.INFO,
    ) = add(Kind.OCR, level, title, detail)

    fun note(
        title: String,
        detail: String? = null,
        level: Level = Level.INFO,
    ) = add(Kind.NOTE, level, title, detail)

    fun error(title: String, detail: String? = null) = add(Kind.TOOL, Level.ERROR, title, detail)

    private fun add(
        kind: Kind,
        level: Level,
        title: String,
        detail: String? = null,
        ms: Long? = null,
    ) {
        val entry = Entry(
            id = counter.incrementAndGet(),
            timeMs = System.currentTimeMillis(),
            kind = kind,
            level = level,
            title = title.take(MAX_TITLE),
            detail = detail?.take(MAX_DETAIL)?.ifBlank { null },
            ms = ms,
        )
        _entries.update { current ->
            val next = ArrayList<Entry>(minOf(current.size + 1, LIMIT))
            next.add(entry)
            next.addAll(current)
            if (next.size > LIMIT) next.subList(LIMIT, next.size).clear()
            next
        }
    }

    /** Промпты и ответы модели длинные, а журнал — для чтения глазами. */
    private const val MAX_TITLE = 160

    /** Подробности всё же нужны, но не целиком: модель отдаёт килобайты. */
    private const val MAX_DETAIL = 4000

    fun clear() {
        _entries.value = emptyList()
    }
}