package eu.kanade.tachiyomi.ui.koma

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.data.ocr.GlensOcrEngine

/**
 * Wrapper для интеграции GlensOcrEngine в KOMA.
 * 
 * GlensOcrEngine работает с Bitmap, а KomaOcrEngine работает с путями к файлам.
 * Этот wrapper адаптирует GlensOcrEngine к интерфейсу KOMA:
 * - Принимает путь к странице (как KomaOcrEngine)
 * - Загружает Bitmap через KomaOcrEngine.bitmap(path)
 * - Передаёт Bitmap в GlensOcrEngine.recognizeText()
 * - Возвращает результат в формате PageOcr
 */
class KomaGlensWrapper(
    private val context: Context,
    private val komaEngine: KomaOcrEngine,
    private val language: String = "ru",
    private val region: String = "RU"
) {
    private val glensEngine = GlensOcrEngine(
        clientLanguage = language,
        clientRegion = region
    )

    /**
     * Распознаёт текст со страницы через Google Lens.
     * 
     * @param path Путь к файлу страницы
     * @return PageOcr с распознанным текстом (без регионов, только текст)
     */
    suspend fun recognize(path: String): PageOcr = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        try {
            // Загружаем Bitmap через KomaOcrEngine
            val bitmap = komaEngine.bitmap(path)
            
            // Распознаём через GlensOcrEngine
            val text = glensEngine.recognizeText(bitmap)
            
            val elapsed = System.currentTimeMillis() - startTime
            
            // Создаём PageOcr с пустыми регионами (Glens не возвращает координаты)
            PageOcr(
                regions = emptyList(),
                engine = "Google Lens",
                millis = elapsed,
                edited = text
            )
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            // Возвращаем пустой результат с ошибкой в edited
            PageOcr(
                regions = emptyList(),
                engine = "Google Lens (error)",
                millis = elapsed,
                edited = "Ошибка распознавания: ${e.message}"
            )
        }
    }

    /**
     * Закрывает движок и освобождает ресурсы.
     */
    fun close() {
        glensEngine.close()
    }
}
