package mihon.data.ocr

import android.content.Context
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
/** Слово из hOCR: рамка, текст, номер строки и уверенность распознавания. */
internal data class HocrWord(
    val text: String,
    val left: Int,
    val top: Int,
    val bottom: Int,
    val line: Int,
    val confidence: Int,
)

/**
 * Языковые данные Tesseract из APK: `assets/tessdata/rus.traineddata` (~3.7 МБ).
 *
 * Раньше модель качалась отдельным пакетом (~8 МБ) — и без неё движок молча
 * отдавал пустоту. Теперь русская модель лежит в APK и распаковывается один раз
 * (помечено versionCode, чтобы после обновления взять новую).
 */
object TesseractData {

    private const val ASSET = "tessdata/rus.traineddata"

    /** Один экземпляр на процесс: инициализация Tesseract недешёвая. */
    private var shared: TesseractOcrEngine? = null

    /**
     * Публичный вход для модуля `app` (сам движок `internal`): прочитать текст
     * с картинки Tesseract'ом. Нужен, когда ONNX на устройстве не живёт.
     */
    suspend fun recognizeText(context: Context, image: android.graphics.Bitmap): String {
        val engine = shared ?: TesseractOcrEngine(
            dataDir = ensure(context),
            prefs = mihon.domain.ocr.service.OcrPreferences(
                tachiyomi.core.common.preference.AndroidPreferenceStore(context),
            ),
        ).also { shared = it }
        return runCatching { engine.recognizeText(image) }.getOrDefault("")
    }

