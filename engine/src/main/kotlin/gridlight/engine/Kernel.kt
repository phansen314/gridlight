package gridlight.engine

/**
 * Storage kernel over `LongArray(18)`, shared by `Board` and `Transient` (D18: no digit planes).
 *
 *  - words 0..13: cells, 6 per word, 10 bits each. Bit 9 = filled tag; bits 0..8 = the one-hot digit (filled)
 *    or the candidates (open). Cell k sits at bit position [Layout.pos]. Bits 60..63 and the three lanes of
 *    word 13 past cell 80 are always zero.
 *  - words 14..15: given mask in the 54 | 27 band split of [CellSet] (D16).
 *  - words 16..17: open-cell mask in the same split (D17): bit set iff the cell's tag bit is 0. Derived state,
 *    kept in step by every write.
 *  - equality and hash cover words 0..15 ([KEY_WORDS]); the open mask is excluded.
 *
 * Digits are 0-based (`d0` = digit - 1) inside the kernel. Every mutator returns whether anything changed.
 * Per cell state (D19):
 *
 *     operation           open               filled              given
 *     place               fills it           replaces its digit  no-op
 *     clear               no-op              open, all nine      no-op
 *     eliminate*          removes candidates no-op               no-op
 *     setCandidates       replaces           no-op               no-op
 *
 * No write touches any cell but its own, except [eliminateFromPeers] / [eliminateFilledFromPeers], which the
 * caller asks for explicitly.
 */
internal object Kernel {
    const val SIZE = 18
    const val CELL_WORDS = 14
    const val GIVEN = 14
    const val OPEN = 16
    const val KEY_WORDS = 16
    const val TAG = 1 shl 9
    const val DIGITS = 0x1FF
    private const val LANE_ONES = Layout.LANE_ONES
    private const val GUARD = LANE_ONES shl 9
    private const val DIGIT_LANES = LANE_ONES * 0x1FF
    private const val BAND = CellSet.BAND
    private const val LO_MASK = (1L shl BAND) - 1
    private const val HI_MASK = (1L shl (81 - BAND)) - 1

    // ── construction ────────────────────────────────────────

    /** Every cell open with all nine candidates, no givens. */
    fun empty(): LongArray {
        val w = LongArray(SIZE)
        for (i in 0 until CELL_WORDS) w[i] = DIGIT_LANES
        w[CELL_WORDS - 1] = DIGIT_LANES and ((1L shl 30) - 1) // only cells 78..80 in the last word
        w[OPEN] = LO_MASK
        w[OPEN + 1] = HI_MASK
        return w
    }

    /** Makes cell [k] a given with digit [d0]; no peer elimination (see [computeCandidates]). */
    fun setGiven(w: LongArray, k: Int, d0: Int) {
        setRaw(w, k, TAG or (1 shl d0))
        setMaskBit(w, GIVEN, k, true)
    }

    /** Removes every filled cell's digit (givens included) from the candidates of its open peers (D19). */
    fun eliminateFilledFromPeers(w: LongArray, geometry: Geometry): Boolean {
        var changed = false
        for (k in 0..80) {
            val c = raw(w, k)
            if (c and TAG != 0 && eliminateFromPeers(w, k, Integer.numberOfTrailingZeros(c and DIGITS), geometry)) changed = true
        }
        return changed
    }

    // ── reads ───────────────────────────────────────────────

    fun raw(w: LongArray, k: Int): Int {
        val p = Layout.POS[k].toInt()
        return (w[p ushr 6] ushr p).toInt() and 0x3FF
    }

    fun isGiven(w: LongArray, k: Int): Boolean = maskBit(w, GIVEN, k)
    fun givenCells(w: LongArray): CellSet = CellSet(w[GIVEN], w[GIVEN + 1])
    fun openCells(w: LongArray): CellSet = CellSet(w[OPEN], w[OPEN + 1])

    /** Open cells with candidate [d0] (D19): one `Long.compress` per cell word (D18), then the open mask. */
    fun cellsWithCandidate(w: LongArray, d0: Int): CellSet {
        var lo = 0L
        var hi = 0L
        // 54 = 9 words * 6 cells, so the band split falls on a word boundary.
        for (i in 0 until 9) lo = lo or (java.lang.Long.compress(w[i] ushr d0, LANE_ONES) shl (6 * i))
        for (i in 9 until CELL_WORDS) hi = hi or (java.lang.Long.compress(w[i] ushr d0, LANE_ONES) shl (6 * (i - 9)))
        return CellSet(lo and w[OPEN], hi and w[OPEN + 1])
    }

