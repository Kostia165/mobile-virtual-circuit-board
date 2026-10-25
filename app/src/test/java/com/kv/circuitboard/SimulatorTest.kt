package com.kv.circuitboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SimulatorTest {

    private val legend = mapOf(
        '.' to Ink.EMPTY, 'g' to Ink.TRACE_FIRST, 'r' to Ink.TRACE_FIRST + 1,
        'R' to Ink.READ, 'W' to Ink.WRITE,
        'b' to Ink.BUFFER, 'n' to Ink.NOT, 'a' to Ink.AND, 'A' to Ink.NAND,
        'o' to Ink.OR, 'O' to Ink.NOR, 'x' to Ink.XOR, 'X' to Ink.XNOR,
        '0' to Ink.CONST0, '1' to Ink.CONST1, 'c' to Ink.CLOCK, 's' to Ink.SWITCH, 'L' to Ink.LED,
        '+' to Ink.CROSS, 'T' to Ink.TUNNEL, 'M' to Ink.MESH, 'B' to Ink.BUS_FIRST,
    )

    private fun board(vararg rows: String): Board {
        val b = Board(rows.maxOf { it.length }, rows.size)
        for ((y, row) in rows.withIndex()) for ((x, ch) in row.withIndex()) b.set(x, y, legend.getValue(ch))
        return b
    }

    private fun run(b: Board, ticks: Int = 5) = Simulator(b).also { s -> repeat(ticks) { s.step() } }
    private fun Simulator.on(b: Board, x: Int, y: Int) = isOn(y * b.w + x)

    private fun gate(gate: Char, in1: Char, in2: Char): Boolean {
        val b = board(
            "${in1}WgR$gate....",
            "....${gate}WgRL",
            "${in2}WrR$gate....",
        )
        return run(b).on(b, 8, 1)
    }

    @Test fun gatesFollowTruthTables() {
        for (p in 0..1) for (q in 0..1) {
            val c1 = ('0' + p)
            val c2 = ('0' + q)
            val a = p + q
            assertEquals("AND $p$q", a == 2, gate('a', c1, c2))
            assertEquals("NAND $p$q", a != 2, gate('A', c1, c2))
            assertEquals("OR $p$q", a > 0, gate('o', c1, c2))
            assertEquals("NOR $p$q", a == 0, gate('O', c1, c2))
            assertEquals("XOR $p$q", a == 1, gate('x', c1, c2))
            assertEquals("XNOR $p$q", a != 1, gate('X', c1, c2))
        }
    }

    @Test fun notAndBuffer() {
        val b = board(
            "1WgRnWgRL",
            ".........",
            "1WgRbWgRL",
        )
        val s = run(b)
        assertFalse(s.on(b, 8, 0))
        assertTrue(s.on(b, 8, 2))
    }

    @Test fun differentTraceColorsDoNotMerge() {
        val b = board("1WggrrRL")
        assertFalse(run(b).on(b, 7, 0))
    }

    @Test fun crossKeepsLinesSeparate() {
        val b = board(
            "...0...",
            "...W...",
            "1Wg+gRL",
            "...r...",
            "...R...",
            "...L...",
        )
        val s = run(b)
        assertTrue(s.on(b, 6, 2))
        assertFalse(s.on(b, 3, 5))
        assertEquals(2, s.netCount)
    }

    @Test fun tunnelSkipsCellsBetween() {
        val b = board("1WgTr.rTgRL")
        assertTrue(run(b).on(b, 10, 0))
    }

    @Test fun meshJoinsRemoteCells() {
        val b = board(
            "1WgM",
            "....",
            "MgRL",
        )
        assertTrue(run(b).on(b, 3, 2))
    }

    @Test fun busJoinsOnlyWithinOneArea() {
        val b = board(
            "1WgBBBgRL",
            ".........",
            "1Wg.BBgRL",
        )
        val s = run(b)
        assertTrue(s.on(b, 8, 0))
        assertFalse(s.on(b, 8, 2))
    }

    @Test fun clockToggles() {
        val b = board("cWgRL")
        b.clockPeriod = 3
        val s = Simulator(b)
        val seen = ArrayList<Boolean>()
        repeat(14) { s.step(); seen.add(s.on(b, 4, 0)) }
        // Clock is on during ticks 3-5, 9-11; the display lags one tick behind the trace.
        assertEquals(listOf(false, false, false, true, true, true, false, false, false, true), seen.take(10))
    }

    @Test fun switchToggles() {
        val b = board("sWgRL")
        val s = run(b)
        assertFalse(s.on(b, 4, 0))
        assertTrue(s.toggleSwitch(0, 0))
        repeat(3) { s.step() }
        assertTrue(s.on(b, 4, 0))
        assertFalse(s.toggleSwitch(2, 0))
    }

    @Test fun saveLoadRoundTrip() {
        val b = board("1WgTr.rTgRL", "cWgRL......")
        b.clockPeriod = 7
        val out = ByteArrayOutputStream()
        b.save(out)
        val c = Board()
        c.load(ByteArrayInputStream(out.toByteArray()))
        assertEquals(b.w, c.w)
        assertEquals(b.h, c.h)
        assertEquals(7, c.clockPeriod)
        assertTrue(b.cells.contentEquals(c.cells))
    }

    @Test fun clipRotateAndPaste() {
        val b = board("1Wg", "...")
        val clip = b.copy(CellRect(0, 0, 3, 1)).rotated()
        assertEquals(1, clip.w)
        assertEquals(3, clip.h)
        val c = Board(4, 4)
        c.paste(clip, 2, 0)
        assertEquals(Ink.CONST1, c.get(2, 0))
        assertEquals(Ink.WRITE, c.get(2, 1))
        assertEquals(Ink.TRACE_FIRST, c.get(2, 2))
    }

    @Test fun resizeKeepsContentAndIsUndoable() {
        val b = Board(4, 4)
        b.set(1, 2, Ink.XOR)
        b.snapshot()
        b.resize(8, 6, 2, 1)
        assertEquals(8, b.w)
        assertEquals(6, b.h)
        assertEquals(Ink.XOR, b.get(3, 3))
        assertEquals(CellRect(3, 3, 4, 4), b.usedBounds())
        assertTrue(b.undo())
        assertEquals(4, b.w)
        assertEquals(Ink.XOR, b.get(1, 2))
        assertTrue(b.redo())
        assertEquals(8, b.w)
        assertEquals(Ink.XOR, b.get(3, 3))
    }

    @Test fun undoRedo() {
        val b = Board(4, 4)
        b.snapshot(); b.set(1, 1, Ink.AND)
        assertTrue(b.undo())
        assertEquals(Ink.EMPTY, b.get(1, 1))
        assertTrue(b.redo())
        assertEquals(Ink.AND, b.get(1, 1))
    }
}
