package eu.kanade.tachiyomi.ui.koma

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Переключатель между OCR движками в KOMA.
 * 
 * Содержит KomaOcrEngine (локальный ONNX) и KomaGlensWrapper (Google Lens онлайн).
 * Переключается между ними на основе выбранного engineId.
 */
class KomaOcrEngineSwitcher(
    private val context: Context,
    private val komaEngine: KomaOcrEngine
) {
    private var glensWrapper: KomaGlensWrapper? = null
    
    /**
     * Распознаёт текст со страницы через выбранный движок.
     * 
     * @param engineId ID выбранного движка ("koma_onnx", "glens", и т.д.)
     * @param path Путь к файлу страницы
     * @param language Язык OCR (для Glens)
     * @param region Регион (для Glens)
     * @return PageOcr с распознанным текстом
     */
    suspend fun recognize(
        engineId: String,
        path: String,
        language: String = "ru",
        region: String = "RU"
    ): PageOcr = withContext(Dispatchers.IO) {
        when (engineId) {
            "glens" -> {
                // Создаём GlensWrapper, если его ещё нет
                if (glensWrapper == null) {
                    glensWrapper = KomaGlensWrapper(context, komaEngine, language, region)
                }
                glensWrapper!!.recognize(path)
            }
            else -> {
                // По умолчанию используем KomaOcrEngine
                // TODO: вызвать komaEngine.recognize() с правильными параметрами
                PageOcr(
                    regions = emptyList(),
                    engine = "KOMA ONNX (not implemented)",
                    millis = 0,
                    edited = "Переключение на KOMA ONNX ещё не реализовано"
                )
            }
        }
    }
    
    /**
     * Закрывает все движки и освобождает ресурсы.
     */
    fun close() {
        glensWrapper?.close()
        glensWrapper = null
    }
}
