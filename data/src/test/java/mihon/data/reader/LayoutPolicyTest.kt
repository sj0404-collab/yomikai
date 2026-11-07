package mihon.data.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class LayoutPolicyTest {

    @Test
    fun `манга читается постранично даже если выбрана лента`() {
        assertEquals(ReaderMode.PAGED, LayoutPolicy.modeFor(ContentKind.MANGA, ReaderMode.STRIP))
        assertEquals(ReaderMode.PAGED, LayoutPolicy.modeFor(ContentKind.MANGA, null))
    }

    @Test
    fun `вебтун читается только лентой даже если выбраны страницы`() {
        assertEquals(ReaderMode.STRIP, LayoutPolicy.modeFor(ContentKind.WEBTOON, ReaderMode.PAGED))
        assertEquals(ReaderMode.STRIP, LayoutPolicy.modeFor(ContentKind.WEBTOON, null))
    }

    @Test
    fun `неизвестный тип уважает ручной выбор и по умолчанию страницы`() {
        assertEquals(ReaderMode.STRIP, LayoutPolicy.modeFor(ContentKind.UNKNOWN, ReaderMode.STRIP))
        assertEquals(ReaderMode.PAGED, LayoutPolicy.modeFor(ContentKind.UNKNOWN, null))
    }

    @Test
    fun `тип книги по метке ленты и по пропорции страниц`() {
        assertEquals(ContentKind.WEBTOON, LayoutPolicy.kindOf(isLongStrip = true, averageAspect = 0f))
        assertEquals(ContentKind.WEBTOON, LayoutPolicy.kindOf(isLongStrip = false, averageAspect = 5.2f))
        assertEquals(ContentKind.MANGA, LayoutPolicy.kindOf(isLongStrip = false, averageAspect = 1.45f))
        assertEquals(ContentKind.UNKNOWN, LayoutPolicy.kindOf(isLongStrip = false, averageAspect = 0f))
    }

    @Test
    fun `ComicInfo Manga=YesAndRightToLeft означает мангу даже у вытянутой страницы`() {
        assertEquals(
            ContentKind.MANGA,
            LayoutPolicy.kindOf(comicInfoManga = "YesAndRightToLeft", isLongStrip = true, averageAspect = 5f),
        )
    }

    @Test
    fun `ComicInfo Manga=No это тоже постраничная книга`() {
        assertEquals(
            ContentKind.MANGA,
            LayoutPolicy.kindOf(comicInfoManga = "No", isLongStrip = false, averageAspect = 1.4f),
        )
    }

    @Test
    fun `без ComicInfo вебтун определяется по пропорции`() {
        assertEquals(
            ContentKind.WEBTOON,
            LayoutPolicy.kindOf(comicInfoManga = null, isLongStrip = false, averageAspect = 4.5f),
        )
        assertEquals(
            ContentKind.MANGA,
            LayoutPolicy.kindOf(comicInfoManga = "Unknown", isLongStrip = false, averageAspect = 1.5f),
        )
    }

    @Test
    fun `вебтун по ComicInfo поля нет, поэтому пропорция решает`() {
        assertEquals(ReaderMode.STRIP, LayoutPolicy.modeFor(LayoutPolicy.kindOf(null, false, 6f), null))
    }

    @Test
    fun `список допустимых режимов не даёт переключить мангу в ленту`() {
        assertFalse(ReaderMode.STRIP in LayoutPolicy.allowedModes(ContentKind.MANGA))
        assertFalse(ReaderMode.PAGED in LayoutPolicy.allowedModes(ContentKind.WEBTOON))
    }
}
