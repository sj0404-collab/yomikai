package mihon.data.ocr

import mihon.domain.ocr.service.ScanRegion

/**
 * Тип контента, под который подбираются параметры детектора и распознавания.
 *
 * Пресет меняет ТОЛЬКО числовые параметры и порядок чтения: он не подменяет
 * модель и не включает словарную коррекцию. Значения подобраны под форму
 * кадров, а не под конкретный тайтл.
 */
enum class OcrContentType(
    val id: String,
    val title: String,
    val hint: String,
    /**
     * Режим чтения, который соответствует пресету. Совпадает с порядком чтения
     * OCR ([OcrTuning.readingOrder]) — связь проверяет `OcrViewerHintTest`.
     */
    val viewer: OcrViewerHint = OcrViewerHint.KEEP,
) {
    /**
     * Универсальный профиль. В точности повторяет значения, которые раньше
     * были зашиты константами в [CyrillicOcrEngine], поэтому поведение
     * приложения без явного выбора пресета не меняется.
     */
    BALANCED(
        id = "balanced",
        title = "Сбалансированный",
        hint = "Поведение по умолчанию: параметры прежних констант движка.",
    ),

    /**
     * Японская манга: мелкие буквы, плотные баллоны, чтение справа налево,
     * много коротких реплик на страницу.
     */
    MANGA(
        id = "manga",
        title = "Манга",
        hint = "Мелкий плотный текст в баллонах, чтение справа налево.",
        viewer = OcrViewerHint.PAGER_RTL,
    ),

    /**
     * Корейская манхва/вебтун: длинные вертикальные полосы, крупные надписи,
     * широкий межсловный пробел, много пустого фона между репликами.
     */
    MANHWA(
        id = "manhwa",
        title = "Манхва / вебтун",
        hint = "Вертикальные полосы, крупные надписи, широкие пробелы.",
        viewer = OcrViewerHint.WEBTOON,
    ),

    /**
     * Китайская маньхуа: вертикальные колонки, читаются справа налево, текст
     * крупнее корейского, но леттеринг плотный и рамки узкие.
     */
    MANHUA(
        id = "manhua",
        title = "Маньхуа",
        hint = "Вертикальные колонки, чтение сверху вниз и справа налево.",
        viewer = OcrViewerHint.PAGER_RTL,
    ),

    /**
     * Западный комикс: плотный леттеринг, крупные заголовки, чтение слева
     * направо, прямоугольные баллоны стоят близко друг к другу.
     */
    COMIC(
        id = "comic",
        title = "Комикс",
        hint = "Плотный леттеринг и заголовки, чтение слева направо.",
        viewer = OcrViewerHint.PAGER_LTR,
    ),
    ;

    companion object {
        fun fromId(id: String?): OcrContentType =
            entries.firstOrNull { it.id == id } ?: BALANCED
    }
}

/**
 * Насколько дорого распознавать: точность против скорости.
 *
 * В отличие от [OcrContentType] пресет НЕ подбирает параметры под форму кадра,
 * а решает, какие проверки выполнять. Оба режима используют одни и те же
 * модели и одно и то же разбиение на строки, поэтому отличаются они только
 * числом лишних проходов:
 *
 * • [ACCURATE] — кроп читается основной моделью, при неуверенности
 *   повторно с усиленным контрастом и сверяется верификатором PP-OCRv5.
 * • [FAST] — один проход основной модели на кроп. Повторы и верификатор
 *   пропускаются: страница узнаётся в несколько раз быстрее, но на выцветших
 *   и зашумлённых кропах ошибок больше.
 *
 * Разбиение длинных кропов на строки включено в обоих режимах: колонка из
 * пяти строк, сжатая в 320×48, нечитаема в любом режиме.
 */
enum class OcrLocalMode(
    val id: String,
    val title: String,
    val hint: String,
) {
    ACCURATE(
        id = "accurate",
        title = "Точно",
        hint = "Каждый кроп читается основной моделью, при сомнении — с усиленным " +
            "контрастом и с проверкой второй моделью. Медленнее, ошибок меньше.",
    ),
    FAST(
        id = "fast",
        title = "Быстро",
        hint = "Один проход распознавания на реплику без повторов и второй модели. " +
            "Заметно быстрее, на выцветшей печати ошибок больше.",
    ),
    ;

    companion object {
        fun fromId(id: String?): OcrLocalMode =
            entries.firstOrNull { it.id == id } ?: ACCURATE
    }
}

/**
 * Полный набор параметров детектора и распознавания для одного прогона.
 *
 * Все значения по умолчанию совпадают с прежними константами
 * [CyrillicOcrEngine], поэтому `BALANCED` ничего не меняет в поведении.
 */
