package eu.kanade.tachiyomi.ui.koma

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.data.ocr.OcrPlugins
import mihon.domain.ocr.model.OcrImage
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.repository.OcrRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Мост «KOMA → движок из настроек».
 *
 * Зачем он нужен: в приложении уже есть реестр движков распознавания
 * (`OcrPlugins`: PP-OCR, Tesseract, ML Kit, Google Lens, Space Bunny, Gemini,
 * OpenRouter, OwOCR) и общий репозиторий, который умеет ими распознавать
 * страницу и сообщать, кто ФАКТИЧЕСКИ отдал текст. Читалка KOMA жила отдельно
 * и знала только свой встроенный PP-OCR — выбор в настройках на неё не влиял.
 *
 * Здесь кадр страницы (полоса вебтуна или лист) уходит в тот же репозиторий.
 * Ответ переводится в [PageOcr]: рамки у движков с поддержкой областей свои,
 * текст — их собственный. Кэш страниц репозитория НЕ используется
 * (`cacheResult = false`): у читалки свой кэш, а номер главы здесь
 * синтетический, и записи от него потом нечем было бы чистить.
 */
object KomaEngineBridge {

    /**
     * Кадр → репозиторий → реплики читалки.
     *
     * Возвращает null, когда движок не дал текста (нет сети/ключа, отказ,
     * пустая страница): вызывающий код обязан прочитать страницу локально и
     * СКАЗАТЬ об этом, а не показать пустой экран.
     */
    suspend fun read(
        context: Context,
        image: Bitmap,
        model: OcrModel,
        bookId: String,
        pageIndex: Int,
    ): PageOcr? = withContext(Dispatchers.Default) {
        val repository = runCatching { Injekt.get<OcrRepository>() }.getOrNull() ?: return@withContext null
        // Кадр уменьшается до разумного предела: облачные движки считают
        // пиксели деньгами и временем, а для локальных 1600 px по длинной стороне
        // достаточно, чтобы не потерять мелкий шрифт.
        val source = scale(image)
        val started = System.currentTimeMillis()
        val (pixels, width, height) = runCatching {
            val w = source.width
            val h = source.height
            val array = IntArray(w * h)
            source.getPixels(array, 0, w, 0, 0, w, h)
            Triple(array, w, h)
        }.getOrNull() ?: return@withContext null
        if (source !== image && !source.isRecycled) source.recycle()
        val result = runCatching {
            repository.scanPage(
                // Синтетический, но СТАБИЛЬНЫЙ номер: по нему репозиторий
                // только журналирует скан. Кэш отключён ниже, поэтому мусор в
                // хранилище глав не появляется.
                chapterId = syntheticChapterId(bookId),
                pageIndex = pageIndex,
                image = OcrImage(width, height, pixels),
                cacheResult = false,
            )
        }.getOrNull()
        // Если выбранный движок не ответил, репозиторий мог сам уйти в
        // фолбэк — тогда в отчёте будет ДРУГОЙ движок, и это надо показать
        // честно: так и появлялось «включил Gemini, а читает как раньше».
        val regions = result?.regions.orEmpty().mapNotNull { region ->
            val box = region.boundingBox
            val text = region.text.trim()
            if (text.isEmpty() || !box.isValid()) return@mapNotNull null
            OcrRegion(
                text = text,
                left = box.left.coerceIn(0f, 1f),
                top = box.top.coerceIn(0f, 1f),
                right = box.right.coerceIn(0f, 1f),
                bottom = box.bottom.coerceIn(0f, 1f),
            )
        }.takeIf { it.isNotEmpty() } ?: return@withContext null
        val engine = result?.ocrModel?.let { runCatching { OcrPlugins.byModel(it).title }.getOrNull() }
            ?: runCatching { OcrPlugins.byModel(model).title }.getOrDefault("движок из настроек")
        PageOcr(
            regions = regions,
            engine = "$engine · ${if (OcrPlugins.byModel(result?.ocrModel ?: model).online) "онлайн" else "офлайн"}",
            millis = System.currentTimeMillis() - started,
        )
    }

    /** Стабильный псевдо-id главы из строки: нужен только для журнала. */
    fun syntheticChapterId(bookId: String): Long =
        (bookId.hashCode().toLong() and 0x7FFFFFFFL) * 1000L

    /**
     * Уменьшение кадра до 1600 px по длинной стороне. Меньше — мелкий шрифт
     * теряется, больше — растёт время ответа и трафик облачных движков.
     */
    private fun scale(image: Bitmap, maxSide: Int = 1600): Bitmap {
        val longSide = maxOf(image.width, image.height)
        if (longSide <= maxSide) return image
        val ratio = maxSide.toFloat() / longSide
        val width = (image.width * ratio).toInt().coerceAtLeast(1)
        val height = (image.height * ratio).toInt().coerceAtLeast(1)
        return runCatching { Bitmap.createScaledBitmap(image, width, height, true) }.getOrDefault(image)
    }
}
