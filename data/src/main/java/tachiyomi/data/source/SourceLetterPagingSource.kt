package tachiyomi.data.source

import androidx.paging.PagingSource
import androidx.paging.PagingState
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.library.model.LibraryIndex
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Выдача источника по первой букве названия.
 *
 * Зачем отдельный пейджер: жалоба с устройства — выбрал букву «X» в указателе
 * источника и получил «Нет результатов». Причина в том, что буква уходила в
 * источник как строка поиска, а сайты по одной букве не ищут.
 *
 * Здесь наоборот: источник листается как каталог (популярное/новинки или текущий
 * запрос пользователя), а буква проверяется на нашей стороне. Листаем сразу
 * несколько страниц каталога за один заход — иначе совпадение на второй странице
 * выглядело бы как пустой результат.
 */
class SourceLetterPagingSource(
    private val source: Source,
    private val letter: String,
    private val baseQuery: String,
    private val filters: FilterList,
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
) : PagingSource<Long, Manga>() {

    private val seenManga = hashSetOf<String>()

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, Manga> {
        var cursor = params.key ?: 1L
        val matched = mutableListOf<SManga>()
        var hasMore = true
        var scanned = 0

        try {
            while (scanned < MAX_PAGES_PER_LOAD && hasMore) {
                val page = withIOContext { fetchPage(cursor.toInt()) }
                scanned++
                matched += page.mangas.filter { LibraryIndex.matchesLetter(it.title, letter) }
                hasMore = page.hasNextPage
                cursor++
                // Набрали достаточно — не дёргаем источник лишний раз.
                if (matched.size >= TARGET_PER_PAGE) break
            }
        } catch (e: Exception) {
            return LoadResult.Error(e)
        }

        val data = matched
            .map { it.toDomainManga(source.id) }
            .filter { seenManga.add(it.url) }
            .let { networkToLocalManga(it) }

        return LoadResult.Page(
            data = data,
            prevKey = null,
            nextKey = if (hasMore) cursor else null,
        )
    }

    private suspend fun fetchPage(page: Int): MangasPage = when (baseQuery) {
        QUERY_POPULAR -> source.getPopularManga(page)
        QUERY_LATEST -> source.getLatestUpdates(page)
        else -> source.getSearchManga(page, baseQuery, filters)
    }

    override fun getRefreshKey(state: PagingState<Long, Manga>): Long? = null

    companion object {
        /** Сколько страниц каталога просматривать за один запрос пейджера. */
        const val MAX_PAGES_PER_LOAD = 6

        /** Сколько совпадений достаточно, чтобы не листать дальше. */
        const val TARGET_PER_PAGE = 24

        // Значения совпадают с GetRemoteManga: сюда приходит listing.query как есть.
        const val QUERY_POPULAR = "eu.kanade.domain.source.interactor.POPULAR"
        const val QUERY_LATEST = "eu.kanade.domain.source.interactor.LATEST"
    }
}
