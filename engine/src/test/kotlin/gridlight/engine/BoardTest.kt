package gridlight.engine

import kotlin.random.Random
import kotlin.test.*

class BoardTest {
    private val g = Geometry.STANDARD
    private fun c(k: Int) = CellIndex.of(k)
    private fun d(n: Int) = Digit.of(n)
    private fun snapshot(b: Board) = (0..80).map { b[c(it)].toString() + (if (b.isGiven(c(it))) "g" else "") }

    private val sample: Board = Board.build {
        addGiven(Row.of(1), Column.of(1), d(5))
        addGiven(Row.of(5), Column.of(5), d(9))
    }.place(c(1), d(3)).eliminate(c(2), DigitSet.of(1, 2))

    @Test fun `Cell accessors throw in the wrong state`() {
        val open = Board.EMPTY[c(0)]; val filled = sample[c(0)]
        assertFalse(open.isFilled); assertEquals(DigitSet.ALL, open.candidates)
        assertFailsWith<IllegalStateException> { open.filled }
        assertTrue(filled.isFilled); assertEquals(d(5), filled.filled)
        assertFailsWith<IllegalStateException> { filled.candidates }
        assertEquals("5", filled.toString()); assertEquals("{1,2,3,4,5,6,7,8,9}", open.toString())
    }

    @Test fun `EMPTY and build`() {
        assertEquals(CellSet.ALL, Board.EMPTY.openCells()); assertEquals(CellSet.EMPTY, Board.EMPTY.givenCells())
        assertEquals(CellSet.EMPTY, Board.EMPTY.filledCells())
        val b = Board.build { addGiven(Row.of(3), Column.of(5), d(7)) }
        assertEquals(CellSet.of(22), b.givenCells()); assertEquals(CellSet.of(22), b.filledCells())
        assertEquals(d(7), b[Row.of(3), Column.of(5)].filled)
        assertTrue(b.isGiven(c(22)))
        assertEquals(DigitSet.ALL, b[c(23)].candidates) // no peer elimination (D19)
        assertFailsWith<IllegalArgumentException> { Board.build { addGiven(Row.of(1), Column.of(1), d(1)); addGiven(Row.of(1), Column.of(1), d(2)) } }
        var leaked: BoardBuilder? = null
        Board.build { leaked = this }
        assertFailsWith<IllegalStateException> { leaked!!.addGiven(Row.of(1), Column.of(1), d(1)) }
    }

    @Test fun `every change leaves the original unchanged`() {
        val before = snapshot(sample)
        val changes = listOf<(Board) -> Board>(
            { it.place(c(60), d(4)) }, { it.place(c(1), d(8)) }, { it.clear(c(1)) }, { it.eliminate(c(60), d(4)) },
            { it.eliminate(CellSet.of(60, 61), d(2)) }, { it.eliminate(c(60), DigitSet.of(1, 2)) },
            { it.eliminateFromPeers(c(60), d(9), g) }, { it.eliminateFilledFromPeers(g) },
            { it.edit { place(c(50), d(1)); eliminate(c(51), d(1)) } },
        )
        for ((i, f) in changes.withIndex()) {
            val r = f(sample)
            assertNotSame(sample, r, "change $i should change something")
            assertEquals(before, snapshot(sample), "original after change $i")
        }
    }

    @Test fun `no-op changes return this`() {
        val b = sample
        val noops = listOf(
            b.place(c(0), d(1)),                 // given
            b.clear(c(0)), b.clear(c(60)),       // given, open
            b.eliminate(c(0), d(5)), b.eliminate(c(0), DigitSet.ALL), b.eliminate(CellSet.of(0), d(5)), // given
            b.eliminate(c(1), d(3)), b.eliminate(c(1), d(1)), // filled
            b.eliminate(c(60), DigitSet.NONE), b.eliminate(CellSet.EMPTY, d(1)),
            b.place(c(1), d(3)),                 // same digit again
            b.eliminate(c(2), d(1)),             // already gone
            b.edit { },
            b.edit { place(c(0), d(1)); clear(c(0)); setCandidates(c(0), DigitSet.NONE); eliminate(c(1), d(3)) },
        )
        for ((i, r) in noops.withIndex()) assertSame(b, r, "no-op $i")
        assertSame(Board.EMPTY, Board.EMPTY.eliminateFilledFromPeers(g))
        val once = b.eliminateFromPeers(c(60), d(9), g)
        assertNotSame(b, once); assertSame(once, once.eliminateFromPeers(c(60), d(9), g))
    }

