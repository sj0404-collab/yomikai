package mihon.data.ocr

import mihon.domain.ocr.service.OcrPreferences
import mihon.domain.ocr.service.ScanRegion

/**
 * Правила перевода настроек области и точной подстройки в профиль пресета.
 *
 * Вынесены из `OcrRepositoryImpl`, чтобы у правила был ровно один хозяин:
 * движок, экран настроек и инструменты AI-агента обязаны видеть область и
 * переопределения одинаково. Любая копия этой логики рано или поздно
 * разошлась бы с движком, и агент начал бы докладывать пользователю не то,
 * что реально применяется к странице.
 *
 * Все функции чистые (кроме `profileOf`, читающей настройки), поэтому правила
 * покрыты юнит-тестами без Android.
 */
object OcrRegionRules {

    /** Ключи области, которые понимает `pref_ocr_preset_region`. */
    val REGION_KEYS = listOf("full", "top", "bottom")

    /** Ключ ключа области → значение настройки. Незнакомый ключ = null. */
    fun regionOf(key: String?): ScanRegion? = when (key) {
        "full" -> ScanRegion.FULL_PAGE
        "top" -> ScanRegion.TOP_HALF
        "bottom" -> ScanRegion.BOTTOM_HALF
        else -> null
    }

    /**
     * Фактическая область сканирования.
     *
     * `pref_ocr_preset_region` задаётся пресетом типа контента, а legacy
     * `pref_scan_region` остаётся быстрым переопределением для значений,
     * которые пресет не покрывает (пустая строка, старая установка).
     */
    fun effectiveRegion(presetKey: String?, legacy: ScanRegion): ScanRegion =
        regionOf(presetKey) ?: legacy

    /**
     * Число с плавающей точкой из поля настройки.
     *
     * Запятая принимается как десятичный разделитель: приложение русское, и
     * «0,19» обязано значить то же, что «0.19». Приводим к точке сами, потому
     * что `String.toFloatOrNull()` трактует запятую как разделитель групп
     * разрядов: «12,5f» у него превращается в 1.25 — пользователь увидел бы
     * значение, которого не вводил. Суффиксы Java-литералов (f, d) отвергаем.
     */
    private fun floatOrNull(value: String): Float? {
        val text = value.trim().replace(',', '.')
        if (text.isEmpty()) return null
        return text.takeIf { it.all { c -> c.isDigit() || c in ".-+eE" } }?.toFloatOrNull()
    }

    /** Целое из поля настройки: пустая строка или опечатка = «как в пресете». */
    private fun intOrNull(value: String): Int? {
        val text = value.trim()
        if (text.isEmpty()) return null
        return text.takeIf { it.all { c -> c.isDigit() || c in "+-" } }?.toIntOrNull()
    }

    /**
     * Переопределения пресета из строк настройки.
     *
     * Пустое или нечисловое поле означает «как в пресете»: значение,
     * сохранённое старой версией или введённое с опечаткой, не должно ломать
     * распознавание.
     */
    fun overridesOf(
        detectorThreshold: String,
        minComponentArea: String,
        maxTextBoxes: String,
        wordGapFactor: String,
        minAcceptConfidence: String,
        shortTextMinConfidence: String,
        minCoverage: String,
        rescueMaxLines: String,
    ): OcrTuningOverrides = OcrTuningOverrides(
        detectorThreshold = floatOrNull(detectorThreshold),
        minComponentArea = intOrNull(minComponentArea),
        maxTextBoxes = intOrNull(maxTextBoxes),
        wordGapFactor = floatOrNull(wordGapFactor),
        minAcceptConfidence = floatOrNull(minAcceptConfidence),
        shortTextMinConfidence = floatOrNull(shortTextMinConfidence),
        minCoverage = floatOrNull(minCoverage),
        rescueMaxLines = intOrNull(rescueMaxLines),
    )

    /** Профиль распознавания, который движок применит к следующей странице. */
    fun profileOf(prefs: OcrPreferences): OcrRegionProfile = OcrRegionProfile(
        contentType = OcrContentType.fromId(prefs.contentType().get()),
        scanRegion = effectiveRegion(prefs.presetScanRegion().get(), prefs.scanRegion().get()),
        localMode = OcrLocalMode.fromId(prefs.localMode().get()),
        overrides = overridesOf(
            detectorThreshold = prefs.detectorThresholdOverride().get(),
            minComponentArea = prefs.minComponentAreaOverride().get(),
            maxTextBoxes = prefs.maxTextBoxesOverride().get(),
            wordGapFactor = prefs.wordGapFactorOverride().get(),
            minAcceptConfidence = prefs.minAcceptConfidenceOverride().get(),
            shortTextMinConfidence = prefs.shortTextConfidenceOverride().get(),
            minCoverage = prefs.minCoverageOverride().get(),
            rescueMaxLines = prefs.rescueMaxLinesOverride().get(),
        ),
    )

    /**
     * Границы обрезки страницы под выбранную область: пара `(cropTop, cropHeight)`
     * в пикселях. Единственное место, где область превращается в прямоугольник,
     * поэтому `TOP_HALF` и `BOTTOM_HALF` обрезаются одинаково для движка, настроек
     * и агента. Раньше `TOP_HALF` здесь не обрабатывался, и область скана
     * фактически не применялась к странице.
     */
    fun cropBounds(sourceHeight: Int, region: ScanRegion): Pair<Int, Int> {
        val height = sourceHeight.coerceAtLeast(1)
        val top = when (region) {
            ScanRegion.FULL_PAGE, ScanRegion.TOP_HALF -> 0
            ScanRegion.BOTTOM_HALF -> height / 2
        }
        val cropHeight = when (region) {
            ScanRegion.FULL_PAGE -> height
            ScanRegion.TOP_HALF -> (height / 2).coerceAtLeast(1)
            ScanRegion.BOTTOM_HALF -> (height - top).coerceAtLeast(1)
        }
        return top to cropHeight
    }

    /** Человекочитаемое имя области — для отчётов и подсказок. */
    fun regionTitle(region: ScanRegion): String = when (region) {
        ScanRegion.FULL_PAGE -> "вся страница"
        ScanRegion.TOP_HALF -> "верхняя половина"
        ScanRegion.BOTTOM_HALF -> "нижняя половина"
    }

    /** Человекочитаемое имя порядка чтения. */
    fun orderTitle(order: String): String = when (order) {
        "rtl" -> "справа налево"
        "ltr" -> "слева направо"
        "vertical" -> "сверху вниз"
        else -> order
    }

    /**
     * Порядок чтения со страницы — из настроек читалки.
     *
     * Раньше его диктовал пресет типа контента (манга → rtl, манхва →
     * vertical, комикс → ltr), из-за чего вертикальный вебтун читался не так,
     * как его настроили, а настройка дублировала режим чтения. Теперь приоритет
     * такой:
     *  1. явный выбор пользователя («Порядок чтения», pref_scan_reading_order);
     *  2. иначе то, что реально стоит в читалке: вертикальный режим читается
     *     сверху вниз, RTL — справа налево, иначе слева направо.
     */
    fun readingOrderFor(prefs: OcrPreferences): String {
        val manual = prefs.scanReadingOrder().get()
        if (manual.isNotBlank() && manual != "auto") return manual
        val ctx = ReaderContextBus.current.value
        return OcrTuning.readingOrderFor(
            vertical = ctx?.vertical == true,
            rtl = ctx?.rtl != false,
        )
    }
}
