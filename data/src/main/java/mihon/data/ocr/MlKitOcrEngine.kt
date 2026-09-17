package mihon.data.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import mihon.domain.ocr.model.OcrBoundingBox
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google ML Kit Text Recognition (on-device).
 *
 * Модель вкомпилирована в APK, поэтому движок полностью офлайн и не требует
 * пакета `cyrillic_ocr` или LiteRT. ML Kit отдаёт строки текста вместе с их
 * координатами, благодаря чему используется и как распознаватель, и как
 * детектор областей: [recognizeRegions] возвращает строки с нормализованными
 * рамками, и тап по реплике открывает именно её.
 *
 * Latin-модель ML Kit уверенно читает латиницу и кириллицу; для японского,
 * корейского и китайского существуют отдельные артефакты, здесь выбран
 * базовый `text-recognition` (латиница/кириллица).
 */
internal class MlKitOcrEngine : LineOcrEngine {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognizeText(image: Bitmap): String = recognizeLines(image)
        .joinToString("\n") { it.text }

    override suspend fun recognizeLine(image: Bitmap): String = recognizeLines(image)
        .joinToString(" ") { it.text }
        .trim()

    /**
     * Строки страницы с нормализованными (0..1) рамками — замена детектору
     * областей. Рамки, которые ML Kit не вернул или которые вырождены,
     * пропускаются.
     */
    suspend fun recognizeRegions(image: Bitmap): List<MlKitRegion> {
        val width = image.width.toFloat()
        val height = image.height.toFloat()
        if (width <= 0f || height <= 0f) return emptyList()

        return recognizeLines(image).mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            val boundingBox = OcrBoundingBox(
                left = (box.left / width).coerceIn(0f, 1f),
                top = (box.top / height).coerceIn(0f, 1f),
                right = (box.right / width).coerceIn(0f, 1f),
                bottom = (box.bottom / height).coerceIn(0f, 1f),
            )
            if (!boundingBox.isValid() || line.text.isBlank()) {
                null
            } else {
                MlKitRegion(boundingBox = boundingBox, text = line.text)
            }
        }
    }

    private suspend fun recognizeLines(image: Bitmap): List<Text.Line> = suspendCancellableCoroutine { cont ->
        val input = InputImage.fromBitmap(image, 0)
        recognizer.process(input)
            .addOnSuccessListener { result ->
                cont.resume(result.textBlocks.flatMap { it.lines })
            }
            .addOnFailureListener { error ->
                cont.resumeWithException(error)
            }
    }

    override fun close() {
        recognizer.close()
    }
}

internal data class MlKitRegion(
    val boundingBox: OcrBoundingBox,
    val text: String,
)
