package gridlight.engine

/** Fixed layout of the packed cell words, shared by every geometry (the 9×9 grid, D13). */
internal object Layout {
    /** Bit 10*i set for lanes i = 0..5 of a cell word. */
    const val LANE_ONES: Long = 0x0004010040100401L

    /** Bit position of cell [k] in the packed cell array: word (k / 6) * 64 plus lane (k % 6) * 10. */
    fun pos(k: Int): Short = ((k / 6) * 64 + (k % 6) * 10).toShort()

    /** `POS[k]` = bit position of cell k. */
    @JvmField val POS: ShortArray = ShortArray(81) { pos(it) }
}

/**
 * Regions, peers and the tables derived from them. One instance, [STANDARD]: rows, columns and boxes of a
 * 9×9 grid. Tables are private or internal, never public mutable arrays.
 */
class Geometry private constructor() {
    /** Rows (index 0..8), then columns (9..17), then boxes (18..26). */
    val regions: List<Region>
    val rows: List<Region>
    val columns: List<Region>
    val boxes: List<Region>

    private val regionsOfCell: Array<List<Region>>
    private val peerSets: Array<CellSet>

    /** `peerCells[k*20 + j]` = j-th peer of cell k, ascending. */
    internal val peerCells: IntArray

    /** `peerPos[k*20 + j]` = bit position of the j-th peer of k in the packed cell words. */
    internal val peerPos: ShortArray

    /** `peerLanes[k*14 + i]`: lane-low bit set for every peer of k in cell word i. */
    internal val peerLanes: LongArray

    init {
        fun set(ks: IntArray): CellSet {
            var s = CellSet.EMPTY
            for (k in ks) s += CellIndex(k)
            return s
        }

        val made = ArrayList<Region>(27)
        for (r in 0..8) made += Region(r, RegionKind.ROW, r, set(IntArray(9) { r * 9 + it }), IntArray(9) { r * 9 + it })
        for (c in 0..8) {
            val ks = IntArray(9) { it * 9 + c }
            made += Region(9 + c, RegionKind.COLUMN, c, set(ks), ks)
        }
        for (b in 0..8) {
            val r0 = b / 3 * 3
            val c0 = b % 3 * 3
            val ks = IntArray(9) { (r0 + it / 3) * 9 + c0 + it % 3 }.also { it.sort() }
            made += Region(18 + b, RegionKind.BOX, b, set(ks), ks)
        }
        // Immutable copies: callers can't cast and mutate the shared geometry.
        regions = java.util.List.copyOf(made)
        rows = java.util.List.copyOf(made.subList(0, 9))
        columns = java.util.List.copyOf(made.subList(9, 18))
        boxes = java.util.List.copyOf(made.subList(18, 27))

        regionsOfCell = Array(81) { k -> java.util.List.copyOf(listOf(rows[k / 9], columns[k % 9], boxes[k / 27 * 3 + k % 9 / 3])) }
        peerSets = Array(81) { k ->
            var s = CellSet.EMPTY
            for (r in regionsOfCell[k]) s += r.cells
            s - CellIndex(k)
        }
        peerCells = IntArray(81 * 20)
        peerPos = ShortArray(81 * 20)
        peerLanes = LongArray(81 * 14)
        for (k in 0..80) {
            var j = 0
            peerSets[k].forEach { p ->
                peerCells[k * 20 + j] = p.k
                peerPos[k * 20 + j] = Layout.pos(p.k)
                peerLanes[k * 14 + p.k / 6] = peerLanes[k * 14 + p.k / 6] or (1L shl ((p.k % 6) * 10))
                j++
            }
            check(j == 20) { "cell $k has $j peers" }
        }
    }

    /** The row, column and box of [c], in that order. */
    fun regionsOf(c: CellIndex): List<Region> = regionsOfCell[c.k]

    fun peers(c: CellIndex): CellSet = peerSets[c.k]
    fun sees(a: CellIndex, b: CellIndex): Boolean = b in peerSets[a.k]
    fun commonPeers(a: CellIndex, b: CellIndex): CellSet = peerSets[a.k] and peerSets[b.k]
    fun intersection(a: Region, b: Region): CellSet = a.cells and b.cells

    companion object {
        val STANDARD: Geometry = Geometry()
    }
}
