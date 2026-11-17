package gridlight.engine

/**
 * Immutable board state: cells, givens and the open-cell mask, nothing else (D11, D18). Holds no geometry:
 * operations that need peers or regions take one. A change returns a new board, or `this` when nothing changed.
 * Per cell state (D16, D19):
 *
 *     operation          open                filled              given
 *     place(c, d)        fills it            replaces its digit  no-op
 *     clear(c)           no-op               open, all nine      no-op
 *     eliminate(...)     removes candidates  no-op               no-op
 *
 * `place` never touches other cells; peer elimination is the explicit [eliminateFromPeers] /
 * [eliminateFilledFromPeers].
 */
class Board private constructor(private val w: LongArray) {
    private var hash = 0 // 0 = not computed yet; a benign race recomputes the same value

    // ── reads ───────────────────────────────────────────────

    operator fun get(c: CellIndex): Cell = Cell(Kernel.raw(w, c.k))
    operator fun get(row: Row, column: Column): Cell = get(CellIndex.at(row, column))
    fun isGiven(c: CellIndex): Boolean = Kernel.isGiven(w, c.k)
    fun givenCells(): CellSet = Kernel.givenCells(w)
    fun openCells(): CellSet = Kernel.openCells(w)
    fun filledCells(): CellSet = CellSet.ALL - Kernel.openCells(w)

    /** Open cells with [d] as a candidate. */
    fun cellsWithCandidate(d: Digit): CellSet = Kernel.cellsWithCandidate(w, d.value - 1)
    fun filledIn(region: Region): DigitSet = DigitSet(Kernel.filledIn(w, region))
    fun missingIn(region: Region): DigitSet = DigitSet(Kernel.missingIn(w, region))

    /** Digits filled more than once in [region]. */
    fun duplicatesIn(region: Region): DigitSet = DigitSet(Kernel.duplicatesIn(w, region))

    // ── single changes ──────────────────────────────────────

    private inline fun change(f: (LongArray) -> Boolean): Board {
        val a = w.copyOf()
        return if (f(a)) Board(a) else this
    }

    fun place(c: CellIndex, d: Digit): Board = change { Kernel.place(it, c.k, d.value - 1) }
    fun clear(c: CellIndex): Board = change { Kernel.clear(it, c.k) }
    fun eliminate(c: CellIndex, d: Digit): Board = change { Kernel.eliminate(it, c.k, d.value - 1) }
    fun eliminate(cells: CellSet, d: Digit): Board = change { Kernel.eliminateCells(it, cells, d.value - 1) }
    fun eliminate(c: CellIndex, ds: DigitSet): Board = change { Kernel.eliminateDigits(it, c.k, ds.bits) }

    /** Removes [d] from the candidates of every open peer of [c]. */
    fun eliminateFromPeers(c: CellIndex, d: Digit, geometry: Geometry): Board =
        change { Kernel.eliminateFromPeers(it, c.k, d.value - 1, geometry) }

    /** Removes every filled cell's digit from its open peers. */
    fun eliminateFilledFromPeers(geometry: Geometry): Board = change { Kernel.eliminateFilledFromPeers(it, geometry) }

    // ── batched changes ─────────────────────────────────────

    fun transient(): Transient = Transient(this, w.copyOf())

    /** Runs [block] on a transient copy; returns this board itself when nothing changed. */
    inline fun edit(block: Transient.() -> Unit): Board {
        val t = transient()
        t.block()
        return t.persistent()
    }

    // ── value semantics ─────────────────────────────────────

    override fun equals(other: Any?): Boolean = other === this || (other is Board && Kernel.sameKey(w, other.w))

    override fun hashCode(): Int {
        var h = hash
        if (h == 0) {
            h = Kernel.hash(w)
            hash = h
        }
        return h
    }

    /** The grid (given `5 `, entered `5'`, open `. `) then the open cells with fewer than nine candidates. */
    override fun toString(): String = buildString {
        append("Board\n")
        for (r in 0..8) {
            for (col in 0..8) {
                val k = r * 9 + col
                val c = get(CellIndex(k))
                if (c.isFilled) append(c.filled.value).append(if (isGiven(CellIndex(k))) ' ' else '\'') else append(". ")
                if (col % 3 == 2 && col != 8) append("| ")
            }
            append('\n')
            if (r % 3 == 2 && r != 8) append("------+-------+------\n")
        }
        append("candidates (open cells with fewer than nine):")
        openCells().forEach { c ->
            val cs = get(c).candidates
            if (cs.size < 9) append("\n  ").append(c).append(' ').append(cs)
        }
    }

