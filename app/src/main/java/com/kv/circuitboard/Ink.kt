package com.kv.circuitboard

/**
 * Every board cell holds one "ink" (stored as a byte). Adjacent cells with the same ink merge
 * into a single entity during simulation, like in Virtual Circuit Board.
 */
object Ink {
    const val EMPTY = 0

    // Traces: 8 colors. Different colors never merge with each other.
    const val TRACE_FIRST = 1
    const val TRACE_COUNT = 8

    // Read / Write connectors: components read inputs through R and write outputs through W.
    // R and W join any adjacent trace (of any color) and each other.
    const val READ = 9
    const val WRITE = 10

    // Gates
    const val BUFFER = 11
    const val NOT = 12
    const val AND = 13
    const val NAND = 14
    const val OR = 15
    const val NOR = 16
    const val XOR = 17
    const val XNOR = 18

    // Sources and outputs
    const val CONST0 = 19
    const val CONST1 = 20
    const val CLOCK = 21
    const val SWITCH = 22
    const val LED = 23

    // Space optimisation (not entities themselves)
    const val CROSS = 24
    const val TUNNEL = 25
    const val MESH = 26
    const val BUS_FIRST = 27
    const val BUS_COUNT = 3

    const val COUNT = BUS_FIRST + BUS_COUNT

    fun isTrace(i: Int) = i >= TRACE_FIRST && i < TRACE_FIRST + TRACE_COUNT
    fun isWire(i: Int) = isTrace(i) || i == READ || i == WRITE
    fun isBus(i: Int) = i >= BUS_FIRST && i < BUS_FIRST + BUS_COUNT
    fun isEntity(i: Int) = i != EMPTY && i != CROSS && i != TUNNEL && i != MESH && !isBus(i)
    fun isComponent(i: Int) = isEntity(i) && !isWire(i)

    /** Whether two touching inks become the same entity. */
    fun connects(a: Int, b: Int): Boolean {
        if (isWire(a) && isWire(b)) {
            return a == b || a == READ || a == WRITE || b == READ || b == WRITE
        }
        return a == b
    }

    private val traceColors = intArrayOf(
        0xFFC8A94E.toInt(), // gold
        0xFFE8505B.toInt(), // red
        0xFFF2994A.toInt(), // orange
        0xFF6FCF5A.toInt(), // green
        0xFF3CD3E0.toInt(), // cyan
        0xFF4A7CF2.toInt(), // blue
        0xFFB062E8.toInt(), // purple
        0xFFB8C0CC.toInt(), // white-ish
    )
    private val busColors = intArrayOf(
        0xFF2F6FA8.toInt(),
        0xFF2E8C5A.toInt(),
        0xFF9C4A8A.toInt(),
    )

    /** Base color shown while editing. */
    val color = IntArray(COUNT).also { c ->
        c[EMPTY] = 0xFF232838.toInt()
        for (k in 0 until TRACE_COUNT) c[TRACE_FIRST + k] = traceColors[k]
        c[READ] = 0xFF3E6FD8.toInt()
        c[WRITE] = 0xFFD83E5A.toInt()
        c[BUFFER] = 0xFF7EE05A.toInt()
        c[NOT] = 0xFFF0607A.toInt()
        c[AND] = 0xFFFFB040.toInt()
        c[NAND] = 0xFFC07818.toInt()
        c[OR] = 0xFF40D8F0.toInt()
        c[NOR] = 0xFF2A9BB8.toInt()
        c[XOR] = 0xFFB070FF.toInt()
        c[XNOR] = 0xFF7E40C8.toInt()
        c[CONST0] = 0xFF5A6070.toInt()
        c[CONST1] = 0xFFE8E8E8.toInt()
        c[CLOCK] = 0xFFFF7A30.toInt()
        c[SWITCH] = 0xFF30E0A0.toInt()
        c[LED] = 0xFFC8CCD4.toInt()
        c[CROSS] = 0xFF4A5468.toInt()
        c[TUNNEL] = 0xFF6A5A48.toInt()
        c[MESH] = 0xFF5868A0.toInt()
        for (k in 0 until BUS_COUNT) c[BUS_FIRST + k] = busColors[k]
    }

    /** Colors used during simulation for entities that are on / off. */
    val onColor = IntArray(COUNT) { mix(color[it], 0xFFFFFFFF.toInt(), 0.25f) }
    val offColor = IntArray(COUNT) { mix(color[it], color[EMPTY], 0.7f) }

    init {
        onColor[LED] = 0xFFFFF6C0.toInt()
        offColor[LED] = 0xFF3A3F4A.toInt()
        onColor[CONST0] = color[CONST0]
        offColor[CONST0] = color[CONST0]
    }

    /** Short glyph drawn on cells when zoomed in. */
    val glyph = Array(COUNT) { "" }.also { g ->
        g[READ] = "R"; g[WRITE] = "W"
        g[BUFFER] = ">"; g[NOT] = "!"
        g[AND] = "&"; g[NAND] = "!&"
        g[OR] = "|"; g[NOR] = "!|"
        g[XOR] = "^"; g[XNOR] = "!^"
        g[CONST0] = "0"; g[CONST1] = "1"
        g[CLOCK] = "C"; g[SWITCH] = "S"
        g[CROSS] = "+"; g[TUNNEL] = "T"; g[MESH] = "#"
        for (k in 0 until BUS_COUNT) g[BUS_FIRST + k] = "B"
    }

    val name = Array(COUNT) { "" }.also { n ->
        n[EMPTY] = "Empty"
        val traceNames = arrayOf("Gold", "Red", "Orange", "Green", "Cyan", "Blue", "Purple", "White")
        for (k in 0 until TRACE_COUNT) n[TRACE_FIRST + k] = traceNames[k]
        n[READ] = "Read"; n[WRITE] = "Write"
        n[BUFFER] = "Buffer"; n[NOT] = "NOT"
        n[AND] = "AND"; n[NAND] = "NAND"
        n[OR] = "OR"; n[NOR] = "NOR"
        n[XOR] = "XOR"; n[XNOR] = "XNOR"
        n[CONST0] = "Const 0"; n[CONST1] = "Const 1"
        n[CLOCK] = "Clock"; n[SWITCH] = "Switch"; n[LED] = "Display"
        n[CROSS] = "Cross"; n[TUNNEL] = "Tunnel"; n[MESH] = "Mesh"
        for (k in 0 until BUS_COUNT) n[BUS_FIRST + k] = "Bus ${k + 1}"
    }

    /** Palette groups shown in the UI, in order. */
    val groups: List<Pair<String, IntArray>> = listOf(
        "Traces" to IntArray(TRACE_COUNT + 2) { if (it < TRACE_COUNT) TRACE_FIRST + it else if (it == TRACE_COUNT) READ else WRITE },
        "Gates" to intArrayOf(BUFFER, NOT, AND, NAND, OR, NOR, XOR, XNOR),
        "I/O" to intArrayOf(CONST0, CONST1, CLOCK, SWITCH, LED),
        "Compact" to IntArray(3 + BUS_COUNT) { if (it < 3) intArrayOf(CROSS, TUNNEL, MESH)[it] else BUS_FIRST + it - 3 },
    )

    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        fun m(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }
}
