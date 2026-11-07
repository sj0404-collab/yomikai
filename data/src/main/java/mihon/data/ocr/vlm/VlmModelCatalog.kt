package mihon.data.ocr.vlm

/**
 * Каталог моделей распознавания: что предлагается сразу, без поиска.
 *
 * Здесь только то, что я проверил живыми запросами к Hugging Face API: репозиторий
 * существует, файлы перечислены, размеры настоящие. Размеры в комментариях —
 * сумма файлов репозитория на 2026-11-07; в списке они берутся заново через
 * [HfModelHub.files], чтобы каталог не врал после обновления модели.
 *
 * Порядок не случайный: сначала то, что работает на рантайме, который уже в APK,
 * потом то, что требует новой нативной библиотеки, и только в конце — тяжёлое.
 */
internal object VlmModelCatalog {

    /** PP-OCRv5 в LiteRT: замена KOMA-ONNX на уже подключённом рантайме. 90 МБ. */
    val PP_OCR_V5_LITERT = VlmCatalogEntry(
        id = "ppocr5-litert",
        title = "PP-OCRv5 (LiteRT) — замена KOMA-ONNX",
        repo = "litert-community/PP-OCRv5-LiteRT",
        minRamMb = 512,
        note = "Детектор 10,1 МБ + распознаватель 17,2 МБ (fp16). Кириллица. " +
            "По моему замеру на 425 строках — 74,5 % слов. Рантайм LiteRT уже в APK.",
    )

    /** SmolVLM-500M в MNN: самая лёгкая видящая модель из найденных. 0,61 ГБ. */
    val SMOLVLM_500M_MNN = VlmCatalogEntry(
        id = "smolvlm500-mnn",
        title = "SmolVLM 500M (MNN) — первый настоящий VLM",
        repo = "taobao-mnn/SmolVLM-500M-Instruct-MNN",
        minRamMb = 2048,
        note = "llm.mnn.weight 403 МБ + visual.mnn.weight 107 МБ + embeddings 94,6 МБ. " +
            "Русская каллиграфия не проверена.",
    )

    /** Xiaomi-OCR-0 в LiteRT-LM: VLM, заточенный именно под OCR. 1,31 ГБ. */
    val XIAOMI_OCR_LITERT = VlmCatalogEntry(
        id = "xiaomi-ocr-litert",
        title = "Xiaomi-OCR-0 (LiteRT-LM) — VLM для OCR",
        repo = "litert-community/Xiaomi-OCR-0",
        minRamMb = 3072,
        note = "Один файл .litertlm int8, 1,31 ГБ. Нужен рантайм LiteRT-LM.",
    )

    /** SmolVLM-500M в GGUF: путь llama.cpp, если MNN не устроит. */
    val SMOLVLM_500M_GGUF = VlmCatalogEntry(
        id = "smolvlm500-gguf",
        title = "SmolVLM 500M (GGUF, llama.cpp)",
        repo = "ggml-org/SmolVLM-500M-Instruct-GGUF",
        minRamMb = 2048,
        note = "Квантованные .gguf + отдельный mmproj для зрения. Нужен llama.cpp с JNI.",
    )

    /** Phi-3.5-vision в формате GenAI: 3,22 ГБ, только для 8+ ГБ ОЗУ. */
    val PHI35_VISION_GENAI = VlmCatalogEntry(
        id = "phi35v-genai",
        title = "Phi-3.5-vision (ONNX GenAI)",
        repo = "microsoft/Phi-3.5-vision-instruct-onnx",
        folder = "cpu_and_mobile/cpu-int4-rtn-block-32-acc-level-4",
        minRamMb = 8192,
        note = "3,22 ГБ: текст 2,3 ГБ + зрение 445 МБ + эмбеддинги 394 МБ. " +
            "Нужен ORT GenAI, которого нет в Maven Central.",
    )

    val ALL = listOf(
        PP_OCR_V5_LITERT,
        SMOLVLM_500M_MNN,
        XIAOMI_OCR_LITERT,
        SMOLVLM_500M_GGUF,
        PHI35_VISION_GENAI,
    )

    /** Что имеет смысл на устройстве с [ramMb] ОЗУ (ActivityManager.MemoryInfo.totalMem). */
    fun forRam(ramMb: Int): List<VlmCatalogEntry> = ALL.filter { it.minRamMb <= ramMb }

    fun byId(id: String): VlmCatalogEntry? = ALL.firstOrNull { it.id == id }

    /** Путь внутри репозитория для конкретного файла (учитывает подпапку модели). */
    fun pathInRepo(entry: VlmCatalogEntry, file: String): String =
        if (entry.folder.isEmpty()) file else entry.folder.trimEnd('/') + "/" + file
}