    companion object {
        val EMPTY: Board = Board(Kernel.empty())

        /** A board with the givens added in [block]; every open cell keeps all nine candidates (D19). */
        fun build(block: BoardBuilder.() -> Unit): Board {
            val b = BoardBuilder()
            b.block()
            return Board(b.finish())
        }

        /** Reads the grid only (see [parseBoard]); throws [ParseException]. Givens are locked, open cells get all nine candidates. */
        fun parse(text: String): Board = parseBoard(text)

        internal fun wrap(w: LongArray): Board = Board(w)
    }
}

/** The only way givens enter a board, besides parse (D12). Open cells keep all nine candidates (D19). */
class BoardBuilder internal constructor() {
    private var w: LongArray? = Kernel.empty()

    /** Throws [IllegalArgumentException] if that cell already has a given, [IllegalStateException] after the build. */
    fun addGiven(row: Row, column: Column, given: Digit) {
        val a = checkNotNull(w) { "builder used after build" }
        val k = CellIndex.at(row, column).k
        require(!Kernel.isGiven(a, k)) { "${CellIndex(k)} already has a given" }
        Kernel.setGiven(a, k, given.value - 1)
    }

    internal fun finish(): LongArray {
        val a = checkNotNull(w) { "builder used after build" }
        w = null
        return a
    }
}

/**
 * Mutable batch of changes started by [Board.transient]; same per-state rules as [Board], but each mutator
 * changes it in place and returns true if anything changed. [persistent] hands the array over (or the original
 * board when nothing changed); any later use throws [IllegalStateException].
 */
class Transient internal constructor(private val origin: Board, words: LongArray) {
    private var w: LongArray? = words
    private var changed = false

    private fun a(): LongArray = checkNotNull(w) { "Transient used after persistent()" }

    private inline fun write(f: (LongArray) -> Boolean): Boolean {
        val r = f(a())
        if (r) changed = true
        return r
    }

    operator fun get(c: CellIndex): Cell = Cell(Kernel.raw(a(), c.k))
    fun isGiven(c: CellIndex): Boolean = Kernel.isGiven(a(), c.k)
    fun openCells(): CellSet = Kernel.openCells(a())

    fun place(c: CellIndex, d: Digit): Boolean = write { Kernel.place(it, c.k, d.value - 1) }
    fun clear(c: CellIndex): Boolean = write { Kernel.clear(it, c.k) }
    fun setCandidates(c: CellIndex, ds: DigitSet): Boolean = write { Kernel.setCandidates(it, c.k, ds.bits) }
    fun eliminate(c: CellIndex, d: Digit): Boolean = write { Kernel.eliminate(it, c.k, d.value - 1) }
    fun eliminate(c: CellIndex, ds: DigitSet): Boolean = write { Kernel.eliminateDigits(it, c.k, ds.bits) }
    fun eliminate(cells: CellSet, d: Digit): Boolean = write { Kernel.eliminateCells(it, cells, d.value - 1) }
    fun eliminateFromPeers(c: CellIndex, d: Digit, geometry: Geometry): Boolean =
        write { Kernel.eliminateFromPeers(it, c.k, d.value - 1, geometry) }
    fun eliminateFilledFromPeers(geometry: Geometry): Boolean = write { Kernel.eliminateFilledFromPeers(it, geometry) }

    /** Open cells with exactly one candidate: raw cell indices into [out] (size ≥ 81); returns the count. */
    fun scanNakedSingles(out: IntArray): Int = Kernel.scanNakedSingles(a(), out)

    /** Hidden singles, packed `(cell shl 4) or (digit - 1)` into [out] (size ≥ 243); returns the count. */
    fun scanHiddenSingles(geometry: Geometry, out: IntArray): Int = Kernel.scanHiddenSingles(a(), geometry, out)

    /** The resulting board: the original itself when nothing changed. This transient is unusable afterwards. */
    fun persistent(): Board {
        val arr = a()
        w = null
        return if (changed) Board.wrap(arr) else origin
    }
}