    /** Digit bits of the filled cells of [region]. */
    fun filledIn(w: LongArray, region: Region): Int {
        var acc = 0
        for (p in region.bitPos) {
            val c = (w[p.toInt() ushr 6] ushr p.toInt()).toInt()
            if (c and TAG != 0) acc = acc or (c and DIGITS)
        }
        return acc
    }

    fun missingIn(w: LongArray, region: Region): Int = DIGITS and filledIn(w, region).inv()

    /** Digit bits filled in more than one cell of [region]. */
    fun duplicatesIn(w: LongArray, region: Region): Int {
        var seen = 0
        var dup = 0
        for (p in region.bitPos) {
            val c = (w[p.toInt() ushr 6] ushr p.toInt()).toInt()
            if (c and TAG != 0) {
                val d = c and DIGITS
                dup = dup or (seen and d)
                seen = seen or d
            }
        }
        return dup
    }

    // ── writes ──────────────────────────────────────────────

    /** Fills [k] with [d0], or replaces a filled cell's digit; peers untouched (D19). No-op on a given. */
    fun place(w: LongArray, k: Int, d0: Int): Boolean {
        if (isGiven(w, k)) return false
        val v = TAG or (1 shl d0)
        if (raw(w, k) == v) return false
        setRaw(w, k, v)
        return true
    }

    /** Filled (not given) → open with all nine candidates (D19). */
    fun clear(w: LongArray, k: Int): Boolean {
        if (raw(w, k) and TAG == 0 || isGiven(w, k)) return false
        setRaw(w, k, DIGITS)
        return true
    }

    /** Replaces an open cell's candidates by [mask] (digit bits only). No-op on filled cells and givens (D19). */
    fun setCandidates(w: LongArray, k: Int, mask: Int): Boolean {
        val c = raw(w, k)
        if (c and TAG != 0 || c == mask) return false
        setRaw(w, k, mask)
        return true
    }

    fun eliminate(w: LongArray, k: Int, d0: Int): Boolean = eliminateDigits(w, k, 1 shl d0)

    /** Clears the digit bits [mask] from open cell [k]; filled cells and givens are left alone. */
    fun eliminateDigits(w: LongArray, k: Int, mask: Int): Boolean {
        val p = Layout.POS[k].toInt()
        val i = p ushr 6
        val c = (w[i] ushr p).toInt()
        if (c and TAG != 0) return false // filled (a given is filled)
        val bits = c and mask and DIGITS
        if (bits == 0) return false
        w[i] = w[i] and (bits.toLong() shl p).inv()
        return true
    }

    fun eliminateCells(w: LongArray, cells: CellSet, d0: Int): Boolean {
        var changed = false
        cells.forEach { if (eliminate(w, it.k, d0)) changed = true }
        return changed
    }

    /** Word-at-a-time: clears [d0] from every open peer of [k]. */
    fun eliminateFromPeers(w: LongArray, k: Int, d0: Int, geometry: Geometry): Boolean {
        val base = k * 14
        val lanes = geometry.peerLanes
        var changed = 0L
        for (i in 0 until CELL_WORDS) {
            val word = w[i]
            val open = ((word ushr 9) and LANE_ONES) xor LANE_ONES
            val rm = (lanes[base + i] and open) shl d0
            changed = changed or (word and rm)
            w[i] = word and rm.inv()
        }
        return changed != 0L
    }

    // ── scans ───────────────────────────────────────────────

    /**
     * Open cells with exactly one candidate, ascending; writes raw cell indices into [out] (size ≥ 81) and
     * returns the count. SWAR per word: x = digit bits of each lane; y = (x|G) - 1 per lane (G = bit 9 guard
     * against cross-lane borrow); z = x & y is 0 iff x has at most one bit.
     */
    fun scanNakedSingles(w: LongArray, out: IntArray): Int {
        var n = 0
        for (i in 0 until CELL_WORDS) {
            val word = w[i]
            val x = word and DIGIT_LANES
            val y = (x or GUARD) - LANE_ONES
            val nonZero = y and GUARD
            val z = x and y and DIGIT_LANES
            val atMostOne = ((z or GUARD) - LANE_ONES).inv() and GUARD
            val open = (word and GUARD) xor GUARD
            var hits = nonZero and atMostOne and open
            while (hits != 0L) {
                val b = java.lang.Long.numberOfTrailingZeros(hits) // 9, 19, ..., 59
                hits = hits and (hits - 1)
                out[n++] = i * 6 + (b - 9) / 10
            }
        }
        return n
    }

