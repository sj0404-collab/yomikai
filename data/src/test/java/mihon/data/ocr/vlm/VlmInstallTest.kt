package mihon.data.ocr.vlm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VlmInstallTest {

    private fun blob(n: String, s: Long) = HfModelHub.Blob(n, s)

    @Test
    fun `берёт только нужную подпапку`() {
        val files = listOf(
            blob("cpu_and_mobile/int4/model.onnx", 100),
            blob("cpu_and_mobile/int4/genai_config.json", 1),
            blob("gpu/int4/model.onnx", 900),
        )
        val picked = VlmInstall.pickFiles("cpu_and_mobile/int4", files)
        assertEquals(2, picked.size)
        assertTrue(picked.none { it.name.startsWith("gpu/") })
    }

    @Test
    fun `выкидывает картинки и readme, но оставляет служебные json`() {
        val files = listOf(
            blob("model.onnx", 10),
            blob("tokenizer.json", 2),
            blob("logo.png", 5),
            blob("README.md", 3),
            blob(".gitattributes", 1),
        )
        val names = VlmInstall.pickFiles("", files).map { it.name }
        assertEquals(listOf("model.onnx", "tokenizer.json"), names)
    }

    @Test
    fun `пустой список означает что качать нечего`() {
        val text = VlmInstall.describe(VlmModelCatalog.PP_OCR_V5_LITERT, emptyList())
        assertTrue(text.contains("не найдены"))
    }

    @Test
    fun `описание говорит формат и нужен ли рантайм`() {
        val files = listOf(
            blob("ppocr_det_fp16.tflite", 10),
            blob("ppocr_rec_fp16.tflite", 17),
            blob("processor_config.json", 1),
        )
        val text = VlmInstall.describe(VlmModelCatalog.PP_OCR_V5_LITERT, files)
        assertTrue(text.contains("TFLite"), text)
        assertTrue(text.contains("видящая"), text)
        assertTrue(text.contains("рантайм есть"), text)
    }

    @Test
    fun `формат GGUF без mmproj не считается видящим`() {
        val files = listOf(blob("smolvlm-500m-q4.gguf", 400))
        val text = VlmInstall.describe(VlmModelCatalog.SMOLVLM_500M_GGUF, files)
        assertTrue(text.contains("GGUF"), text)
        assertTrue(text.contains("только текст"), text)
    }
}
