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
 * Распознавание страниц книг — и только страниц.
 *
 * **Онлайн здесь не нужен и раньше не использовался по замыслу, но
 * использовался по факту.** Вызывался обычный [OcrProcessor.getText], а он берёт
 * движок из общей настройки распознавания — той, что настроена под мангу. Если
 * читатель выбрал там Glens, Gemini или OpenRouter, открытие книги молча
 * отправляло страницы в сеть: с ключом, без ключа, с трафиком и с задержкой в
 * десятки секунд. Плюс цепочка фолбэков локального движка тоже умела уйти в
 * онлайн.
 *
 * Теперь вызывается [OcrProcessor.getLocalText]: движок выбирается по
 * фактической готовности (кириллический PP-OCR, если скачан пакет моделей и
 * есть LiteRT, иначе встроенный Google ML Kit), сеть запрещена и первичным
 * движком, и фолбэком. Книга, у которой текст уже есть (EPUB, FB2, TXT, DOCX,
 * HTML), сюда не попадает вовсе — распознаётся только страница PDF/DJVU.
 *
 * Все ошибки обёрнуты: OCR никогда не роняет читалку. Но отсутствие локального
 * движка теперь не тишина, а [Result]: читателю нужно знать, что поставить
 * пакет моделей, иначе он гадает, почему страница молчит.
 */
object BookOcr {

    private val processor: OcrProcessor by lazy { Injekt.get<OcrProcessor>() }

    /** Результат распознавания страницы с честной причиной отсутствия текста. */
    sealed interface Result {
        /** Текст страницы; пустая строка — распознали, но текста нет. */
        data class Text(val text: String) : Result

        /** Локальный движок не дал результата. */
        data class Unavailable(val reason: String) : Result
    }

    /**
     * Распознаёт страницу книги локальным движком.
     *
     * null остаётся для вызывающих, которым достаточно «есть текст или нет»
     * ([recognize]); там пустой результат честно превращается в пустую строку.
     */
    suspend fun recognize(bitmap: Bitmap): String? = when (val r = recognizeResult(bitmap)) {
        is Result.Text -> r.text.trim().takeIf { it.isNotBlank() }
        is Result.Unavailable -> {
            logcat(LogPriority.WARN) { "BookOcr: ${r.reason}" }
            null
        }
    }

    /** То же, но с причиной: нужно читалке, чтобы сказать читателю, что делать. */
    suspend fun recognizeResult(bitmap: Bitmap): Result = withContext(Dispatchers.IO) {
        runCatching {
            val needsCopy = bitmap.config != Bitmap.Config.ARGB_8888
            val bmp = if (needsCopy) bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap else bitmap
            try {
                val text = processor.getLocalText(bmp.toOcrImage()).trim()
                if (text.isNotBlank()) Result.Text(text) else Result.Unavailable("На странице нет распознанного текста")
            } finally {
                if (needsCopy && bmp !== bitmap && !bmp.isRecycled) bmp.recycle()
            }
        }.onFailure { e ->
            logcat(LogPriority.WARN, e) { "BookOcr: recognition failed" }
        }.getOrElse { e ->
            Result.Unavailable(e.message ?: e.javaClass.simpleName)
        }
    }
}