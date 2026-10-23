package eu.kanade.tachiyomi.ui.koma

/**
 * Детектор панелей (кадров) страницы.
 *
 * Зачем: две панели могут стоять рядом, и надписи в них оказываются на почти
 * одинаковой высоте. Разбор только по облачкам в таком случае читает их
 * вперемешку: строка левой панели, строка правой, снова левой. По кадрам
 * каждая панель вычитывается целиком, прежде чем начаться следующая — как в
 * бумажной манге.
 *
 * Как: страница уменьшается, темнеет и разбивается на связные области. Кадр
 * манги — прямоугольник с обводкой, поэтому у области, которая легла на рамку,
 * тёмная почти вся граница своего бокса. Такие боксы и есть панели; вложенные
 * (подпись в рамке внутри кадра) отбрасываются — они внутри найденной панели.
 *
 * Порядок чтения панелей: строки сверху вниз, внутри строки справа налево.
 * Если рамок нет (вебтун, разворот без обводки, страница-кадр целиком),
 * возвращается пустой список — тогда работает прежний разбор по облачкам.
 * Пустой список честнее выдуманной панели.
 */
object KomaPanels {

    const val TARGET = 400          // длинная сторона уменьшенной копии
    private const val DARK = 120    // порог «тёмного» пикселя
    private const val MIN_AREA = 0.02f
    private const val MAX_AREA = 0.92f
    private const val MIN_W = 0.15f
    private const val MIN_H = 0.10f
    private const val BORDER = 0.62f // доля тёмных пикселей по границе бокса
    private const val ROW_TOL = 0.35f

