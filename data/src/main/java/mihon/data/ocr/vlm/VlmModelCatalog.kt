package mihon.data.ocr.vlm

/**
 * Каталог моделей распознавания — только два пути (решение 2026-11-07).
 *
 * GGUF, MNN, LiteRT-LM и Phi-3.5-vision убраны: под каждый нужна своя нативная
 * библиотека, а замеров по русской каллиграфии нет ни у одного. Осталось то, у
 * чего есть либо замер на нашей разметке, либо уже собранный рантайм.
 */
internal object VlmModelCatalog {

    /** PP-OCRv5 в LiteRT — 27 МБ, рантайм LiteRT уже в APK. Замер: 74,5 % слов. */
    val PP_OCR_V5_LITERT = VlmCatalogEntry(
        id = "ppocr5-litert",
        title = "PP-OCRv5 (LiteRT) — 27 МБ",
        repo = "litert-community/PP-OCRv5-LiteRT",
        minRamMb = 512,
        note = "Детектор 10,1 МБ + распознаватель 17,2 МБ (fp16) + словарь 0,07 МБ. " +
            "На 425 строках: 74,5 % слов, CER 37,3 %; маньхуа +11,5 п.п., комикс +9 п.п.",
    )

    /** Наш rec.onnx в INT8 — 82,4 % слов против 70,6 % у float32; на 33 % медленнее. */
    val OUR_REC_INT8 = VlmCatalogEntry(
        id = "rec-int8",
        title = "Наш чтец rec.onnx (INT8) — 8 МБ",
        repo = "",
        minRamMb = 512,
        note = "Пересчёт нашей же модели: +11,8 п.п. слов на 425 строках. " +
            "Требует проверки внутри приложения — замер сделан на отдельных строках.",
    )

    val ALL = listOf(PP_OCR_V5_LITERT, OUR_REC_INT8)

    /** Что имеет смысл на устройстве с [ramMb] ОЗУ. */
    fun forRam(ramMb: Int): List<VlmCatalogEntry> = ALL.filter { it.minRamMb <= ramMb }

    fun byId(id: String): VlmCatalogEntry? = ALL.firstOrNull { it.id == id }

    /** Путь внутри репозитория для конкретного файла (учитывает подпапку модели). */
    fun pathInRepo(entry: VlmCatalogEntry, file: String): String =
        if (entry.folder.isEmpty()) file else entry.folder.trimEnd('/') + "/" + file
}
