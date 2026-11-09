package gridlight.bench

import gridlight.bench.PackedTableBoard.Companion.DIGIT_LANES
import gridlight.bench.PackedTableBoard.Companion.GUARD
import gridlight.bench.PackedTableBoard.Companion.LANE_ONES
import gridlight.bench.PackedTableBoard.Companion.PEER_POS
import gridlight.bench.PackedTableBoard.Companion.POS
import gridlight.bench.PackedTableBoard.Companion.UNIT_POS

/**
 * The "board with digit planes kept in step" design (scope notes; research sketch
 * board-with-planes.kt). One LongArray(34):
 *
 *   words  0..13  cells, same 10-bit encoding and layout as PackedBoard (reads via PackedTable's tables)
 *   words 14..15  given mask
 *   words 16..33  digit planes: digit d (0..8) -> words 16+2d (cells 0..53) and 17+2d (cells 54..80),
 *                 so every row and every box lies inside one long. Planes hold UNPLACED candidates only.
 *
 * Every write updates cells and planes together. equals/hashCode cover words 0..15 only (planes
 * are derived). The hash is the same 31-polynomial over those 16 words as the other boards use,
 * so hash benchmarks compare representations, not hash functions.
 */
class PlanesBoard private constructor(private val w: LongArray) : MutableBoard<PlanesBoard> {
    constructor() : this(LongArray(SIZE))

    private fun at(pos: Int): Cell = Cell((w[pos ushr 6] ushr pos).toInt() and 0x3FF)

    override fun cell(k: Int): Cell = at(POS[k].toInt())
    override fun get(r: Int, c: Int): Cell = at(POS[r * 9 + c].toInt())
    override fun unitCell(u: Int, i: Int): Cell = at(UNIT_POS[u * 9 + i].toInt())
    override fun peerCell(k: Int, j: Int): Cell = at(PEER_POS[k * 20 + j].toInt())
    override fun candidates(k: Int): Int = cell(k).bits and Cell.DIGITS
    override fun isPlaced(k: Int): Boolean = cell(k).bits and Cell.TAG != 0
    override fun isGiven(k: Int): Boolean = (w[GIVEN + (k ushr 6)] ushr k) and 1L != 0L

    private fun writeCell(k: Int, v: Int) {
        val pos = POS[k].toInt()
        val i = pos ushr 6
        w[i] = (w[i] and (0x3FFL shl pos).inv()) or (v.toLong() shl pos)
    }

    override fun place(k: Int, d: Int) {
        require(d in 0..8) { "digit $d" }
        writeCell(k, Cell.TAG or (1 shl d))
        // A placed cell leaves every plane (9 read-modify-writes, as in the sketch).
        val base = PLANE_BASE[k]
        val keep = PLANE_BIT[k].inv()
        for (d0 in 0 until 9) { val i = base + 2 * d0; w[i] = w[i] and keep }
    }

    override fun placeGiven(k: Int, d: Int) {
        place(k, d)
        w[GIVEN + (k ushr 6)] = w[GIVEN + (k ushr 6)] or (1L shl k)
    }

    override fun setCandidates(k: Int, mask: Int) {
        require(mask and Cell.DIGITS.inv() == 0) { "mask $mask" }
        writeCell(k, mask)
        val base = PLANE_BASE[k]
        val bit = PLANE_BIT[k]
        for (d0 in 0 until 9) {
            val i = base + 2 * d0
            val set = -((mask ushr d0) and 1).toLong() and bit // bit if d0 in mask, else 0
            w[i] = (w[i] and bit.inv()) or set
        }
    }

    override fun eliminate(k: Int, d: Int): Boolean {
        val pos = POS[k].toInt()
        val i = pos ushr 6
        val word = w[i]
        val cell = (word ushr pos).toInt()
        val keep = ((cell ushr 9) and 1) - 1
        val bit = (1 shl d) and keep and cell
        if (bit == 0) return false
        w[i] = word and (bit.toLong() shl pos).inv()
        val p = PLANE_BASE[k] + 2 * d
        w[p] = w[p] and PLANE_BIT[k].inv()
        return true
    }

    /**
     * Cells: PackedTable's word-at-a-time AND-NOT, placed cells skipped. Planes: two ANDs, safe
     * without masking because placed cells are never in a plane.
     */
    override fun eliminateFromPeers(k: Int, d: Int): Boolean {
        val base = k * 14
        var changed = 0L
        for (i in 0 until 14) {
            val word = w[i]
            val unplaced = ((word ushr 9) and LANE_ONES) xor LANE_ONES
            val rm = (PackedBoard.PEER_LANES[base + i] and unplaced) shl d
            changed = changed or (word and rm)
            w[i] = word and rm.inv()
        }
        val p = PLANES + 2 * d
        w[p] = w[p] and PEER_LO[k].inv()
        w[p + 1] = w[p + 1] and PEER_HI[k].inv()
        return changed != 0L
    }