    fun ensure(context: Context): java.io.File? = runCatching {
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        }.getOrDefault(0L)
        val root = java.io.File(context.filesDir, "tessdata-$version").apply { mkdirs() }
        val target = java.io.File(root, "rus.traineddata")
        if (!target.isFile || target.length() < 1_000_000) {
            context.assets.open(ASSET).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        // Старые копии не нужны: 3.7 МБ в filesDir на каждую версию.
        runCatching {
            context.filesDir.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("tessdata-") && it.name != root.name }
                ?.forEach { it.deleteRecursively() }
        }
        root
    }.getOrNull()
}

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
            // Ищем через hOCR ВСЕГДА, а не только на странице: уверенность
            // слов нужна и в одиночном бабле. Именно там Tesseract чаще всего
            // выдаёт куски рамки панели и заливку как слова — на замере
            // «(Боеуше» шло с conf 0, а настоящее «не» с conf 90.
            val cleaned = runCatching { api.getHOCRText(HOCR_LAYOUT_BLOCKS) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { hocrFrom(it, minShare = PAGE_KEPT_LETTERS_SHARE) }
                .orEmpty()
            // hOCR ничего не вернул или текста после отсева не осталось —
            // отдаём пустоту, а не сырой мусор из getUTF8Text().
            cleaned
        } finally {
            if (prepared != null && prepared !== image && !prepared.isRecycled) prepared.recycle()
        }
    }

    /**
     * Разбор hOCR в чистый текст.
     *
     * hOCR — единственный вывод Tesseract, где у КАЖДОГО слова есть рамка и
     * уверенность (`x_wconf`). Именно она отличает настоящий текст от линий
     * панели и рисунка: у слова из арта уверенность 0–20, у настоящего — 80+.
     *
     * Важно: уверенность говорит «это текст, а не арт», но НЕ «слово написано
     * верно». Проверено на реальном бабле: «(Боеуше» — conf 0, «никогда» — 22,
     * «не» — 90. То есть мусор фильтруется надёжно, а опечатки остаются; за
     * них отвечает словарь и чистка в AutoReadEngine.
     *
     * Раньше здесь печаталась верхняя координата рамки вместо самого слова —
     * на странице движок выдавал числа вместо текста.
     */
    private fun hocrFrom(hocr: String, minShare: Float = 0f): String {
        val words = WORD_RE.findAll(hocr)
            .mapNotNull { match ->
                val left = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val top = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                val bottom = match.groupValues[4].toIntOrNull() ?: return@mapNotNull null
                val text = match.groupValues[5].trim()
                if (text.isEmpty()) return@mapNotNull null
                HocrWord(
                    text = text,
                    left = left,
                    top = top,
                    bottom = bottom,
                    line = match.groupValues[6].toIntOrNull() ?: 0,
                    confidence = match.groupValues[7].toIntOrNull() ?: 0,
                )
            }
            .toList()
        if (words.isEmpty()) return ""
        return cleanWords(words, minShare)
    }

    /**
     * Отсев мусора по уверенности и геометрии, затем сборка по строкам.
     *
     * Проверка результата обязательна: если после отсева осталось меньше
     * [PAGE_KEPT_LETTERS_SHARE] букв от исходных, значит мы вычитывали рисунок, а
     * не текст. Лучше вернуть пустоту — тогда авточтение промолчит, чем
     * прочитает «БУБУ» (такое уже случалось на странице без текста).
     */

    override fun close() {
        runCatching { api?.recycle() }
    }

    internal companion object {
        /**
         * Слово hOCR: `bbox L T R B; x_wconf N` внутри `ocr_line N` и текст.
         * Группы: 1=L, 2=T, 3=R, 4=B, 5=текст, 6=номер строки, 7=уверенность.
         */
        private val WORD_RE = Regex(
            """ocr_line_(\d+)[^>]*>(?:(?!</span>).)*?class='ocrx_word'[^>]*""" +
                """title='bbox\s+\d+\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+);[^']*""" +
                """x_wconf\s+(-?\d+)'[^>]*>([^<]+)<""",
        )
        internal fun cleanWords(words: List<HocrWord>, minShare: Float = 0f): String {
            val totalLetters = words.sumOf { it.text.count(Char::isLetter) }
            if (totalLetters == 0) return ""
            val heights = words.map { it.bottom - it.top }.filter { it > 0 }.sorted()
            val medianHeight = heights.getOrNull(heights.size / 2) ?: 1
            val kept = words.filter { word ->
                val height = word.bottom - word.top
                val letters = word.text.count(Char::isLetter)
                // Уверенность НЕ различает текст и рисунок: на реальной
                // странице «ЧУВСТВУЮТ» пришло с conf 0, а мусор «ИГРА.» — с
                // conf 29. Поэтому жёсткий порог здесь съедал настоящие
                // слова. Правило двух уровней: уверенные проходят всегда,
                // сомнительные — только если это похоже на слово, а не на
                // штрих рамки.
                val trustworthy = word.confidence >= CONFIDENT_WORD_CONFIDENCE ||
                    (word.confidence >= UNCERTAIN_WORD_CONFIDENCE && letters >= REAL_WORD_LETTERS)
                trustworthy &&
                    letters >= MIN_WORD_LETTERS &&
                    height >= MIN_WORD_HEIGHT_RATIO * medianHeight &&
                    height <= MAX_WORD_HEIGHT_RATIO * medianHeight
            }
            val keptLetters = kept.sumOf { it.text.count(Char::isLetter) }
            // Страховка от «мы вычитали рисунок»: применяется только к целой
            // странице (minShare), у бабла не применяется — там настоящие слова
            // закономерно получают низкую уверенность.
            if (minShare > 0f && keptLetters < minShare * totalLetters) return ""
            val byLine = kept.groupBy { it.line }
            return byLine.entries
                .sortedBy { entry -> entry.value.minOf { it.top } }
                .joinToString("\n") { entry ->
                    entry.value.sortedBy { it.left }.joinToString(" ") { it.text }
                }
                .trim()
        }

        /**
         * Уверенность, начиная с которой слово считаем настоящим без оговорок.
         */
        private const val CONFIDENT_WORD_CONFIDENCE = 60

        /** Ниже этого — штрих рамки или одиночный символ, даже если букв хватает. */
        private const val UNCERTAIN_WORD_CONFIDENCE = 25

        /** Столько букв нужно, чтобы «слово» сочлось текстом, а не мусором. */
        private const val REAL_WORD_LETTERS = 4

        /** Слово короче этих долей медианной высоты — штрих, а не буква. */
        private const val MIN_WORD_HEIGHT_RATIO = 0.3f

        /** Сильно выше медианной — тоже арт (вспышки, заливки). */
        private const val MAX_WORD_HEIGHT_RATIO = 3.5f

        /** Минимум букв в слове: одиночные точки и чёрточки — мусор. */
        private const val MIN_WORD_LETTERS = 2

        /**
         * Доля букв, которую должна пережить чистка. Ниже — мы вычитывали
         * рисунок, и движок честно отдаёт пустоту вместо мусора.
         */
        private const val PAGE_KEPT_LETTERS_SHARE = 0.5f

        /** Формат hOCR: блоки — так слова одной реплики не разлетаются. */
        private const val HOCR_LAYOUT_BLOCKS = 3
    }
}
