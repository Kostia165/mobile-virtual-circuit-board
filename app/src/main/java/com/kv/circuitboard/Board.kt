package com.kv.circuitboard

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A rectangular piece of a board, used for copy / paste. */
class Clip(val w: Int, val h: Int, val data: ByteArray) {
    fun get(x: Int, y: Int): Int = data[y * w + x].toInt()

    /** Rotated 90 degrees clockwise. */
    fun rotated(): Clip {
        val out = ByteArray(data.size)
        for (y in 0 until h) for (x in 0 until w) {
            out[x * h + (h - 1 - y)] = data[y * w + x]
        }
        return Clip(h, w, out)
    }

    fun flipped(): Clip {
        val out = ByteArray(data.size)
        for (y in 0 until h) for (x in 0 until w) {
            out[y * w + (w - 1 - x)] = data[y * w + x]
        }
        return Clip(w, h, out)
    }
}

/** Cell rectangle, [x0, x1) x [y0, y1). */
data class CellRect(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val width get() = x1 - x0
    val height get() = y1 - y0

    companion object {
        /** Rectangle spanning two cells (inclusive), in any order. */
        fun spanning(ax: Int, ay: Int, bx: Int, by: Int) =
            CellRect(min(ax, bx), min(ay, by), max(ax, bx) + 1, max(ay, by) + 1)
    }
}

class Board(width: Int = DEFAULT_SIZE, height: Int = DEFAULT_SIZE) {
    var w = width
        private set
    var h = height
        private set
    var cells = ByteArray(w * h)
        private set

    /** Clock half-period in ticks. */
    var clockPeriod = 8

    /** Undo history entry; boards can change size, so the size is kept too. */
    private class Snap(val w: Int, val h: Int, val cells: ByteArray)

    private val undoStack = ArrayDeque<Snap>()
    private val redoStack = ArrayDeque<Snap>()

    fun inBounds(x: Int, y: Int) = x in 0 until w && y in 0 until h
    fun get(x: Int, y: Int): Int = cells[y * w + x].toInt()
    fun set(x: Int, y: Int, ink: Int) {
        if (inBounds(x, y)) cells[y * w + x] = ink.toByte()
    }

    // ---- undo / redo -------------------------------------------------------------------------

    /** Call before every modification. */
    fun snapshot() {
        undoStack.addLast(Snap(w, h, cells.copyOf()))
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
    }

    /** Drops the last snapshot and restores it (used to cancel a stroke). */
    fun revertSnapshot() {
        if (undoStack.isNotEmpty()) restore(undoStack.removeLast())
    }

    fun canUndo() = undoStack.isNotEmpty()
    fun canRedo() = redoStack.isNotEmpty()

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        redoStack.addLast(Snap(w, h, cells))
        restore(undoStack.removeLast())
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        undoStack.addLast(Snap(w, h, cells))
        restore(redoStack.removeLast())
        return true
    }

    private fun restore(s: Snap) {
        w = s.w
        h = s.h
        cells = s.cells
    }

    // ---- editing -----------------------------------------------------------------------------

    fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int, ink: Int) {
        // Bresenham
        var x = x0
        var y = y0
        val dx = abs(x1 - x0)
        val dy = -abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            set(x, y, ink)
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }

    fun clip(r: CellRect): CellRect? {
        val c = CellRect(max(0, r.x0), max(0, r.y0), min(w, r.x1), min(h, r.y1))
        return if (c.width > 0 && c.height > 0) c else null
    }

    fun copy(r: CellRect): Clip {
        val data = ByteArray(r.width * r.height)
        for (y in 0 until r.height) for (x in 0 until r.width) {
            val bx = r.x0 + x
            val by = r.y0 + y
            if (inBounds(bx, by)) data[y * r.width + x] = cells[by * w + bx]
        }
        return Clip(r.width, r.height, data)
    }

    fun clear(r: CellRect) {
        for (y in r.y0 until r.y1) for (x in r.x0 until r.x1) set(x, y, Ink.EMPTY)
    }

    /** Pastes non-empty cells of [clip] with its top-left corner at (x, y). */
    fun paste(clip: Clip, x: Int, y: Int) {
        for (cy in 0 until clip.h) for (cx in 0 until clip.w) {
            val ink = clip.get(cx, cy)
            if (ink != Ink.EMPTY) set(x + cx, y + cy, ink)
        }
    }

    /** Changes the board size, moving the content by (dx, dy). Cells that end up outside are lost. */
    fun resize(newW: Int, newH: Int, dx: Int, dy: Int) {
        val out = ByteArray(newW * newH)
        for (y in 0 until h) {
            val ny = y + dy
            if (ny !in 0 until newH) continue
            for (x in 0 until w) {
                val nx = x + dx
                if (nx in 0 until newW) out[ny * newW + nx] = cells[y * w + x]
            }
        }
        w = newW
        h = newH
        cells = out
    }

    /** Smallest rectangle containing every non-empty cell, or null for an empty board. */
    fun usedBounds(): CellRect? {
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (cells[y * w + x].toInt() != Ink.EMPTY) {
                x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y)
            }
        }
        return if (x1 < 0) null else CellRect(x0, y0, x1 + 1, y1 + 1)
    }

    fun clearAll() {
        cells = ByteArray(w * h)
    }

    // ---- persistence -------------------------------------------------------------------------

    fun save(out: OutputStream) {
        DataOutputStream(GZIPOutputStream(out)).use { d ->
            d.writeInt(MAGIC)
            d.writeInt(VERSION)
            d.writeInt(w)
            d.writeInt(h)
            d.writeInt(clockPeriod)
            d.write(cells)
        }
    }

    /** Replaces this board's content (and size) with the stream's. History is cleared. */
    fun load(input: InputStream) {
        DataInputStream(GZIPInputStream(input)).use { d ->
            if (d.readInt() != MAGIC) throw IOException("Not a circuit file")
            if (d.readInt() > VERSION) throw IOException("File made by a newer version")
            val nw = d.readInt()
            val nh = d.readInt()
            if (nw !in 1..MAX_SIZE || nh !in 1..MAX_SIZE) throw IOException("Bad board size")
            val period = d.readInt()
            val data = ByteArray(nw * nh)
            d.readFully(data)
            for (b in data) if (b < 0 || b >= Ink.COUNT) throw IOException("Corrupted file")
            w = nw
            h = nh
            cells = data
            clockPeriod = period.coerceIn(1, 100000)
            undoStack.clear()
            redoStack.clear()
        }
    }

    companion object {
        const val DEFAULT_SIZE = 256
        const val MAX_SIZE = 1024
        private const val MAX_UNDO = 50
        private const val MAGIC = 0x56434231 // "VCB1"
        private const val VERSION = 1
    }
}
