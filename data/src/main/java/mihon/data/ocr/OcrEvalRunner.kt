package mihon.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import com.google.ai.edge.litert.Environment
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Проверка локального OCR на эталонах, которые рисует сам телефон.
 *
 * Зачем это отдельный инструмент, а не обычный тест: локальный движок —
 * это PP-OCR на TFLite (`CyrillicOcrEngine`), интерпретатор и модели
 * нативные. В JVM-тестах (`testDebugUnitTest`) его не запустить, поэтому
 * ни одна проверка качества или скорости на CI невозможна. Здесь страница
 * рисуется прямо на устройстве через [Canvas], после чего тем же самым
 * движком читается и сравнивается с эталоном.
 *
 * Эталонов намеренно шесть: они повторяют то, что реально встречается в
 * авточтении — крупный текст, реплика в «пузыре», мелкий кегль, слабый
 * контраст, скан с шумом и наклоном, и две строки вплотную (проверка
 * разбиения на строки, которое недавно добавили).
 *
 * Меряется три числа: сколько стоит загрузка моделей, сколько занимает
 * детект и сколько — чтение целиком. Плюс CER (доля ошибок символов) —
 * единственная честная метрика «прочитал ли он это правильно».
 */
class OcrEvalRunner(
    private val context: Context,
    private val tuningProvider: () -> OcrTuning = { OcrTuning.DEFAULT },
) {

    /** Эталон: страница с заданными параметрами отрисовки. */
    data class Variant(
        val name: String,
        val note: String,
        val lines: List<String>,
        val textSize: Float = 44f,
        val textColor: Int = Color.BLACK,
        val bgColor: Int = Color.WHITE,
        val lineHeight: Float = 70f,
        val firstBaseline: Float = 120f,
        val bubble: Boolean = false,
        val rotationDeg: Float = 0f,
        val noiseDots: Int = 0,
    )

    /** Что получилось на одном эталоне. */
    data class Result(
        val name: String,
        val note: String,
        val expected: String,
        val actual: String,
        val cerPercent: Int,
        val boxes: Int,
        val detectMs: Long,
        val totalMs: Long,
    )

    data class Report(
        val loadMs: Long,
        val results: List<Result>,
    ) {
        val totalMs: Long get() = results.sumOf { it.totalMs }
        val meanCerPercent: Int
            get() = if (results.isEmpty()) 0 else results.sumOf { it.cerPercent } / results.size

        /** Отчёт для показа пользователю: без Markdown, моноширинный текст. */
        fun format(): String = buildString {
            append("Локальный OCR: ").append(results.size).append(" эталонов\n")
            append("Загрузка моделей: ").append(loadMs).append(" мс\n")
            append("Средний CER: ").append(meanCerPercent).append("%\n")
            append("Всего чтение: ").append(totalMs).append(" мс\n\n")
            for ((index, r) in results.withIndex()) {
                append(index + 1).append(". ").append(r.name).append(" — ").append(r.note).append('\n')
                append("   CER ").append(r.cerPercent).append("%")
                append(" | рамок ").append(r.boxes)
                append(" | детект ").append(r.detectMs).append(" мс")
                append(" | всего ").append(r.totalMs).append(" мс\n")
                append("   ждём: ").append(r.expected).append('\n')
                append("   факт: ").append(r.actual).append("\n\n")
            }
        }
    }

    /**
     * Читает все эталоны. Модели грузятся один раз перед замерами, иначе
     * первая же цифра в отчёте была бы временем загрузки, а не временем
     * распознавания.
     */
    suspend fun run(onProgress: (String) -> Unit = {}): Report {
        val engine = CyrillicOcrEngine(context, Environment.create(), TextPostprocessor(), tuningProvider)
        val variants = variants()

        onProgress("Загрузка моделей…")
        val loadStart = SystemClock.elapsedRealtime()
        val warmup = render(variants.first())
        engine.detectRegions(warmup)
        warmup.recycle()
        val loadMs = SystemClock.elapsedRealtime() - loadStart

        val results = variants.map { variant ->
            onProgress("Читаю «${variant.name}»…")
            val bitmap = render(variant)
            val detectStart = SystemClock.elapsedRealtime()
            val boxes = runCatching { engine.detectRegions(bitmap) }.getOrDefault(emptyList())
            val detectMs = SystemClock.elapsedRealtime() - detectStart

            val readStart = SystemClock.elapsedRealtime()
            val actual = runCatching { engine.recognizeText(bitmap) }.getOrDefault("")
            val totalMs = SystemClock.elapsedRealtime() - readStart
            bitmap.recycle()

            Result(
                name = variant.name,
                note = variant.note,
                expected = OcrEvalMetrics.normalize(variant.lines.joinToString(" ")),
                actual = OcrEvalMetrics.normalize(actual),
                cerPercent = cerPercent(variant.lines.joinToString(" "), actual),
                boxes = boxes.size,
                detectMs = detectMs,
                totalMs = totalMs,
            )
        }
        return Report(loadMs = loadMs, results = results)
    }

    /**
     * Эталоны. Тексты взяты разные по набору букв: с «ё», «ъ», «ь», со
     * строчными/прописными и цифрами, чтобы было видно, где движок спотыкается,
     * а не только «прочитал первую строку».
     */
    fun variants(): List<Variant> = listOf(
        Variant(
            name = "крупно",
            note = "чёрный по белому, 44 px",
            lines = listOf("Привет, мир!", "Это проверка OCR.", "Съешь ещё этих мягких булок."),
        ),
        Variant(
            name = "пузырь",
            note = "реплика в рамке, 38 px",
            lines = listOf("Ты меня слышишь?", "Да, слышу."),
            textSize = 38f,
            bubble = true,
            lineHeight = 96f,
        ),
        Variant(
            name = "мелко",
            note = "20 px, тонкие буквы",
            lines = listOf("Съешь ещё этих мягких булок, да выпей чаю."),
            textSize = 20f,
            firstBaseline = 60f,
        ),
        Variant(
            name = "контраст",
            note = "серый по светло-серому",
            lines = listOf("Бледный текст читается хуже."),
            textColor = 0xFF9AA0A6.toInt(),
            bgColor = 0xFFF2F2F2.toInt(),
        ),
        Variant(
            name = "скан",
            note = "шум 4000 точек + наклон 2°",
            lines = listOf("Скан с наклоном и шумом."),
            rotationDeg = 2f,
            noiseDots = 4000,
        ),
        Variant(
            name = "две строки",
            note = "строки вплотную, 30 px",
            lines = listOf("Первая строка подписана", "второй строкой ниже"),
            textSize = 30f,
            lineHeight = 38f,
        ),
    )

    private fun render(variant: Variant): Bitmap {
        val bitmap = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(variant.bgColor)
        if (variant.rotationDeg != 0f) {
            canvas.rotate(variant.rotationDeg, PAGE_WIDTH / 2f, PAGE_HEIGHT / 2f)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            color = variant.textColor
            textSize = variant.textSize
        }
        var baseline = variant.firstBaseline
        for (line in variant.lines) {
            if (variant.bubble) {
                drawBubble(canvas, line, baseline, paint)
            } else {
                canvas.drawText(line, MARGIN, baseline, paint)
            }
            baseline += variant.lineHeight
        }
        if (variant.noiseDots > 0) {
            val random = Random(variant.name.hashCode())
            val dot = Paint().apply { color = 0x22000000 }
            repeat(variant.noiseDots) {
                val x = random.nextFloat() * PAGE_WIDTH
                val y = random.nextFloat() * PAGE_HEIGHT
                canvas.drawCircle(x, y, random.nextFloat() * 1.6f + 0.3f, dot)
            }
        }
        return bitmap
    }

    private fun drawBubble(canvas: Canvas, line: String, baseline: Float, paint: Paint) {
        val width = paint.measureText(line)
        val rect = RectF(
            MARGIN - 16f,
            baseline - paint.textSize - 10f,
            MARGIN + width + 16f,
            baseline + 12f,
        )
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = paint.color
        }
        canvas.drawRoundRect(rect, 18f, 18f, border)
        canvas.drawText(line, MARGIN, baseline, paint)
    }

    /**
     * CER: доля несовпавших символов. Сравниваются только буквы, цифры и
     * пробелы — пунктуация движком меняется (тире, кавычки), и из-за неё
     * метрика показывала бы ошибки там, где их нет.
     */
    fun cerPercent(expected: String, actual: String): Int = OcrEvalMetrics.cerPercent(expected, actual)

    private companion object {
        const val PAGE_WIDTH = 1000
        const val PAGE_HEIGHT = 1400
        const val MARGIN = 60f
    }
}

