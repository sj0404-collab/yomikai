package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.ReaderActivity

/**
 * Implementation of a left to right PagerViewer.
 */
class L2RPagerViewer(activity: ReaderActivity) : PagerViewer(activity) {
    /**
     * Creates a new left to right pager.
     */
    override fun createPager(): Pager {
        return Pager(activity)
    }
}

/**
 * Implementation of a right to left PagerViewer.
 */
class R2LPagerViewer(activity: ReaderActivity) : PagerViewer(activity) {
    /**
     * Creates a new right to left pager.
     */
    override fun createPager(): Pager {
        return Pager(activity)
    }

    /**
     * Moves to the next page. On a R2L pager the next page is the one at the left.
<<<<<<< HEAD
     */
    override fun moveToNext() {
        if (tryAdvancePanelForward()) return
        moveLeft()
=======
     * `false` — открыта последняя страница главы.
     */
    override fun moveToNext(): Boolean {
        if (tryAdvancePanelForward()) return true
        return moveLeft()
>>>>>>> 9b00d60 (fix(ocr): склейка коротких слов, словарь 56k словоформ, порядок реплик по панелям)
    }

    /**
     * Moves to the previous page. On a R2L pager the previous page is the one at the right.
<<<<<<< HEAD
     */
    override fun moveToPrevious() {
        if (tryAdvancePanelBackward()) return
        moveRight()
=======
     * `false` — открыта первая страница главы.
     */
    override fun moveToPrevious(): Boolean {
        if (tryAdvancePanelBackward()) return true
        return moveRight()
>>>>>>> 9b00d60 (fix(ocr): склейка коротких слов, словарь 56k словоформ, порядок реплик по панелям)
    }
}

/**
 * Implementation of a vertical (top to bottom) PagerViewer.
 */
class VerticalPagerViewer(activity: ReaderActivity) : PagerViewer(activity) {
    /**
     * Creates a new vertical pager.
     */
    override fun createPager(): Pager {
        return Pager(activity, isHorizontal = false)
    }
}
