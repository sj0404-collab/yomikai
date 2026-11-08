package mihon.data.ocr

/**
 * Потолок высоты области распознавания.
 *
 * Детектор иногда склеивает реплику с пустым полем под ней и отдаёт бокс на полстраницы.
 * Такой бокс даёт паузу и «прыжок» при чтении, поэтому высоту региона режем:
 * не больше [MAX_REGION_HEIGHT] высоты страницы, верхняя граница сохраняется.
 */
object RegionCaps {
    const val MAX_REGION_HEIGHT = 0.22f

    /** Возвращает (top, bottom) с высотой не больше [maxFraction] от страницы. */
    fun capHeight(top: Float, bottom: Float, maxFraction: Float = MAX_REGION_HEIGHT): Pair<Float, Float> {
        val t = top.coerceIn(0f, 1f)
        val b = bottom.coerceIn(0f, 1f)
        if (b <= t) return t to b
        val cap = maxFraction.coerceIn(0.01f, 1f)
        if (b - t <= cap) return t to b
        return t to (t + cap).coerceAtMost(1f)
    }
}
