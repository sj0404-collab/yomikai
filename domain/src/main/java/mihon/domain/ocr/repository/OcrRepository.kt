package mihon.domain.ocr.repository

import mihon.domain.ocr.model.OcrImage
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.model.OcrPageResult
import mihon.domain.ocr.model.OcrRegion

interface OcrRepository {
    suspend fun recognizeText(image: OcrImage): String

    /**
     * Распознать картинку ЗАДАННЫМ движком, минуя выбор движка приложения.
     *
     * [recognizeText] всегда берёт движок из общей настройки — той, что
     * настроена под мангу. Оверле поверх чужого приложения распознаёт совсем
     * другой шрифт (игровой, мелкий, цветной), и для него нужен отдельный
     * выбор. Без этой функции переключатель «свой движок у оверлея» был
     * пустой надписью: на кнопке менялось только название, а распознавал всё
     * равно движок читалки.
     *
     * @param model движок; null означает «как у читалки»
     */
    suspend fun recognizeText(image: OcrImage, model: OcrModel?): String

    /**
     * Движок, который ФАКТИЧЕСКИ вернул последний распознанный текст.
     *
     * Нужен читателю, а не разработчику: без сети онлайн-движок молча
     * пропускается, и страницу читает локальный. Отсюда жалоба «у онлайн
     * качество такое же, как у локального» — на самом деле это был один и
     * тот же движок. null означает, что до вызова ни один движок не
     * отработал.
     */
    val lastRecognizedEngine: OcrModel?

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

    /**
     * Распознать страницу.
     *
     * [onPartial] вызывается из того же прохода, который строит regions, по
     * мере готовности каждой области: локальный движок узнаёт рамки и идёт по
     * ним по очереди, поэтому текст можно озвучивать и показывать, не дожидаясь
* последней реплики страницы. Вызывается на рабочем потоке, последовательно
     * и только с НУЛЕВЫМ результатом области (пустые реплики не приходят).
     * Движки, отдающие страницу одним вызовом (онлайн), колбэк не вызывают.
     *
     * [cacheResult] = false для авточтения: там кадр — это ОКНО вебтуна, а не
     * страница, и рамки нормализованы к обрезанному кадру. Запись такого кадра
     * в кэш страницы портила подсветку оверлея, который потом подсвечивал не
     * там. Страницы, открытые читателем, кэшируются как раньше.
     */
    suspend fun scanPage(
        chapterId: Long,
        pageIndex: Int,
        image: OcrImage,
        onPartial: ((OcrRegion) -> Unit)? = null,
        cacheResult: Boolean = true,
    ): OcrPageResult

    suspend fun getCachedPage(
        chapterId: Long,
        pageIndex: Int,
    ): OcrPageResult?

    /**
     * Перезаписать результат страницы в кэше.
     *
     * Нужна после проверки моделью: локальный OCR отдаёт текст с ошибками и
     * пропусками, а читать должен проверенный вариант. Регионы приходят уже
     * с рамками — у строк, добавленных моделью, рамки нет, и выдумывать её
     * значило бы подсветить текст в пустоте.
     */
    suspend fun savePage(result: OcrPageResult)

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
