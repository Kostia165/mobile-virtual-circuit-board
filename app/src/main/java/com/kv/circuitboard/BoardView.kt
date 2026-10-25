package com.kv.circuitboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

enum class Tool { DRAW, ERASE, SELECT, PAN }

class BoardView(context: Context) : View(context) {

    var board = Board()
        private set

    var tool = Tool.DRAW
    var ink = Ink.TRACE_FIRST

    /** Called whenever selection, paste mode, history or simulation state changes. */
    var onStateChanged: () -> Unit = {}

    // ---- view transform: screen = offset + cell * scale ---------------------------------------
    private var scale = 0f
    private var offX = 0f
    private var offY = 0f

    // ---- selection and paste -----------------------------------------------------------------
    var selection: CellRect? = null
        private set
    var clipboard: Clip? = null
        private set
    var pasteClip: Clip? = null
        private set
    private var pasteX = 0
    private var pasteY = 0
    private var ghost: Bitmap? = null

    // ---- simulation --------------------------------------------------------------------------
    var sim: Simulator? = null
        private set
    var running = false
        private set
    var ticksPerSecond = 10.0
    private var tickBudget = 0.0
    private var lastFrameNanos = 0L

    // ---- rendering ---------------------------------------------------------------------------
    private var bitmap = Bitmap.createBitmap(board.w, board.h, Bitmap.Config.ARGB_8888)
    private var pixels = IntArray(board.w * board.h)
    private var bitmapDirty = true

