package eu.kanade.tachiyomi.ui.koma

/**
 * Правила, по которым байты страницы превращаются в растр.
 *
 * Вынесены из [KomaPageStore] отдельным объектом без Android: их можно проверить
 * тестом, а не глазами на устройстве. Здесь нет ни потоков, ни Bitmap — только
 * два решения, которые раньше принимались «на месте» и молча ломались:
 *
 *  1. **Это вообще изображение?** До 2.9.39 в декодер уходили любые байты,
 *     которые отдал источник: страница ошибки, оглавление архива, обрезанная
 *     запись. Нативный Skia на таком входе не возвращает `null`, а падает
 *     сигналом (`SEGV_ACCERR`), и поймать это в Kotlin невозможно. Поэтому
 *     сигнатура проверяется ДО декодера.
 *  2. **С каким уменьшением читать?** Слишком большая страница — это пик памяти
 *     на телефоне с 3 ГБ, а `inSampleSize` применяется на этапе декода, то есть
 *     экономит именно то, чего не хватает.
 */
internal object KomaImageBytes {

    /** Предел распакованной страницы: больше — уже не читаемо, только память. */
    const val MAX_PIXELS = 16_000_000L

    /** Предел ширины после уменьшения: экран телефона шире не показывает. */
    const val MAX_WIDTH = 2600

    /** С этой ширины страница отдаётся в 16 битах (RGB_565), а не в 32. */
    const val COMPACT_FROM_WIDTH = 1400

    /**
     * Байты выглядят как картинка, которую умеет BitmapFactory.
     *
     * Проверяются только сигнатуры: JPEG (`FF D8 FF`), PNG, WebP (RIFF…WEBP),
     * GIF, BMP. Всё остальное — не наша страница.
     */
    fun looksLikeImage(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.size < 12) return false
        fun at(index: Int): Int = bytes[index].toInt() and 0xFF
        return when {
            // JPEG: SOI + начало маркера. Один FF D8 мало: встречается в мусоре.
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> true
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> true // PNG
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x38 -> true // GIF8
            at(0) == 0x42 && at(1) == 0x4D -> true // BMP («BM»)
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> true // RIFF/WEBP
            else -> false
        }
    }

    /**
     * Во сколько раз уменьшать при декоде (`inSampleSize` — степень двойки).
     *
     * Уменьшаем, пока страница не влезает в [MAX_PIXELS] или пока её ширина
     * больше [MAX_WIDTH]. Раньше порог считался на месте и менялся вместе с
     * кодом; теперь он здесь и проверяется тестом.
     */
    fun sampleFor(
        width: Int,
        height: Int,
        maxPixels: Long = MAX_PIXELS,
        maxWidth: Int = MAX_WIDTH,
    ): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (
            width.toLong() * height / (sample.toLong() * sample) > maxPixels ||
            width / sample > maxWidth
        ) {
            // Защита от бесконечного цикла на абсурдных входах.
            if (sample >= 1 shl 12) break
            sample *= 2
        }
        return sample
    }

    /** Отдавать ли страницу в 16 битах: широкая страница не нуждается в 32. */
    fun prefer565(widthAfterSample: Int): Boolean = widthAfterSample > COMPACT_FROM_WIDTH
}
