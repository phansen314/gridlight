package gridlight.bench

/** Static Sudoku geometry. k = r*9 + c. Units 0..8 rows, 9..17 cols, 18..26 boxes. */
object Geo {
    /** UNITS[u*9 + i] = i-th cell of unit u. */
    @JvmField val UNITS: IntArray = IntArray(27 * 9).also { a ->
        for (r in 0 until 9) for (c in 0 until 9) {
            val k = r * 9 + c
            a[r * 9 + c] = k
            a[(9 + c) * 9 + r] = k
            val b = (r / 3) * 3 + c / 3
            val i = (r % 3) * 3 + c % 3
            a[(18 + b) * 9 + i] = k
        }
    }

    /** PEERS[k*20 + j] = j-th peer of cell k (20 distinct cells sharing a unit, ascending). */
    @JvmField val PEERS: IntArray = IntArray(81 * 20).also { a ->
        for (k in 0 until 81) {
            val r = k / 9; val c = k % 9
            val set = sortedSetOf<Int>()
            for (j in 0 until 81) {
                if (j == k) continue
                val rj = j / 9; val cj = j % 9
                if (rj == r || cj == c || (rj / 3 == r / 3 && cj / 3 == c / 3)) set += j
            }
            check(set.size == 20)
            set.forEachIndexed { j, p -> a[k * 20 + j] = p }
        }
    }
}
