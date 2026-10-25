package com.kv.circuitboard

/**
 * Compiles a board into nets (wires) and components, then simulates it tick by tick.
 *
 * Connectivity rules:
 *  - 4-neighbour cells with connecting inks (see [Ink.connects]) form one entity.
 *  - Cross: a single cell that links left<->right and up<->down independently.
 *  - Tunnel: entering a tunnel cell in some direction links to the cell just past the next tunnel
 *    cell in that direction, if that cell has the same ink.
 *  - Mesh: all cells of the same ink touching any mesh cell are linked, anywhere on the board.
 *  - Bus: like mesh, but only within one connected bus area of one bus color.
 *  - A component reads every net that touches it through an R cell and writes every net that
 *    touches it through a W cell. A net is on if any of its writers is on.
 *  - Every component has a delay of one tick.
 */
class Simulator(board: Board) {
    private val w = board.w
    private val h = board.h
    private val cells = board.cells.copyOf()
    private val clockPeriod = board.clockPeriod

    /** Net index for wire cells, -1 otherwise. */
    private val netOf = IntArray(w * h) { -1 }

    /** Component index for component cells, -1 otherwise. */
    private val compOf = IntArray(w * h) { -1 }

    var netCount = 0
        private set
    var compCount = 0
        private set

    private lateinit var netVal: BooleanArray
    private lateinit var compType: IntArray
    private lateinit var compOut: BooleanArray
    private lateinit var switchState: BooleanArray
    private lateinit var inStart: IntArray
    private lateinit var inList: IntArray
    private lateinit var outStart: IntArray
    private lateinit var outList: IntArray

    var tick = 0L
        private set

    init {
        build()
    }

    private fun ink(i: Int) = cells[i].toInt()

    // ---- compilation -------------------------------------------------------------------------

    private fun build() {
        val n = w * h
        val parent = IntArray(n) { it }
        fun find(a: Int): Int {
            var x = a
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        // Direct neighbours, crosses and tunnels. Links are symmetric, so right and down suffice.
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val a = ink(i)
            if (!Ink.isEntity(a)) continue
            val r = link(x, y, 1, 0, a)
            if (r >= 0 && Ink.connects(a, ink(r))) union(i, r)
            val d = link(x, y, 0, 1, a)
            if (d >= 0 && Ink.connects(a, ink(d))) union(i, d)
        }

        // Bus areas: label connected regions of each bus color.
        val busLabel = IntArray(n) { -1 }
        var labels = 0
        val stack = IntArray(n)
        for (i in 0 until n) {
            val b = ink(i)
            if (!Ink.isBus(b) || busLabel[i] >= 0) continue
            var sp = 0
            stack[sp++] = i
            busLabel[i] = labels
            while (sp > 0) {
                val c = stack[--sp]
                val cx = c % w
                val cy = c / w
                forNeighbours(cx, cy) { j ->
                    if (busLabel[j] < 0 && ink(j) == b) {
                        busLabel[j] = labels
                        stack[sp++] = j
                    }
                }
            }
            labels++
        }

        // Mesh (global) and bus (per area) links.
        val meshRep = IntArray(Ink.COUNT) { -1 }
        val busRep = HashMap<Long, Int>()
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val a = ink(i)
            if (!Ink.isEntity(a)) continue
            forNeighbours(x, y) { j ->
                val b = ink(j)
                if (b == Ink.MESH) {
                    if (meshRep[a] < 0) meshRep[a] = i else union(i, meshRep[a])
                } else if (Ink.isBus(b)) {
                    val key = busLabel[j].toLong() * Ink.COUNT + a
                    val rep = busRep[key]
                    if (rep == null) busRep[key] = i else union(i, rep)
                }
            }
        }

        // Number the entities.
        val rootId = IntArray(n) { -1 }
        val types = IntArray(n)
        for (i in 0 until n) {
            val a = ink(i)
            if (!Ink.isEntity(a)) continue
            val r = find(i)
            if (rootId[r] < 0) {
                if (Ink.isWire(a)) {
                    rootId[r] = netCount++
                } else {
                    types[compCount] = a
                    rootId[r] = compCount++
                }
            }
            if (Ink.isWire(a)) netOf[i] = rootId[r] else compOf[i] = rootId[r]
        }
        compType = types.copyOf(compCount)
        netVal = BooleanArray(netCount)
        compOut = BooleanArray(compCount)
        switchState = BooleanArray(compCount)

