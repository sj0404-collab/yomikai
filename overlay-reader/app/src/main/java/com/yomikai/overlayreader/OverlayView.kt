package com.yomikai.overlayreader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Полноэкранный оверлей поверх любого приложения/сайта: затемнение вне рамки,
 * рамка выбора области, тулбар с кнопками и нижняя панель распознанного текста.
 *
 * Вся графика рисуется на Canvas (без внешних UI-библиотек), тач-обработка —
 * на MotionEvent. Кнопки срабатывают по ACTION_UP, рамка перетаскивается и
 * масштабируется за углы/грани.
 */
class OverlayView(
    context: Context,
    private val callback: Callback,
) : View(context) {

    interface Callback {
        fun onScan()
        fun onSpeak()
        fun onCycleVoice()
        fun onOpacity(delta: Float)
        fun onPassThrough()
        fun onResetFrame()
        fun onExit()
        fun onFrameChanged(f: RectFBean)
    }

    enum class Btn(val label: String) {
        SCAN("Скан"),
        SPEAK("Читать"),
        VOICE("Голос"),
        OPAQUE("Прозр"),
        HIDE("Скрыть"),
        EXIT("Выход"),
    }

    private enum class Mode { NONE, MOVE, H_RESIZE, V_RESIZE, CORNER_RESIZE, SCROLL }

    // Публичное состояние, управляется сервисом.
    var scanning: Boolean = false
    var statusText: String = ""
    var resultText: String = ""

    // «Прокачка» кадров на время захвата: оверлей постоянно перерисовывается,
    // поэтому дисплей (а с ним и VirtualDisplay) получает свежие кадры даже на
    // полностью статичном экране. Панели при этом не рисуются, чтобы кадр был
    // чистым.
    private var capturePumping = false
    private val pumpHandler = Handler(Looper.getMainLooper())
    private val pumpRunnable = object : Runnable {
        override fun run() {
            if (capturePumping) {
                invalidate()
                pumpHandler.postDelayed(this, 90)
            }
        }
    }

    fun beginCapturePump() {
        if (capturePumping) return
        capturePumping = true
        pumpHandler.post(pumpRunnable)
    }

    fun endCapturePump() {
        capturePumping = false
        pumpHandler.removeCallbacks(pumpRunnable)
        invalidate()
    }

    private lateinit var frame: RectF
    private var toolbarRect = RectF()
    private var panelRect = RectF()
    private val buttons = ArrayList<Pair<RectF, Btn>>()

    private var mode = Mode.NONE
    private var pressedBtn: Btn? = null
    private var touchX = 0f
    private var touchY = 0f
    private var startFrame = RectF()
    private var scrollLines = 0
    private var lastPanelY = 0f

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.parseColor("#4FC3F7")
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#4FC3F7")
    }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6121420")
    }
    private val toolbarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F0141A2E")
    }
    private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12f)
        typeface = Typeface.DEFAULT
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(14f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#90CAF9")
        textSize = sp(11f)
    }
    private val resultPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(13f)
        typeface = Typeface.SANS_SERIF
    }

    private val density: Float = resources.displayMetrics.density

    private fun dp(v: Float): Float = v * density

    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutMetrics(w.toFloat(), h.toFloat())
    }

    private var viewH = 0f
    private var viewW = 0f

    private fun layoutMetrics(w: Float, h: Float) {
        viewW = w
        viewH = h
        val toolbarH = dp(44f)
        val panelH = dp(132f)
        toolbarRect = RectF(0f, 0f, w, toolbarH)
        panelRect = RectF(0f, h - panelH, w, h)

        val pref = Prefs.frame()
        frame = RectF(
            pref.left * w,
            pref.top * h,
            pref.right * w,
            pref.bottom * h,
        )
        clampFrame()

        buttons.clear()
        val count = Btn.entries.size
        val bw = w / count
        for (i in Btn.entries.indices) {
            buttons += Pair(
                RectF(i * bw, 0f, (i + 1) * bw, toolbarH),
                Btn.entries[i],
            )
        }
    }

    private fun clampFrame() {
        val minSide = dp(48f)
        val left = max(frame.left, dp(6f))
        val top = max(frame.top, toolbarRect.bottom + dp(6f))
        val right = min(frame.right, viewW - dp(6f))
        val bottom = min(frame.bottom, panelRect.top - dp(6f))
        frame.set(left, top, max(right, left + minSide), max(bottom, top + minSide))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (capturePumping) return

        val alpha = (Prefs.overlayOpacity().coerceIn(0.1f, 0.9f) * 255).toInt()
        dimPaint.color = Color.argb(alpha, 4, 8, 20)

        // Затемнение вокруг рамки.
        canvas.drawRect(0f, 0f, viewW, toolbarRect.bottom, dimPaint)
        canvas.drawRect(0f, toolbarRect.bottom, frame.left, frame.top, dimPaint)
        canvas.drawRect(frame.right, toolbarRect.bottom, viewW, frame.bottom, dimPaint)
        canvas.drawRect(0f, frame.bottom, viewW, viewH, dimPaint)
        canvas.drawRect(frame.left, frame.bottom, frame.right, viewH, dimPaint)
        canvas.drawRect(0f, toolbarRect.bottom, frame.left, viewH, dimPaint)

        // Рамка захвата.
        canvas.drawRoundRect(frame, dp(10f), dp(10f), borderPaint)
        drawHandles(canvas)

        // Тулбар.
        canvas.drawRoundRect(toolbarRect, dp(10f), dp(10f), toolbarPaint)
        drawToolbarButtons(canvas)

        // Панель результата.
        canvas.drawRoundRect(panelRect, dp(10f), dp(10f), panelPaint)
        drawResultPanel(canvas)
    }

    private fun drawToolbarButtons(canvas: Canvas) {
        for ((rect, btn) in buttons) {
            btnPaint.color = if (btn == pressedBtn) Color.parseColor("#FF8A65") else Color.WHITE
            btnPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(btn.label, rect.centerX(), rect.centerY() + dp(4f), btnPaint)
        }
    }

    private fun drawHandles(canvas: Canvas) {
        val hs = dp(16f)
        val half = hs / 2
        for ((cx, cy) in handlePositions()) {
            canvas.drawRect(RectF(cx - half, cy - half, cx + half, cy + half), handlePaint)
        }
    }

    private fun handlePositions(): List<Pair<Float, Float>> {
        val midX = (frame.left + frame.right) / 2
        val midY = (frame.top + frame.bottom) / 2
        return listOf(
            frame.left to frame.top,
            midX to frame.top,
            frame.right to frame.top,
            frame.left to midY,
            frame.right to midY,
            frame.left to frame.bottom,
            midX to frame.bottom,
            frame.right to frame.bottom,
        )
    }

    private fun drawResultPanel(canvas: Canvas) {
        val pad = dp(10f)
        statusPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(
            if (scanning) "Распознаю текст…" else statusText.ifBlank { "Готово" },
            pad,
            panelRect.top + dp(18f),
            statusPaint,
        )
        val maxW = (panelRect.width() - pad * 2).toInt()
        val text = resultText
        if (text.isNotBlank()) {
            val lines = wrapText(resultPaint, text, maxW)
            val lineH = resultPaint.textSize * 1.25f
            val available = panelRect.height() - dp(34f)
            val visible = (available / lineH).toInt().coerceAtLeast(1)
            val maxScroll = max(0, lines.size - visible)
            scrollLines = scrollLines.coerceIn(0, maxScroll)
            var y = panelRect.top + dp(34f) + lineH
            for (i in scrollLines until min(scrollLines + visible, lines.size)) {
                canvas.drawText(lines[i], pad, y, resultPaint)
                y += lineH
            }
        }
    }

    private fun wrapText(paint: Paint, text: String, maxW: Int): List<String> {
        val words = text.split(" ")
        val lines = ArrayList<String>()
        var line = StringBuilder()
        for (word in words) {
            val probe = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(probe) <= maxW) {
                line = StringBuilder(probe)
            } else {
                if (line.isNotEmpty()) lines += line.toString()
                line = StringBuilder(word)
                while (line.isNotEmpty() && paint.measureText(line.toString()) > maxW) {
                    var cut = line.length
                    while (cut > 1 && paint.measureText(line.substring(0, cut)) > maxW) cut--
                    lines += line.substring(0, cut)
                    line = StringBuilder(line.substring(cut))
                }
            }
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return onDown(x, y)
            MotionEvent.ACTION_MOVE -> { onMove(x, y); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onUp()
                return true
            }
        }
        return true
    }

    private fun onDown(x: Float, y: Float): Boolean {
        touchX = x
        touchY = y
        startFrame.set(frame)
        lastPanelY = y

        for ((rect, btn) in buttons) {
            if (rect.contains(x, y)) {
                pressedBtn = btn
                invalidate()
                return true
            }
        }
        if (panelRect.contains(x, y) && resultText.isNotBlank()) {
            mode = Mode.SCROLL
            return true
        }
        val handle = findHandle(x, y)
        if (handle != null) {
            mode = Mode.CORNER_RESIZE
            handleIndex = handle
            return true
        }
        if (frame.contains(x, y)) {
            mode = Mode.MOVE
            return true
        }
        return true
    }

    private var handleIndex = -1

    private fun findHandle(x: Float, y: Float): Int? {
        val r = dp(20f)
        for ((i, pos) in handlePositions().withIndex()) {
            if (Math.abs(x - pos.first) <= r && Math.abs(y - pos.second) <= r) return i
        }
        return null
    }

    private fun onMove(x: Float, y: Float) {
        val dx = x - touchX
        val dy = y - touchY
        when (mode) {
            Mode.MOVE -> {
                val newLeft = startFrame.left + dx
                val newTop = startFrame.top + dy
                val newRight = startFrame.right + dx
                val newBottom = startFrame.bottom + dy
                val shiftX = newLeft.coerceAtLeast(dp(6f)) - newLeft
                val fixedLeft = newLeft + shiftX
                val fixedRight = newRight + shiftX
                frame.set(
                    fixedLeft,
                    newTop.coerceIn(toolbarRect.bottom + dp(6f), panelRect.top - frame.height() - dp(6f)),
                    fixedRight,
                    newTop.coerceIn(toolbarRect.bottom + dp(6f), panelRect.top - frame.height() - dp(6f)) + frame.height(),
                )
                invalidate()
            }
            Mode.CORNER_RESIZE -> {
                resizeTo(dx, dy)
                invalidate()
            }
            Mode.SCROLL -> {
                val lineH = resultPaint.textSize * 1.25f
                val delta = (y - lastPanelY) / lineH
                scrollLines += delta.toInt()
                lastPanelY = y
                invalidate()
            }
            else -> Unit
        }
        touchX = x
        touchY = y
    }

    private fun resizeTo(dx: Float, dy: Float) {
        val minSide = dp(48f)
        val l = frame.left
        val t = frame.top
        val r = frame.right
        val b = frame.bottom
        when (handleIndex) {
            0 -> { val nl = (startFrame.left + dx).coerceAtMost(r - minSide); frame.set(nl, (startFrame.top + dy).coerceAtMost(b - minSide), r, b) }
            1 -> frame.set(l, (startFrame.top + dy).coerceAtMost(b - minSide), r, b)
            2 -> { val nr = (startFrame.right + dx).coerceAtLeast(l + minSide); frame.set(l, (startFrame.top + dy).coerceAtMost(b - minSide), nr, b) }
            3 -> { val nl = (startFrame.left + dx).coerceAtMost(r - minSide); frame.set(nl, t, r, b) }
            4 -> { val nr = (startFrame.right + dx).coerceAtLeast(l + minSide); frame.set(l, t, nr, b) }
            5 -> { val nl = (startFrame.left + dx).coerceAtMost(r - minSide); val nb = (startFrame.bottom + dy).coerceAtLeast(t + minSide); frame.set(nl, t, r, nb) }
            6 -> frame.set(l, t, r, (startFrame.bottom + dy).coerceAtLeast(t + minSide))
            7 -> { val nb = (startFrame.bottom + dy).coerceAtLeast(t + minSide); frame.set(l, t, (startFrame.right + dx).coerceAtLeast(l + minSide), nb) }
        }
        clampFrame()
    }

    private fun onUp() {
        val btn = pressedBtn
        if (btn != null) {
            perform(btn)
        }
        pressedBtn = null
        mode = Mode.NONE
        callback.onFrameChanged(bean())
        invalidate()
    }

    private fun perform(btn: Btn) {
        when (btn) {
            Btn.SCAN -> callback.onScan()
            Btn.SPEAK -> callback.onSpeak()
            Btn.VOICE -> callback.onCycleVoice()
            Btn.OPAQUE -> callback.onOpacity(0.08f)
            Btn.HIDE -> callback.onPassThrough()
            Btn.EXIT -> callback.onExit()
        }
    }

    private fun bean(): RectFBean = RectFBean(
        left = (frame.left / viewW).coerceIn(0f, 1f),
        top = (frame.top / viewH).coerceIn(0f, 1f),
        right = (frame.right / viewW).coerceIn(0f, 1f),
        bottom = (frame.bottom / viewH).coerceIn(0f, 1f),
    )

    fun resetFrame() {
        frame.set(viewW * 0.06f, toolbarRect.bottom + dp(24f), viewW * 0.94f, viewW * 0.94f * 0.55f + toolbarRect.bottom)
        clampFrame()
        invalidate()
    }
}