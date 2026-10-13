package mihon.data.ocr

/**
 * Частотный словарь русских словоформ для разбиения слипшегося OCR-текста
 * на слова (только вставка пробелов, без замены символов — словарная
 * «коррекция» запрещена правилами проекта).
 *
 * Источники (пересборка — `scripts/gen_ruwordlist.py`):
 *  * OpenSubtitles через [FrequencyWords](https://github.com/hermitdave/FrequencyWords) —
 *    тот же источник, из которого собран `Latin_to_Cyrillic` в `dictionaries/`:
 *    словоформы с частотой >= 200 вхождений;
 *  * ё/е-варианты каждой формы: OCR читает ё непоследовательно, и «НАМЁКА»
 *    обязано находиться так же, как «НАМЕКА»;
 *  * литературная лексика из прежней версии словаря (её в субтитрах нет);
 *  * сиды device-регрессий и однобуквенные служебные слова.
 *
 * Размер словаря — это ровно то, от чего зависит «недоскан»: DP-разбиение
 * слипшегося прогона требует, чтобы КАЖДЫЙ сегмент был известным словом.
 * На 7008 формах полноценно делились редкие строки («ЗАЭТИГОДЫЯ…»), на
 * 61 028 — подавляющее большинство (≈92% словоупотреблений корпуса).
 * Словарь только разрешает вставить границу: неизвестное остаётся как есть.

 */
object RuWordList {

    private val parts: Array<Array<String>> = arrayOf(
        ruWordListPart000(),
        ruWordListPart001(),
        ruWordListPart002(),
        ruWordListPart003(),
        ruWordListPart004(),
        ruWordListPart005(),
        ruWordListPart006(),
        ruWordListPart007(),
        ruWordListPart008(),
        ruWordListPart009(),
        ruWordListPart010(),
        ruWordListPart011(),
        ruWordListPart012(),

    )

    /** Словоформы ВЕРХНИМ регистром: прогоны OCR приходят капсом. */
    val upper: Set<String> by lazy {
        val set = HashSet<String>(parts.sumOf { it.size } * 2)
        for (part in parts) for (w in part) set.add(w.uppercase())
        set
    }

    val lower: Set<String> by lazy {
        val set = HashSet<String>(parts.sumOf { it.size } * 2)
        for (part in parts) for (w in part) set.add(w)
        set
    }
}
