package gridlight.bench

/**
 * Minimal mutable board API shared by both representations (the "MutableBoard" of the scope notes; the
 * immutable copy-on-write wrapper is out of scope for this benchmark).
 *
 * Digits are 0..8 throughout (digit 1..9 in the UI). Cells k are 0..80.
 *
 * F-bounded so copy()/copyFrom()/contentEquals() are typed per implementation; benchmarks call
 * through this interface but each JMH fork only ever instantiates one implementation, so the
 * call sites are monomorphic (see REPORT.md, "Dispatch").
 */
interface MutableBoard<B : MutableBoard<B>> {
    fun cell(k: Int): Cell
    operator fun get(r: Int, c: Int): Cell
    /** Low 9 bits of the cell (candidates or one-hot placed digit). */
    fun candidates(k: Int): Int
    fun isPlaced(k: Int): Boolean
    fun isGiven(k: Int): Boolean

    /** Place digit d (0..8) at k. Does not touch peers. Does not change the given bit. */
    fun place(k: Int, d: Int)
    /** Place digit d at k and mark it given. */
    fun placeGiven(k: Int, d: Int)
    /** Make k unplaced with the given candidate mask (0..0x1FF). Does not change the given bit. */
    fun setCandidates(k: Int, mask: Int)

    /** Remove candidate d from k. No-op (returns false) on a placed cell, so the tag invariant holds. */
    fun eliminate(k: Int, d: Int): Boolean
    /** Remove candidate d from all 20 peers of k (placed peers untouched). True if anything changed. */
    fun eliminateFromPeers(k: Int, d: Int): Boolean

    /** i-th cell (0..8) of unit u. Overridable so a board can use a precomputed position table. */
    fun unitCell(u: Int, i: Int): Cell = cell(Geo.UNITS[u * 9 + i])
    /** j-th peer (0..19) of cell k. */
    fun peerCell(k: Int, j: Int): Cell = cell(Geo.PEERS[k * 20 + j])

    /**
     * Unplaced cells with exactly one candidate, ascending k; out[i] = k shl 4 or d. Returns count.
     * Default reads cell by cell; a board may override with a word-at-a-time version.
     */
    fun scanNakedSingles(out: IntArray): Int {
        var n = 0
        for (k in 0 until 81) {
            val c = cell(k)
            if (!c.isPlaced && Integer.bitCount(c.mask) == 1) out[n++] = (k shl 4) or c.digit
        }
        return n
    }

    /**
     * Hidden singles, same output and order as [hiddenSinglesBitSliced] (which is the default).
     * A board with digit planes overrides it.
     */
    fun scanHiddenSingles(out: IntArray): Int {
        @Suppress("UNCHECKED_CAST")
        return hiddenSinglesBitSliced(this as B, out)
    }

    /** How many unplaced cells in unit u still have candidate d: the core query for fish/subsets. */
    fun placesInUnit(u: Int, d: Int): Int {
        var n = 0
        for (i in 0 until 9) {
            val bits = unitCell(u, i).bits
            n += ((bits ushr d) and 1) and ((bits ushr 9) xor 1)
        }
        return n
    }

    fun copy(): B
    fun copyFrom(other: B)
    fun contentEquals(other: B): Boolean
}
