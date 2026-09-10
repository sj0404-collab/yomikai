package eu.kanade.domain.source.model

enum class ContentType(val displayName: String, val icon: String) {
    MANGA("Манга", "📚"),
    ANIME("Аниме", "🎬"),
    RANOBE("Ранобэ", "📖"),
    BOOKS("Книги", "🎧");

    companion object {
        val DEFAULT = MANGA
    }
}