        // Component <-> net connections through R and W cells.
        val inputs = HashSet<Long>()
        val outputs = HashSet<Long>()
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val a = ink(i)
            if (a != Ink.READ && a != Ink.WRITE) continue
            val net = netOf[i]
            forNeighbours(x, y) { j ->
                val c = compOf[j]
                if (c >= 0) {
                    val key = (c.toLong() shl 32) or net.toLong()
                    if (a == Ink.READ) inputs.add(key) else outputs.add(key)
                }
            }
        }
        inStart = IntArray(compCount + 1)
        inList = IntArray(inputs.size)
        fillCsr(inputs, inStart, inList)
        outStart = IntArray(compCount + 1)
        outList = IntArray(outputs.size)
        fillCsr(outputs, outStart, outList)

        for (c in 0 until compCount) compOut[c] = compType[c] == Ink.CONST1
    }

    private fun fillCsr(pairs: Set<Long>, start: IntArray, list: IntArray) {
        for (p in pairs) start[(p ushr 32).toInt() + 1]++
        for (c in 1 until start.size) start[c] += start[c - 1]
        val pos = start.copyOf()
        for (p in pairs) {
            val c = (p ushr 32).toInt()
            list[pos[c]++] = (p and 0xFFFFFFFFL).toInt()
        }
    }

    private inline fun forNeighbours(x: Int, y: Int, f: (Int) -> Unit) {
        if (x > 0) f(y * w + x - 1)
        if (x < w - 1) f(y * w + x + 1)
        if (y > 0) f((y - 1) * w + x)
        if (y < h - 1) f((y + 1) * w + x)
    }

    /** The cell linked to (x, y) in direction (dx, dy), taking crosses and tunnels into account. */
    private fun link(x: Int, y: Int, dx: Int, dy: Int, a: Int): Int {
        var nx = x + dx
        var ny = y + dy
        if (nx !in 0 until w || ny !in 0 until h) return -1
        when (ink(ny * w + nx)) {
            Ink.CROSS -> {
                nx += dx
                ny += dy
                if (nx !in 0 until w || ny !in 0 until h) return -1
                val j = ny * w + nx
                return if (Ink.isEntity(ink(j))) j else -1
            }
            Ink.TUNNEL -> {
                for (k in 1..MAX_TUNNEL_LENGTH) {
                    val tx = nx + k * dx
                    val ty = ny + k * dy
                    if (tx !in 0 until w || ty !in 0 until h) return -1
                    if (ink(ty * w + tx) != Ink.TUNNEL) continue
                    val ex = tx + dx
                    val ey = ty + dy
                    if (ex !in 0 until w || ey !in 0 until h) return -1
                    if (ink(ey * w + ex) == a) return ey * w + ex
                }
                return -1
            }
            else -> return ny * w + nx
        }
    }

    // ---- simulation --------------------------------------------------------------------------

    fun step() {
        java.util.Arrays.fill(netVal, false)
        for (c in 0 until compCount) {
            if (!compOut[c]) continue
            for (k in outStart[c] until outStart[c + 1]) netVal[outList[k]] = true
        }
        tick++
        val clockOn = (tick / clockPeriod) % 2 == 1L
        for (c in 0 until compCount) {
            val total = inStart[c + 1] - inStart[c]
            var active = 0
            for (k in inStart[c] until inStart[c + 1]) if (netVal[inList[k]]) active++
            compOut[c] = when (compType[c]) {
                Ink.BUFFER, Ink.OR, Ink.LED -> active > 0
                Ink.NOT, Ink.NOR -> active == 0
                Ink.AND -> active > 0 && active == total
                Ink.NAND -> !(active > 0 && active == total)
                Ink.XOR -> active % 2 == 1
                Ink.XNOR -> active % 2 == 0
                Ink.CONST1 -> true
                Ink.CLOCK -> clockOn
                Ink.SWITCH -> switchState[c]
                else -> false
            }
        }
    }

    /** Whether the entity covering cell i is currently on. */
    fun isOn(i: Int): Boolean {
        val net = netOf[i]
        if (net >= 0) return netVal[net]
        val c = compOf[i]
        return c >= 0 && compOut[c]
    }

    /** Toggles the switch at cell (x, y). Returns false if there is no switch there. */
    fun toggleSwitch(x: Int, y: Int): Boolean {
        if (x !in 0 until w || y !in 0 until h) return false
        val c = compOf[y * w + x]
        if (c < 0 || compType[c] != Ink.SWITCH) return false
        switchState[c] = !switchState[c]
        compOut[c] = switchState[c]
        return true
    }

    companion object {
        const val MAX_TUNNEL_LENGTH = 256
    }
}
