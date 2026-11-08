package mihon.domain.ocr.interactor

import mihon.domain.ocr.model.OcrPageResult
import mihon.domain.ocr.repository.OcrRepository

/** Записать проверенный результат страницы в кэш OCR. */
class SaveCachedPageOcr(
    private val ocrRepository: OcrRepository,
) {
    suspend fun await(result: OcrPageResult) = ocrRepository.savePage(result)
}
