package mihon.domain.ocr.interactor

import mihon.domain.ocr.model.OcrImage
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.repository.OcrRepository

class OcrProcessor(
    private val ocrRepository: OcrRepository,
) {
    suspend fun getText(image: OcrImage): String {
        return ocrRepository.recognizeText(image)
    }

    /**
     * Только локальный движок, без сети. Для библиотеки книг: там текст уже
     * текст, и онлайн-распознавание не нужно тем более, что молча уводило
     * чтение книги в Gemini/OpenRouter.
     */
    suspend fun getLocalText(image: OcrImage): String {
        return ocrRepository.recognizeLocalText(image)
    }

    /**
     * Движок, который реально вернул текст последнего [getText].
     *
     * Не совпадает с выбранным в настройках, когда сети нет: онлайн-движок
     * пропускается, и читает локальный. Показываем читателю именно
     * фактического исполнителя, иначе «онлайн» в подписи была бы враньём.
     */
    fun lastUsedEngine(): OcrModel? = ocrRepository.lastRecognizedEngine
}
