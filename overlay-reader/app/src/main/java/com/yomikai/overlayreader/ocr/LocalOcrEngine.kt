package com.yomikai.overlayreader.ocr

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Самодельный оффлайн-распознаватель текста БЕЗ внешних моделей: без
 * Tesseract, без ML Kit, без TensorFlow. Алгоритм:
 *
 *  1. бинаризация Оцу (с автопереворотом полярности для светлого текста);
 *  2. связные компоненты → строки;
 *  3. сегментация строк на символы DP-кластеризацией компонент;
 *  4. классификация методом шаблонов ([GlyphTemplates]) по IoU;
 *  5. постобработка склейкой переносов + автофикс кириллицы.
 *
 * Точность уступает нейронным движкам, но это «локальный» путь для
 * авточтения: работает офлайн и с любым шрифтом крупного высококонтрастного
 * текста. Для сложных страниц по умолчанию используется онлайн-движок.
 */
class LocalOcrEngine : OcrEngine {

    override suspend fun recognizeText(image: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        run(image)
    }

    fun run(image: Bitmap): OcrResult {
        val w = image.width
        val h = image.height
        if (w < 8 || h < 8) return OcrResult.empty()

        val argb = IntArray(w * h)
        image.getPixels(argb, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        for (i in argb.indices) gray[i] = luminance(argb[i])

        val ink = binarize(gray, w, h)
        val comps = ConnectedComponents.label(w, h, ink)
        if (comps.isEmpty()) return OcrResult.empty()

        val heights = comps.map { it.height }.filter { it > 0 }.sorted()
        if (heights.isEmpty()) return OcrResult.empty()
        val medH = heights[heights.size / 2].toFloat()
        if (medH <= 3f) return OcrResult.empty()

        val filtered = comps.filter {
            it.height >= medH * 0.4f && it.height <= medH * 3.5f &&
                it.pixels.size >= max(3, (medH * medH * 0.15).toInt()) &&
                it.pixels.size <= (medH * medH * 40f).toInt()
        }
        if (filtered.isEmpty()) return OcrResult.empty()

        val lines = groupLines(filtered, medH)
        if (lines.isEmpty()) return OcrResult.empty()

        val regions = lines.mapNotNull { line ->
            val text = recognizeLine(w, h, ink, line, medH)
            if (text.isBlank()) {
                null
            } else {
                OcrRegion(
                    text = OcrTextCleaner.postprocess(text),
                    left = clamp01(line.minX.toFloat() / w),
                    top = clamp01(line.minY.toFloat() / h),
                    right = clamp01((line.maxX + 1).toFloat() / w),
                    bottom = clamp01((line.maxY + 1).toFloat() / h),
                )
            }
        }
        val fullText = regions.joinToString(" ") { it.text.trim() }.trim()
        return OcrResult(fullText, regions)
    }

    // ------------------------------------------------------------------ UI

    private class Line(val comps: MutableList<ConnectedComponents.Component>) {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = 0
        var maxY = 0

        fun add(c: ConnectedComponents.Component) {
            comps += c
            minX = min(minX, c.minX)
            minY = min(minY, c.minY)
            maxX = max(maxX, c.maxX)
            maxY = max(maxY, c.maxY)
        }
    }

    private class Cluster {
        val comps = ArrayList<ConnectedComponents.Component>()
        val cells = HashSet<Int>()
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = 0
        var maxY = 0

        val width: Int get() = maxX - minX + 1

        val height: Int get() = maxY - minY + 1

        fun add(c: ConnectedComponents.Component) {
            comps += c
            for (p in c.pixels) cells += p
            minX = min(minX, c.minX)
            minY = min(minY, c.minY)
            maxX = max(maxX, c.maxX)
            maxY = max(maxY, c.maxY)
        }

        fun merged(other: Cluster): Cluster {
            val out = Cluster()
            for (c in comps) out.add(c)
            for (c in other.comps) out.add(c)
            return out
        }

        fun mask(imageWidth: Int): FloatArray = GlyphTemplates.toCellMask(
            maxX - minX + 1,
            maxY - minY + 1,
        ) { dx, dy -> (minY + dy) * imageWidth + (minX + dx) in cells }

        /** Ширина самой широкой вертикальной «кишки» без чернила внутри bbox. */
        fun widestGutter(imageWidth: Int): Int {
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val colFill = IntArray(bw)
            for (y in 0 until bh) {
                for (x in 0 until bw) {
                    if ((minY + y) * imageWidth + (minX + x) in cells) colFill[x]++
                }
            }
            var best = 0
            var cur = 0
            for (x in 1 until bw - 1) {
                if (colFill[x] == 0) cur++ else cur = 0
                if (cur > best) best = cur
            }
            return best
        }
    }

    private fun groupLines(comps: List<ConnectedComponents.Component>, medH: Float): List<Line> {
        val sorted = comps.sortedBy { it.cy }
        val lines = ArrayList<Line>()
        for (c in sorted) {
            var placed = false
            for (line in lines.asReversed()) {
                val overlap = ConnectedComponents.overlapLength(line.minY, line.maxY, c.minY, c.maxY)
                if (overlap.toFloat() >= medH * 0.3f) {
                    line.add(c)
                    placed = true
                    break
                }
            }
            if (!placed) {
                lines += Line(mutableListOf()).also { it.add(c) }
            }
        }
        return lines
    }

    private fun recognizeLine(
        imageWidth: Int,
        imageHeight: Int,
        ink: BooleanArray,
        line: Line,
        medH: Float,
    ): String {
        val comps = line.comps.sortedWith(compareBy({ it.minX }, { it.minY }))
        if (comps.isEmpty()) return ""

        val clusters = ArrayList<Cluster>()
        for (c in comps) {
            if (clusters.isEmpty()) {
                clusters += Cluster().also { it.add(c) }
                continue
            }
            val prev = clusters.last()
            val xOverlap = ConnectedComponents.overlapLength(prev.minX, prev.maxX, c.minX, c.maxX)
            val vertGap = c.minY - prev.maxY
            if (xOverlap >= medH * 0.15f && vertGap < medH * 0.6f) {
                prev.add(c)
            } else {
                clusters += Cluster().also { it.add(c) }
            }
        }

        val widths = clusters.map { it.width }.filter { it > 0 }.sorted()
        val medW = if (widths.isEmpty()) medH else max(widths[widths.size / 2].toFloat(), 1f)

        // Очень широкий одиночный кластер (склеенные буквы) — пробуем разбить.
        val split = ArrayList<Cluster>()
        for (cl in clusters) {
            if (cl.width > medW * 1.9f) split += maybeSplit(cl, imageWidth, medW) else split += cl
        }

        val groups = segmentClusters(split, medW, imageWidth)
        if (groups.isEmpty()) return ""

        val sb = StringBuilder()
        var prevRight = Int.MIN_VALUE
        for (g in groups) {
            val (ch, score) = GlyphTemplates.classify(g.mask(imageWidth))
            if (prevRight != Int.MIN_VALUE) {
                val gap = g.minX - prevRight
                if (gap > medW * 0.8f && gap > medH * 0.4f) sb.append(' ')
            }
            sb.append(if (score >= GlyphTemplates.MIN_SCORE) ch else '?')
            prevRight = g.maxX
        }
        return sb.toString()
    }

    private fun maybeSplit(cluster: Cluster, imageWidth: Int, medW: Float): List<Cluster> {
        val gutter = cluster.widestGutter(imageWidth)
        if (gutter < medW * 0.3f) return listOf(cluster)
        val left = Cluster()
        val right = Cluster()
        val splitX = cluster.minX + cluster.width / 2
        for (c in cluster.comps) {
            val mid = (c.minX + c.maxX) / 2
            if (mid < splitX) left.add(c) else right.add(c)
        }
        if (left.comps.isEmpty() || right.comps.isEmpty()) return listOf(cluster)
        return if (left.width + right.width <= cluster.width) {
            listOf(left, right)
        } else {
            listOf(cluster)
        }
    }

    /** DP-сегментация кластеров в символы; максимум два соседних в один символ. */
    private fun segmentClusters(
        clusters: List<Cluster>,
        medW: Float,
        imageWidth: Int,
    ): List<Cluster> {
        val n = clusters.size
        if (n == 0) return emptyList()
        val inf = 1e9f
        val dp = FloatArray(n + 1) { inf }
        val prev = IntArray(n + 1) { -1 }
        dp[0] = 0f
        for (i in 0 until n) {
            if (dp[i] >= inf) continue
            for (len in 1..2) {
                val j = i + len - 1
                if (j >= n) break
                val cost = costFor(clusters, i, len, medW, imageWidth)
                if (cost >= inf) continue
                val cand = dp[i] + cost
                if (cand < dp[j + 1]) {
                    dp[j + 1] = cand
                    prev[j + 1] = i
                }
            }
        }
        if (dp[n] >= inf) {
            return clusters.map { cl ->
                Cluster().also { out -> cl.comps.forEach { out.add(it) } }
            }
        }
        val out = ArrayList<Cluster>()
        var pos = n
        while (pos > 0) {
            val start = prev[pos]
            if (start < 0) break
            val merged = Cluster()
            for (k in start until pos) clusters[k].comps.forEach { merged.add(it) }
            out.add(0, merged)
            pos = start
        }
        return out
    }

    private fun costFor(
        clusters: List<Cluster>,
        start: Int,
        len: Int,
        medW: Float,
        imageWidth: Int,
    ): Float {
        val grp = clusters.slice(start until start + len)
        val bboxW = grp.maxOf { it.maxX } - grp.minOf { it.minX } + 1
        if (len == 2) {
            val a = clusters[start]
            val b = clusters[start + 1]
            val gap = b.minX - a.maxX
            if (gap > medW * 0.6f) return 1e9f
        }
        if (bboxW > medW * 1.6f) return 1e9f
        val merged = Cluster()
        grp.forEach { cl -> cl.comps.forEach { merged.add(it) } }
        val (_, score) = GlyphTemplates.classify(merged.mask(imageWidth))
        return if (score >= GlyphTemplates.MIN_SCORE) -ln(score.toDouble()).toFloat() else 2.5f
    }

    companion object {
        private fun luminance(argb: Int): Int {
            val r = Color.red(argb)
            val g = Color.green(argb)
            val b = Color.blue(argb)
            return (299 * r + 587 * g + 114 * b) / 1000
        }

        private fun binarize(gray: IntArray, w: Int, h: Int): BooleanArray {
            val threshold = otsu(gray)
            var dark = 0
            for (intensity in gray) if (intensity < threshold) dark++
            val invert = dark.toFloat() > gray.size * 0.55f
            val ink = BooleanArray(gray.size)
            for (i in gray.indices) {
                val isDark = gray[i] < threshold
                ink[i] = if (invert) !isDark else isDark
            }
            return ink
        }

        private fun otsu(gray: IntArray): Int {
            val hist = IntArray(256)
            for (v in gray) hist[v.coerceIn(0, 255)]++
            val total = gray.size
            var sum = 0L
            for (i in 0 until 256) sum += i.toLong() * hist[i]
            var sumB = 0L
            var wB = 0L
            var maxVar = -1.0
            var best = 127
            for (t in 0 until 256) {
                wB += hist[t]
                if (wB == 0L) continue
                val wF = total - wB
                if (wF == 0L) break
                sumB += t.toLong() * hist[t]
                val mB = sumB.toDouble() / wB
                val mF = (sum - sumB).toDouble() / wF
                val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
                if (between > maxVar) {
                    maxVar = between
                    best = t
                }
            }
            return best
        }

        private fun clamp01(v: Float): Float = v.coerceIn(0f, 1f)
    }
}