package gridlight.bench

/**
 * Baseline: ShortArray(81), same 10-bit encoding per element. Given mask held as two Long fields
 * (cells 0..63 in g0, 64..80 in g1) rather than a BooleanArray: no second array object to allocate,
 * copy, compare or hash, it matches the packed layout bit-for-bit, and it is 16 bytes vs ~104.
 */
class ShortBoard private constructor(
    private val c: ShortArray,
    private var g0: Long,
    private var g1: Long,
) : MutableBoard<ShortBoard> {
    constructor() : this(ShortArray(81), 0L, 0L)

    override fun cell(k: Int): Cell = Cell(c[k].toInt())
    override fun get(r: Int, c: Int): Cell = cell(r * 9 + c)
    override fun candidates(k: Int): Int = c[k].toInt() and Cell.DIGITS
    override fun isPlaced(k: Int): Boolean = c[k].toInt() and Cell.TAG != 0
    override fun isGiven(k: Int): Boolean = ((if (k < 64) g0 else g1) ushr k) and 1L != 0L

    override fun place(k: Int, d: Int) {
        require(d in 0..8) { "digit $d" }
        c[k] = (Cell.TAG or (1 shl d)).toShort()
    }

    override fun placeGiven(k: Int, d: Int) {
        place(k, d)
        if (k < 64) g0 = g0 or (1L shl k) else g1 = g1 or (1L shl k)
    }

    override fun setCandidates(k: Int, mask: Int) {
        require(mask and Cell.DIGITS.inv() == 0) { "mask $mask" }
        c[k] = mask.toShort()
    }

    override fun eliminate(k: Int, d: Int): Boolean {
        val cell = c[k].toInt()
        val keep = ((cell ushr 9) and 1) - 1
        val bit = (1 shl d) and keep and cell
        if (bit == 0) return false
        c[k] = (cell and bit.inv()).toShort()
        return true
    }

    /** Peer-table loop, branchless per cell. */
    override fun eliminateFromPeers(k: Int, d: Int): Boolean {
        val base = k * 20
        val bitD = 1 shl d
        var changed = 0
        for (j in 0 until 20) {
            val p = Geo.PEERS[base + j]
            val cell = c[p].toInt()
            val rm = bitD and (((cell ushr 9) and 1) - 1)
            changed = changed or (cell and rm)
            c[p] = (cell and rm.inv()).toShort()
        }
        return changed != 0
    }

    override fun copy(): ShortBoard = ShortBoard(c.copyOf(), g0, g1)
    override fun copyFrom(other: ShortBoard) {
        System.arraycopy(other.c, 0, c, 0, 81)
        g0 = other.g0; g1 = other.g1
    }
    override fun contentEquals(other: ShortBoard): Boolean =
        g0 == other.g0 && g1 == other.g1 && c.contentEquals(other.c)
    override fun equals(other: Any?): Boolean = other is ShortBoard && contentEquals(other)
    override fun hashCode(): Int = (c.contentHashCode() * 31 + g0.hashCode()) * 31 + g1.hashCode()
    override fun toString(): String = "ShortBoard(${toString81()})"
}
