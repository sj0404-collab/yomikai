package mihon.data.ocr

import android.graphics.Bitmap
import logcat.LogPriority
import com.googlecode.tesseract.android.TessBaseAPI
import mihon.domain.ocr.service.OcrPreferences
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Настоящий Tesseract с русской моделью.
 *
 * До этого пункт «Tesseract» в настройках был фикцией: [OcrRepositoryImpl]
 * отображал его в `EngineType.CYRILLIC`, и под именем Tesseract работал
 * PP-OCR. Настройки рядом (языки, сегментация страницы, увеличение кропа,
 * предобработка) не влияли ни на что, потому что движка за ними не было.
 *
 * Движок медленный: это честная цена точности Tesseract на кириллице. Он
 * нужен там, где важнее не ошибиться в тексте, а не успеть прочитать кадр.
 *
 * Модель (`rus.traineddata`) не вшита в APK, а скачивается в
 * `ocr_models/tesseract/` тем же загрузчиком, что и остальные модели: APK не
 * должен тащить за собой ещё десяток мегабайт.
 */
internal class TesseractOcrEngine(
    private val dataDir: File?,
    private val prefs: OcrPreferences,
) : LineOcrEngine {

    private val api: TessBaseAPI? by lazy {
        val dir = dataDir
        if (dir == null || !dir.isDirectory || !hasLanguageData(dir)) {
            logcat(LogPriority.WARN) {
                "Tesseract: нет языковых данных в ${dir?.absolutePath ?: "неизвестно"} " +
                    "(нужен rus.traineddata)"
            }
            null
        } else {
            runCatching {
                TessBaseAPI().apply {
                    // Сегментация — до init: часть движков применяет её только
                    // на старте. Языки берём из настройки («rus» по умолчанию).
                    setPageSegMode(pageSegModeFromPref())
                    init(dir.absolutePath, langs().joinToString("+"))
                }
            }.onFailure {
                logcat(LogPriority.WARN, it) { "Tesseract: init не удался" }
            }.getOrNull()
        }
    }

    private fun hasLanguageData(dir: File): Boolean =
        langs().all { File(dir, "$it.traineddata").isFile }

    private fun langs(): List<String> =
        prefs.tessLangs().get()
            .split('+', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf("rus") }

    private fun pageSegModeFromPref(): Int = when (prefs.tessPsm().get()) {
        "single_line" -> TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
        "auto" -> TessBaseAPI.PageSegMode.PSM_AUTO
        "sparse" -> TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
        else -> TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
    }

    override suspend fun recognizeText(image: Bitmap): String =
        recognizePrepared(image, wholePage = true)

    override suspend fun recognizeLine(image: Bitmap): String =
        recognizePrepared(image, wholePage = false)

    /**
     * Распознавание с подготовкой кропа.
     *
     * Мелкий текст в баблах Tesseract читает заметно хуже, поэтому кроп
     * увеличивается до [OcrPreferences.tessUpscaleMinSide] — если пользователь
     * не отключил предобработку.
     */
    private fun recognizePrepared(image: Bitmap, wholePage: Boolean): String {
        val api = api ?: return ""
        val minSide = if (prefs.tessPreprocess().get()) prefs.tessUpscaleMinSide().get() else 0
        val prepared = if (minSide > 0 && minOf(image.width, image.height) < minSide) {
            val scale = minSide.toFloat() / minOf(image.width, image.height).coerceAtLeast(1)
            runCatching {
                Bitmap.createScaledBitmap(
                    image,
                    (image.width * scale).toInt().coerceAtLeast(1),
                    (image.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            }.getOrNull()
        } else {
            null
        }
        return try {
            api.setImage(prepared ?: image)
            val raw = api.getUTF8Text().orEmpty()
            // getUTF8Text() склеивает слова вплотную и сносит переводы строк —
            // для дальнейшей очистки и разбивки на реплики это не годится.
            // На странице берём hOCR: в нём есть координаты слов, по ним текст
            // снова разбивается на строки.
            if (wholePage) runCatching { api.getHOCRText(HOCR_LAYOUT_BLOCKS) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { hocrFrom(it) }
                ?: raw
            else {
                raw
            }
        } finally {
            if (prepared != null && prepared !== image && !prepared.isRecycled) prepared.recycle()
        }
    }

    /**
     * Разбор hOCR: Tesseract отдаёт в нём координаты каждого блока, поэтому
     * реплики не слипаются в одну простыню, а остаются отдельными строками.
     */
    private fun hocrFrom(hocr: String): String {
        val text = StringBuilder()
        var previousBottom: Int? = null
        WORD_RE.findAll(hocr).forEach { match ->
            val bbox = BBOX_RE.find(match.value)
            val bottom = bbox?.groupValues?.getOrNull(4)?.toIntOrNull()
            val left = bbox?.groupValues?.getOrNull(2)?.toIntOrNull()
            val previousLeft = previousLeftRef
            if (previousBottom != null && bottom != null) {
                val newBlock = bottom - previousBottom > LINE_JUMP_PX ||
                    (left != null && previousLeft != null && left < previousLeft - COLUMN_JUMP_PX)
                if (newBlock) text.append('\n')
                else text.append(' ')
            } else if (previousBottom != null) {
                text.append(' ')
            }
            text.append(match.groupValues[2])
            previousLeftRef = left
            previousBottom = bottom
        }
        return text.toString()
            .lines()
            .joinToString("\n") { it.trim() }
            .trim()
    }

    private var previousLeftRef: Int? = null

    override fun close() {
        runCatching { api?.recycle() }
    }

    private companion object {
        /** Слова hOCR: `bbox x0 y0 x1 y1 …>СЛОВО<` */
        private val WORD_RE = Regex("""bbox \d+ (\d+) (\d+) (\d+) (\d+) [^>]*>([^<]+)<""")
        private val BBOX_RE = Regex("""bbox (\d+) (\d+) (\d+) (\d+)""")
        /** Формат hOCR: блоки — так слова одной реплики не разлетаются. */
        private const val HOCR_LAYOUT_BLOCKS = 3
        private const val LINE_JUMP_PX = 25
        private const val COLUMN_JUMP_PX = 40
    }
}
