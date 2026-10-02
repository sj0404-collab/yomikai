package com.yomikai.overlayreader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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

    /**
     * Распознанный текст. Панель результата держится высотой в одну строку статуса,
     * пока текста нет, и разворачивается на полную высоту вместе с текстом — при
     * запуске оверлей не занимает низ экрана впустую.
     */
    var resultText: String = ""
        set(value) {
            if (field == value) return
            field = value
            scrollLines = 0
            scrollAccum = 0f
            layoutPanel()
            clampFrame()
            invalidate()
        }

    // «Прокачка» кадров на время захвата: оверлей постоянно перерисовывается,
    // поэтому дисплей (а с ним и VirtualDisplay) получает свежие кадры даже на
    // полностью статичном экране. Сама графика при этом не рисуется вовсе —
    // иначе в кадр попали бы рамка и ручки, лежащие на границе области захвата.
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
    private var scrollAccum = 0f
    private var lastPanelY = 0f
    private var contentLines: List<String> = emptyList()
    private var visibleLines = 1

    private val path = Path()

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
        // Высоты панелей ограничены долей экрана: на плотных экранах (density 3+)
        // фиксированные dp съедали бы заметную часть дисплея.
        val toolbarH = min(dp(44f), h * 0.07f)
        toolbarRect = RectF(0f, 0f, w, toolbarH)
        layoutPanel()

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

    /** Нижняя панель: строка статуса без результата, полная высота с текстом. */
    private fun layoutPanel() {
        if (viewH <= 0f) return
        val collapsed = min(statusPaint.textSize * 2.2f, viewH * 0.09f)
        val expanded = min(dp(132f), viewH * 0.30f)
        val h = if (resultText.isBlank()) collapsed else max(collapsed, expanded)
        panelRect = RectF(0f, viewH - h, viewW, viewH)
    }

    private fun clampFrame() {
        if (viewW <= 0f || viewH <= 0f) return
        val padL = min(dp(6f), viewW * 0.02f)
        val padT = min(dp(6f), viewH * 0.01f)
        val boxTop = toolbarRect.bottom + padT
        val boxBottom = panelRect.top - padT
        val boxRight = viewW - padL
        // Минимальная сторона не должна превышать половину доступной высоты,
        // иначе на низких экранах рамка вылезала бы под панели.
        val minSide = max(dp(20f), min(dp(48f), (boxBottom - boxTop) * 0.5f))

        val left = frame.left.coerceIn(padL, max(padL, boxRight - minSide))
        val top = frame.top.coerceIn(boxTop, max(boxTop, boxBottom - minSide))
        val right = frame.right.coerceIn(left + minSide, max(left + minSide, boxRight))
        val bottom = frame.bottom.coerceIn(top + minSide, max(top + minSide, boxBottom))
        frame.set(left, top, right, bottom)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Пока идёт захват, не рисуем НИЧЕГО. Задача «прокачки» — лишь
        // заставить компоновщик выдавать кадры, поэтому ранний выход не мешает
        // самой прокачке. А вот область захвата совпадает с рамкой, значит в
        // кадр попадали бы и обводка рамки, и восемь светлых ручек (они
        // центрированы на границе и уходят внутрь на половину) — OCR читал бы
        // эти квадраты как текст.
        if (capturePumping) return

        val alpha = (Prefs.overlayOpacity().coerceIn(0.1f, 0.9f) * 255).toInt()
        dimPaint.color = Color.argb(alpha, 4, 8, 20)

        // Затемнение вокруг рамки. Четыре прямоугольника идут строго встык, иначе
        // наложение давало бы участки, затемнённые вдвое.
        canvas.drawRect(0f, 0f, viewW, toolbarRect.bottom, dimPaint)
        canvas.drawRect(0f, toolbarRect.bottom, frame.left, frame.top, dimPaint)
        canvas.drawRect(frame.right, toolbarRect.bottom, viewW, frame.top, dimPaint)
        canvas.drawRect(0f, frame.top, frame.left, frame.bottom, dimPaint)
        canvas.drawRect(frame.right, frame.top, viewW, frame.bottom, dimPaint)
        canvas.drawRect(0f, frame.bottom, viewW, viewH, dimPaint)

        // Рамка захвата.
        canvas.drawRoundRect(frame, dp(10f), dp(10f), borderPaint)
        drawHandles(canvas)

        // Тулбар и панель результата.
        roundRectPath(toolbarRect, 0f, 0f, dp(10f), dp(10f))
        canvas.drawPath(path, toolbarPaint)
        drawToolbarButtons(canvas)

        roundRectPath(panelRect, dp(10f), dp(10f), 0f, 0f)
        canvas.drawPath(path, panelPaint)
        drawResultPanel(canvas)
    }

    /** Прямоугольник с независимым радиусом каждого угла (в dp-радиусах — px). */
    private fun roundRectPath(rect: RectF, tl: Float, tr: Float, br: Float, bl: Float) {
        path.reset()
        path.moveTo(rect.left + tl, rect.top)
        path.lineTo(rect.right - tr, rect.top)
        path.quadTo(rect.right, rect.top, rect.right, rect.top + tr)
        path.lineTo(rect.right, rect.bottom - br)
        path.quadTo(rect.right, rect.bottom, rect.right - br, rect.bottom)
        path.lineTo(rect.left + bl, rect.bottom)
        path.quadTo(rect.left, rect.bottom, rect.left, rect.bottom - bl)
        path.lineTo(rect.left, rect.top + tl)
        path.quadTo(rect.left, rect.top, rect.left + tl, rect.top)
        path.close()
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
        val status = if (scanning) "Распознаю текст…" else statusText.ifBlank { "Готово" }
        val textMetrics = statusPaint.fontMetrics
        val statusBaseline = if (resultText.isBlank()) {
            // Свёрнутая панель — одна строка по центру.
            panelRect.centerY() - (textMetrics.ascent + textMetrics.descent) / 2f
        } else {
            panelRect.top + dp(20f)
        }
        canvas.drawText(status, pad, statusBaseline, statusPaint)

        contentLines = emptyList()
        visibleLines = 0
        val text = resultText
        if (text.isNotBlank()) {
            val maxW = (panelRect.width() - pad * 2).toInt()
            val lines = wrapText(resultPaint, text, maxW)
            val lineH = resultPaint.textSize * 1.25f
            val available = panelRect.height() - dp(34f)
            val visible = (available / lineH).toInt().coerceAtLeast(1)
            contentLines = lines
            visibleLines = visible
            scrollLines = scrollLines.coerceIn(0, max(0, lines.size - visible))
            var y = panelRect.top + dp(34f) + lineH
            for (i in scrollLines until min(scrollLines + visible, lines.size)) {
                canvas.drawText(lines[i], pad, y, resultPaint)
                y += lineH
            }
        }
    }

    private fun maxScroll(): Int = max(0, contentLines.size - visibleLines)

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
            MotionEvent.ACTION_UP -> {
                onUp()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                // Отмена (перехват другим окном, палец вне области) — это не
                // нажатие: кнопку жать нельзя, рамку не надо сохранять.
                // Раньше CANCEL шёл тем же путём, что UP, и включал скан.
                cancelTouch()
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
                val fw = startFrame.width()
                val fh = startFrame.height()
                val pad = dp(6f)
                // Сдвиг ограничиваем по обеим осям сразу: иначе рамка не доезжала
                // до левого/верхнего края, зато уезжала за правый/нижний.
                val maxLeft = viewW - pad - fw
                val newLeft = (startFrame.left + dx).coerceIn(min(pad, maxLeft), max(pad, maxLeft))
                val minTop = toolbarRect.bottom + pad
                val maxTop = panelRect.top - pad - fh
                val newTop = (startFrame.top + dy).coerceIn(min(minTop, maxTop), max(minTop, maxTop))
                frame.set(newLeft, newTop, newLeft + fw, newTop + fh)
                clampFrame()
                invalidate()
            }
            Mode.CORNER_RESIZE -> {
                resizeTo(dx, dy)
                invalidate()
            }
            Mode.SCROLL -> {
                val lineH = resultPaint.textSize * 1.25f
                // Дробный остаток копится: иначе медленное перетаскивание
                // (меньше строки за кадр) не листало текст вовсе.
                scrollAccum += (y - lastPanelY) / lineH
                val whole = scrollAccum.toInt()
                if (whole != 0) {
                    scrollAccum -= whole
                    val next = (scrollLines + whole).coerceIn(0, maxScroll())
                    if (next == scrollLines) scrollAccum = 0f
                    scrollLines = next
                }
                lastPanelY = y
                invalidate()
            }
            else -> Unit
        }
        touchX = x
        touchY = y
    }

    private fun resizeTo(dx: Float, dy: Float) {
        val minSide = min(dp(48f), max(dp(20f), (panelRect.top - toolbarRect.bottom) * 0.5f))
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

    private fun cancelTouch() {
        pressedBtn = null
        mode = Mode.NONE
        invalidate()
    }

    private fun onUp() {
        val btn = pressedBtn
        pressedBtn = null
        mode = Mode.NONE
        // Рамку сохраняем до действия: «Скан» читает Prefs.frame(), и при
        // прежнем порядке захватывалась старая область, если рамку только что
        // передвинули.
        bean()?.let { callback.onFrameChanged(it) }
        if (btn != null) perform(btn)
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

    // Делим только на ненулевые размеры: до первой компоновки viewW/viewH
    // равны нулю, и в bean() попадали NaN, которые coerceIn не лечит.
    private fun bean(): RectFBean? {
        if (viewW <= 0f || viewH <= 0f) return null
        return RectFBean(
            left = (frame.left / viewW).coerceIn(0f, 1f),
            top = (frame.top / viewH).coerceIn(0f, 1f),
            right = (frame.right / viewW).coerceIn(0f, 1f),
            bottom = (frame.bottom / viewH).coerceIn(0f, 1f),
        )
    }

    fun resetFrame() {
        val boxTop = toolbarRect.bottom + dp(10f)
        val boxBottom = panelRect.top - dp(10f)
        val boxH = max(dp(48f), boxBottom - boxTop)
        val left = min(viewW * 0.06f, viewW - dp(6f))
        // Высота рамки считалась от viewW, а не от viewH, — в альбомной
        // ориентации уезжала далеко за нижнюю панель.
        val top = min(boxTop + boxH * 0.1f, boxBottom)
        val right = max(min(viewW * 0.94f, viewW - dp(6f)), left)
        val bottom = max(min(top + boxH * 0.55f, boxBottom), top)
        frame.set(left, top, right, bottom)
        clampFrame()
        invalidate()
    }
}