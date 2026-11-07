package mihon.data.ocr.vlm

/**
 * Постобработка карты вероятностей детектора PP-OCR (DB).
 *
 * На вход — карта `width × height` со значениями 0..1, на выход — рамки текстовых
 * блоков. Тоже чистая арифметика: проверяется юнит-тестами на нарисованных картах,
 * без модели и устройства.
 *
 * Пороги подобраны так, чтобы не терять мелкий кегль: пиксель считается текстом
 * при вероятности выше [threshold], компонент отбрасывается, если он меньше
 * [minPixels] пикселей или тоньше [minSide].
 */
internal object PpOcrDb {

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    fun boxes(
        probs: FloatArray,
        width: Int,
        height: Int,
        threshold: Float = 0.3f,
        minPixels: Int = 8,
        minSide: Int = 2,
    ): List<Box> {
        if (width <= 0 || height <= 0 || probs.size < width * height) return emptyList()
        val seen = BooleanArray(width * height)
        val out = ArrayList<Box>()
        val stack = IntArray(width * height)
        for (start in 0 until width * height) {
            if (seen[start] || probs[start] < threshold) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var minX = width; var minY = height; var maxX = -1; var maxY = -1; var count = 0
            while (sp > 0) {
                val idx = stack[--sp]
                val x = idx % width
                val y = idx / width
                count++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                // 8-связность: у наклонных надписей диагональные соседи — тот же блок
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                    val ni = ny * width + nx
                    if (!seen[ni] && probs[ni] >= threshold) { seen[ni] = true; stack[sp++] = ni }
                }
            }
            val b = Box(minX, minY, maxX + 1, maxY + 1)
            if (count >= minPixels && b.width >= minSide && b.height >= minSide) out.add(b)
        }
        return out
    }

    /**
     * Порядок чтения: сверху вниз полосами, внутри полосы слева направо.
     * Полоса — [rowTolerance] высот средней рамки, иначе облачка на одной строке
     * читаются вперемешку по вертикальному сдвигу в 1–2 пикселя.
     */
    fun readingOrder(boxes: List<Box>, rowTolerance: Float = 0.6f): List<Box> {
        if (boxes.isEmpty()) return emptyList()
        val avgH = boxes.sumOf { it.height }.toFloat() / boxes.size
        val tol = maxOf(1f, avgH * rowTolerance)
        // Полосы собираем последовательно: рамка попадает в текущую полосу, если
        // её верх не ниже начала полосы больше чем на tol. Целочисленное деление
        // (top / tol) для этого не годится — соседние рамки с верхом 5 и 6 px
        // уезжали в разные полосы, и правая читалась раньше левой.
        val bands = ArrayList<MutableList<Box>>()
        var bandTop = Float.NaN
        boxes.sortedBy { it.top }.forEach { b ->
            if (bands.isEmpty() || b.top - bandTop > tol) {
                bands.add(mutableListOf(b))
                bandTop = b.top.toFloat()
            } else {
                bands.last().add(b)
            }
        }
        return bands.flatMap { band -> band.sortedBy { it.left } }
    }
}
