package com.yomikai.overlayreader.ocr

object ConnectedComponents {

    class Component(
        val pixels: IntArray,
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
    ) {
        val width: Int get() = maxX - minX + 1
        val height: Int get() = maxY - minY + 1
        val cx: Float get() = (minX + maxX + 1) / 2f
        val cy: Float get() = (minY + maxY + 1) / 2f
    }

    fun label(width: Int, height: Int, ink: BooleanArray): List<Component> {
        val visited = BooleanArray(width * height)
        val stack = IntArray(width * height)
        val out = ArrayList<Component>()

        for (start in ink.indices) {
            if (!ink[start] || visited[start]) continue
            var sp = 0
            stack[sp++] = start
            visited[start] = true
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = 0
            var maxY = 0
            val pixels = ArrayList<Int>(64)
            while (sp > 0) {
                val idx = stack[--sp]
                pixels += idx
                val x = idx % width
                val y = idx / width
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0 && !visited[idx - 1] && ink[idx - 1]) {
                    stack[sp++] = idx - 1; visited[idx - 1] = true
                }
                if (x < width - 1 && !visited[idx + 1] && ink[idx + 1]) {
                    stack[sp++] = idx + 1; visited[idx + 1] = true
                }
                if (y > 0 && !visited[idx - width] && ink[idx - width]) {
                    stack[sp++] = idx - width; visited[idx - width] = true
                }
                if (y < height - 1 && !visited[idx + width] && ink[idx + width]) {
                    stack[sp++] = idx + width; visited[idx + width] = true
                }
            }
            if (pixels.size >= 2) {
                out += Component(pixels.toIntArray(), minX, minY, maxX, maxY)
            }
        }
        return out
    }

    fun overlapLength(a1: Int, a2: Int, b1: Int, b2: Int): Int {
        val lo = maxOf(a1, b1)
        val hi = minOf(a2, b2)
        return maxOf(0, hi - lo)
    }
}