data class OcrTuning(
    // ---- Детектор текстовых областей (PP-OCRv4) ----
    /** Порог активации карты вероятностей детектора. */
    val detectorThreshold: Float = 0.20f,

    /** Минимальная площадь связной области в пикселях карты 736x736. */
    val minComponentArea: Int = 24,

    /** Сколько боксов максимум берётся со страницы. */
    val maxTextBoxes: Int = 96,

    /**
     * Порог «достаточности» основного прохода детектора: если он уже нашёл
     * больше боксов, дополнительные тайлы 2x2 не запускаются. Тайлы добавляют
     * ещё четыре прохода модели на кадр и были главным тормазом локального OCR
     * в авточтении: на плотной странице основной проход и так видит реплики.
     */
    val tilingMinTextBoxes: Int = 8,

    /** Доля вертикального перекрытия, при которой два бокса склеиваются. */
    val mergeOverlapYFactor: Float = 0.55f,

    /** Горизонтальный зазор (в высотах строки), при котором боксы склеиваются. */
    val mergeGapXFactor: Float = 0.55f,

    // ---- Деление строки на слова по проекции чернил ----
    /** Минимальная ширина кропа, при которой вообще пробуем делить на слова. */
    val splitMinWidthPx: Int = 32,

    /** Во сколько раз зазор должен превышать медианный, чтобы считать его пробелом. */
    val wordGapFactor: Float = 1.7f,

    /** Абсолютный минимум зазора в пикселях. */
    val minWordGapPx: Int = 5,

    // ---- Деление кропа на строки по проекции строк ----
    /**
     * Ниже этой высоты кроп строки не пробуем делить на строки: детектор
     * отдаёт строки точно, а лишний проход по пикселям на типичной
     * однострочной реплике ничего не даёт.
     */
    val lineSplitMinHeightPx: Int = 34,

    /**
     * Доля тёмных пикселей в строке, выше которой строка считается «текстом».
     * Порог низкий: у аккуратной строки шрифт занимает треть ширины, а у
     * жирной надписи — почти всю.
     */
    val lineSplitMinInkRatio: Float = 0.02f,

    /**
     * Межстрочный зазор в долях высоты строки, при котором полосы считаются
     * одной строкой. Диапазон срабатывания тот же, что у [mergeGapXFactor]
     * по горизонтали: у текущего шрифта это примерно 0.3–0.5 высоты.
     */
    val lineSplitMaxGapFactor: Float = 0.45f,

    /** Запас по вертикали при вырезании полосы, чтобы не срезать край глифа. */
    val lineSplitPadPx: Int = 2,

    // ---- Признание результата ----
    /**
     * Запускать ли верификатор PP-OCRv5 и повтор с усиленным контрастом.
     * Это два самых дорогих шага: каждый удваивает инференс кропа. Режим
     * «Быстро» их отключает, «Точно» оставляет.
     */
    val verifierEnabled: Boolean = true,
    val contrastRetryEnabled: Boolean = true,

    /** Ниже этого порога кроп распознаётся повторно с усиленным контрастом. */
    val contrastRetryConfidence: Float = 0.90f,

    /**
     * Выше этого порога уверенности PP-OCRv3 с чистой кириллицей верификатор
     * PP-OCRv5 уже не запускается (ускорение: v5 почти удваивает инференс
     * каждого кропа, а на каждой распознанной строке их ещё и по одному на
     * слово). Защита от «уверенного мусора» сохраняется: латинско-похожий
     * вывод из device-регрессии не проходит [OcrTextCleaner.isAcceptableCyrillicOcrText]
     * и по-прежнему идёт на сравнение с v5.
     */
    val verifierSkipConfidence: Float = 0.82f,

    /** Пол строки/кропа отбрасывается ниже этой уверенности. */
    val minAcceptConfidence: Float = 0.32f,

    /** Более мягкий порог для коротких реплик («а», «а!», «а-а-а»). */
    val shortTextMinConfidence: Float = 0.18f,

    /**
     * Минимальная доля «чернил» (тёмных пикселей по порогу Оцу) в кропе.
     * Полупрозрачный мелкий водяной знак почти не оставляет тёмных пикселей,
     * поэтому ниже этого значения кроп отвергается, даже если распознаватель
     * выдал высокую уверенность.
     */
    val minCropInkRatio: Float = 0.03f,

    /**
     * Кроп с долей чернил ниже этого порога НЕ распознаётся повторно с
     * усиленным контрастом: `createHighContrast()` делает полупрозрачный
     * водяной знак таким же «надёжным», как настоящий текст, и тот начинает
     * читаться вслух.
     */
    val contrastSkipInkThreshold: Float = 0.02f,

    /** Доля пропущенных CTC-шагов, после которой уверенность снижается. */
    val minCoverage: Float = 0.12f,

    // ---- Ранжирование моделей и путей ----
    /** Бонус PP-OCRv5, когда он дал валидную кириллицу. */
    val verifierCyrillicBonus: Float = 0.20f,

    /** Бонус цельного кропа, если его слитный вывод разделяется на известные слова. */
    val wholeLineBoundaryBonus: Float = 0.08f,

    /**
     * Бонус ранжирования за словарное покрытие (встроенный [RuWordList] +
     * пользовательский [OcrVocabulary]). Из двух кандидатов v3/v5 с равной
     * уверенностью побеждает тот, чьи слова реально есть в словарях. Словарь
     * не подменяет текст — он лишь «голосует» за читаемую гипотезу.
     */
    val dictionaryCoverageBonus: Float = 0.08f,

    /** Сколько лучших отклонённых строк поднимает второй rescue-эшелон. */
    val rescueMaxLines: Int = 6,

    // ---- Порядок чтения и область страницы ----
    /** rtl — манга, ltr — комиксы, vertical — вебтуны. */
    val readingOrder: String = "rtl",

    /** Какая часть страницы уходит в распознавание. */
    val scanRegion: ScanRegion = ScanRegion.FULL_PAGE,
) {
    init {
        require(detectorThreshold in 0.01f..0.99f) { "detectorThreshold вне диапазона 0.01..0.99" }
        require(minComponentArea in 1..4096) { "minComponentArea вне диапазона 1..4096" }
        require(maxTextBoxes in 1..1024) { "maxTextBoxes вне диапазона 1..1024" }
        require(tilingMinTextBoxes in 0..1024) { "tilingMinTextBoxes вне диапазона 0..1024" }
        require(contrastRetryConfidence in 0.05f..1f) { "contrastRetryConfidence вне диапазона 0.05..1" }
        require(verifierSkipConfidence in 0f..1f) { "verifierSkipConfidence вне диапазона 0..1" }
        require(minAcceptConfidence in 0f..1f) { "minAcceptConfidence вне диапазона 0..1" }
        require(shortTextMinConfidence in 0f..1f) { "shortTextMinConfidence вне диапазона 0..1" }
        require(minCropInkRatio in 0f..0.5f) { "minCropInkRatio вне диапазона 0..0.5" }
        require(contrastSkipInkThreshold in 0f..0.5f) { "contrastSkipInkThreshold вне диапазона 0..0.5" }
        require(minCoverage in 0f..0.9f) { "minCoverage вне диапазона 0..0.9" }
        require(rescueMaxLines in 0..64) { "rescueMaxLines вне диапазона 0..64" }
        require(dictionaryCoverageBonus in 0f..0.5f) { "dictionaryCoverageBonus вне диапазона 0..0.5" }
        require(readingOrder in READING_ORDERS) { "неизвестный readingOrder: $readingOrder" }
    }

    companion object {
        val READING_ORDERS = setOf("rtl", "ltr", "vertical")

        /** Профиль по умолчанию = прежнее поведение движка. */
        val DEFAULT = OcrTuning()

        /**
         * Пресет типа контента.
         *
         * Меняются только те параметры, где форма кадров действительно
         * другая: у вебтуна длинные полосы и крупные буквы (можно реже
         * склеивать боксы и требовать меньше строк), у манги мелкий плотный
         * текст (нужен ниже порог детектора и больше боксов на страницу).
         */
        fun preset(type: OcrContentType, scanRegion: ScanRegion = ScanRegion.FULL_PAGE): OcrTuning =
            when (type) {
                OcrContentType.BALANCED -> DEFAULT.copy(scanRegion = scanRegion)

                OcrContentType.MANGA -> DEFAULT.copy(
                    detectorThreshold = 0.17f,
                    minComponentArea = 18,
                    maxTextBoxes = 128,
                    tilingMinTextBoxes = 6,
                    mergeOverlapYFactor = 0.60f,
                    mergeGapXFactor = 0.45f,
                    wordGapFactor = 1.5f,
                    minWordGapPx = 4,
                    contrastRetryConfidence = 0.88f,
                    minAcceptConfidence = 0.28f,
                    minCropInkRatio = 0.025f,
                    rescueMaxLines = 8,
                    readingOrder = "rtl",
                    scanRegion = scanRegion,
                )

                OcrContentType.MANHWA -> DEFAULT.copy(
                    detectorThreshold = 0.18f,
                    minComponentArea = 28,
                    maxTextBoxes = 64,
                    tilingMinTextBoxes = 12,
                    mergeOverlapYFactor = 0.45f,
                    mergeGapXFactor = 0.80f,
                    splitMinWidthPx = 40,
                    wordGapFactor = 2.0f,
                    minWordGapPx = 7,
                    minAcceptConfidence = 0.34f,
                    rescueMaxLines = 5,
                    readingOrder = "vertical",
                    scanRegion = scanRegion,
                )

                OcrContentType.MANHUA -> DEFAULT.copy(
                    detectorThreshold = 0.19f,
                    minComponentArea = 24,
                    maxTextBoxes = 80,
                    tilingMinTextBoxes = 8,
                    mergeOverlapYFactor = 0.50f,
                    mergeGapXFactor = 0.65f,
                    splitMinWidthPx = 36,
                    wordGapFactor = 1.8f,
                    minWordGapPx = 6,
                    minAcceptConfidence = 0.32f,
                    rescueMaxLines = 6,
                    readingOrder = "vertical",
                    scanRegion = scanRegion,
                )

                OcrContentType.COMIC -> DEFAULT.copy(
                    detectorThreshold = 0.22f,
                    minComponentArea = 26,
                    maxTextBoxes = 96,
                    tilingMinTextBoxes = 10,
                    mergeOverlapYFactor = 0.58f,
                    mergeGapXFactor = 0.50f,
                    wordGapFactor = 1.6f,
                    minWordGapPx = 5,
                    minAcceptConfidence = 0.30f,
                    readingOrder = "ltr",
                    scanRegion = scanRegion,
                )
            }
    }
}

