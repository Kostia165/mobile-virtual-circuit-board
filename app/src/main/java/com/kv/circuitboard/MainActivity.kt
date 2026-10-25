package com.kv.circuitboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private lateinit var boardView: BoardView
    private lateinit var status: TextView
    private lateinit var btnRun: TextView
    private lateinit var btnPause: TextView
    private lateinit var btnStep: TextView
    private lateinit var btnUndo: TextView
    private lateinit var btnRedo: TextView
    private lateinit var speedLabel: TextView
    private lateinit var toolRow: LinearLayout
    private lateinit var pasteRow: LinearLayout
    private lateinit var palette: LinearLayout
    private lateinit var btnCopy: TextView
    private lateinit var btnCut: TextView
    private lateinit var btnPaste: TextView
    private lateinit var btnDelete: TextView
    private val toolButtons = HashMap<Tool, TextView>()
    private val swatches = ArrayList<SwatchView>()

    private var currentName = ""

    private val circuitsDir get() = File(filesDir, "circuits").also { it.mkdirs() }
    private val autosaveFile get() = File(filesDir, "autosave.vcb")

    private val boardSizes = intArrayOf(128, 256, 384, 512, 768, 1024)

    private val speeds = intArrayOf(1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        boardView = BoardView(this)
        setContentView(buildUi())

        val board = Board()
        val loaded = autosaveFile.exists() && runCatching { autosaveFile.inputStream().use { board.load(it) } }.isSuccess
        boardView.replaceBoard(if (loaded) board else demoBoard())
        boardView.onStateChanged = ::updateUi
        updateUi()
    }

    override fun onPause() {
        super.onPause()
        boardView.pauseSimulation()
        runCatching { autosaveFile.outputStream().use { boardView.board.save(it) } }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            boardView.pasteClip != null -> boardView.cancelPaste()
            boardView.selection != null -> boardView.clearSelection()
            boardView.sim != null -> boardView.stopSimulation()
            else -> super.onBackPressed()
        }
    }

    // ==== UI construction =======================================================================

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF14171F.toInt())
        }

        // Top bar: simulation and file actions.
        val top = row()
        btnRun = button("▶ Run") {
            if (boardView.sim == null) boardView.startSimulation() else boardView.stopSimulation()
        }
        btnPause = button("⏸") {
            if (boardView.running) boardView.pauseSimulation() else boardView.startSimulation()
        }
        btnStep = button("Step") { boardView.stepSimulation() }
        speedLabel = TextView(this).apply {
            setTextColor(0xFFB8C0CC.toInt())
            textSize = 12f
            minWidth = dp(64)
            gravity = Gravity.CENTER
        }
        val speedBar = SeekBar(this).apply {
            max = speeds.size - 1
            progress = 3
            layoutParams = LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) = setSpeed(speeds[p])
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        setSpeed(speeds[3])
        btnUndo = button("↶") { boardView.undo() }
        btnRedo = button("↷") { boardView.redo() }
        top.addAll(btnRun, btnPause, btnStep, speedLabel, speedBar, btnUndo, btnRedo,
            button("Save") { showSaveDialog() },
            button("Load") { showLoadDialog() })
        val menuBtn = button("⋮") {}
        menuBtn.setOnClickListener { showMenu(menuBtn) }
        top.addView(menuBtn)
        root.addView(scroller(top))

        // Board with a status line on top.
        val frame = FrameLayout(this)
        frame.addView(boardView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        status = TextView(this).apply {
            setTextColor(0xFF8A93A6.toInt())
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        frame.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val zoomCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        zoomCol.addView(button("+") { boardView.zoom(1.5f) })
        zoomCol.addView(button("−") { boardView.zoom(1 / 1.5f) })
        zoomCol.addView(button("Fit") { boardView.fitView() })
        frame.addView(zoomCol, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.TOP))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Tool row: drawing tools and selection actions.
        toolRow = row()
        for ((t, label) in listOf(Tool.DRAW to "✎ Draw", Tool.ERASE to "⌫ Erase", Tool.SELECT to "⬚ Select", Tool.PAN to "✋ Pan")) {
            val b = button(label) { boardView.tool = t; updateUi() }
            toolButtons[t] = b
            toolRow.addView(b)
        }
        btnCopy = button("Copy") { boardView.copySelection(); toast("Copied") }
        btnCut = button("Cut") { boardView.cutSelection() }
        btnPaste = button("Paste") { boardView.startPaste() }
        btnDelete = button("Delete") { boardView.deleteSelection() }
        toolRow.addAll(btnCopy, btnCut, btnPaste, btnDelete)
        root.addView(scroller(toolRow))

        // Paste row, shown instead of the tool row while placing a paste.
        pasteRow = row()
        pasteRow.addAll(
            label("Drag to move:"),
            button("⟳ Rotate") { boardView.rotatePaste() },
            button("⇋ Flip") { boardView.flipPaste() },
            button("✔ Place") { boardView.placePaste() },
            button("✖ Cancel") { boardView.cancelPaste() })
        pasteRow.visibility = View.GONE
        root.addView(scroller(pasteRow))

        // Ink palette.
        palette = row()
        for ((group, inks) in Ink.groups) {
            palette.addView(label(group))
            for (i in inks) {
                val s = SwatchView(this, i)
                s.setOnClickListener {
                    boardView.ink = i
                    if (boardView.tool != Tool.DRAW) boardView.tool = Tool.DRAW
                    updateUi()
                }
                swatches.add(s)
                palette.addView(s, LinearLayout.LayoutParams(dp(48), dp(58)))
            }
        }
        root.addView(scroller(palette))
        return root
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(2), dp(4), dp(2))
    }

    private fun scroller(content: View) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(content)
    }

    private fun LinearLayout.addAll(vararg views: View) = views.forEach { addView(it) }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(0xFF8A93A6.toInt())
        textSize = 11f
        setPadding(dp(8), 0, dp(4), 0)
    }

    private fun button(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 14f
        gravity = Gravity.CENTER
        minWidth = dp(40)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { onClick() }
        styleButton(this, false)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(2), dp(2), dp(2), dp(2))
        }
    }

    private fun styleButton(b: TextView, selected: Boolean) {
        b.background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(if (selected) 0xFFF2B33D.toInt() else 0xFF262B38.toInt())
        }
        b.setTextColor(if (selected) 0xFF14171F.toInt() else 0xFFE0E4EC.toInt())
    }

    private fun setEnabled(v: View, enabled: Boolean) {
        v.isEnabled = enabled
        v.alpha = if (enabled) 1f else 0.35f
    }

    private fun setSpeed(tps: Int) {
        boardView.ticksPerSecond = tps.toDouble()
        speedLabel.text = "$tps t/s"
    }

    private fun updateUi() {
        val v = boardView
        val simMode = v.sim != null
        val pasting = v.pasteClip != null

        btnRun.text = if (simMode) "■ Stop" else "▶ Run"
        styleButton(btnRun, simMode)
        btnPause.text = if (v.running) "⏸" else "▶"
        setEnabled(btnPause, simMode)
        setEnabled(btnStep, !v.running)
        setEnabled(btnUndo, !simMode && v.board.canUndo())
        setEnabled(btnRedo, !simMode && v.board.canRedo())

        toolRow.visibility = if (pasting) View.GONE else View.VISIBLE
        (toolRow.parent as View).visibility = toolRow.visibility
        (pasteRow.parent as View).visibility = if (pasting) View.VISIBLE else View.GONE
        pasteRow.visibility = if (pasting) View.VISIBLE else View.GONE

        for ((t, b) in toolButtons) {
            styleButton(b, t == v.tool)
            setEnabled(b, !simMode)
        }
        val hasSel = v.selection != null && !simMode
        setEnabled(btnCopy, hasSel)
        setEnabled(btnCut, hasSel)
        setEnabled(btnDelete, hasSel)
        setEnabled(btnPaste, v.clipboard != null && !simMode)
        for (s in swatches) {
            s.active = s.ink == v.ink && v.tool == Tool.DRAW
            setEnabled(s, !simMode)
            s.invalidate()
        }

        val s = v.sim
        status.text = if (s != null) {
            "${if (v.running) "RUNNING" else "PAUSED"}  tick ${s.tick}  nets ${s.netCount}  parts ${s.compCount}"
        } else {
            val sel = v.selection?.let { "  sel ${it.width}×${it.height}" } ?: ""
            "EDIT ${v.board.w}×${v.board.h}  ${v.tool.name.lowercase()}  ${Ink.name[v.ink]}$sel" + (if (currentName.isNotEmpty()) "  [$currentName]" else "")
        }
    }

    // ==== menu and dialogs ======================================================================

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add("New board")
        m.menu.add("Board size…")
        m.menu.add("Clock period…")
        m.menu.add("Export to file…")
        m.menu.add("Import from file…")
        m.menu.add("Load demo")
        m.menu.add("Help")
        m.setOnMenuItemClickListener {
            when (it.title) {
                "New board" -> confirm("Clear the whole board?") {
                    boardView.stopSimulation()
                    boardView.board.snapshot()
                    boardView.board.clearAll()
                    boardView.boardChanged()
                    currentName = ""
                    updateUi()
                }
                "Board size…" -> showSizeDialog()
                "Clock period…" -> showClockDialog()
                "Export to file…" -> startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_TITLE, (currentName.ifEmpty { "circuit" }) + ".vcb")
                }, REQ_EXPORT)
                "Import from file…" -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, REQ_IMPORT)
                "Load demo" -> { boardView.replaceBoard(demoBoard()); currentName = ""; updateUi() }
                "Help" -> showHelp()
            }
            true
        }
        m.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        try {
            when (requestCode) {
                REQ_EXPORT -> {
                    contentResolver.openOutputStream(uri)!!.use { boardView.board.save(it) }
                    toast("Exported")
                }
                REQ_IMPORT -> {
                    val b = Board()
                    contentResolver.openInputStream(uri)!!.use { b.load(it) }
                    boardView.replaceBoard(b)
                    currentName = ""
                    updateUi()
                }
            }
        } catch (e: Exception) {
            toast("Failed: ${e.message}")
        }
    }

    private fun showSaveDialog() {
        val input = EditText(this).apply {
            setText(currentName)
            hint = "Circuit name"
            inputType = InputType.TYPE_CLASS_TEXT
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Save circuit")
            .setView(padded(input))
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                if (name.isEmpty()) { toast("Enter a name"); return@setPositiveButton }
                val f = File(circuitsDir, "$name.vcb")
                val save = {
                    try {
                        f.outputStream().use { boardView.board.save(it) }
                        currentName = name
                        updateUi()
                        toast("Saved \"$name\"")
                    } catch (e: Exception) {
                        toast("Save failed: ${e.message}")
                    }
                }
                if (f.exists() && name != currentName) confirm("Overwrite \"$name\"?", save) else save()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun savedCircuits(): List<File> =
        (circuitsDir.listFiles { f -> f.name.endsWith(".vcb") } ?: emptyArray()).sortedByDescending { it.lastModified() }

    private fun showLoadDialog() {
        val files = savedCircuits()
        if (files.isEmpty()) { toast("No saved circuits yet"); return }
        val names = files.map { it.nameWithoutExtension }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Load circuit")
            .setItems(names) { _, which ->
                try {
                    val b = Board()
                    files[which].inputStream().use { b.load(it) }
                    boardView.replaceBoard(b)
                    currentName = names[which]
                    updateUi()
                } catch (e: Exception) {
                    toast("Load failed: ${e.message}")
                }
            }
            .setNeutralButton("Delete…") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Delete circuit")
                    .setItems(names) { _, which ->
                        confirm("Delete \"${names[which]}\"?") {
                            files[which].delete()
                            if (currentName == names[which]) { currentName = ""; updateUi() }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSizeDialog() {
        val b = boardView.board
        val labels = boardSizes.map { n -> "$n × $n" + if (n == b.w && n == b.h) "   ✓ current" else "" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Board size (now ${b.w} × ${b.h})")
            .setItems(labels) { _, which ->
                val n = boardSizes[which]
                if (n == b.w && n == b.h) return@setItems
                val used = b.usedBounds()
                val dx = (n - b.w) / 2
                val dy = (n - b.h) / 2
                val fits = used == null ||
                    (used.x0 + dx >= 0 && used.y0 + dy >= 0 && used.x1 + dx <= n && used.y1 + dy <= n)
                if (fits) {
                    boardView.resizeBoard(n, n)
                } else {
                    confirm("Parts of the circuit don't fit in $n × $n and will be cut off. Continue? (Undo brings them back.)") {
                        boardView.resizeBoard(n, n)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showClockDialog() {
        val input = EditText(this).apply {
            setText(boardView.board.clockPeriod.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Clock: ticks on / ticks off")
            .setView(padded(input))
            .setPositiveButton("OK") { _, _ ->
                val p = input.text.toString().toIntOrNull()
                if (p == null || p < 1) { toast("Enter a number ≥ 1"); return@setPositiveButton }
                boardView.board.clockPeriod = p
                if (boardView.sim != null) toast("Applies after the simulation is restarted")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showHelp() {
        val text = TextView(this).apply {
            text = HELP
            setTextColor(0xFFE0E4EC.toInt())
            textSize = 14f
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("How it works")
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton("OK", null)
            .show()
    }

    private fun padded(v: View) = FrameLayout(this).apply {
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(v)
    }

    private fun confirm(message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("Yes") { _, _ -> action() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ==== demo ==================================================================================

    /** A clock and a switch feeding an XOR gate that drives a display, plus a constant and a NOT. */
    private fun demoBoard(): Board {
        val b = Board()
        val x = b.w / 2 - 12
        val y = b.h / 2 - 4
        fun rect(x0: Int, y0: Int, w: Int, h: Int, ink: Int) {
            for (j in 0 until h) for (i in 0 until w) b.set(x + x0 + i, y + y0 + j, ink)
        }
        val gold = Ink.TRACE_FIRST
        val red = Ink.TRACE_FIRST + 1
        val cyan = Ink.TRACE_FIRST + 4

        // Clock -> W -> gold trace -> R -> XOR
        rect(0, 0, 2, 2, Ink.CLOCK)
        rect(2, 0, 1, 1, Ink.WRITE)
        rect(3, 0, 6, 1, gold)
        rect(9, 0, 1, 1, Ink.READ)
        // Switch -> W -> red trace -> R -> XOR (bottom input)
        rect(0, 3, 2, 2, Ink.SWITCH)
        rect(2, 4, 1, 1, Ink.WRITE)
        rect(3, 4, 6, 1, red)
        rect(8, 2, 1, 2, red)
        rect(9, 2, 1, 1, Ink.READ)
        // XOR -> W -> cyan trace -> R -> display
        rect(10, 0, 2, 3, Ink.XOR)
        rect(12, 1, 1, 1, Ink.WRITE)
        rect(13, 1, 5, 1, cyan)
        rect(18, 1, 1, 1, Ink.READ)
        rect(19, 0, 3, 3, Ink.LED)
        // Constant 1 -> NOT -> second display (stays dark)
        rect(0, 7, 2, 2, Ink.CONST1)
        rect(2, 7, 1, 1, Ink.WRITE)
        rect(3, 7, 6, 1, gold)
        rect(9, 7, 1, 1, Ink.READ)
        rect(10, 7, 2, 2, Ink.NOT)
        rect(12, 7, 1, 1, Ink.WRITE)
        rect(13, 7, 5, 1, cyan)
        rect(18, 7, 1, 1, Ink.READ)
        rect(19, 7, 3, 3, Ink.LED)
        return b
    }

    companion object {
        private const val REQ_EXPORT = 1
        private const val REQ_IMPORT = 2

        private val HELP = """
            Draw circuits with colored "inks" on the grid. Touching cells of the same ink become one part.

            EDITING
            • One finger: use the current tool (Draw, Erase, Select, Pan).
            • Two fingers: pinch to zoom, drag to move. +, −, Fit buttons zoom too.
            • Select a rectangle, then Copy / Cut / Delete. Paste shows the copy as a ghost: drag it, Rotate / Flip, then Place.
            • ↶ / ↷ undo and redo.
            • ⋮ → Board size makes the grid bigger or smaller (up to 1024 × 1024); the circuit stays centred.

            TRACES AND CONNECTORS
            • Traces carry signals. Traces of different colors never join.
            • A part reads its inputs through R (Read) cells and writes its output through W (Write) cells: part → W → trace → R → part.
            • R and W join traces of any color. A trace is ON when any part writing to it is ON.

            GATES (A = active inputs, T = all inputs)
            • Buffer: A > 0      • NOT: A = 0
            • AND: A > 0 and A = T   • NAND: not AND
            • OR: A > 0      • NOR: A = 0
            • XOR: A is odd      • XNOR: A is even
            Every part has a delay of 1 tick.

            I/O
            • Const 0 / Const 1: fixed signals.
            • Clock: on/off every N ticks (⋮ → Clock period).
            • Switch: tap it while the simulation runs to toggle.
            • Display: lights up when any input is ON. Draw any shape (e.g. 7-segment digits).

            COMPACTING
            • Cross: one cell where a horizontal and a vertical line cross without joining.
            • Tunnel: a line entering a tunnel cell continues after the next tunnel cell in the same direction, skipping everything between.
            • Mesh: all cells of the same ink touching any mesh, anywhere, are joined.
            • Bus: like mesh, but only along one connected bus of one color.

            SIMULATION
            • ▶ Run compiles the board and starts; ■ Stop returns to editing. ⏸ pauses, Step advances one tick. The slider sets ticks per second.

            FILES
            • Save / Load keep circuits inside the app. ⋮ → Export / Import to share .vcb files. The board is autosaved when you leave the app.
        """.trimIndent()
    }
}

/** A palette button showing an ink's color, glyph and name. */
class SwatchView(context: Context, val ink: Int) : View(context) {
    var active = false
    private val density = context.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.color[ink] }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF2B33D.toInt(); style = Paint.Style.STROKE; strokeWidth = 3 * density
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC000000.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true; textSize = 14 * density
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB8C0CC.toInt(); textAlign = Paint.Align.CENTER; textSize = 10 * density
    }
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        val s = 32 * density
        box.set((width - s) / 2, 4 * density, (width + s) / 2, 4 * density + s)
        canvas.drawRoundRect(box, 4 * density, 4 * density, fill)
        if (active) canvas.drawRoundRect(box, 4 * density, 4 * density, outline)
        val g = Ink.glyph[ink]
        if (g.isNotEmpty()) canvas.drawText(g, box.centerX(), box.centerY() - (glyph.ascent() + glyph.descent()) / 2, glyph)
        canvas.drawText(Ink.name[ink], width / 2f, height - 4 * density, label)
    }
}
