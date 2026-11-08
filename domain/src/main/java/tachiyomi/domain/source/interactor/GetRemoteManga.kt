package tachiyomi.domain.source.interactor

import eu.kanade.tachiyomi.source.model.FilterList
import tachiyomi.domain.source.repository.SourcePagingSource
import tachiyomi.domain.source.repository.SourceRepository

class GetRemoteManga(
    private val repository: SourceRepository,
) {

    operator fun invoke(
        sourceId: Long,
        query: String,
        filterList: FilterList,
        letter: String? = null,
    ): SourcePagingSource {
        // Указатель по букве: листаем раздел, а букву проверяем сами.
        if (letter != null) return repository.byLetter(sourceId, letter, query, filterList)
        return when (query) {
            QUERY_POPULAR -> repository.getPopular(sourceId)
            QUERY_LATEST -> repository.getLatest(sourceId)
            else -> repository.search(sourceId, query, filterList)
        }
    }

    companion object {
        const val QUERY_POPULAR = "eu.kanade.domain.source.interactor.POPULAR"
        const val QUERY_LATEST = "eu.kanade.domain.source.interactor.LATEST"
    }
}
