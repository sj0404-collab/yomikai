package tachiyomi.domain.source.repository

import androidx.paging.PagingSource
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.model.SourceWithCount

typealias SourcePagingSource = PagingSource<Long, Manga>

interface SourceRepository {

    fun getSources(): Flow<List<Source>>

    fun getOnlineSources(): Flow<List<Source>>

    fun getSourcesWithFavoriteCount(): Flow<List<Pair<Source, Long>>>

    fun getSourcesWithNonLibraryManga(): Flow<List<SourceWithCount>>

    fun search(sourceId: Long, query: String, filterList: FilterList): SourcePagingSource

    fun getPopular(sourceId: Long): SourcePagingSource

    fun getLatest(sourceId: Long): SourcePagingSource

    /**
     * Выдача источника по первой букве названия.
     *
     * [baseQuery] — раздел, который листаем (популярное, новинки или текущий
     * запрос пользователя). Буква НЕ уходит в источник строкой поиска: часть
     * сайтов по одной букве ничего не находит и отдаёт пустоту.
     */
    fun byLetter(sourceId: Long, letter: String, baseQuery: String, filterList: FilterList): SourcePagingSource
}
