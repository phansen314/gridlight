package gridlight.bench

/**
 * Same storage as [PackedBoard] (LongArray(16), identical bit layout), but every cell location
 * comes from a precomputed table instead of k/6 and (k%6)*10, and the naked-single scan works a
 * word at a time.
 *
 * A location is a single "bit position" pos = word*64 + shift: `w[pos ushr 6] ushr pos` reads the
 * cell, because JVM long shifts only use the low 6 bits of the count. One table load per read.
 */
class PackedTableBoard private constructor(private val w: LongArray) : MutableBoard<PackedTableBoard> {
    constructor() : this(LongArray(16))

    private fun at(pos: Int): Cell = Cell((w[pos ushr 6] ushr pos).toInt() and 0x3FF)

    override fun cell(k: Int): Cell = at(POS[k].toInt())
    override fun get(r: Int, c: Int): Cell = at(POS[r * 9 + c].toInt())
    override fun unitCell(u: Int, i: Int): Cell = at(UNIT_POS[u * 9 + i].toInt())
    override fun peerCell(k: Int, j: Int): Cell = at(PEER_POS[k * 20 + j].toInt())
    override fun candidates(k: Int): Int = cell(k).bits and Cell.DIGITS
    override fun isPlaced(k: Int): Boolean = cell(k).bits and Cell.TAG != 0
    override fun isGiven(k: Int): Boolean = (w[14 + (k ushr 6)] ushr k) and 1L != 0L

    private fun setRaw(k: Int, v: Int) {
        val pos = POS[k].toInt()
        val i = pos ushr 6
        w[i] = (w[i] and (0x3FFL shl pos).inv()) or (v.toLong() shl pos)
    }

    override fun place(k: Int, d: Int) {
        require(d in 0..8) { "digit $d" }
        setRaw(k, Cell.TAG or (1 shl d))
    }

    override fun placeGiven(k: Int, d: Int) {
        place(k, d)
        w[14 + (k ushr 6)] = w[14 + (k ushr 6)] or (1L shl k)
    }

    override fun setCandidates(k: Int, mask: Int) {
        require(mask and Cell.DIGITS.inv() == 0) { "mask $mask" }
        setRaw(k, mask)
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
        return true
    }

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
        return changed != 0L
    }

    /**
     * SWAR naked singles. Per word, with G = bit 9 of every lane as a borrow guard:
     *   x  = digit bits of each lane
     *   y  = (x|G) - 1 per lane    -> bit 9 survives iff x != 0; low bits = x-1
     *   z  = x & y                 -> 0 iff x has at most one bit
     *   hit = (x != 0) & (z == 0) & unplaced
     * The guard stops borrows crossing lanes. Only lanes that hit are visited.
     */
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
                val b = java.lang.Long.numberOfTrailingZeros(hits) // 9, 19, ..., 59
                hits = hits and (hits - 1)
                val lo = b - 9
                val d = Integer.numberOfTrailingZeros((x ushr lo).toInt())
                out[n++] = ((i * 6 + lo / 10) shl 4) or d
            }
        }
        return n
    }

    override fun copy(): PackedTableBoard = PackedTableBoard(w.copyOf())
    override fun copyFrom(other: PackedTableBoard) = System.arraycopy(other.w, 0, w, 0, 16)
    override fun contentEquals(other: PackedTableBoard): Boolean = w.contentEquals(other.w)
    override fun equals(other: Any?): Boolean = other is PackedTableBoard && w.contentEquals(other.w)
    override fun hashCode(): Int = w.contentHashCode()
    override fun toString(): String = "PackedTableBoard(${toString81()})"

    internal fun rawWord(i: Int): Long = w[i]

    companion object {
        const val LANE_ONES: Long = PackedBoard.LANE_ONES
        const val GUARD: Long = LANE_ONES shl 9           // tag bit of every lane
        const val DIGIT_LANES: Long = LANE_ONES * 0x1FF   // bits 0..8 of every lane

        private fun pos(k: Int): Short = ((k / 6) * 64 + (k % 6) * 10).toShort() // max 13*64+50 = 882

        /** POS[k] = bit position of cell k. 162 bytes. */
        @JvmField val POS: ShortArray = ShortArray(81) { pos(it) }
        /** UNIT_POS[u*9+i] = bit position of the i-th cell of unit u. 486 bytes. */
        @JvmField val UNIT_POS: ShortArray = ShortArray(27 * 9) { pos(Geo.UNITS[it]) }
        /** PEER_POS[k*20+j] = bit position of the j-th peer of k. 3.2 KB. */
        @JvmField val PEER_POS: ShortArray = ShortArray(81 * 20) { pos(Geo.PEERS[it]) }
    }
}
