package com.yomikai.overlayreader.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface

/**
 * Библиотека шаблонов глифов для самодельного оффлайн-распознавателя.
 *
 * Шаблоны рендерятся в рантайме из системного шрифта (без внешних моделей,
 * без Tesseract и без ML Kit): каждая буква/цифра форматируется в бинарную
 * маску и нормализуется в ячейку [CELL_W]x[CELL_H]. Классификатор сравнивает
 * распознаваемый фрагмент с шаблоном через IoU масок.
 */
object GlyphTemplates {

    const val CELL_W = 22
    const val CELL_H = 28

    const val MIN_SCORE = 0.30f

    data class Template(val char: Char, val mask: FloatArray, val fg: Int)

    private var cache: List<Template>? = null

    fun all(): List<Template> = cache ?: build().also { cache = it }

    private const val CHARSET =
        "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ" +
            "абвгдеёжзийклмнопрстуфхцчшщъыьэюя" +
            "0123456789" +
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ" +
            "abcdefghijklmnopqrstuvwxyz" +
            ".,!?;:'\"()«»—…-"

    private fun build(): List<Template> {
        val list = ArrayList<Template>()
        val styles = listOf(
            Typeface.create(Typeface.DEFAULT, Typeface.NORMAL),
            Typeface.create(Typeface.DEFAULT, Typeface.BOLD),
            Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL),
        )
        for (typeface in styles) {
            for (sizePx in intArrayOf(44, 64)) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                    this.typeface = typeface
                    textSize = sizePx.toFloat()
                    color = Color.BLACK
                }
                for (char in CHARSET) {
                    val mask = renderToMask(char, paint) ?: continue
                    val fg = mask.count { it > 0.35f }
                    if (fg < 2) continue
                    list += Template(char, mask, fg)
                }
            }
        }
        return list
    }

    private fun renderToMask(char: Char, paint: Paint): FloatArray? {
        val bounds = Rect()
        paint.getTextBounds(char.toString(), 0, 1, bounds)
        val pad = 6
        val w = bounds.width() + pad * 2
        val h = bounds.height() + pad * 2
        if (w <= 2 || h <= 2) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bmp)
            canvas.drawText(
                char.toString(),
                (-bounds.left + pad).toFloat(),
                (-bounds.top + pad).toFloat(),
                paint,
            )
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            toCellMask(w, h) { x, y -> Color.alpha(px[y * w + x]) > 130 }
        } finally {
            bmp.recycle()
        }
    }

    /**
     * Нормализация бинарной маски в ячейку [CELL_W]x[CELL_H] с сохранением
     * пропорций: обрезка по bbox чернила, масштаб, центрирование.
     */
    fun toCellMask(width: Int, height: Int, inkAt: (Int, Int) -> Boolean): FloatArray {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (inkAt(x, y)) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return FloatArray(CELL_W * CELL_H)

        val bw = maxX - minX + 1
        val bh = maxY - minY + 1
        val availW = (CELL_W - 2).coerceAtLeast(1)
        val availH = (CELL_H - 2).coerceAtLeast(1)
        val scale = minOf(availW.toFloat() / bw, availH.toFloat() / bh)
        val tw = (bw * scale).coerceAtLeast(0.5f)
        val th = (bh * scale).coerceAtLeast(0.5f)
        val offX = ((CELL_W - tw) / 2f).toInt()
        val offY = ((CELL_H - th) / 2f).toInt()

        val mask = FloatArray(CELL_W * CELL_H)
        for (y in 0 until height) {
            if (y < minY || y > maxY) continue
            val sy = ((y - minY) * scale).toInt()
            if (sy >= th) continue
            val cy = offY + sy
            if (cy !in 0 until CELL_H) continue
            for (x in 0 until width) {
                if (x < minX || x > maxX) continue
                if (!inkAt(x, y)) continue
                val sx = ((x - minX) * scale).toInt()
                if (sx >= tw) continue
                val cx = offX + sx
                if (cx in 0 until CELL_W) mask[cy * CELL_W + cx] = 1f
            }
        }
        return mask
    }

    /** Максимум IoU по всем шаблонам: возвращает символ и его уверенность. */
    fun classify(mask: FloatArray): Pair<Char, Float> {
        var bestChar = '?'
        var bestScore = 0f
        for (t in all()) {
            var inter = 0f
            var union = 0f
            for (i in mask.indices) {
                val a = mask[i]
                val b = t.mask[i]
                if (a > b) {
                    union += a
                    inter += b
                } else {
                    union += b
                    inter += a
                }
            }
            if (union > 0f) {
                val score = inter / union
                if (score > bestScore) {
                    bestScore = score
                    bestChar = t.char
                }
            }
        }
        return bestChar to bestScore
    }
}