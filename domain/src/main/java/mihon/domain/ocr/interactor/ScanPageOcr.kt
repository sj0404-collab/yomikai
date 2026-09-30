package mihon.domain.ocr.interactor

import mihon.domain.ocr.model.OcrImage
import mihon.domain.ocr.model.OcrPageResult
import mihon.domain.ocr.model.OcrRegion
import mihon.domain.ocr.repository.OcrRepository

class ScanPageOcr(
    private val ocrRepository: OcrRepository,
) {
    /**
     * [onPartial] — области по мере их распознавания, см.
     * [OcrRepository.scanPage]. Позволяет озвучивать первые реплики страницы,
     * пока движок ещё читает последние.
     */
    suspend fun await(
        chapterId: Long,
        pageIndex: Int,
        image: OcrImage,
        onPartial: ((OcrRegion) -> Unit)? = null,
    ): OcrPageResult {
        return ocrRepository.scanPage(chapterId, pageIndex, image, onPartial)
    }
}