    private val bitmapPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val ghostPaint = Paint().apply { isFilterBitmap = false; alpha = 170 }
    private val gridPaint = Paint().apply { color = 0x22FFFFFF; strokeWidth = 1f }
    private val majorGridPaint = Paint().apply { color = 0x40FFFFFF; strokeWidth = 1f }
    private val borderPaint = Paint().apply { color = 0xFF3A4256.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val glyphPaint = Paint().apply {
        color = 0xCC000000.toInt()
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        isFakeBoldText = true
    }
    private val selPaint = Paint().apply {
        color = 0xFFF2B33D.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val selFill = Paint().apply { color = 0x22F2B33D }
    private val pastePaint = Paint().apply { color = 0xFF40D8F0.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f }

    // ---- touch -------------------------------------------------------------------------------
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var multiTouch = false
    private var strokeActive = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastCellX = 0
    private var lastCellY = 0
    private var moved = false
    private var pasteStartX = 0
    private var pasteStartY = 0
    private var selStartX = 0
    private var selStartY = 0

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomAt(d.focusX, d.focusY, d.scaleFactor)
            return true
        }
    })

    // ==== public API ============================================================================

    fun replaceBoard(b: Board) {
        stopSimulation()
        board = b
        selection = null
        cancelPaste()
        boardResized()
        fitView()
        onStateChanged()
    }

    /** Must be called after the board's size or whole content changed. */
    fun boardChanged() {
        if (bitmap.width != board.w || bitmap.height != board.h) boardResized()
        bitmapDirty = true
        invalidate()
        onStateChanged()
    }

    private fun boardResized() {
        bitmap = Bitmap.createBitmap(board.w, board.h, Bitmap.Config.ARGB_8888)
        pixels = IntArray(board.w * board.h)
        selection = selection?.let { board.clip(it) }
        bitmapDirty = true
    }

    fun undo() = historyStep { board.undo() }
    fun redo() = historyStep { board.redo() }

    /** Runs an undo/redo; if it changed the board size, keeps the content still on screen. */
    private inline fun historyStep(action: () -> Boolean) {
        val oldW = board.w
        val oldH = board.h
        if (!action()) return
        if (board.w != oldW || board.h != oldH) {
            offX -= (board.w - oldW) / 2 * scale
            offY -= (board.h - oldH) / 2 * scale
            selection = null
        }
        boardChanged()
    }

    /** Resizes the board keeping its content centred (and still on screen). Undoable. */
    fun resizeBoard(newW: Int, newH: Int) {
        stopSimulation()
        cancelPaste()
        val dx = (newW - board.w) / 2
        val dy = (newH - board.h) / 2
        board.snapshot()
        board.resize(newW, newH, dx, dy)
        offX -= dx * scale
        offY -= dy * scale
        selection = null
        boardChanged()
    }

    fun zoom(factor: Float) = zoomAt(width / 2f, height / 2f, factor)

    /** Zooms so the used part of the board (or the whole board when empty) fills the view. */
    fun fitView() {
        if (width == 0 || height == 0) return
        val used = board.usedBounds()
        if (used == null) {
            // Empty board: show ~40 cells around the centre.
            scale = width / 40f
            offX = width / 2f - board.w / 2f * scale
            offY = height / 2f - board.h / 2f * scale
        } else {
            val cw = used.width + 8
            val ch = used.height + 8
            scale = min(width.toFloat() / cw, height.toFloat() / ch).coerceIn(MIN_SCALE, MAX_SCALE)
            offX = width / 2f - (used.x0 + used.x1) / 2f * scale
            offY = height / 2f - (used.y0 + used.y1) / 2f * scale
        }
        invalidate()
    }

    fun clearSelection() {
        selection = null
        invalidate()
        onStateChanged()
    }

    fun copySelection() {
        val s = selection ?: return
        clipboard = board.copy(s)
        onStateChanged()
    }

    fun deleteSelection() {
        val s = selection ?: return
        board.snapshot()
        board.clear(s)
        boardChanged()
    }

    fun cutSelection() {
        copySelection()
        deleteSelection()
    }

    /** Shows the clipboard as a ghost in the middle of the view; drag it, then [placePaste]. */
    fun startPaste() {
        val c = clipboard ?: return
        setPasteClip(c)
        val cx = floor((width / 2f - offX) / scale).toInt()
        val cy = floor((height / 2f - offY) / scale).toInt()
        pasteX = cx - c.w / 2
        pasteY = cy - c.h / 2
        invalidate()
        onStateChanged()
    }

    fun rotatePaste() { pasteClip?.let { setPasteClip(it.rotated()); invalidate() } }
    fun flipPaste() { pasteClip?.let { setPasteClip(it.flipped()); invalidate() } }

    fun placePaste() {
        val c = pasteClip ?: return
        board.snapshot()
        board.paste(c, pasteX, pasteY)
        selection = board.clip(CellRect(pasteX, pasteY, pasteX + c.w, pasteY + c.h))
        cancelPaste()
        boardChanged()
    }

    fun cancelPaste() {
        pasteClip = null
        ghost = null
        invalidate()
        onStateChanged()
    }

    private fun setPasteClip(c: Clip) {
        pasteClip = c
        val px = IntArray(c.w * c.h) { val i = c.data[it].toInt(); if (i == Ink.EMPTY) Color.TRANSPARENT else Ink.color[i] }
        ghost = Bitmap.createBitmap(px, c.w, c.h, Bitmap.Config.ARGB_8888)
    }

    // ---- simulation control ------------------------------------------------------------------

    fun startSimulation() {
        if (sim == null) {
            cancelPaste()
            sim = Simulator(board)
        }
        running = true
        tickBudget = 0.0
        lastFrameNanos = 0L
        postOnAnimation(frameLoop)
        bitmapDirty = true
        invalidate()
        onStateChanged()
    }

    fun pauseSimulation() {
        running = false
        removeCallbacks(frameLoop)
        onStateChanged()
    }

    fun stepSimulation() {
        val s = sim ?: Simulator(board).also { sim = it }
        s.step()
        bitmapDirty = true
        invalidate()
        onStateChanged()
    }

    fun stopSimulation() {
        running = false
        removeCallbacks(frameLoop)
        sim = null
        bitmapDirty = true
        invalidate()
        onStateChanged()
    }

    private val frameLoop = object : Runnable {
        override fun run() {
            val s = sim
            if (!running || s == null) return
            val now = System.nanoTime()
            if (lastFrameNanos != 0L) {
                val dt = (now - lastFrameNanos) / 1e9
                tickBudget += ticksPerSecond * min(dt, 0.1)
                var n = tickBudget.toInt()
                tickBudget -= n
                // Never block the UI for too long, even at absurd speeds.
                val deadline = now + 12_000_000L
                while (n-- > 0) {
                    s.step()
                    if ((n and 63) == 0 && System.nanoTime() > deadline) { tickBudget = 0.0; break }
                }
            }
            lastFrameNanos = now
            bitmapDirty = true
            invalidate()
            onStateChanged()
            postOnAnimation(this)
        }
    }

    // ==== rendering =============================================================================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (scale == 0f) fitView()
    }

    private fun refreshBitmap() {
        val cells = board.cells
        val s = sim
        for (i in cells.indices) {
            val k = cells[i].toInt()
            pixels[i] = when {
                s == null || !Ink.isEntity(k) -> Ink.color[k]
                s.isOn(i) -> Ink.onColor[k]
                else -> Ink.offColor[k]
            }
        }
        bitmap.setPixels(pixels, 0, board.w, 0, 0, board.w, board.h)
        bitmapDirty = false
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF171A24.toInt())
        if (scale == 0f) return
        if (bitmapDirty) refreshBitmap()

        canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(scale, scale)
        canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
        canvas.restore()
        canvas.drawRect(offX, offY, offX + board.w * scale, offY + board.h * scale, borderPaint)

        // Visible cell range.
        val vx0 = max(0, floor(-offX / scale).toInt())
        val vy0 = max(0, floor(-offY / scale).toInt())
        val vx1 = min(board.w, ceil((width - offX) / scale).toInt())
        val vy1 = min(board.h, ceil((height - offY) / scale).toInt())

        if (scale >= 10f) {
            for (x in vx0..vx1) {
                val sx = offX + x * scale
                canvas.drawLine(sx, offY + vy0 * scale, sx, offY + vy1 * scale, if (x % 8 == 0) majorGridPaint else gridPaint)
            }
            for (y in vy0..vy1) {
                val sy = offY + y * scale
                canvas.drawLine(offX + vx0 * scale, sy, offX + vx1 * scale, sy, if (y % 8 == 0) majorGridPaint else gridPaint)
            }
        } else if (scale >= 3f) {
            for (x in (vx0 + 7) / 8 * 8..vx1 step 8) {
                val sx = offX + x * scale
                canvas.drawLine(sx, offY + vy0 * scale, sx, offY + vy1 * scale, gridPaint)
            }
            for (y in (vy0 + 7) / 8 * 8..vy1 step 8) {
                val sy = offY + y * scale
                canvas.drawLine(offX + vx0 * scale, sy, offX + vx1 * scale, sy, gridPaint)
            }
        }

        if (scale >= 18f) {
            glyphPaint.textSize = scale * 0.55f
            val baseline = scale / 2f - (glyphPaint.ascent() + glyphPaint.descent()) / 2f
            for (y in vy0 until vy1) for (x in vx0 until vx1) {
                val g = Ink.glyph[board.get(x, y)]
                if (g.isNotEmpty()) canvas.drawText(g, offX + (x + 0.5f) * scale, offY + y * scale + baseline, glyphPaint)
            }
        }

        selection?.let {
            val l = offX + it.x0 * scale; val t = offY + it.y0 * scale
            val r = offX + it.x1 * scale; val b = offY + it.y1 * scale
            canvas.drawRect(l, t, r, b, selFill)
            canvas.drawRect(l, t, r, b, selPaint)
        }

        val c = pasteClip
        val g = ghost
        if (c != null && g != null) {
            canvas.save()
            canvas.translate(offX + pasteX * scale, offY + pasteY * scale)
            canvas.scale(scale, scale)
            canvas.drawBitmap(g, 0f, 0f, ghostPaint)
            canvas.restore()
            canvas.drawRect(offX + pasteX * scale, offY + pasteY * scale,
                offX + (pasteX + c.w) * scale, offY + (pasteY + c.h) * scale, pastePaint)
        }
    }

    // ==== touch =================================================================================

    private fun cellX(sx: Float) = floor((sx - offX) / scale).toInt()
    private fun cellY(sy: Float) = floor((sy - offY) / scale).toInt()

    private fun zoomAt(fx: Float, fy: Float, factor: Float) {
        val ns = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val f = ns / scale
        offX = fx - (fx - offX) * f
        offY = fy - (fy - offY) * f
        scale = ns
        invalidate()
    }

    private fun focus(e: MotionEvent): Pair<Float, Float> {
        var fx = 0f; var fy = 0f
        val skip = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        var n = 0
        for (p in 0 until e.pointerCount) {
            if (p == skip) continue
            fx += e.getX(p); fy += e.getY(p); n++
        }
        return Pair(fx / n, fy / n)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multiTouch = false
                moved = false
                downX = e.x; downY = e.y
                lastX = e.x; lastY = e.y
                singleDown(e.x, e.y)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                if (!multiTouch) {
                    multiTouch = true
                    cancelSingle()
                }
                val (fx, fy) = focus(e)
                lastX = fx; lastY = fy
            }
            MotionEvent.ACTION_MOVE -> {
                if (multiTouch) {
                    val (fx, fy) = focus(e)
                    offX += fx - lastX; offY += fy - lastY
                    lastX = fx; lastY = fy
                    invalidate()
                } else {
                    if (abs(e.x - downX) > touchSlop || abs(e.y - downY) > touchSlop) moved = true
                    singleMove(e.x, e.y)
                    lastX = e.x; lastY = e.y
                }
            }
            MotionEvent.ACTION_UP -> if (!multiTouch) singleUp(e.x, e.y)
            MotionEvent.ACTION_CANCEL -> if (!multiTouch) cancelSingle()
        }
        return true
    }

    private val panning get() = sim != null || pasteClip == null && tool == Tool.PAN

    private fun singleDown(x: Float, y: Float) {
        val cx = cellX(x); val cy = cellY(y)
        when {
            panning -> {}
            pasteClip != null -> { pasteStartX = pasteX - cx; pasteStartY = pasteY - cy }
            tool == Tool.SELECT -> {
                selStartX = cx; selStartY = cy
                selection = board.clip(CellRect.spanning(cx, cy, cx, cy))
                onStateChanged()
            }
            else -> {
                board.snapshot()
                strokeActive = true
                lastCellX = cx; lastCellY = cy
                board.set(cx, cy, if (tool == Tool.ERASE) Ink.EMPTY else ink)
                bitmapDirty = true
            }
        }
        invalidate()
    }

    private fun singleMove(x: Float, y: Float) {
        val cx = cellX(x); val cy = cellY(y)
        when {
            panning -> { offX += x - lastX; offY += y - lastY }
            pasteClip != null -> { pasteX = pasteStartX + cx; pasteY = pasteStartY + cy }
            tool == Tool.SELECT -> {
                val ns = board.clip(CellRect.spanning(selStartX, selStartY, cx, cy))
                if (ns != selection) { selection = ns; onStateChanged() }
            }
            strokeActive -> {
                if (cx != lastCellX || cy != lastCellY) {
                    board.drawLine(lastCellX, lastCellY, cx, cy, if (tool == Tool.ERASE) Ink.EMPTY else ink)
                    lastCellX = cx; lastCellY = cy
                    bitmapDirty = true
                }
            }
        }
        invalidate()
    }

    private fun singleUp(x: Float, y: Float) {
        val s = sim
        if (s != null && !moved) {
            if (s.toggleSwitch(cellX(x), cellY(y))) {
                bitmapDirty = true
                invalidate()
            }
        }
        if (strokeActive) {
            strokeActive = false
            onStateChanged()
        }
    }

    /** A second finger arrived: undo whatever the first finger started. */
    private fun cancelSingle() {
        if (strokeActive) {
            strokeActive = false
            board.revertSnapshot()
            bitmapDirty = true
            invalidate()
        }
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 96f
    }
}
