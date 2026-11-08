package tachiyomi.data.source

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Отчёт о том, насколько выдача источника совпала с запросом.
 *
 * Зачем отдельный объект: фильтрация происходит в [SourceSearchPagingSource],
 * а показать её результат надо в интерфейсе. Счётчики складываются здесь, по
 * источнику, и UI подписывается на изменения.
 *
 * Это не «здоровье источника»: пустая выдача и игнорирование запроса — разные
 * вещи, и путать их нельзя (иначе живой сайт помечается сломанным).
 */
object SearchRelevance {

    data class Stat(
        val query: String = "",
        /** Сколько карточек источник отдал до фильтра. */
        val returned: Int = 0,
        /** Сколько из них содержат слова запроса. */
        val matched: Int = 0,
        /** Сколько страниц пришлось пролистать, чтобы найти совпадения. */
        val pages: Int = 0,
    ) {
        /** Источник вернул карточки, но ни одна не по запросу. */
        val ignoredQuery: Boolean get() = query.isNotBlank() && returned > 0 && matched == 0

        /** Совпало частично — остальное отброшено как «не по запросу». */
        val partial: Boolean get() = query.isNotBlank() && returned > 0 && matched in 1 until returned
    }

    private val _stats = MutableStateFlow<Map<Long, Stat>>(emptyMap())
    val stats: StateFlow<Map<Long, Stat>> = _stats.asStateFlow()

    fun report(sourceId: Long, stat: Stat) {
        if (stat.query.isBlank()) {
            reset(sourceId)
            return
        }
        _stats.value = _stats.value + (sourceId to stat)
    }

    fun of(sourceId: Long): Stat? = _stats.value[sourceId]

    fun reset(sourceId: Long) {
        if (sourceId in _stats.value) {
            _stats.value = _stats.value - sourceId
        }
    }
}
