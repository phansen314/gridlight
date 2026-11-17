package gridlight.engine

/** Naive reference for the kernel: IntArray cells (tag bit 9 + digit bits), peers from r/c/box arithmetic. */
class RefBoard {
    val cells = IntArray(81) { 0x1FF }
    val given = BooleanArray(81)

    companion object {
        fun peers(k: Int): List<Int> {
            val r = k / 9; val c = k % 9
            return (0..80).filter { p ->
                p != k && (p / 9 == r || p % 9 == c || (p / 27 == r / 3 && p % 9 / 3 == c / 3))
            }
        }
        fun region(kind: RegionKind, n: Int): List<Int> = (0..80).filter {
            when (kind) {
                RegionKind.ROW -> it / 9 == n
                RegionKind.COLUMN -> it % 9 == n
                RegionKind.BOX -> it / 27 * 3 + it % 9 / 3 == n
            }
        }
    }

    fun isOpen(k: Int) = cells[k] and 0x200 == 0
    fun openSet() = (0..80).filter { isOpen(it) }
    fun setGiven(k: Int, d0: Int) { cells[k] = 0x200 or (1 shl d0); given[k] = true }
    fun computeCandidates() {
        for (k in 0..80) if (given[k]) for (p in peers(k)) if (isOpen(p)) cells[p] = cells[p] and (cells[k] and 0x1FF).inv()
    }

    private fun set(k: Int, v: Int): Boolean { val ch = cells[k] != v; cells[k] = v; return ch }

    fun place(k: Int, d0: Int): Boolean {
        if (given[k]) return false
        var ch = set(k, 0x200 or (1 shl d0))
        for (p in peers(k)) if (isOpen(p) && cells[p] and (1 shl d0) != 0) { cells[p] = cells[p] and (1 shl d0).inv(); ch = true }
        return ch
    }
    fun setCandidates(k: Int, mask: Int): Boolean = if (given[k]) false else set(k, mask)
    fun eliminateDigits(k: Int, mask: Int): Boolean {
        if (!isOpen(k) || cells[k] and mask == 0) return false
        cells[k] = cells[k] and mask.inv(); return true
    }
    fun eliminate(k: Int, d0: Int) = eliminateDigits(k, 1 shl d0)

    fun withCandidate(d0: Int) = (0..80).filter { cells[it] and (1 shl d0) != 0 }
    fun filledIn(cs: List<Int>) = cs.filter { !isOpen(it) }.fold(0) { a, k -> a or (cells[k] and 0x1FF) }
    fun duplicatesIn(cs: List<Int>): Int {
        var dup = 0
        for (d0 in 0..8) if (cs.count { !isOpen(it) && cells[it] and (1 shl d0) != 0 } > 1) dup = dup or (1 shl d0)
        return dup
    }
    fun nakedSingles() = (0..80).filter { isOpen(it) && Integer.bitCount(cells[it]) == 1 }
    fun hiddenSingles(): List<Int> {
        val out = mutableListOf<Int>()
        val regs = (0..8).flatMap { listOf(region(RegionKind.ROW, it), region(RegionKind.COLUMN, it), region(RegionKind.BOX, it)) }
        for (d0 in 0..8) {
            val found = sortedSetOf<Int>()
            for (r in regs) {
                if (r.any { !isOpen(it) && cells[it] and (1 shl d0) != 0 }) continue
                val here = r.filter { isOpen(it) && cells[it] and (1 shl d0) != 0 }
                if (here.size == 1) found += here[0]
            }
            found.forEach { out += (it shl 4) or d0 }
        }
        return out
    }
}
