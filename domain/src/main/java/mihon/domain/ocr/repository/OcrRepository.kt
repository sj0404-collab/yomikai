package mihon.domain.ocr.repository

import mihon.domain.ocr.model.OcrImage
import mihon.domain.ocr.model.OcrPageResult

interface OcrRepository {
    suspend fun recognizeText(image: OcrImage): String

    /**
     * Спросить у ВЫБРАННОЙ vision-модели произвольный вопрос о картинке.
     *
     * Нужна агенту-оркестратору: OCR отдаёт только текст страницы, а
     * оркестратору нужно понимать изображение — что нарисовано, кто с кем,
     * что происходит в сцене. null означает «выбранный движок не умеет
     * отвечать на вопросы» (локальный Tesseract, например), и вызывающий код
     * обязан продолжить без взгляда на страницу, а не падать.
     */
    suspend fun askAboutImage(image: OcrImage, question: String): String?

    suspend fun scanPage(
        chapterId: Long,
        pageIndex: Int,
        image: OcrImage,
    ): OcrPageResult

    suspend fun getCachedPage(
        chapterId: Long,
        pageIndex: Int,
    ): OcrPageResult?

    suspend fun getCachedChapterIds(chapterIds: Collection<Long>): Set<Long>

    suspend fun clearCachedChapter(chapterId: Long)

    suspend fun clearCache()

    suspend fun getCacheSizeBytes(): Long

    suspend fun <T> withScanSession(block: suspend () -> T): T

    /**
     * Cleanup and release all OCR resources, which can take up lots of RAM.
     * Used for memory management when system is under pressure.
     */
    fun cleanup()
}