    @Test fun `place clear and candidate rules`() {
        val p = sample.place(c(60), d(4))
        assertEquals(d(4), p[c(60)].filled); assertFalse(p.isGiven(c(60)))
        assertEquals(DigitSet.ALL, p[c(61)].candidates, "place leaves peers alone")
        assertFalse(c(60) in p.openCells()); assertTrue(c(60) in p.filledCells())
        assertEquals(d(6), p.place(c(60), d(6))[c(60)].filled)
        val cl = p.clear(c(60)); assertEquals(DigitSet.ALL, cl[c(60)].candidates); assertEquals(sample, cl)
        assertEquals(DigitSet.of(3, 4, 5, 6, 7, 8, 9), sample[c(2)].candidates)
        assertFalse(c(60) in p.cellsWithCandidate(d(4)))
        assertEquals(DigitSet.ALL - d(4), p.eliminateFromPeers(c(60), d(4), g)[c(61)].candidates)
        val peers = sample.eliminateFilledFromPeers(g)
        assertEquals(DigitSet.ALL - d(5) - d(3), peers[c(8)].candidates) // row 1 holds 5 (given) and 3
        assertFalse(c(2) in peers.cellsWithCandidate(d(5)))
        assertEquals(d(5), peers[c(0)].filled)
    }

    @Test fun `region reads`() {
        val b = Board.EMPTY.place(c(0), d(5)).place(c(1), d(5)).place(c(2), d(7))
        val row = g.rows[0]
        assertEquals(DigitSet.of(5, 7), b.filledIn(row))
        assertEquals(DigitSet.ALL - DigitSet.of(5, 7), b.missingIn(row))
        assertEquals(DigitSet.of(5), b.duplicatesIn(row))
        assertEquals(DigitSet.NONE, b.duplicatesIn(g.rows[1]))
    }

    @Test fun `transient use after persistent throws`() {
        val t = sample.transient()
        t.place(c(60), d(1))
        val r = t.persistent()
        assertEquals(d(1), r[c(60)].filled)
        assertFailsWith<IllegalStateException> { t.place(c(61), d(1)) }
        assertFailsWith<IllegalStateException> { t[c(0)] }
        assertFailsWith<IllegalStateException> { t.openCells() }
        assertFailsWith<IllegalStateException> { t.persistent() }
        assertFailsWith<IllegalStateException> { t.scanNakedSingles(IntArray(81)) }
        val u = sample.transient(); assertSame(sample, u.persistent())
        assertFailsWith<IllegalStateException> { u.eliminate(c(60), d(1)) }
    }

    @Test fun `transient sees pending changes and reports changes`() {
        val t = sample.transient()
        assertTrue(t.place(c(60), d(1))); assertFalse(t.place(c(60), d(1)))
        assertEquals(d(1), t[c(60)].filled); assertFalse(c(60) in t.openCells())
        assertTrue(t.setCandidates(c(61), DigitSet.of(2))); assertFalse(t.setCandidates(c(61), DigitSet.of(2)))
        assertFalse(t.setCandidates(c(60), DigitSet.of(2)), "filled")
        assertFalse(t.setCandidates(c(0), DigitSet.of(2)), "given")
        val out = IntArray(81)
        assertEquals(listOf(61), out.take(t.scanNakedSingles(out)))
        assertTrue(t.eliminate(c(62), d(3))); assertFalse(t.eliminate(c(62), d(3)))
        assertTrue(t.eliminate(c(63), DigitSet.of(3, 4))); assertTrue(t.eliminate(CellSet.of(64, 65), d(7)))
        assertFalse(t.eliminate(c(0), d(5))); assertFalse(t.eliminate(c(0), DigitSet.ALL))
        assertTrue(t.clear(c(60))); assertFalse(t.clear(c(60)))
        assertTrue(t.eliminateFromPeers(c(0), d(5), g)); assertFalse(t.eliminateFromPeers(c(0), d(5), g))
        assertTrue(t.eliminateFilledFromPeers(g)); assertFalse(t.eliminateFilledFromPeers(g))
    }