    /** Same word-at-a-time scan as PackedTableBoard (the cell words are identical). */
    override fun scanNakedSingles(out: IntArray): Int {
        var n = 0
        for (i in 0 until 14) {
            val word = w[i]
            val x = word and DIGIT_LANES
            val y = (x or GUARD) - LANE_ONES
            val nonZero = y and GUARD
            val z = x and y and DIGIT_LANES
            val atMostOne = ((z or GUARD) - LANE_ONES).inv() and GUARD
            val unplaced = (word and GUARD) xor GUARD
            var hits = nonZero and atMostOne and unplaced
            while (hits != 0L) {
                val b = java.lang.Long.numberOfTrailingZeros(hits)
                hits = hits and (hits - 1)
                val lo = b - 9
                val d = Integer.numberOfTrailingZeros((x ushr lo).toInt())
                out[n++] = ((i * 6 + lo / 10) shl 4) or d
            }
        }
        return n
    }

    override fun placesInUnit(u: Int, d: Int): Int {
        val p = PLANES + 2 * d
        return java.lang.Long.bitCount(w[p] and UNIT_LO[u]) + java.lang.Long.bitCount(w[p + 1] and UNIT_HI[u])
    }

    /**
     * Hidden singles from the planes: popcount(plane AND unit mask) == 1. Planes only hold unplaced
     * candidates, so "d already placed in this unit" can't be seen from them; that case (only
     * possible on a board that hasn't been propagated, e.g. a conflicting user digit) is checked
     * from the cells, but only for the rare hits. Same output and order as hiddenSinglesBitSliced.
     */
    override fun scanHiddenSingles(out: IntArray): Int {
        var n = 0
        for (u in 0 until 27) {
            val ulo = UNIT_LO[u]
            val uhi = UNIT_HI[u]
            for (d in 0 until 9) {
                val p = PLANES + 2 * d
                val lo = w[p] and ulo
                val hi = w[p + 1] and uhi
                if (java.lang.Long.bitCount(lo) + java.lang.Long.bitCount(hi) != 1) continue
                if (placedInUnit(u, d)) continue
                val k = if (lo != 0L) java.lang.Long.numberOfTrailingZeros(lo) else BAND_SPLIT + java.lang.Long.numberOfTrailingZeros(hi)
                out[n++] = (k shl 4) or d
            }
        }
        return n
    }

    private fun placedInUnit(u: Int, d: Int): Boolean {
        val want = Cell.TAG or (1 shl d)
        for (i in 0 until 9) if (unitCell(u, i).bits == want) return true
        return false
    }

    override fun copy(): PlanesBoard = PlanesBoard(w.copyOf())
    override fun copyFrom(other: PlanesBoard) = System.arraycopy(other.w, 0, w, 0, SIZE)
    override fun contentEquals(other: PlanesBoard): Boolean = java.util.Arrays.equals(w, 0, KEY_WORDS, other.w, 0, KEY_WORDS)
    override fun equals(other: Any?): Boolean = other is PlanesBoard && contentEquals(other)
    override fun hashCode(): Int {
        var h = 1
        for (i in 0 until KEY_WORDS) h = 31 * h + w[i].hashCode() // == LongArray(16).contentHashCode()
        return h
    }
    override fun toString(): String = "PlanesBoard(${toString81()})"

    internal fun rawWord(i: Int): Long = w[i]

    companion object {
        const val SIZE = 34
        const val GIVEN = 14
        const val PLANES = 16
        const val KEY_WORDS = 16
        const val BAND_SPLIT = 54

        /** First plane word (digit 0) holding cell k: 16 for cells 0..53, 17 for 54..80. */
        @JvmField val PLANE_BASE = IntArray(81) { PLANES + if (it < BAND_SPLIT) 0 else 1 }
        @JvmField val PLANE_BIT = LongArray(81) { 1L shl (if (it < BAND_SPLIT) it else it - BAND_SPLIT) }
        @JvmField val UNIT_LO = LongArray(27)
        @JvmField val UNIT_HI = LongArray(27)
        @JvmField val PEER_LO = LongArray(81)
        @JvmField val PEER_HI = LongArray(81)

        init {
            for (u in 0 until 27) for (i in 0 until 9) {
                val k = Geo.UNITS[u * 9 + i]
                if (k < BAND_SPLIT) UNIT_LO[u] = UNIT_LO[u] or PLANE_BIT[k] else UNIT_HI[u] = UNIT_HI[u] or PLANE_BIT[k]
            }
            for (k in 0 until 81) for (j in 0 until 20) {
                val p = Geo.PEERS[k * 20 + j]
                if (p < BAND_SPLIT) PEER_LO[k] = PEER_LO[k] or PLANE_BIT[p] else PEER_HI[k] = PEER_HI[k] or PLANE_BIT[p]
            }
        }
    }
}