    /**
     * Hidden singles: an open cell that is the only open cell holding digit d in some region that has no d
     * filled. Writes `(cell shl 4) or d0` into [out] (size ≥ 243), ordered by digit then cell, each pair once
     * even when several regions agree. Returns the count. Computed from the cells (D18).
     */
    fun scanHiddenSingles(w: LongArray, geometry: Geometry, out: IntArray): Int {
        val regions = geometry.regions
        val filled = IntArray(regions.size) { filledIn(w, regions[it]) }
        var n = 0
        for (d0 in 0..8) {
            val candidates = cellsWithCandidate(w, d0)
            var found = CellSet.EMPTY
            for (r in regions.indices) {
                if (filled[r] and (1 shl d0) != 0) continue
                val here = candidates and regions[r].cells
                if (here.size == 1) found += here.first
            }
            found.forEach { out[n++] = (it.k shl 4) or d0 }
        }
        return n
    }

    // ── equality, hash, invariants ──────────────────────────

    fun sameKey(a: LongArray, b: LongArray): Boolean = java.util.Arrays.equals(a, 0, KEY_WORDS, b, 0, KEY_WORDS)

    fun hash(w: LongArray): Int {
        var h = 0x9E3779B97F4A7C15uL.toLong()
        for (i in 0 until KEY_WORDS) {
            h = (h xor w[i]) * -0x61c8864680b583ebL
            h = h xor (h ushr 29)
        }
        return (h xor (h ushr 32)).toInt()
    }

    /** First broken invariant, or null: tag ⇒ one digit bit, spare bits zero, open mask in step, givens filled. */
    fun violation(w: LongArray): String? {
        if (w.size != SIZE) return "size ${w.size}"
        for (i in 0 until CELL_WORDS) if (w[i] ushr 60 != 0L) return "word $i spare bits"
        if (w[CELL_WORDS - 1] ushr 30 != 0L) return "lanes past cell 80 not zero"
        var open = CellSet.EMPTY
        var given = CellSet.EMPTY
        for (k in 0..80) {
            val c = raw(w, k)
            if (c and TAG != 0) {
                if (Integer.bitCount(c and DIGITS) != 1) return "cell $k tagged with ${Integer.bitCount(c and DIGITS)} digit bits"
            } else open += CellIndex(k)
            if (isGiven(w, k)) {
                given += CellIndex(k)
                if (c and TAG == 0) return "given cell $k is open"
            }
        }
        if (w[GIVEN] and LO_MASK.inv() != 0L || w[GIVEN + 1] and HI_MASK.inv() != 0L) return "given spare bits"
        if (w[OPEN] and LO_MASK.inv() != 0L || w[OPEN + 1] and HI_MASK.inv() != 0L) return "open spare bits"
        if (open != openCells(w)) return "open mask ${openCells(w)} != cells without tag $open"
        if (given != givenCells(w)) return "given mask mismatch"
        return null
    }

    // ── helpers ─────────────────────────────────────────────

    /** Writes the 10-bit content of [k] and keeps the open mask in step (open iff no tag). */
    private fun setRaw(w: LongArray, k: Int, v: Int) {
        val p = Layout.POS[k].toInt()
        val i = p ushr 6
        w[i] = (w[i] and (0x3FFL shl p).inv()) or (v.toLong() shl p)
        setMaskBit(w, OPEN, k, v and TAG == 0)
    }

    private fun maskBit(w: LongArray, base: Int, k: Int): Boolean =
        if (k < BAND) (w[base] ushr k) and 1L != 0L else (w[base + 1] ushr (k - BAND)) and 1L != 0L

    private fun setMaskBit(w: LongArray, base: Int, k: Int, on: Boolean) {
        val i = if (k < BAND) base else base + 1
        val bit = 1L shl (if (k < BAND) k else k - BAND)
        w[i] = if (on) w[i] or bit else w[i] and bit.inv()
    }
}
