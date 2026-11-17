package gridlight.engine

enum class RegionKind { ROW, COLUMN, BOX }

/**
 * A row, column or box (D13): built once per [Geometry] and shared; equality is identity. Holds its own data,
 * so region reads need no geometry lookup. Slot i is the region's i-th cell in ascending cell index (D6, D14).
 */
class Region internal constructor(
    /** Its place in [Geometry.regions]. */
    val index: Int,
    val kind: RegionKind,
    /** Its number within its kind, 0..8 (row 1 is 0). */
    internal val ordinalInKind: Int,
    /** Its [CellSet.lo] / [CellSet.hi] words are the region's masks, in the shared 54 | 27 split (D7). */
    val cells: CellSet,
    /** The 9 cells, ascending; slot i is `cellIndices[i]`. Read by the inline [forEachCell]. */
    @PublishedApi internal val cellIndices: IntArray,
) {
    /** Bit position of slot i's cell in the packed cell words (see [Layout.pos]); for the board kernel. */
    internal val bitPos: ShortArray = ShortArray(9) { Layout.pos(cellIndices[it]) }

    operator fun get(slot: RegionSlot): CellIndex = CellIndex(cellIndices[slot.i])

    /** The cells of [cells] that lie in this region, in the region's own slot numbering (D6). */
    fun slotsOf(cells: CellSet): RegionSlotSet {
        val loBits = java.lang.Long.compress(cells.lo, this.cells.lo)
        val hiBits = java.lang.Long.compress(cells.hi, this.cells.hi)
        val shift = java.lang.Long.bitCount(this.cells.lo)
        return RegionSlotSet((loBits or (hiBits shl shift)).toInt())
    }

    inline fun forEachCell(action: (c: CellIndex) -> Unit) {
        for (k in cellIndices) action(CellIndex(k))
    }

    override fun toString(): String = when (kind) {
        RegionKind.ROW -> "row ${ordinalInKind + 1}"
        RegionKind.COLUMN -> "column ${ordinalInKind + 1}"
        RegionKind.BOX -> "box ${ordinalInKind + 1}"
    }
}