/**
 * Чистая часть проверки: нормализация текста и CER. Вынесена отдельно от
 * [OcrEvalRunner], чтобы считалась в обычном JVM-тесте — сам движок требует
 * устройства, а метрика не требует ничего.
 */
internal object OcrEvalMetrics {

    /**
     * Приводит текст к сравнимому виду: нижний регистр, «ё» к «е», только
     * буквы/цифры/пробелы, схлопнутые пробелы.
     */
    fun normalize(text: String): String = text
        .lowercase()
        .replace('ё', 'е')
        // Всё, что не буква и не цифра, становится РАЗДЕЛИТЕЛЕМ, а не
        // исчезает: иначе «Да» — слышу» превратится в «да  слышу» с двумя
        // пробелами, и один лишний пробел уже считался бы ошибкой в CER.
        // Перевод строки и табуляция сюда же попадают — они тоже разделители.
        .map { if (it.isLetterOrDigit()) it else ' ' }
        .joinToString("")
        .replace(Regex(" +"), " ")
        .trim()

    /** Доля несовпавших символов, в процентах от длины эталона. */
    fun cerPercent(expected: String, actual: String): Int {
        val a = normalize(expected)
        val b = normalize(actual)
        if (a.isEmpty()) return if (b.isEmpty()) 0 else 100
        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            System.arraycopy(current, 0, previous, 0, current.size)
        }
        return (previous[b.length] * 100f / a.length).roundToInt()
    }
}