    /** Панель в нормированных координатах страницы (0..1). */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
        fun distance(x: Float, y: Float): Float {
            val dx = maxOf(left - x, 0f, x - right)
            val dy = maxOf(top - y, 0f, y - bottom)
            return dx * dx + dy * dy
        }
    }

    /**
     * Панели в порядке чтения.
     *
     * @param pixels ARGB-пиксели изображения построчно, размер width × height.
     */
    fun detect(width: Int, height: Int, pixels: IntArray): List<Box> {
        if (width < 8 || height < 8 || pixels.size < width * height) return emptyList()
        val labels = label(width, height, pixels)
        val boxes = componentBoxes(labels, width, height, pixels)
        val panels = dropNested(boxes)
        if (panels.size < 2) return emptyList()
        return readingOrder(panels, width, height)
    }

    /** Двухпроходная разметка связных тёмных областей. -1 — светлый фон. */
    private fun label(width: Int, height: Int, pixels: IntArray): IntArray {
        val labels = IntArray(width * height) { -1 }
        val parent = ArrayList<Int>()
        fun find(start: Int): Int {
            var root = start
            while (parent[root] != root) root = parent[root]
            var node = start
            while (parent[node] != root) {
                val next = parent[node]
                parent[node] = root
                node = next
            }
            return root
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra == rb) return
            if (ra < rb) parent[rb] = ra else parent[ra] = rb
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                if (luma(pixels[i]) >= DARK) continue
                var label = if (x > 0) labels[i - 1] else -1
                if (y > 0) {
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= width) continue
                        val up = labels[(y - 1) * width + nx]
                        if (up < 0) continue
                        if (label < 0) label = up else union(label, up)
                    }
                }
                if (label < 0) {
                    label = parent.size
                    parent.add(label)
                }
                labels[i] = label
            }
        }
        for (i in labels.indices) {
            val value = labels[i]
            if (value >= 0) labels[i] = find(value)
        }
        return labels
    }

    private fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 77 + g * 151 + b * 28) shr 8
    }

    /** Боксы всех областей, похожих на кадр: размер в норме и тёмная граница. */
    private fun componentBoxes(labels: IntArray, width: Int, height: Int, pixels: IntArray): List<Box> {
        val lefts = HashMap<Int, Int>()
        val tops = HashMap<Int, Int>()
        val rights = HashMap<Int, Int>()
        val bottoms = HashMap<Int, Int>()
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val label = labels[row + x]
                if (label < 0) continue
                lefts[label] = minOf(lefts[label] ?: x, x)
                tops[label] = minOf(tops[label] ?: y, y)
                rights[label] = maxOf(rights[label] ?: x, x)
                bottoms[label] = maxOf(bottoms[label] ?: y, y)
            }
        }
        val out = mutableListOf<Box>()
        for ((label, left) in lefts) {
            val right = (rights[label] ?: continue) + 1
            val top = tops[label] ?: continue
            val bottom = (bottoms[label] ?: continue) + 1
            val boxW = (right - left).toFloat() / width
            val boxH = (bottom - top).toFloat() / height
            val area = boxW * boxH
            if (area < MIN_AREA || area > MAX_AREA) continue
            if (boxW < MIN_W || boxH < MIN_H) continue
            if (!framed(pixels, width, left, top, right, bottom)) continue
            out.add(Box(left.toFloat() / width, top.toFloat() / height,
                right.toFloat() / width, bottom.toFloat() / height))
        }
        return out
    }

    /** Тёмная ли граница бокса (в пикселях уменьшенной копии). */
    private fun framed(pixels: IntArray, width: Int, x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        val lastX = x2 - 1
        val lastY = y2 - 1
        if (lastX <= x1 || lastY <= y1) return false
        var dark = 0
        var total = 0
        for (x in x1..lastX) {
            if (luma(pixels[y1 * width + x]) < DARK) dark++
            if (luma(pixels[lastY * width + x]) < DARK) dark++
            total += 2
        }
        for (y in y1..lastY) {
            if (luma(pixels[y * width + x1]) < DARK) dark++
            if (luma(pixels[y * width + lastX]) < DARK) dark++
            total += 2
        }
        return total > 0 && dark.toFloat() / total >= BORDER
    }

    private fun dropNested(boxes: List<Box>): List<Box> {
        val sorted = boxes.sortedByDescending { it.width * it.height }
        val kept = mutableListOf<Box>()
        for (box in sorted) {
            val inside = kept.any { outer ->
                box.left >= outer.left - 0.002f && box.top >= outer.top - 0.002f &&
                    box.right <= outer.right + 0.002f && box.bottom <= outer.bottom + 0.002f
            }
            if (!inside) kept.add(box)
        }
        return kept
    }

    /** Строки сверху вниз, внутри строки — справа налево (манга). */
    private fun readingOrder(boxes: List<Box>, width: Int, height: Int): List<Box> {
        val rows = mutableListOf<MutableList<Box>>()
        for (box in boxes.sortedBy { it.top }) {
            val row = rows.firstOrNull { existing ->
                existing.any { other ->
                    minOf(box.bottom, other.bottom) - maxOf(box.top, other.top) >
                        ROW_TOL * minOf(box.height, other.height)
                }
            }
            if (row != null) row.add(box) else rows.add(mutableListOf(box))
        }
        val ordered = ArrayList<Box>(boxes.size)
        for (row in rows) {
            row.sortByDescending { it.left }
            ordered.addAll(row)
        }
        return ordered
    }

    /**
     * Раскладывает найденные куски текста по панелям.
     *
     * Куском командует та панель, в которой лежит его центр; кусок вне всех
     * панелей (надпись на обрезе кадра, подпись на поле страницы) уходит в
     * ближайшую — иначе такие реплики уезжали в конец страницы.
     *
     * @return по списку на каждую панель, панели — в порядке чтения.
     */
    fun assign(regions: List<OcrRegion>, panels: List<Box>): List<List<OcrRegion>> {
        if (panels.isEmpty()) return emptyList()
        val groups = List(panels.size) { mutableListOf<OcrRegion>() }
        for (region in regions) {
            val cx = (region.left + region.right) / 2f
            val cy = (region.top + region.bottom) / 2f
            var best = 0
            var bestDistance = Float.MAX_VALUE
            for ((index, panel) in panels.withIndex()) {
                if (panel.contains(cx, cy)) {
                    best = index
                    break
                }
                val distance = panel.distance(cx, cy)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = index
                }
            }
            groups[best].add(region)
        }
        return groups.filter { it.isNotEmpty() }
    }

    /** Доля кусков текста, попавших внутрь панелей: по ней решается доверять ли. */
    fun coverage(regions: List<OcrRegion>, panels: List<Box>): Float {
        if (regions.isEmpty() || panels.isEmpty()) return 0f
        var inside = 0
        for (region in regions) {
            val cx = (region.left + region.right) / 2f
            val cy = (region.top + region.bottom) / 2f
            if (panels.any { it.contains(cx, cy) }) inside++
        }
        return inside.toFloat() / regions.size
    }
}
