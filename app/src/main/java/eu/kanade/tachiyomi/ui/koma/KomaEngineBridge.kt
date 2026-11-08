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
     *
     * FIX 1: Lens Adapter in KOMA path (1071ms -> "no text")
     * - Aligns GlensOcrEngine request payload/timeout with KomaOcrEngine slot/band handling
     * - Takes values from ReaderActivity bridge logic (skipKoma, onOpenLegacy)
     * - If GlensOcrEngine fails or times out (<1071ms), returns null to trigger KOMA fallback
     * - Adjusts timeout in GlensOcrEngine.kt (CONNECT_TIMEOUT_MS=800, READ_TIMEOUT_MS=30000)
     */
    suspend fun read(
        context: Context,
        image: Bitmap,
        model: OcrModel,
        bookId: String,
        pageIndex: Int,
        // Added slot and band parameters to align with KomaOcrEngine.recognize
        slot: Int = 0,
        band: Int = 0,
        // ReaderActivity bridge values
        skipKoma: Boolean = false,
        onOpenLegacy: (() -> Unit)? = null,
    ): PageOcr? = withContext(Dispatchers.Default) {
        // FIX 1: If skipKoma is true (from ReaderActivity bridge), trigger KOMA path directly
        // and bypass GlensOcrEngine request sending logic to avoid 1071ms "no text" freeze.
        // The bridge logic in KomaReaderScreen handles the fallback if this returns null.
        if (skipKoma) {
            // Skip GlensOcrEngine and return null to let KomaOcrEngine handle it directly
            // This aligns with ReaderActivity.kt skipKoma intent extra and onOpenLegacy bridge.
            // Also trigger onOpenLegacy callback if provided (from ReaderActivity)
            onOpenLegacy?.invoke()
            return@withContext null
        }

        val repository = runCatching { Injekt.get<OcrRepository>() }.getOrNull() ?: return@withContext null
        
        // FIX 1: Lens Adapter in KOMA path (1071ms -> "no text")
        // - Aligns GlensOcrEngine request payload/timeout with KomaOcrEngine slot/band handling
        // - Takes values from ReaderActivity bridge logic (skipKoma, onOpenLegacy)
        // - If GlensOcrEngine fails or times out (<1071ms), returns null to trigger KOMA fallback
        // - Adjusts timeout in GlensOcrEngine.kt (CONNECT_TIMEOUT_MS=800, READ_TIMEOUT_MS=30000)
        
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
        
        // FIX 1: If GlensOcrEngine (online) fails or times out (e.g. 1071ms threshold),
        // result will be null or empty. KomaReaderScreen will then show "«...» не дал текста"
        // and use KomaOcrEngine (PaddleOCR) as fallback.
        // The bridge logic respects ReaderActivity bridge values (skipKoma, onOpenLegacy).
        
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
        
        // Include slot/band info in engine name for KOMA diagnostics
        val engineDisplay = if (slot > 0 || band > 0) "$engine · слот=$slot, полоса=$band" else engine
        
        PageOcr(
            regions = regions,
            engine = "$engineDisplay · ${if (OcrPlugins.byModel(result?.ocrModel ?: model).online) "онлайн" else "офлайн"}",
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
