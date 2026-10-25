package eu.kanade.tachiyomi.ui.library

import eu.kanade.tachiyomi.source.getNameForMangaInfo
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource

private const val LOCAL_SOURCE_ID_ALIAS = "local"

data class LibraryItem(
    val libraryManga: LibraryManga,
    val downloadCount: Int,
    val unreadCount: Long,
    val isLocal: Boolean,
    val badges: Badges,
) {
    val id: Long = libraryManga.id

    /**
     * Checks if a query matches the manga
     *
     * @param constraint the query to check.
     * @return true if the manga matches the query, false otherwise.
     */
    fun matches(constraint: String, sourceManager: SourceManager): Boolean {
        // Источник нужен только для `src:` и для сравнения с его названием,
        // поэтому создаётся лениво: раньше getOrStub вызывался для КАЖДОГО
        // тайтла на каждое нажатие клавиши, и поиск по большой библиотеке
        // заметно тормозил.
        val sourceName by lazy { sourceManager.getOrStub(libraryManga.manga.source).getNameForMangaInfo() }
        if (constraint.startsWith("id:", true)) {
            return id == constraint.substringAfter("id:").toLongOrNull()
        } else if (constraint.startsWith("src:", true)) {
            val querySource = constraint.substringAfter("src:")
            return if (querySource.equals(LOCAL_SOURCE_ID_ALIAS, ignoreCase = true)) {
                libraryManga.manga.source == LocalSource.ID
            } else {
                libraryManga.manga.source == querySource.toLongOrNull()
            }
        }
        // Мягкий поиск: «ё» = «е», без учёта регистра и знаков, и все слова
        // запроса должны найтись в любом порядке. Раньше искалась ТОЧНАЯ
        // подстрока, поэтому «берс макс» не находило «Берсерк Максимус», а
        // «елка» — «Ёлка».
        val haystack = buildString {
            append(libraryManga.manga.title).append(' ')
            libraryManga.manga.author?.let { append(it).append(' ') }
            libraryManga.manga.artist?.let { append(it).append(' ') }
            libraryManga.manga.description?.let { append(it).append(' ') }
            libraryManga.manga.genre?.let { g -> g.forEach { append(it).append(' ') } }
        }
        if (softMatch(haystack, constraint)) return true
        return libraryManga.manga.title.contains(constraint, true) ||
            (libraryManga.manga.author?.contains(constraint, true) ?: false) ||
            (libraryManga.manga.artist?.contains(constraint, true) ?: false) ||
            (libraryManga.manga.description?.contains(constraint, true) ?: false) ||
            constraint.split(",").map { it.trim() }.all { subconstraint ->
                checkNegatableConstraint(subconstraint) {
                    sourceName.contains(it, true) ||
                        (libraryManga.manga.genre?.any { genre -> genre.equals(it, true) } ?: false)
                }
            }
    }

    /**
     * Checks a predicate on a negatable constraint. If the constraint starts with a minus character,
     * the minus is stripped and the result of the predicate is inverted.
     *
     * @param constraint the argument to the predicate. Inverts the predicate if it starts with '-'.
     * @param predicate the check to be run against the constraint.
     * @return !predicate(x) if constraint = "-x", otherwise predicate(constraint)
     */
    private fun checkNegatableConstraint(
        constraint: String,
        predicate: (String) -> Boolean,
    ): Boolean {
        return if (constraint.startsWith("-")) {
            !predicate(constraint.substringAfter("-").trimStart())
        } else {
            predicate(constraint)
        }
    }

    data class Badges(
        val downloadCount: Int,
        val unreadCount: Long,
        val isLocal: Boolean,
        val sourceLanguage: String,
    )
}

/** Нормализация для поиска: регистр, «ё», знаки → пробелы. */
private fun normalizeForSearch(text: String): String =
    text.lowercase()
        .replace('ё', 'е')
        .map { if (it.isLetterOrDigit()) it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ")

/**
 * Все слова запроса есть в тексте (в любом порядке). Пустой запрос совпадает
 * со всем — вызывающий код такой случай не использует, но поведение предсказуемо.
 */
private fun softMatch(haystack: String, needle: String): Boolean {
    val query = normalizeForSearch(needle.substringAfterLast(','))
    if (query.isBlank()) return false
    val text = normalizeForSearch(haystack)
    return query.split(' ').all { token -> text.contains(token) }
}
