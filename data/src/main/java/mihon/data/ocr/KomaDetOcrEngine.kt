package mihon.data.ocr

import android.graphics.Bitmap
import mihon.domain.ocr.exception.OcrException
import mihon.domain.ocr.model.OcrBoundingBox

/**
 * Детектор KOMA-пути: отдаёт рамки из того же ONNX-детектора PP-OCRv4, что и
 * сам распознаватель, поэтому второй экземпляр модели не открывается.
 *
 * Владелец движка — репозиторий ([mihon.data.ocr.OcrRepositoryImpl]), так что
 * [close] ничего не закрывает: иначе первый же `closeEngines()` закрыл бы
 * сессию ONNX дважды.
 */
internal class KomaDetOcrEngine(
    private val engineProvider: () -> KomaOnnxOcrEngine,
) : DetOcrEngine {

    override suspend fun detectTextRegions(image: Bitmap): List<OcrBoundingBox> {
        return try {
            engineProvider().detectRegions(image)
        } catch (e: OcrException) {
            throw OcrException.DetectionUnavailable(e)
        }
    }

    override fun close() = Unit
}
