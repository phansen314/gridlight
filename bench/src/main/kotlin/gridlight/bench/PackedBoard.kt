package gridlight.bench

/**
 * Decided design (scope notes): LongArray(16). Cell k lives in word k/6 at shift (k%6)*10 (6 cells/word,
 * words 0..13; the top 4 bits of each word stay zero). Words 14..15 are the 81-bit given mask
 * (bit k of word 14 + k/64).
 */
class PackedBoard private constructor(private val w: LongArray) : MutableBoard<PackedBoard> {
    constructor() : this(LongArray(16))

    override fun cell(k: Int): Cell = Cell((w[k / 6] ushr ((k % 6) * 10)).toInt() and 0x3FF)
    override fun get(r: Int, c: Int): Cell = cell(r * 9 + c)
    override fun candidates(k: Int): Int = (w[k / 6] ushr ((k % 6) * 10)).toInt() and Cell.DIGITS
    override fun isPlaced(k: Int): Boolean = (w[k / 6] ushr ((k % 6) * 10 + 9)) and 1L != 0L
    // JVM long shifts use only the low 6 bits of the count, so `ushr k` == `ushr (k and 63)`.
    override fun isGiven(k: Int): Boolean = (w[14 + (k ushr 6)] ushr k) and 1L != 0L

    private fun setRaw(k: Int, v: Int) {
        val i = k / 6
        val sh = (k % 6) * 10
        w[i] = (w[i] and (0x3FFL shl sh).inv()) or (v.toLong() shl sh)
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
        val i = k / 6
        val sh = (k % 6) * 10
        val word = w[i]
        val cell = (word ushr sh).toInt()
        val keep = ((cell ushr 9) and 1) - 1 // -1 if unplaced, 0 if placed
        val bit = (1 shl d) and keep and cell
        if (bit == 0) return false
        w[i] = word and (bit.toLong() shl sh).inv()
        return true
    }

    /** SWAR: one masked AND-NOT per word, skipping placed lanes, no per-cell branches. */
    override fun eliminateFromPeers(k: Int, d: Int): Boolean {
        val base = k * 14
        var changed = 0L
        for (i in 0 until 14) {
            val word = w[i]
            val unplaced = ((word ushr 9) and LANE_ONES) xor LANE_ONES
            val rm = (PEER_LANES[base + i] and unplaced) shl d
            changed = changed or (word and rm)
            w[i] = word and rm.inv()
        }
        return changed != 0L
    }

    override fun copy(): PackedBoard = PackedBoard(w.copyOf())
    override fun copyFrom(other: PackedBoard) = System.arraycopy(other.w, 0, w, 0, 16)
    override fun contentEquals(other: PackedBoard): Boolean = w.contentEquals(other.w)
    override fun equals(other: Any?): Boolean = other is PackedBoard && w.contentEquals(other.w)
    override fun hashCode(): Int = w.contentHashCode()
    override fun toString(): String = "PackedBoard(${toString81()})"

    /** For tests: raw word access (spare-bit checks). */
    internal fun rawWord(i: Int): Long = w[i]

    companion object {
        /** Bit 10*i set for lanes i = 0..5. */
        const val LANE_ONES: Long = 0x0004010040100401L // bits 0,10,20,30,40,50
        /** PEER_LANES[k*14 + i]: LANE bit (bit 0 of each lane) set for every peer of k in word i. */
        @JvmField val PEER_LANES: LongArray = LongArray(81 * 14).also { a ->
            for (k in 0 until 81) for (j in 0 until 20) {
                val p = Geo.PEERS[k * 20 + j]
                a[k * 14 + p / 6] = a[k * 14 + p / 6] or (1L shl ((p % 6) * 10))
            }
        }
        /** Bits 60..63 of each cell word. */
        const val SPARE_MASK: Long = 0xFL shl 60
    }
}
