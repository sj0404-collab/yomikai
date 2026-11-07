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
        // Манга: по умолчанию постранично, но РУЧНОЙ выбор ленты уважаем —
        // человек вправе открыть мангу лентой, и тогда читаем её лентой,
        // сохраняя порядок реплик.
        ContentKind.MANGA -> manual ?: ReaderMode.PAGED
        ContentKind.WEBTOON -> ReaderMode.STRIP
        ContentKind.UNKNOWN -> manual ?: ReaderMode.PAGED
    }

    /**
     * Тип книги по ComicInfo.xml — это единственный стандартный источник ответа.
     * Поле `<Manga>`: Yes / YesAndRightToLeft — манга (постранично), No — западный
     * комикс (тоже постранично). Про вебтун в схеме ComicInfo поля НЕТ, поэтому
     * лента определяется пропорцией страниц: полотно выше, чем в 3 раза шире.
     *
     * Порядок важен: если ComicInfo прямо сказал «манга» или «не манга» — верим
     * ему, даже когда страницы вытянутые (бывают длинные манга-страницы).
     */
    fun kindOf(comicInfoManga: String?, isLongStrip: Boolean, averageAspect: Float): ContentKind {
        when (comicInfoManga?.trim()?.lowercase()) {
            "yes", "yesandrighttoleft" -> return ContentKind.MANGA
            "no" -> return ContentKind.MANGA
        }
        return kindOf(isLongStrip, averageAspect)
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
        ContentKind.MANGA -> setOf(ReaderMode.PAGED, ReaderMode.STRIP)
        ContentKind.WEBTOON -> setOf(ReaderMode.STRIP)
        ContentKind.UNKNOWN -> setOf(ReaderMode.PAGED, ReaderMode.STRIP)
    }
}
