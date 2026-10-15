package mihon.data.ocr

import kotlin.math.exp

/**
 * JVM-ядро локального ONNX OCR-движка (CRNN+CTC, кириллица): алфавит,
 * жадное декодирование CTC, пост-обработка и сегментатор страницы.
 *
 * Всё это чистый Kotlin без Android, чтобы покрываться юнит-тестами; обвязка
 * с `Bitmap` и `OrtSession` живёт в [OnnxRuOcrEngine]. Порядок операций
 * дословно повторяет эталонный Python-пайплайн из `~/yomikai-ocr`
 * (`generate.normalize_line` → CRNN → `infer.read_line` → `postprocess`,
 * страница — `segment.find_text_lines`).
 */

/** Прямоугольник строки на странице (px, в координатах исходного битмапа). */
internal data class OnnxRegionBox(val x: Int, val y: Int, val width: Int, val height: Int)

internal object OnnxRuOcrCore {

    /** Алфавит модели: кириллица RU+UK и пунктуация/цифры. Длина 109. */
    const val CHARSET: String =
        "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ" +
        "абвгдеёжзийклмнопрстуфхцчшщъыьэюя" +
        "ЇїІіЄєҐґ" +
        " .,!?;:\"'«»„“”()[]—–-…№*/0123456789"

    /** Классов в выходе модели: charset + blank + <unk> == 111. */
    const val NUM_CLASSES: Int = 111

    /** Индекс CTC-blank. */
    const val BLANK: Int = 109

    /** Индекс <unk>. */
    const val UNK: Int = 110

    /** Высокая нормализация строки под CRNN. */
    const val LINE_HEIGHT: Int = 32

    /** Ширина строки после ресайза: от 8 до [LINE_HEIGHT]*14=448 px. */
    const val LINE_MAX_ASPECT: Int = 14

    /**
     * Декодирует поток id (greedy argmax) в текст: склеивает повторы,
     * выбрасывает blank/unk. Идентично `lexicon.decode` в Python-части.
     */
    fun decode(ids: IntArray): String {
        val out = StringBuilder(ids.size)
        var prev = -2
        for (id in ids) {
            when {
                id == BLANK || id == UNK -> prev = id
                id != prev && id >= 0 && id < CHARSET.length -> {
                    out.append(CHARSET[id])
                    prev = id
                }
                else -> prev = id
            }
        }
        return out.toString()
    }

    /**
     * Жадное argmax-декодирование логитов (T*K, T кадров по [NUM_CLASSES])
     * с «уверенностью» — медианой softmax-максимумов по не-blank кадрам,
     * в точности как `infer.read_line_conf`.
     */
    fun greedyDecode(logits: FloatArray, frames: Int): Pair<String, Float> {
        if (frames <= 0 || logits.size < frames * NUM_CLASSES) return "" to 0f
        val ids = IntArray(frames)
        val frameProb = FloatArray(frames)
        for (t in 0 until frames) {
            val base = t * NUM_CLASSES
            var bestId = 0
            var bestLogit = Float.NEGATIVE_INFINITY
            for (k in 0 until NUM_CLASSES) {
                val v = logits[base + k]
                if (v > bestLogit) {
                    bestLogit = v
                    bestId = k
                }
            }
            ids[t] = bestId
            // softmax p(argmax) с численной стабилизацией на max
            var sum = 0f
            for (k in 0 until NUM_CLASSES) {
                sum += exp((logits[base + k] - bestLogit).toDouble()).toFloat()
            }
            frameProb[t] = 1f / sum
        }
        val text = decode(ids)
        // kept-вероятности — только по кадрам, реально породившим символы.
        val kept = ArrayList<Float>(frames)
        var prev = -2
        for (i in 0 until frames) {
            val id = ids[i]
            if (id == prev || id == BLANK || id == UNK) {
                prev = id
                continue
            }
            kept.add(frameProb[i])
            prev = id
        }
        val conf = if (kept.isEmpty()) 0f else kept.sorted()[kept.size / 2]
        return text to conf
    }

    /**
     * Пост-обработка строки: схлопнуть CTC-повторы «………»→«…»,
     * нормализовать запятые и пробелы. Без словарной коррекции
     * (словарная правка слов в приложении делается позже [OcrTextCleaner]).
     */
    fun postprocess(raw: String): String {
        var t = raw
        t = t.replace(Regex("…{2,}"), "…")
        t = t.replace(Regex("\\s*,\\s*"), ", ")
        t = t.replace(Regex("\\s+"), " ")
        return t.trim()
    }
}

