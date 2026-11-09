package gridlight.bench

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BoardAgreementTest {

    /** Planes rebuilt naively from the cell words: bit k of digit d's plane iff k unplaced and has d. */
    private fun rebuildPlanes(b: PlanesBoard): LongArray {
        val out = LongArray(18)
        for (k in 0 until 81) {
            val c = b.cell(k)
            if (c.isPlaced) continue
            for (d in 0 until 9) if ((c.mask shr d) and 1 != 0) {
                if (k < 54) out[2 * d] = out[2 * d] or (1L shl k) else out[2 * d + 1] = out[2 * d + 1] or (1L shl (k - 54))
            }
        }
        return out
    }

    private fun assertPlanes(p: PackedBoard, pl: PlanesBoard, ctx: String) {
        for (i in 0 until 16) assertEquals(p.rawWord(i), pl.rawWord(i), "$ctx planes board word $i differs from packed")
        val want = rebuildPlanes(pl)
        for (i in 0 until 18) assertEquals(want[i], pl.rawWord(16 + i), "$ctx plane word ${16 + i} out of step")
    }

    private fun assertSame3(ref: RefBoard, p: PackedBoard, s: ShortBoard, ctx: String, t: PackedTableBoard? = null, pl: PlanesBoard? = null) {
        if (pl != null) {
            assertPlanes(p, pl, ctx)
            for (k in 0 until 81) {
                assertEquals(p.cell(k), pl.cell(k), "$ctx planes cell $k"); assertEquals(p[k / 9, k % 9], pl[k / 9, k % 9])
                assertEquals(p.candidates(k), pl.candidates(k)); assertEquals(p.isPlaced(k), pl.isPlaced(k)); assertEquals(p.isGiven(k), pl.isGiven(k))
            }
        }
        if (t != null) {
            for (i in 0 until 16) assertEquals(p.rawWord(i), t.rawWord(i), "$ctx table board word $i differs from packed")
            for (k in 0 until 81) {
                assertEquals(p.cell(k), t.cell(k), "$ctx table cell $k"); assertEquals(p[k / 9, k % 9], t[k / 9, k % 9])
                assertEquals(p.candidates(k), t.candidates(k)); assertEquals(p.isPlaced(k), t.isPlaced(k)); assertEquals(p.isGiven(k), t.isGiven(k))
            }
        }
        for (k in 0 until 81) {
            val r = k / 9; val c = k % 9
            assertEquals(ref.cells[k], p.cell(k).bits, "$ctx packed cell $k")
            assertEquals(ref.cells[k], s.cell(k).bits, "$ctx short cell $k")
            assertEquals(p.cell(k), p[r, c], "$ctx packed get(r,c) $k")
            assertEquals(s.cell(k), s[r, c], "$ctx short get(r,c) $k")
            assertEquals(ref.cells[k] and Cell.DIGITS, p.candidates(k), "$ctx packed cands $k")
            assertEquals(ref.cells[k] and Cell.DIGITS, s.candidates(k), "$ctx short cands $k")
            val placed = ref.cells[k] and Cell.TAG != 0
            assertEquals(placed, p.isPlaced(k), "$ctx packed placed $k")
            assertEquals(placed, s.isPlaced(k), "$ctx short placed $k")
            assertEquals(ref.given[k], p.isGiven(k), "$ctx packed given $k")
            assertEquals(ref.given[k], s.isGiven(k), "$ctx short given $k")
        }
        for (i in 0 until 14) assertEquals(0L, p.rawWord(i) and PackedBoard.SPARE_MASK, "$ctx spare bits word $i")
        val givenWords = p.rawWord(14) to p.rawWord(15)
        assertEquals(0L, givenWords.second ushr 17, "$ctx given mask beyond bit 80")
    }

    @Test
    fun geometry() {
        for (k in 0 until 81) {
            val peers = (0 until 20).map { Geo.PEERS[k * 20 + it] }.toSet()
            assertEquals(20, peers.size)
            assertFalse(k in peers)
        }
        val counts = IntArray(81)
        Geo.UNITS.forEach { counts[it]++ }
        assertTrue(counts.all { it == 3 })
        for (k in 0 until 81) {
            val lanes = (0 until 14).sumOf { java.lang.Long.bitCount(PackedBoard.PEER_LANES[k * 14 + it]) }
            assertEquals(20, lanes, "peer lanes of $k")
        }
    }

    @Test
    fun puzzlesAreValidAndParseIdentically() {
        for (s in Puzzles.ALL) {
            val norm = s.replace('0', '.')
            val p = parseInto(PackedBoard(), s)
            val q = parseInto(ShortBoard(), s)
            assertEquals(norm, p.toString81())
            assertEquals(norm, q.toString81())
            // givens consistent: no placed digit is a candidate-killer for another placed peer
            for (k in 0 until 81) if (p.isPlaced(k)) for (j in 0 until 20) {
                val pk = Geo.PEERS[k * 20 + j]
                if (p.isPlaced(pk)) assertNotEquals(p.cell(k), p.cell(pk), "conflicting givens in $s at $k/$pk")
            }
            val ref = RefBoard()
            for (k in 0 until 81) {
                val ch = s[k]
                if (ch in '1'..'9') ref.placeGiven(k, ch - '1') else ref.setCandidates(k, Cell.DIGITS)
            }
            for (k in 0 until 81) if (ref.cells[k] and Cell.TAG != 0) ref.eliminateFromPeers(k, Integer.numberOfTrailingZeros(ref.cells[k]))
            assertSame3(ref, p, q, "parse $s")
        }
    }

    @Test
    fun randomOperationSequencesAgree() {
        for (seed in 0 until 40) {
            val rnd = Random(seed)
            val ref = RefBoard(); val p = PackedBoard(); val s = ShortBoard(); val t = PackedTableBoard(); val pl = PlanesBoard()
            for (k in 0 until 81) { ref.setCandidates(k, Cell.DIGITS); p.setCandidates(k, Cell.DIGITS); s.setCandidates(k, Cell.DIGITS); t.setCandidates(k, Cell.DIGITS); pl.setCandidates(k, Cell.DIGITS) }
            repeat(1500) { step ->
                val k = rnd.nextInt(81); val d = rnd.nextInt(9)
                val ctx = "seed=$seed step=$step"
                when (rnd.nextInt(10)) {
                    0 -> { ref.place(k, d); p.place(k, d); s.place(k, d); t.place(k, d); pl.place(k, d) }
                    1 -> { ref.placeGiven(k, d); p.placeGiven(k, d); s.placeGiven(k, d); t.placeGiven(k, d); pl.placeGiven(k, d) }
                    2 -> { val m = rnd.nextInt(512); ref.setCandidates(k, m); p.setCandidates(k, m); s.setCandidates(k, m); t.setCandidates(k, m); pl.setCandidates(k, m) }
                    in 3..5 -> {
                        val e = ref.eliminate(k, d)
                        assertEquals(e, p.eliminate(k, d), "$ctx packed eliminate ret")
                        assertEquals(e, s.eliminate(k, d), "$ctx short eliminate ret")
                        assertEquals(e, t.eliminate(k, d), "$ctx table eliminate ret")
                        assertEquals(e, pl.eliminate(k, d), "$ctx planes eliminate ret")
                    }
                    else -> {
                        val e = ref.eliminateFromPeers(k, d)
                        assertEquals(e, p.eliminateFromPeers(k, d), "$ctx packed peers ret")
                        assertEquals(e, s.eliminateFromPeers(k, d), "$ctx short peers ret")
                        assertEquals(e, t.eliminateFromPeers(k, d), "$ctx table peers ret")
                        assertEquals(e, pl.eliminateFromPeers(k, d), "$ctx planes peers ret")
                    }
                }
                assertSame3(ref, p, s, ctx, t, pl)
                // unit/peer accessors agree with cell(k)
                val u = rnd.nextInt(27); val i = rnd.nextInt(9); val j = rnd.nextInt(20)
                for (b in listOf<MutableBoard<*>>(p, s, t, pl)) {
                    assertEquals(b.cell(Geo.UNITS[u * 9 + i]), b.unitCell(u, i), "$ctx unitCell ${b::class.simpleName}")
                    assertEquals(b.cell(Geo.PEERS[k * 20 + j]), b.peerCell(k, j), "$ctx peerCell ${b::class.simpleName}")
                }
                // word-at-a-time naked-single scan == cell-by-cell scan, on arbitrary (even invalid) states
                if (step % 7 == 0) {
                    val a = IntArray(81); val b2 = IntArray(81)
                    val na = nakedSingles(p, a); val nb = t.scanNakedSingles(b2)
                    assertEquals(na, nb, "$ctx swar naked count"); assertTrue(a.copyOf(na) contentEquals b2.copyOf(nb), "$ctx swar naked cells")
                    val b3 = IntArray(81); val nc = pl.scanNakedSingles(b3)
                    assertEquals(na, nc, "$ctx planes naked count"); assertTrue(a.copyOf(na) contentEquals b3.copyOf(nc), "$ctx planes naked cells")
                    // plane hidden singles == bit-sliced, on unpropagated states too (digit placed in unit + candidate elsewhere)
                    val h1 = IntArray(243); val h2 = IntArray(243)
                    val nh = hiddenSinglesBitSliced(p, h1); val nh2 = pl.scanHiddenSingles(h2)
                    assertEquals(nh, nh2, "$ctx planes hidden count"); assertTrue(h1.copyOf(nh) contentEquals h2.copyOf(nh2), "$ctx planes hidden cells")
                    for (uu in 0 until 27) for (dd in 0 until 9) {
                        val want = p.placesInUnit(uu, dd)
                        assertEquals(want, pl.placesInUnit(uu, dd), "$ctx placesInUnit u=$uu d=$dd")
                        assertEquals(want, s.placesInUnit(uu, dd)); assertEquals(want, t.placesInUnit(uu, dd))
                    }
                }
                // tag invariant: placed => exactly one digit bit
                for (j in 0 until 81) if (p.isPlaced(j)) assertEquals(1, Integer.bitCount(p.candidates(j)), "$ctx invariant $j")
            }
        }
    }

    @Test
    fun bulkPeerEliminationMatchesPerCellLoop() {
        for (s in Puzzles.ALL) for (k in 0 until 81) for (d in 0 until 9) {
            val a = parseInto(PackedBoard(), s); val b = a.copy()
            val x = parseInto(ShortBoard(), s); val y = x.copy()
            val ra = a.eliminateFromPeers(k, d)
            var rb = false; for (j in 0 until 20) rb = b.eliminate(Geo.PEERS[k * 20 + j], d) or rb
            val rx = x.eliminateFromPeers(k, d)
            var ry = false; for (j in 0 until 20) ry = y.eliminate(Geo.PEERS[k * 20 + j], d) or ry
            assertEquals(a, b); assertEquals(x, y); assertEquals(ra, rb); assertEquals(rx, ry); assertEquals(ra, rx)
            assertEquals(a.toString81(), x.toString81())
            val t = parseInto(PackedTableBoard(), s)
            assertEquals(ra, t.eliminateFromPeers(k, d))
            for (j in 0 until 81) assertEquals(a.cell(j), t.cell(j))
            val pl = parseInto(PlanesBoard(), s)
            assertEquals(ra, pl.eliminateFromPeers(k, d))
            assertPlanes(a, pl, "bulk k=$k d=$d")
            for (j in 0 until 81) assertEquals(a.cell(j), x.cell(j))
        }
    }

    @Test
    fun copyIsIndependentAndEqual() {
        for (s in Puzzles.ALL) {
            fun <B : MutableBoard<B>> check(b: B) {
                val c = b.copy()
                assertEquals(b, c); assertEquals(b.hashCode(), c.hashCode()); assertTrue(b.contentEquals(c))
                val k = (0 until 81).first { !b.isPlaced(it) }
                c.place(k, Integer.numberOfTrailingZeros(b.candidates(k)))
                assertNotEquals(b, c); assertFalse(b.isPlaced(k))
                b.copyFrom(c); assertEquals(b, c); assertTrue(b.isPlaced(k))
            }
            check(parseInto(PackedBoard(), s)); check(parseInto(ShortBoard(), s)); check(parseInto(PackedTableBoard(), s)); check(parseInto(PlanesBoard(), s))
        }
    }

    @Test
    fun equalsDetectsEveryCellAndGivenBit() {
        val s = Puzzles.ALL[2]
        fun <B : MutableBoard<B>> check(base: B) {
            for (k in 0 until 81) {
                val c = base.copy()
                if (c.isPlaced(k)) c.setCandidates(k, 0x1FF) else c.place(k, 0)
                assertNotEquals(base, c, "cell $k")
                val g = base.copy()
                // same cell content, given bit toggled on
                if (!g.isGiven(k)) {
                    if (g.isPlaced(k)) g.placeGiven(k, g.cell(k).digit) else { g.placeGiven(k, 0); base.copy().also { it.place(k, 0); assertNotEquals(it, g, "given $k") } }
                }
            }
        }
        check(parseInto(PackedBoard(), s)); check(parseInto(ShortBoard(), s)); check(parseInto(PackedTableBoard(), s)); check(parseInto(PlanesBoard(), s))
    }

    @Test
    fun scansAgree() {
        for (s in Puzzles.ALL) {
            val p = parseInto(PackedBoard(), s); val q = parseInto(ShortBoard(), s); val t = parseInto(PackedTableBoard(), s); val pl = parseInto(PlanesBoard(), s)
            // advance a few steps so scans see a mix of states
            repeat(3) { step ->
                val a = IntArray(27); val b = IntArray(27)
                assertEquals(unitMissing(p, a), unitMissing(q, b)); assertTrue(a contentEquals b)
                val a3 = IntArray(27); assertEquals(unitMissing(p, a), unitMissing(t, a3)); assertTrue(a contentEquals a3)
                for (u in 0 until 27) {
                    var placed = 0
                    for (i in 0 until 9) { val c = p.cell(Geo.UNITS[u * 9 + i]); if (c.isPlaced) placed = placed or c.mask }
                    assertEquals(0x1FF and placed.inv(), a[u])
                }
                val n1 = IntArray(81); val n2 = IntArray(81)
                val nn = nakedSingles(p, n1); assertEquals(nn, nakedSingles(q, n2))
                assertTrue(n1.copyOf(nn) contentEquals n2.copyOf(nn))
                assertPlanes(p, pl, "scans $s step $step")
                val a4 = IntArray(27); assertEquals(unitMissing(p, a), unitMissing(pl, a4)); assertTrue(a contentEquals a4)
                for (b in listOf<MutableBoard<*>>(p, q, t, pl)) {
                    val n3 = IntArray(81); assertEquals(nn, b.scanNakedSingles(n3)); assertTrue(n1.copyOf(nn) contentEquals n3.copyOf(nn))
                }
                val h1 = IntArray(243); val h2 = IntArray(243); val h3 = IntArray(243); val h4 = IntArray(243)
                val hn = hiddenSinglesNaive(p, h1)
                assertEquals(hn, hiddenSinglesNaive(q, h2)); assertEquals(hn, hiddenSinglesBitSliced(p, h3)); assertEquals(hn, hiddenSinglesBitSliced(q, h4))
                assertTrue(h1.copyOf(hn) contentEquals h2.copyOf(hn)); assertTrue(h1.copyOf(hn) contentEquals h3.copyOf(hn)); assertTrue(h1.copyOf(hn) contentEquals h4.copyOf(hn))
                val h5 = IntArray(243); val h6 = IntArray(243)
                assertEquals(hn, hiddenSinglesNaive(t, h5)); assertEquals(hn, hiddenSinglesBitSliced(t, h6))
                assertTrue(h1.copyOf(hn) contentEquals h5.copyOf(hn)); assertTrue(h1.copyOf(hn) contentEquals h6.copyOf(hn))
                for (b in listOf<MutableBoard<*>>(p, q, t, pl)) {
                    val h7 = IntArray(243); assertEquals(hn, b.scanHiddenSingles(h7)); assertTrue(h1.copyOf(hn) contentEquals h7.copyOf(hn), "${b::class.simpleName} scanHiddenSingles")
                }
                val h8 = IntArray(243); assertEquals(hn, hiddenSinglesNaive(pl, h8)); assertTrue(h1.copyOf(hn) contentEquals h8.copyOf(hn))
                // place one naked single (or first hidden) on both and continue
                val pick = if (nn > 0) n1[0] else if (hn > 0) h1[0] else return@repeat
                val k = pick ushr 4; val d = pick and 15
                p.place(k, d); p.eliminateFromPeers(k, d); q.place(k, d); q.eliminateFromPeers(k, d); t.place(k, d); t.eliminateFromPeers(k, d); pl.place(k, d); pl.eliminateFromPeers(k, d)
                assertEquals(p.toString81(), q.toString81(), "step $step"); assertEquals(p, PackedBoard().also { it.copyFrom(p) })
                for (i in 0 until 16) assertEquals(p.rawWord(i), t.rawWord(i))
            }
        }
    }

    @Test
    fun singlesSolverAgreesAndIsSound() {
        val scratch = IntArray(243)
        val statuses = mutableListOf<String>()
        for (s in Puzzles.ALL) {
            val p = parseInto(PackedBoard(), s); val q = parseInto(ShortBoard(), s)
            val t = parseInto(PackedTableBoard(), s)
            val rp = solveSingles(p, scratch); val rq = solveSingles(q, scratch); val rt = solveSingles(t, scratch)
            val pl = parseInto(PlanesBoard(), s); val rpl = solveSingles(pl, scratch)
            assertEquals(rp, rq, s); assertEquals(rp, rt, s); assertEquals(rp, rpl, s)
            assertPlanes(p, pl, "solve $s")
            for (i in 0 until 16) assertEquals(p.rawWord(i), t.rawWord(i))
            assertEquals(p.toString81(), q.toString81())
            for (k in 0 until 81) assertEquals(p.cell(k), q.cell(k))
            // soundness: no two placed peers share a digit; givens preserved
            for (k in 0 until 81) {
                if (s[k] in '1'..'9') assertEquals(s[k] - '1', p.cell(k).digit)
                if (p.isPlaced(k)) for (j in 0 until 20) {
                    val pk = Geo.PEERS[k * 20 + j]
                    if (p.isPlaced(pk)) assertNotEquals(p.cell(k).digit, p.cell(pk).digit)
                }
            }
            statuses += "${SolveStatus.name(rp and 3)}:${rp ushr 2}"
        }
        println("singles solver: $statuses")
        val solved = parseInto(PackedBoard(), Puzzles.ALL[0]).also { solveSingles(it, scratch) }
        assertEquals("483921657967345821251876493548132976729564138136798245372689514814253769695417382", solved.toString81())
    }

    @Test
    fun conflictingUserDigitSurvivesBulkElimination() {
        // A user enters a digit that conflicts with a peer; propagation from the given must not
        // corrupt the placed lane (one-hot invariant) in either representation.
        val s = Puzzles.ALL[0] // r0c2 = 3 (given)
        fun <B : MutableBoard<B>> check(b: B) {
            val k = 2; val d = b.cell(k).digit
            val peer = (0 until 20).map { Geo.PEERS[k * 20 + it] }.first { !b.isPlaced(it) }
            b.place(peer, d) // conflict
            b.eliminateFromPeers(k, d)
            assertTrue(b.isPlaced(peer)); assertEquals(1 shl d, b.candidates(peer))
            b.eliminateFromPeers(peer, d)
            assertTrue(b.isPlaced(k)); assertEquals(1 shl d, b.candidates(k))
        }
        check(parseInto(PackedBoard(), s)); check(parseInto(ShortBoard(), s)); check(parseInto(PackedTableBoard(), s)); check(parseInto(PlanesBoard(), s))
    }

    @Test
    fun onDemandPlanesMatchKeptPlanes() {
        val out = LongArray(18)
        for (s in Puzzles.ALL) {
            val pl = parseInto(PlanesBoard(), s); val t = parseInto(PackedTableBoard(), s)
            solveSingles(pl, IntArray(243)); solveSingles(t, IntArray(243))
            buildPlanes(t, out)
            for (i in 0 until 18) assertEquals(pl.rawWord(16 + i), out[i], "$s plane word $i (from PackedTable)")
            buildPlanes(pl, out)
            for (i in 0 until 18) assertEquals(pl.rawWord(16 + i), out[i], "$s plane word $i (from Planes cells)")
        }
    }

    @Test
    fun planesBoardEqualityIgnoresNothingItShouldNot() {
        // equals/hash cover cells + given (words 0..15); planes are derived, so equal cells => equal planes anyway.
        for (s in Puzzles.ALL) {
            val a = parseInto(PlanesBoard(), s); val b = parseInto(PlanesBoard(), s)
            assertEquals(a, b); assertEquals(a.hashCode(), b.hashCode())
            assertEquals(parseInto(PackedBoard(), s).hashCode(), a.hashCode(), "same 31-poly over the same 16 words")
        }
    }
}
