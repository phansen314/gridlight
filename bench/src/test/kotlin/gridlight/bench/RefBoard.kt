package gridlight.bench

/** Deliberately naive reference semantics: one Int per cell, BooleanArray for givens. */
class RefBoard {
    val cells = IntArray(81) // 10-bit cell
    val given = BooleanArray(81)

    fun place(k: Int, d: Int) { cells[k] = Cell.TAG or (1 shl d) }
    fun placeGiven(k: Int, d: Int) { place(k, d); given[k] = true }
    fun setCandidates(k: Int, m: Int) { cells[k] = m }
    fun eliminate(k: Int, d: Int): Boolean {
        val c = cells[k]
        if (c and Cell.TAG != 0) return false
        if (c and (1 shl d) == 0) return false
        cells[k] = c and (1 shl d).inv()
        return true
    }
    fun eliminateFromPeers(k: Int, d: Int): Boolean {
        val r = k / 9; val c = k % 9
        var changed = false
        for (j in 0 until 81) {
            if (j == k) continue
            val rj = j / 9; val cj = j % 9
            if (rj == r || cj == c || (rj / 3 == r / 3 && cj / 3 == c / 3)) changed = eliminate(j, d) or changed
        }
        return changed
    }
}
