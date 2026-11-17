package gridlight.engine

import kotlin.random.Random
import kotlin.test.*

class KernelTest {
    private val g = Geometry.STANDARD
    private var nakedSeen = 0
    private var hiddenSeen = 0

    private fun assertAgree(w: LongArray, ref: RefBoard, ctx: String) {
        assertNull(Kernel.violation(w), ctx)
        for (k in 0..80) {
            assertEquals(ref.cells[k], Kernel.raw(w, k), "cell $k; $ctx")
            assertEquals(ref.given[k], Kernel.isGiven(w, k), "given $k; $ctx")
        }
        assertEquals(ref.openSet(), Kernel.openCells(w).toList().map { it.k }, "open mask; $ctx")
        assertEquals((0..80).filter { ref.given[it] }, Kernel.givenCells(w).toList().map { it.k }, "given mask; $ctx")
        for (d0 in 0..8) assertEquals(ref.withCandidate(d0), Kernel.cellsWithCandidate(w, d0).toList().map { it.k }, "cwc $d0; $ctx")
        for (r in g.regions) {
            val cs = r.cellIndices.toList()
            assertEquals(ref.filledIn(cs), Kernel.filledIn(w, r), "filledIn $r; $ctx")
            assertEquals(0x1FF and ref.filledIn(cs).inv(), Kernel.missingIn(w, r), "missingIn $r; $ctx")
            assertEquals(ref.duplicatesIn(cs), Kernel.duplicatesIn(w, r), "dups $r; $ctx")
        }
        val out = IntArray(300)
        assertEquals(ref.nakedSingles(), out.take(Kernel.scanNakedSingles(w, out)), "naked; $ctx")
        assertEquals(ref.hiddenSingles(), out.take(Kernel.scanHiddenSingles(w, g, out)), "hidden; $ctx")
        if (ref.nakedSingles().isNotEmpty()) nakedSeen++
        if (ref.hiddenSingles().isNotEmpty()) hiddenSeen++
    }

    private fun start(rnd: Random): Pair<LongArray, RefBoard> {
        val w = Kernel.empty(); val ref = RefBoard()
        repeat(rnd.nextInt(0, 30)) {
            val k = rnd.nextInt(81)
            if (!ref.given[k]) { val d0 = rnd.nextInt(9); Kernel.setGiven(w, k, d0); ref.setGiven(k, d0) }
        }
        Kernel.computeCandidates(w, g); ref.computeCandidates()
        return w to ref
    }

    @Test fun `empty board`() {
        val w = Kernel.empty()
        assertNull(Kernel.violation(w))
        assertEquals(CellSet.ALL, Kernel.openCells(w)); assertEquals(CellSet.EMPTY, Kernel.givenCells(w))
        for (d0 in 0..8) assertEquals(CellSet.ALL, Kernel.cellsWithCandidate(w, d0))
        assertEquals(0, Kernel.scanNakedSingles(w, IntArray(81)))
    }

    @Test fun `random operation sequences agree with the reference`() {
        for (seed in 0 until 300) {
            val rnd = Random(seed)
            val (w, ref) = start(rnd)
            assertAgree(w, ref, "seed $seed start")
            repeat(60) { step ->
                val k = rnd.nextInt(81); val d0 = rnd.nextInt(9)
                val mask = rnd.nextInt(0, 0x200)
                val (name, got, want) = when (rnd.nextInt(6)) {
                    0, 1 -> Triple("place r${k} $d0", Kernel.place(w, k, d0, g), ref.place(k, d0))
                    2 -> Triple("eliminate $k $d0", Kernel.eliminate(w, k, d0), ref.eliminate(k, d0))
                    3 -> Triple("eliminateDigits $k $mask", Kernel.eliminateDigits(w, k, mask), ref.eliminateDigits(k, mask))
                    4 -> Triple("setCandidates $k $mask", Kernel.setCandidates(w, k, mask), ref.setCandidates(k, mask))
                    else -> {
                        var s = CellSet.EMPTY
                        for (c in 0..80) if (rnd.nextInt(4) == 0) s += CellIndex(c)
                        var want = false
                        s.forEach { if (ref.eliminate(it.k, d0)) want = true }
                        Triple("eliminateCells $s $d0", Kernel.eliminateCells(w, s, d0), want)
                    }
                }
                assertEquals(want, got, "return of $name, seed $seed step $step")
                assertAgree(w, ref, "seed $seed step $step after $name")
            }
        }
        // the comparison isn't vacuous: the scans found something in many states
        assertTrue(nakedSeen > 500, "naked singles seen in $nakedSeen states")
        assertTrue(hiddenSeen > 500, "hidden singles seen in $hiddenSeen states")
    }

    @Test fun `writes to a given change nothing`() {
        val w = Kernel.empty(); Kernel.setGiven(w, 10, 4); Kernel.computeCandidates(w, g)
        val before = w.copyOf()
        assertFalse(Kernel.place(w, 10, 2, g)); assertFalse(Kernel.setCandidates(w, 10, 0x3))
        assertFalse(Kernel.eliminate(w, 10, 4)); assertFalse(Kernel.eliminateDigits(w, 10, 0x1FF))
        assertFalse(Kernel.eliminateCells(w, CellSet.of(10), 4))
        assertContentEquals(before, w)
    }

    @Test fun `no-op writes report false and leave the array unchanged`() {
        val w = Kernel.empty()
        assertTrue(Kernel.place(w, 40, 3, g))
        val before = w.copyOf()
        assertFalse(Kernel.place(w, 40, 3, g))
        assertFalse(Kernel.eliminate(w, 41, 3))      // already removed by the placement
        assertFalse(Kernel.setCandidates(w, 0, 0x1FF))
        assertContentEquals(before, w)
    }

    @Test fun `givens only come from setGiven and computeCandidates removes their digit from peers`() {
        val w = Kernel.empty(); Kernel.setGiven(w, 0, 4); Kernel.computeCandidates(w, g)
        for (p in g.peers(CellIndex(0))) assertEquals(0x1FF and (1 shl 4).inv(), Kernel.raw(w, p.k))
        assertEquals(0x1FF, Kernel.raw(w, 40))
        assertNull(Kernel.violation(w))
    }

    @Test fun `equality and hash cover words 0 to 15 only`() {
        val a = Kernel.empty(); val b = Kernel.empty()
        assertTrue(Kernel.sameKey(a, b)); assertEquals(Kernel.hash(a), Kernel.hash(b))
        b[16] = 0 // open mask is excluded
        assertTrue(Kernel.sameKey(a, b)); assertEquals(Kernel.hash(a), Kernel.hash(b))
        b[0] = b[0] xor 1
        assertFalse(Kernel.sameKey(a, b)); assertNotEquals(Kernel.hash(a), Kernel.hash(b))
        val c = Kernel.empty(); c[15] = c[15] or 1
        assertFalse(Kernel.sameKey(a, c))
    }

    @Test fun `violation detects broken invariants`() {
        val w = Kernel.empty()
        w[16] = w[16] xor 1 // open mask out of step
        assertNotNull(Kernel.violation(w))
        val t = Kernel.empty(); t[0] = t[0] or (1L shl 9) // tag with 9 digit bits
        assertNotNull(Kernel.violation(t))
        val s = Kernel.empty(); s[0] = s[0] or (1L shl 63)
        assertNotNull(Kernel.violation(s))
    }
}
