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
     *
     * [cacheResult] = false для кадров авточтения: они не страницы (см.
     * [OcrRepository.scanPage]).
     */
    suspend fun await(
        chapterId: Long,
        pageIndex: Int,
        image: OcrImage,
        onPartial: ((OcrRegion) -> Unit)? = null,
        cacheResult: Boolean = true,
    ): OcrPageResult {
        return ocrRepository.scanPage(chapterId, pageIndex, image, onPartial, cacheResult)
    }
}

/** Что делать с резервной цепочкой после очередного движка. */
enum class PageScanStep {
    /** Движок дал текст — выдаём результат. */
    STOP,

    /** Пробуем следующий движок. */
    CONTINUE,
}

/**
 * Правило хода резервной цепочки распознавания.
 *
 * Различает два принципиально разных исхода, которые раньше считались одним:
 *
 *  - движок **упал** (исключение) — резервная цепочка здесь и нужна, идём дальше;
 *  - движок **отработал и не нашёл текста** — это ответ «страница-картинка».
 *    Идти дальше незачем: остальные движки повторят тот же дорогой проход и тоже
 *    ничего не найдут, а при пресете с онлайном ещё и уведут картинку в сеть.
 *
 * Отдельно [skipUntitledPages] — настройка читателя: выключив её, пользователь
 * возвращает прежнее поведение, когда пустой результат считался поводом искать
 * дальше (это нужно, если движок находит текст не с первого раза).
 */
fun pageScanStepAfterEngine(
    failed: Boolean,
    regionCount: Int,
    skipUntitledPages: Boolean,
): PageScanStep = when {
    failed -> PageScanStep.CONTINUE
    regionCount > 0 -> PageScanStep.STOP
    skipUntitledPages -> PageScanStep.STOP
    else -> PageScanStep.CONTINUE
}
