package eu.kanade.tachiyomi.ui.koma

/**
 * Полоса страницы — единица чтения и распознавания.
 *
 * Зачем: у манхвы «страница» часто в 3–10 раз выше экрана. Раньше экран отдавал
 * в распознавание и озвучку ВСЮ страницу — отсюда жалоба «читает текст за
 * пределами экрана» и минуты ожидания на длинных кадрах. Теперь страница режется
 * на полосы по реальной видимой высоте экрана (с перекрытием), и каждая полоса
 * читается тогда, когда она на экране.
 *
 * Координаты — доли высоты изображения (0..1): так они не зависят от того, в
 * каком разрешении страница декодирована.
 */
data class KomaBand(val index: Int, val top: Float, val bottom: Float) {

    /** Доля высоты страницы, которую занимает полоса. */
    val height: Float get() = (bottom - top).coerceAtLeast(0.01f)

    /** Полоса покрывает страницу целиком (обычная страница, а не вебтун). */
    val whole: Boolean get() = top <= 0.002f && bottom >= 0.998f

    /** Пересчёт координаты из полосы в координаты страницы. */
    fun mapY(y: Float): Float = (top + y * height).coerceIn(0f, 1f)

    companion object {
        val Whole = KomaBand(0, 0f, 1f)
    }
}

object KomaBands {

    /** Перекрытие соседних полос: реплика на стыке не должна теряться. */
    const val OVERLAP = 0.15f

    /** Больше восьми полос на страницу не бывает: такие кадры режем по ширине. */
    const val MAX = 8

    /**
     * Полосы страницы.
     *
     * @param viewportFrac какая доля высоты страницы видна на экране (0..1).
     *   `null` или значение ≥ [WHOLE_THRESHOLD] — страница читается целиком.
     */
    fun of(viewportFrac: Float?): List<KomaBand> {
        if (viewportFrac == null || viewportFrac <= 0f || viewportFrac >= WHOLE_THRESHOLD) {
            return listOf(KomaBand.Whole)
        }
        val visible = viewportFrac.coerceIn(0.12f, WHOLE_THRESHOLD)
        val bands = ArrayList<KomaBand>(MAX)
        var top = 0f
        var index = 0
        while (index < MAX) {
            val bottom = (top + visible).coerceAtMost(1f)
            bands += KomaBand(index, top, bottom)
            if (bottom >= 0.999f) break
            // Следующая полоса начинается с перекрытием — стык всегда попадает
            // в одну из полос целиком.
            top = (bottom - visible * OVERLAP).coerceAtLeast(0.0001f)
            index++
        }
        // Последняя полоса дотягивается до низа страницы, иначе хвост текста
        // остаётся нечитанным.
        val last = bands.lastOrNull()
        if (last != null && last.bottom < 0.999f) {
            bands[bands.lastIndex] = last.copy(bottom = 1f)
        }
        return bands
    }

    /** Страница целиком: обычный режим для кадров, которые влезают в экран. */
    val whole: List<KomaBand> get() = listOf(KomaBand.Whole)

    private const val WHOLE_THRESHOLD = 0.72f
}

/** Сколько разных полос максимум может быть у одной страницы (для кэша). */
const val MAX_BANDS: Int = KomaBands.MAX