    @Test fun `two edits from one board do not affect each other`() {
        val a = sample.edit { place(c(60), d(1)) }
        val b = sample.edit { place(c(60), d(2)); eliminate(c(61), d(9)) }
        assertEquals(d(1), a[c(60)].filled); assertEquals(d(2), b[c(60)].filled)
        assertEquals(DigitSet.ALL, a[c(61)].candidates)
        assertEquals(DigitSet.ALL - d(9), b[c(61)].candidates)
        assertEquals(DigitSet.ALL, sample[c(60)].candidates)
        val t1 = sample.transient(); val t2 = sample.transient()
        t1.place(c(60), d(1))
        assertEquals(DigitSet.ALL, t2[c(60)].candidates)
    }

    @Test fun `hidden single scan on a transient`() {
        var b = Board.EMPTY
        for (cell in 10..17) b = b.eliminate(c(cell), d(7)) // r2c1 is the only cell of row 2 with a 7
        val out = IntArray(243)
        val n = b.transient().scanHiddenSingles(g, out)
        assertTrue(((9 shl 4) or 6) in out.take(n))
    }

    @Test fun `equal boards have equal hashes and work as keys`() {
        val x = sample.edit { place(c(60), d(1)) }
        val y = sample.edit { place(c(60), d(1)) }
        assertEquals(x, y); assertEquals(x.hashCode(), y.hashCode()); assertEquals(x.hashCode(), x.hashCode())
        assertNotEquals(x, sample); assertNotEquals<Any?>(x, "x"); assertNotEquals<Any?>(x, null)
        val map = HashMap<Board, Int>(); map[x] = 1
        assertEquals(1, map[y]); assertNull(map[sample])
        val set = HashSet<Board>(); set += x; set += y; set += sample
        assertEquals(2, set.size)
    }

    @Test fun `changing any single cell or given bit makes boards unequal`() {
        val base = sample
        val seen = HashSet<Board>(); seen += base
        for (k in 0..80) {
            if (base.isGiven(c(k))) continue
            val variants = mutableListOf<Board>()
            variants += base.place(c(k), d(1)); variants += base.place(c(k), d(2))
            if (!base[c(k)].isFilled) { variants += base.eliminate(c(k), d(9)); variants += base.edit { setCandidates(c(k), DigitSet.of(1, 2)) } }
            for (v in variants) { assertNotEquals(base, v, "cell $k"); assertNotEquals(base.hashCode(), v.hashCode(), "hash cell $k") }
        }
        // a given vs the same filled digit differs only in the given bit
        val given = Board.build { addGiven(Row.of(1), Column.of(1), d(5)) }
        val entered = Board.EMPTY.place(c(0), d(5))
        assertNotEquals(given, entered)
    }

    @Test fun `random edits agree with the kernel reference`() {
        for (seed in 0 until 50) {
            val rnd = Random(seed)
            val givenCells = (0..80).shuffled(rnd).take(5)
            var b = Board.build { for (k in givenCells) addGiven(c(k).row, c(k).column, d(rnd.nextInt(1, 10))) }
            val ref = RefBoard()
            // rebuild the reference from the board's givens
            for (k in 0..80) if (b.isGiven(c(k))) ref.setGiven(k, b[c(k)].filled.value - 1)
            repeat(40) {
                val k = rnd.nextInt(81); val dd = rnd.nextInt(1, 10)
                val before = b
                val mask = rnd.nextInt(0, 0x200)
                when (rnd.nextInt(5)) {
                    0 -> { b = b.place(c(k), d(dd)); ref.place(k, dd - 1) }
                    1 -> { b = b.clear(c(k)); ref.clear(k) }
                    2 -> { b = b.eliminate(c(k), d(dd)); ref.eliminate(k, dd - 1) }
                    3 -> { b = b.eliminate(c(k), DigitSet(mask)); ref.eliminateDigits(k, mask) }
                    else -> { b = b.eliminateFromPeers(c(k), d(dd), g); ref.eliminateFromPeers(k, dd - 1) }
                }
                for (i in 0..80) {
                    val cell = b[c(i)]
                    val bits = if (cell.isFilled) 0x200 or (1 shl (cell.filled.value - 1)) else cell.candidates.bits
                    assertEquals(ref.cells[i], bits, "seed $seed cell $i")
                    assertEquals(ref.given[i], b.isGiven(c(i)))
                }
                if (before !== b) assertNotEquals(before, b)
            }
        }
    }

    @Test fun `toString shows grid and candidates`() {
        val s = sample.toString()
        assertTrue("5 " in s && "9 " in s, s)
        assertTrue("3'" in s, s)
        assertTrue("r1c3 {3,4,5,6,7,8,9}" in s, s)
    }
}