/**
 * Сегментатор страницы: бинаризация (порог 170, инверсия в «чернила»),
 * морфологическое замыкание прямоугольным ядром 25×3 в 2 итерации и
 * маркировка 8-связных компонент. Строки сортируются сверху-вниз,
 * слева-направо, читательский порядок.
 *
 * Порт `segment.find_text_lines` с той же теорией: замыкание соединяет
 * глифы одной строки в один блок при высоте ядра 3 и ширине 25; компоненты
 * с высотой <10 и шириной <12 отбрасываются как мусор.
 */
internal object OnnxRuPageSegmenter {

    /** Максимальная сторона изображения для локальной сегментации (экономия памяти). */
    const val SEGMENT_MAX_DIM: Int = 1400
    const val INK_THRESHOLD: Int = 170
    const val MIN_LINE_HEIGHT: Int = 10
    const val MIN_LINE_WIDTH: Int = 12
    const val CLOSE_W: Int = 25
    const val CLOSE_H: Int = 3
    const val CLOSE_ITERATIONS: Int = 2

    private class Box(val x: Int, val y: Int, val w: Int, val h: Int)

    /**
     * Найти строки на полутоновой картинке (яркости пикселей 0..255,
     * row-major, длина == w*h). Возвращает рамки в тех же координатах.
     */
    fun findLineBoxes(gray: IntArray, w: Int, h: Int): List<OnnxRegionBox> {
        if (w <= 0 || h <= 0 || gray.size < w * h) return emptyList()
        // Бинаризация: чернила = тёмное (menьше порога)
        var mask = ByteArray(w * h)
        for (i in 0 until w * h) {
            mask[i] = if (gray[i] < INK_THRESHOLD) 1.toByte() else 0
        }
        // Морфологическое замыкание rect(25,3) x2. Реализуем раскладкой на
        // горизонтальное/вертикальное сканы (separable kernel): close =
        // dilate X (25), dilate Y (3), erode X (25), erode Y (3).
        repeat(CLOSE_ITERATIONS) {
            mask = morphClose(mask, w, h)
        }
        // Компоненты 8-связности, bbox каждой
        val boxes = labelComponents(mask, w, h)
        return boxes
            .filter { it.h >= MIN_LINE_HEIGHT && it.w >= MIN_LINE_WIDTH }
            .sortedWith(compareBy({ it.y }, { it.x }))
            .map { OnnxRegionBox(it.x, it.y, it.w, it.h) }
    }

    /** close = erode(dilate(m)). dilate/erode через separable max/min сканы. */
    private fun morphClose(src: ByteArray, w: Int, h: Int): ByteArray {
        val tmp1 = ByteArray(w * h)
        val tmp2 = ByteArray(w * h)
        val tmp3 = ByteArray(w * h)
        val tmp4 = ByteArray(w * h)
        // dilate: max по X (радиус 25/2=12, ширина 25)
        horizontalMax(src, tmp1, w, h, CLOSE_W)
        // dilate: max по Y (радиус 1, высота 3)
        verticalMax(tmp1, tmp2, w, h, CLOSE_H)
        // erode: min по X
        horizontalMin(tmp2, tmp3, w, h, CLOSE_W)
        // erode: min по Y
        verticalMin(tmp3, tmp4, w, h, CLOSE_H)
        return tmp4
    }

    /** Скользящий максимум по строкам: ширина окна [kw] (нечётная), центр в текущей точке. */
    private fun horizontalMax(src: ByteArray, dst: ByteArray, w: Int, h: Int, kw: Int) {
        val r = kw / 2
        val deque = IntArray(kw + 1) // монотонный дек индексов (макс окно 2r+1, +1 на момент вставки)
        for (y in 0 until h) {
            val row = y * w
            var head = 0
            var tail = 0
            var last = -1 // последний добавленный индекс
            for (x in 0 until w) {
                // поддерживаем окно [x-r, x+r]; семантика краёв — повтор граничного пикселя
                val right = minOf(x + r, w - 1)
                while (last < right) {
                    last++
                    val v = src[row + last]
                    while (tail > head && src[row + deque[tail - 1]] <= v) tail--
                    deque[tail++] = last
                }
                while (head < tail && deque[head] < x - r) head++
                dst[row + x] = src[row + deque[head]]
            }
        }
    }

    private fun horizontalMin(src: ByteArray, dst: ByteArray, w: Int, h: Int, kw: Int) {
        val r = kw / 2
        val deque = IntArray(kw + 1)
        for (y in 0 until h) {
            val row = y * w
            var head = 0
            var tail = 0
            var last = -1
            for (x in 0 until w) {
                val right = minOf(x + r, w - 1)
                while (last < right) {
                    last++
                    val v = src[row + last]
                    while (tail > head && src[row + deque[tail - 1]] >= v) tail--
                    deque[tail++] = last
                }
                while (head < tail && deque[head] < x - r) head++
                dst[row + x] = src[row + deque[head]]
            }
        }
    }

