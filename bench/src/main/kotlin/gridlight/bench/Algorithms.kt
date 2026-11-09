package gridlight.bench

import gridlight.bench.Geo.UNITS

/*
 * Representation-independent algorithms, written once against MutableBoard. Each JMH fork uses
 * exactly one implementation, so the interface calls in here stay monomorphic and get inlined.
 */

/** Parse an 81-char puzzle ('1'..'9' given; '0' or '.' empty) and compute initial candidates. */
fun <B : MutableBoard<B>> parseInto(b: B, s: String): B {
    require(s.length == 81) { "length ${s.length}" }
    for (k in 0 until 81) {
        when (val ch = s[k]) {
            in '1'..'9' -> b.placeGiven(k, ch - '1')
            '0', '.' -> b.setCandidates(k, Cell.DIGITS)
            else -> throw IllegalArgumentException("bad char '$ch' at $k")
        }
    }
    initCandidates(b)
    return b
}

/** For every unplaced cell: candidates = all digits not placed in any peer. */
fun <B : MutableBoard<B>> initCandidates(b: B) {
    for (k in 0 until 81) {
        if (b.isPlaced(k)) continue
        var used = 0
        for (j in 0 until 20) {
            val p = b.peerCell(k, j)
            if (p.isPlaced) used = used or p.mask
        }
        b.setCandidates(k, Cell.DIGITS and used.inv())
    }
}

fun MutableBoard<*>.toString81(): String = buildString(81) {
    for (k in 0 until 81) {
        val c = cell(k)
        append(if (c.isPlaced) '1' + c.digit else '.')
    }
}

/** out[u] = digits (mask) not yet placed in unit u. Branchless per cell. Returns XOR of masks. */
fun <B : MutableBoard<B>> unitMissing(b: B, out: IntArray): Int {
    var acc = 0
    for (u in 0 until 27) {
        var placed = 0
        val base = u * 9
        for (i in 0 until 9) {
            val bits = b.unitCell(u, i).bits
            placed = placed or (bits and -(bits ushr 9)) // keep mask only if tag set
        }
        val m = Cell.DIGITS and placed.inv()
        out[u] = m
        acc = acc xor (m shl (u and 7))
    }
    return acc
}

/** Unplaced cells with exactly one candidate. out[i] = k shl 4 or d. Returns count. */
fun <B : MutableBoard<B>> nakedSingles(b: B, out: IntArray): Int {
    var n = 0
    for (k in 0 until 81) {
        val c = b.cell(k)
        if (!c.isPlaced && Integer.bitCount(c.mask) == 1) out[n++] = (k shl 4) or c.digit
    }
    return n
}

/**
 * Hidden singles, the literal way: per unit, per digit, count unplaced cells that have it
 * (skipping digits already placed in the unit). out[i] = k shl 4 or d (may repeat across units).
 */
fun <B : MutableBoard<B>> hiddenSinglesNaive(b: B, out: IntArray): Int {
    var n = 0
    for (u in 0 until 27) {
        val base = u * 9
        for (d in 0 until 9) {
            val bit = 1 shl d
            var count = 0
            var where = -1
            var placedHere = false
            for (i in 0 until 9) {
                val c = b.unitCell(u, i)
                if (c.mask and bit != 0) {
                    if (c.isPlaced) placedHere = true else { count++; where = i }
                }
            }
            if (!placedHere && count == 1) out[n++] = (UNITS[base + where] shl 4) or d
        }
    }
    return n
}

/**
 * Hidden singles via bit-sliced counting: one pass over the unit's 9 cells gives once/twice masks,
 * then each digit seen exactly once is located. Same output as [hiddenSinglesNaive].
 */
fun <B : MutableBoard<B>> hiddenSinglesBitSliced(b: B, out: IntArray): Int {
    var n = 0
    for (u in 0 until 27) {
        val base = u * 9
        var once = 0
        var twice = 0
        var placed = 0
        for (i in 0 until 9) {
            val bits = b.unitCell(u, i).bits
            val tag = -(bits ushr 9) // -1 placed, 0 not
            val m = bits and Cell.DIGITS
            placed = placed or (m and tag)
            val cand = m and tag.inv()
            twice = twice or (once and cand)
            once = once or cand
        }
        var exactly = once and twice.inv() and placed.inv()
        while (exactly != 0) {
            val d = Integer.numberOfTrailingZeros(exactly)
            exactly = exactly and (exactly - 1)
            for (i in 0 until 9) {
                val c = b.unitCell(u, i)
                if (!c.isPlaced && (c.mask ushr d) and 1 != 0) { out[n++] = (UNITS[base + i] shl 4) or d; break }
            }
        }
    }
    return n
}

/** Result of [solveSingles]: placements made and final status. */
object SolveStatus {
    const val SOLVED = 0
    const val STUCK = 1
    const val CONTRADICTION = 2
    fun name(s: Int) = when (s) { SOLVED -> "SOLVED"; STUCK -> "STUCK"; else -> "CONTRADICTION" }
}

/**
 * Singles-only solver: repeat { naked singles (scan, place, propagate); if none, hidden singles }
 * until solved, stuck or a contradiction (an unplaced cell with no candidates, or a hidden single
 * whose cell lost the digit). Returns (placements shl 2) or status. [scratch] needs >= 243 slots.
 */
fun <B : MutableBoard<B>> solveSingles(b: B, scratch: IntArray): Int {
    var placements = 0
    while (true) {
        var progress = false
        var unplaced = 0
        for (k in 0 until 81) {
            val c = b.cell(k)
            if (c.isPlaced) continue
            val m = c.mask
            if (m == 0) return (placements shl 2) or SolveStatus.CONTRADICTION
            if (m and (m - 1) == 0) {
                val d = Integer.numberOfTrailingZeros(m)
                b.place(k, d)
                b.eliminateFromPeers(k, d)
                placements++
                progress = true
            } else unplaced++
        }
        if (progress) continue
        if (unplaced == 0) return (placements shl 2) or SolveStatus.SOLVED
        val n = b.scanHiddenSingles(scratch) // bit-sliced by default; planes board overrides
        for (i in 0 until n) {
            val k = scratch[i] ushr 4
            val d = scratch[i] and 15
            if (b.isPlaced(k)) continue // already placed via another unit this round
            if ((b.candidates(k) ushr d) and 1 == 0) return (placements shl 2) or SolveStatus.CONTRADICTION
            b.place(k, d)
            b.eliminateFromPeers(k, d)
            placements++
            progress = true
        }
        if (!progress) return (placements shl 2) or SolveStatus.STUCK
    }
}

/**
 * "Derive on demand" baseline for digit planes: build all 9 planes (same split as PlanesBoard:
 * out[2d] = cells 0..53, out[2d+1] = cells 54..80) from the cells in one pass. Unplaced candidates only.
 */
fun <B : MutableBoard<B>> buildPlanes(b: B, out: LongArray) {
    java.util.Arrays.fill(out, 0, 18, 0L)
    for (k in 0 until 81) {
        val bits = b.cell(k).bits
        var m = bits and Cell.DIGITS and ((bits ushr 9) - 1) // 0 if placed
        val half = if (k < 54) 0 else 1
        val bit = 1L shl (if (k < 54) k else k - 54)
        while (m != 0) {
            val d = Integer.numberOfTrailingZeros(m)
            m = m and (m - 1)
            out[2 * d + half] = out[2 * d + half] or bit
        }
    }
}
