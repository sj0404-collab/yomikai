package mihon.data.ocr.vlm

/**
 * Во что упакована модель и какой рантайм её исполнит.
 *
 * Смысл файла: человек ищет модель на Hugging Face по названию, а не по формату.
 * Поэтому формат определяется по СПИСКУ ФАЙЛОВ модели (он приходит из
 * [HfModelHub.files]), а не по угадыванию из названия репозитория: одно и то же
 * семейство (SmolVLM-500M) лежит и в MNN, и в GGUF, и в ONNX, и в LiteRT.
 */
internal enum class VlmFormat(
    val title: String,
    /** Рантайм, который нужен именно этому формату. */
    val runtime: String,
    /** Есть ли рантайм в APK уже сейчас (без новых нативных библиотек). */
    val runtimeInApk: Boolean,
) {
    ONNX_GENAI("ONNX (GenAI)", "onnxruntime-genai", false),
    ONNX_RAW("ONNX (обычный, свой декод)", "onnxruntime-android", true),
    GGUF("GGUF (llama.cpp)", "llama.cpp", false),
    MNN("MNN", "MNN", false),
    LITERT_LM("LiteRT-LM (.litertlm)", "litert-lm", false),
    TFLITE("TFLite / LiteRT (.tflite)", "litert (уже в APK)", true),
    TASK("MediaPipe .task (только текст)", "mediapipe-genai", true),
    UNKNOWN("не распознан", "—", false);

    companion object {
        /**
         * Определение формата по файлам модели.
         * Порядок проверок важен: GenAI-каталог тоже содержит model.onnx, но
         * рядом всегда есть genai_config.json — иначе это обычный ONNX.
         */
        fun detect(files: List<String>): VlmFormat {
            val names = files.map { it.substringAfterLast('/') }
            return when {
                names.any { it == "genai_config.json" } -> ONNX_GENAI
                names.any { it.endsWith(".litertlm") } -> LITERT_LM
                names.any { it.endsWith(".gguf") } -> GGUF
                names.any { it.endsWith(".mnn") || it.endsWith(".mnn.weight") } -> MNN
                names.any { it.endsWith(".tflite") } -> TFLITE
                names.any { it.endsWith(".task") } -> TASK
                names.any { it.endsWith(".onnx") } -> ONNX_RAW
                else -> UNKNOWN
            }
        }

        /**
         * Видящая ли это модель. GGUF/MNN/ONNX-сборки бывают текстовыми:
         * у видящих рядом с весами лежит отдельная «глазная» часть.
         */
        fun looksVision(files: List<String>): Boolean {
            val n = files.map { it.lowercase() }
            return n.any {
                it.contains("mmproj") || it.contains("visual") || it.contains("vision") ||
                    it.contains("image_") || it.contains("processor_config") ||
                    it.contains("preprocessor_config") || it.contains("embedding.onnx")
            }
        }
    }
}

/**
 * Запись каталога: то, что показывается в списке моделей.
 *
 * Размер и набор файлов НЕ дублируются здесь — они берутся с Hugging Face
 * ([HfModelHub.files]) в момент показа, иначе каталог врёт через месяц.
 */
internal data class VlmCatalogEntry(
    val id: String,
    val title: String,
    /** Репозиторий на Hugging Face. */
    val repo: String,
    /** Подпапка внутри репозитория (у большинства VLM их несколько: cpu/gpu/int4). */
    val folder: String = "",
    /** Минимум ОЗУ, при котором модель имеет смысл запускать. */
    val minRamMb: Int,
    /** Что модель умеет и насколько ей можно верить (честно, без рекламы). */
    val note: String,
)
