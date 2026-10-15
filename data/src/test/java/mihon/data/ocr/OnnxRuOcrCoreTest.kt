package mihon.data.ocr

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeGreaterThan
import org.junit.jupiter.api.Test

/**
 * Ядро локального ONNX OCR (CRNN+CTC, кириллица): алфавит, greedy-декод,
 * типографика и сегментатор страницы. Все функции — чистый Kotlin, поэтому
 * проверяются без Android и без модели.
 */
class OnnxRuOcrCoreTest {

    @Test
    fun `charset matches the trained model meta`() {
        // Лексикон обученной модели (~/yomikai-ocr): RU66 + UK8 + PUNCT35 = 109,
        // классов = 109 + blank + unk = 111. Проверяем согласованность
        // констант, чтобы следующая правка алфавита не рассинхронизировалась
        // с экспортером модели.
        OnnxRuOcrCore.CHARSET.length shouldBe 109
        OnnxRuOcrCore.NUM_CLASSES shouldBe OnnxRuOcrCore.CHARSET.length + 2
        OnnxRuOcrCore.BLANK shouldBe OnnxRuOcrCore.CHARSET.length
        OnnxRuOcrCore.UNK shouldBe OnnxRuOcrCore.CHARSET.length + 1
        OnnxRuOcrCore.LINE_HEIGHT shouldBe 32
        OnnxRuOcrCore.CHARSET.iterator().let { seq ->
            seq.asSequence().toSet().size shouldBe OnnxRuOcrCore.CHARSET.length
        }
    }

    @Test
    fun `ctc decode collapses repeats and drops blanks`() {
        // «ПП-р-ивет»: повторы склеиваются CTC-правилом, blank/unk теряются.
        val ids = intArrayOf(
            idx('П'), idx('П'), OnnxRuOcrCore.BLANK, idx('р'),
            OnnxRuOcrCore.UNK, idx('и'), idx('в'), idx('в'), idx('е'), idx('т'),
        )
        OnnxRuOcrCore.decode(ids) shouldBe "Привет"
    }

    @Test
    fun `ctc decode keeps legit doubles across blank`() {
        // Повтор через blank — это две КАЖДЫЕ буквы: «ночЧь» не должно схлопнуться.
        // Используем украинскую «ї» для проверки второго блока алфавита.
        val ids = intArrayOf(idx('ї'), OnnxRuOcrCore.BLANK, idx('ї'))
        OnnxRuOcrCore.decode(ids) shouldBe "її"
        val doubles = intArrayOf(idx('о'), OnnxRuOcrCore.BLANK, idx('о'))
        OnnxRuOcrCore.decode(doubles) shouldBe "оо"
    }

    @Test
    fun `greedy decode picks argmax and reports confidence`() {
        // Два кадра: «А» уверенно, blank — тоже уверенно (blank не часть текста).
        val frames = 3
        val logits = FloatArray(frames * OnnxRuOcrCore.NUM_CLASSES)
        val kA = idx('А')
        for (k in 0 until OnnxRuOcrCore.NUM_CLASSES) {
            logits[0 * OnnxRuOcrCore.NUM_CLASSES + k] = -5f
            logits[1 * OnnxRuOcrCore.NUM_CLASSES + k] = -2f
            logits[2 * OnnxRuOcrCore.NUM_CLASSES + k] = -5f
        }
        logits[kA] = 5f
        logits[OnnxRuOcrCore.NUM_CLASSES + OnnxRuOcrCore.BLANK] = 4f
        logits[2 * OnnxRuOcrCore.NUM_CLASSES + idx('!')] = 5f

        val (text, conf) = OnnxRuOcrCore.greedyDecode(logits, frames)
        text shouldBe "А!"
        conf shouldBeGreaterThan 0.5f
        OnnxRuOcrCore.greedyDecode(FloatArray(0), 0).first shouldBe ""
        OnnxRuOcrCore.greedyDecode(logits, 0).first shouldBe ""
    }

    @Test
    fun `postprocess collapses ctc ellipsis and tidies commas`() {
        OnnxRuOcrCore.postprocess("Где… …Это…") shouldBe "Где… …Это…"
        OnnxRuOcrCore.postprocess("Смотри ,тут , странно") shouldBe "Смотри, тут, странно"
        OnnxRuOcrCore.postprocess("  много   пробелов  ") shouldBe "много пробелов"
        OnnxRuOcrCore.postprocess("А………!") shouldBe "А…!"
        OnnxRuOcrCore.postprocess("") shouldBe ""
    }

    @Test
    fun `segmenter finds a solid text line`() {
        val w = 200
        val h = 80
        val gray = IntArray(w * h) { 245 }
        // «Строка» из чернил: высокая (20 px) сплошная полоса.
        for (y in 30 until 50) {
            for (x in 20 until 170) {
                gray[y * w + x] = 20
            }
        }
        val boxes = OnnxRuPageSegmenter.findLineBoxes(gray, w, h)
        boxes.size shouldBe 1
        val box = boxes.single()
        // Морфологическое замыкание 25×3 раздувает края — рамка может быть
        // шире исходной полосы, но геометрия и порядок сохраняются.
        box.width shouldBeGreaterThan 149
        box.height shouldBeGreaterThan 19
        box.y shouldBeGreaterThan 24
    }

    @Test
    fun `segmenter keeps reader order and drops specks`() {
        val w = 300
        val h = 200
        val gray = IntArray(w * h) { 245 }
        // Две строки: нижняя идёт первой по порядку записи в массив,
        // но сортировка по (y, x) обязана вернуть верхнюю первой.
        for (y in 140 until 165) {
            for (x in 40 until 200) gray[y * w + x] = 30
        }
        for (y in 40 until 60) {
            for (x in 30 until 240) gray[y * w + x] = 30
        }
        // Точки-мусор (< пороговых размеров) — не строки.
        gray[180 * w + 10] = 0
        gray[10 * w + 290] = 0
        for (y in 95 until 99) {
            for (x in 98 until 103) gray[y * w + x] = 5
        }

        val boxes = OnnxRuPageSegmenter.findLineBoxes(gray, w, h)
        boxes.size shouldBe 2
        boxes[0].y shouldBe 40
        boxes[1].y shouldBe 140
    }

    @Test
    fun `letters of one line merge through the closing kernel`() {
        // Отдельные «буквы» (6×12) через каждые 8 px: без морфологии были бы
        // десятки компонент, после close(25×3) собираются в одну строку.
        val w = 260
        val h = 60
        val gray = IntArray(w * h) { 245 }
        for (bar in 0 until 25) {
            val bx = 8 * bar + 4
            for (yy in 24 until 36) {
                for (xx in bx until bx + 6) {
                    if (xx < w) gray[yy * w + xx] = 20
                }
            }
        }
        val boxes = OnnxRuPageSegmenter.findLineBoxes(gray, w, h)
        boxes.size shouldBe 1
    }

    @Test
    fun `empty and borderline images segment safely`() {
        OnnxRuPageSegmenter.findLineBoxes(IntArray(0), 0, 0) shouldBe emptyList()
        OnnxRuPageSegmenter.findLineBoxes(IntArray(100) { 200 }, 10, 10) shouldBe emptyList()
        OnnxRuPageSegmenter.findLineBoxes(IntArray(4) { 0 }, 2, 2) shouldBe emptyList()
        val uniform = IntArray(50 * 50) { 250 }
        OnnxRuPageSegmenter.findLineBoxes(uniform, 50, 50) shouldBe emptyList()
    }

    /** Индекс символа в алфавите модели. */
    private fun idx(c: Char): Int = OnnxRuOcrCore.CHARSET.indexOf(c)
}
