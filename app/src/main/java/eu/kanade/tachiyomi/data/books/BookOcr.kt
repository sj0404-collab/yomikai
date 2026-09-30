package eu.kanade.tachiyomi.data.books

import android.graphics.Bitmap
import eu.kanade.tachiyomi.util.ocr.toOcrImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.ocr.interactor.OcrProcessor
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Распознавание текста страниц для книг (сканированные PDF, картинки внутри
 * EPUB). Использует штатный OCR-конвейер приложения (движок книг — CYRILLIC,
 * без сети и без моделей). Все ошибки обёрнуты в runCatching — OCR никогда
 * не роняет читалку.
 */
object BookOcr {

    private val processor: OcrProcessor by lazy { Injekt.get<OcrProcessor>() }

    /** Распознаёт текст на битмапе. null при любой ошибке (без исключений). */
    suspend fun recognize(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        runCatching {
            val needsCopy = bitmap.config != Bitmap.Config.ARGB_8888
            val bmp = if (needsCopy) bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap else bitmap
            val text = processor.getText(bmp.toOcrImage())
            if (needsCopy && bmp !== bitmap) bmp.recycle()
            text.trim()
        }.onFailure { e ->
            logcat(LogPriority.WARN, e) { "BookOcr: recognition failed" }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}