/**
 * Точные переопределения пресета из настроек.
 *
 * Каждое поле может быть `null` — тогда берётся значение пресета. Все поля
 * ограничены диапазонами: пользователь не должен иметь возможность выставить
 * порог, при котором детектор перестаёт работать.
 */
data class OcrTuningOverrides(
    val detectorThreshold: Float? = null,
    val minComponentArea: Int? = null,
    val maxTextBoxes: Int? = null,
    val wordGapFactor: Float? = null,
    val minAcceptConfidence: Float? = null,
    val shortTextMinConfidence: Float? = null,
    val minCoverage: Float? = null,
    val rescueMaxLines: Int? = null,
) {
    val isEmpty: Boolean
        get() = detectorThreshold == null &&
            minComponentArea == null &&
            maxTextBoxes == null &&
            wordGapFactor == null &&
            minAcceptConfidence == null &&
            shortTextMinConfidence == null &&
            minCoverage == null &&
            rescueMaxLines == null

    /**
     * Применяет переопределения к пресету, отбрасывая значения вне допустимых
     * диапазонов. Молчаливый отброс намеренный: настройка, сохранённая старой
     * версией приложения, не должна ронять распознавание.
     */
    fun applyTo(base: OcrTuning): OcrTuning = base.copy(
        detectorThreshold = detectorThreshold?.coerceIn(0.01f, 0.99f) ?: base.detectorThreshold,
        minComponentArea = minComponentArea?.coerceIn(1, 4096) ?: base.minComponentArea,
        maxTextBoxes = maxTextBoxes?.coerceIn(1, 1024) ?: base.maxTextBoxes,
        wordGapFactor = wordGapFactor?.coerceIn(1.0f, 6.0f) ?: base.wordGapFactor,
        minAcceptConfidence = minAcceptConfidence?.coerceIn(0.0f, 1.0f) ?: base.minAcceptConfidence,
        shortTextMinConfidence = shortTextMinConfidence
            ?.coerceIn(0.0f, 1.0f)
            ?: base.shortTextMinConfidence,
        minCoverage = minCoverage?.coerceIn(0.0f, 0.9f) ?: base.minCoverage,
        rescueMaxLines = rescueMaxLines?.coerceIn(0, 64) ?: base.rescueMaxLines,
    )
}

/**
 * Применяет режим «Точно»/«Быстро» к пресету типа контента.
 *
 * Порядок важен: сначала пресет контента, потом режим, потом точные
 * переопределения пользователя — так ручная настройка всегда побеждает.
 */
fun OcrTuning.withLocalMode(mode: OcrLocalMode): OcrTuning = when (mode) {
    OcrLocalMode.ACCURATE -> copy(verifierEnabled = true, contrastRetryEnabled = true)
    OcrLocalMode.FAST -> copy(verifierEnabled = false, contrastRetryEnabled = false)
}
