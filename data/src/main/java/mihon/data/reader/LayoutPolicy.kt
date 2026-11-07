package mihon.data.reader

/** Что за книга: манга (страницы) или вебтун (непрерывная лента). */
enum class ContentKind { MANGA, WEBTOON, UNKNOWN }

/** Как читать: постранично или лентой. */
enum class ReaderMode { PAGED, STRIP }

/**
 * Одно правило на оба ридера: режим определяется ТИПОМ КНИГИ, а не тем, что было
 * выбрано в прошлый раз.
 *
 * Раньше ручной выбор перебивал всё, и манга, открытая «в ленте», прыгала между
 * репликами — текст верный, порядок рваный. Теперь: манга — всегда постранично,
 * даже если открыта лентой; вебтун — только вертикальная лента; неизвестный тип —
 * уважаем ручной выбор, иначе постранично (безопаснее: страница не «уезжает»).
 */
object LayoutPolicy {

    fun modeFor(kind: ContentKind, manual: ReaderMode?): ReaderMode = when (kind) {
        ContentKind.MANGA -> ReaderMode.PAGED
        ContentKind.WEBTOON -> ReaderMode.STRIP
        ContentKind.UNKNOWN -> manual ?: ReaderMode.PAGED
    }

    /** Тип книги: метка long strip у источника либо полотно выше, чем в 3 раза шире. */
    fun kindOf(isLongStrip: Boolean, averageAspect: Float): ContentKind = when {
        isLongStrip -> ContentKind.WEBTOON
        averageAspect >= 3f -> ContentKind.WEBTOON
        averageAspect > 0f -> ContentKind.MANGA
        else -> ContentKind.UNKNOWN
    }

    /** Ручной выбор не должен пережить смену типа книги. */
    fun allowedModes(kind: ContentKind): Set<ReaderMode> = when (kind) {
        ContentKind.MANGA -> setOf(ReaderMode.PAGED)
        ContentKind.WEBTOON -> setOf(ReaderMode.STRIP)
        ContentKind.UNKNOWN -> setOf(ReaderMode.PAGED, ReaderMode.STRIP)
    }
}