    private fun verticalMax(src: ByteArray, dst: ByteArray, w: Int, h: Int, kh: Int) {
        val r = kh / 2
        val deque = IntArray(kh + 1)
        for (x in 0 until w) {
            var head = 0
            var tail = 0
            var last = -1
            for (y in 0 until h) {
                val bot = minOf(y + r, h - 1)
                while (last < bot) {
                    last++
                    val v = src[last * w + x]
                    while (tail > head && src[deque[tail - 1] * w + x] <= v) tail--
                    deque[tail++] = last
                }
                while (head < tail && deque[head] < y - r) head++
                dst[y * w + x] = src[deque[head] * w + x]
            }
        }
    }

    private fun verticalMin(src: ByteArray, dst: ByteArray, w: Int, h: Int, kh: Int) {
        val r = kh / 2
        val deque = IntArray(kh + 1)
        for (x in 0 until w) {
            var head = 0
            var tail = 0
            var last = -1
            for (y in 0 until h) {
                val bot = minOf(y + r, h - 1)
                while (last < bot) {
                    last++
                    val v = src[last * w + x]
                    while (tail > head && src[deque[tail - 1] * w + x] >= v) tail--
                    deque[tail++] = last
                }
                while (head < tail && deque[head] < y - r) head++
                dst[y * w + x] = src[deque[head] * w + x]
            }
        }
    }
    /** Двухпроходная маркировка 8-связных компонент (union-find). */
    private fun labelComponents(src: ByteArray, w: Int, h: Int): List<Box> {
        val n = w * h
        val labels = IntArray(n)
        val parent = ArrayList<Int>()
        val rank = ArrayList<Int>()

        fun newLabel(): Int {
            parent.add(parent.size)
            rank.add(0)
            return parent.size - 1
        }

        fun find(a: Int): Int {
            var x = a
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }

        fun union(a: Int, b: Int) {
            var ra = find(a)
            var rb = find(b)
            if (ra == rb) return
            if (rank[ra] < rank[rb]) {
                val t = ra; ra = rb; rb = t
            }
            parent[rb] = ra
            if (rank[ra] == rank[rb]) rank[ra]++
        }

        // Первый проход: назначаем метки, смотрим соседей слева/сверху.
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (src[i].toInt() == 0) continue
                var m = Int.MAX_VALUE
                if (x > 0 && src[i - 1].toInt() != 0) m = minOf(m, find(labels[i - 1]))
                if (y > 0) {
                    if (src[i - w].toInt() != 0) m = minOf(m, find(labels[i - w]))
                    if (x > 0 && src[i - w - 1].toInt() != 0) m = minOf(m, find(labels[i - w - 1]))
                    if (x < w - 1 && src[i - w + 1].toInt() != 0) m = minOf(m, find(labels[i - w + 1]))
                }
                if (m == Int.MAX_VALUE) {
                    labels[i] = newLabel()
                } else {
                    labels[i] = m
                    if (x > 0 && src[i - 1].toInt() != 0) union(labels[i - 1], m)
                    if (y > 0) {
                        if (src[i - w].toInt() != 0) union(labels[i - w], m)
                        if (x > 0 && src[i - w - 1].toInt() != 0) union(labels[i - w - 1], m)
                        if (x < w - 1 && src[i - w + 1].toInt() != 0) union(labels[i - w + 1], m)
                    }
                }
            }
        }
        // Второй проход: сводим к корню и копим bbox.
        val minX = HashMap<Int, Int>()
        val maxX = HashMap<Int, Int>()
        val minY = HashMap<Int, Int>()
        val maxY = HashMap<Int, Int>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (src[i].toInt() == 0) continue
                val root = find(labels[i])
                minX[root] = minOf(minX[root] ?: Int.MAX_VALUE, x)
                maxX[root] = maxOf(maxX[root] ?: Int.MIN_VALUE, x)
                minY[root] = minOf(minY[root] ?: Int.MAX_VALUE, y)
                maxY[root] = maxOf(maxY[root] ?: Int.MIN_VALUE, y)
            }
        }
        return minX.keys.map { root ->
            val x0 = minX[root]!!
            val y0 = minY[root]!!
            Box(x0, y0, (maxX[root]!! - x0 + 1), (maxY[root]!! - y0 + 1))
        }
    }
}
