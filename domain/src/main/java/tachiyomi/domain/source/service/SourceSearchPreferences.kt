package tachiyomi.domain.source.service

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * Настройки поиска по внешним источникам (расширениям).
 *
 * Живёт в domain, а не в app, потому что фильтрация выдачи выполняется в слое
 * данных ([tachiyomi.data.source.SourceSearchPagingSource]), а слой данных
 * зависит только от domain.
 */
class SourceSearchPreferences(
    private val preferenceStore: PreferenceStore,
) {

    /**
     * Отбрасывать карточки, в названии которых нет слов запроса.
     *
     * По умолчанию включено: часть расширений на запрос отдаёт своё «популярное»
     * (на устройстве: «I am» в источнике Desu → «How To Survive in Bed With The
     * Emperor»), и это нельзя выдавать за результат поиска.
     */
    val relevanceFilter: Preference<Boolean> = preferenceStore.getBoolean(
        "source_search_relevance_filter",
        true,
    )
}
