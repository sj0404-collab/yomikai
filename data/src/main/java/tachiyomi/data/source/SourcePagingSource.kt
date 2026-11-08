package tachiyomi.data.source

import androidx.paging.PagingState
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.source.service.SourceSearchPreferences
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.library.model.LibraryIndex
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.repository.SourcePagingSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SourceSearchPagingSource(
    source: Source,
    private val query: String,
    private val filters: FilterList,
) : BaseSourcePagingSource(source) {

    /**
     * Поиск с проверкой релевантности.
     *
     * Часть расширений на запрос возвращает своё «популярное»: на устройстве
     * запрос «I am» в источнике Desu отдал «How To Survive in Bed With The
     * Emperor» и ещё два десятка тайтлов, где нет ни «i», ни «am». Пользователь
     * видел это как результат поиска и решал, что сломан поиск в приложении.
     *
     * Поэтому карточки, в названии которых нет слов запроса, отбрасываются, а
     * счётчики уходят в [SearchRelevance] — интерфейс показывает, что источник
     * запрос проигнорировал. Если совпадений нет, пролистываем ещё до
     * [MAX_EXTRA_PAGES] страниц: совпадение может быть не на первой.
     */
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        val first = source.getSearchManga(currentPage, query, filters)
        // Пользователь может вернуть «как отдаёт источник» — тогда ничего не
        // фильтруем и не считаем.
        if (query.isBlank() || !searchPreferences.relevanceFilter.get()) return first

        var page = first
        var pageNumber = currentPage
        var fetchedPages = 0
        var returned = page.mangas.size
        var matched = page.mangas.count { LibraryIndex.matchesText(it.title, query) }

        while (matched == 0 && page.hasNextPage && fetchedPages < MAX_EXTRA_PAGES) {
            pageNumber++
            page = source.getSearchManga(pageNumber, query, filters)
            fetchedPages++
            returned += page.mangas.size
            matched += page.mangas.count { LibraryIndex.matchesText(it.title, query) }
        }

        SearchRelevance.report(
            sourceId = source.id,
            stat = SearchRelevance.Stat(
                query = query,
                returned = returned,
                matched = matched,
                pages = fetchedPages + 1,
            ),
        )

        val relevant = page.mangas.filter { LibraryIndex.matchesText(it.title, query) }
        return MangasPage(relevant, page.hasNextPage)
    }

    private val searchPreferences: SourceSearchPreferences by lazy { Injekt.get() }

    private companion object {
        /** Сколько дополнительных страниц листать, если на первой нет совпадений. */
        const val MAX_EXTRA_PAGES = 2
    }
}

class SourcePopularPagingSource(source: Source) : BaseSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source.getPopularManga(currentPage)
    }
}

class SourceLatestPagingSource(source: Source) : BaseSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source.getLatestUpdates(currentPage)
    }
}

abstract class BaseSourcePagingSource(
    protected val source: Source,
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
) : SourcePagingSource() {

    private val seenManga = hashSetOf<String>()

    abstract suspend fun requestNextPage(currentPage: Int): MangasPage

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, Manga> {
        val page = params.key ?: 1

        return try {
            val mangasPage = withIOContext { requestNextPage(page.toInt()) }

            // Пустая выдача — это НЕ ошибка. Раньше здесь бросался
            // NoResultsException, и «ничего не найдено» показывалось красным
            // «ошибка», а ещё портило счётчик здоровья источника: пять пустых
            // поисков помечали живой сайт сломанным.
            if (mangasPage.mangas.isEmpty()) {
                return LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)
            }

            val manga = mangasPage.mangas
                .map { it.toDomainManga(source.id) }
                .filter { seenManga.add(it.url) }
                .let { networkToLocalManga(it) }

            LoadResult.Page(
                data = manga,
                prevKey = null,
                nextKey = if (mangasPage.hasNextPage) page + 1 else null,
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Long, Manga>): Long? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey ?: anchorPage?.nextKey
        }
    }
}

class NoResultsException : Exception()
