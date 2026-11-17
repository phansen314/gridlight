package gridlight.engine

import kotlin.test.*

class CellSetTest {
    private fun c(k: Int) = CellIndex.of(k)
    private val loMask = (1L shl 54) - 1
    private val hiMask = (1L shl 27) - 1
    private fun assertClean(s: CellSet) {
        assertEquals(0L, s.lo and loMask.inv(), "lo spare bits")
        assertEquals(0L, s.hi and hiMask.inv(), "hi spare bits")
    }

    @Test fun `of rejects out of range`() {
        for (k in listOf(-1, 81, 1000)) assertFailsWith<IllegalArgumentException> { CellSet.of(k) }
        assertFailsWith<IllegalArgumentException> { CellSet.of(1, 2, 81) }
        assertEquals(CellSet.EMPTY, CellSet.of())
    }

    @Test fun `band split layout`() {
        assertEquals(1L, CellSet.of(0).lo); assertEquals(0L, CellSet.of(0).hi)
        assertEquals(1L shl 53, CellSet.of(53).lo); assertEquals(0L, CellSet.of(53).hi)
        assertEquals(0L, CellSet.of(54).lo); assertEquals(1L, CellSet.of(54).hi)
        assertEquals(1L shl 26, CellSet.of(80).hi)
        assertEquals(81, CellSet.ALL.size)
        assertClean(CellSet.ALL)
    }

    @Test fun `algebra across the band boundary`() {
        val a = CellSet.of(0, 53, 54, 80)
        val b = CellSet.of(53, 54, 60)
        assertEquals(CellSet.of(0, 53, 54, 60, 80), a + b)
        assertEquals(CellSet.of(0, 80), a - b)
        assertEquals(CellSet.of(53, 54), a and b)
        assertEquals(CellSet.of(0, 10, 53, 54, 80), a + c(10))
        assertEquals(CellSet.of(0, 53, 54, 70, 80), a + c(70))
        assertEquals(CellSet.of(0, 54, 80), a - c(53))
        assertEquals(CellSet.of(0, 53, 80), a - c(54))
        assertEquals(a, a - c(1)); assertEquals(a, a + c(0))
        assertTrue(c(53) in a); assertTrue(c(54) in a); assertFalse(c(55) in a); assertFalse(c(1) in a)
        assertEquals(CellSet.EMPTY, CellSet.ALL - CellSet.ALL)
    }

    @Test fun `size first isEmpty`() {
        assertEquals(0, CellSet.EMPTY.size); assertTrue(CellSet.EMPTY.isEmpty())
        assertEquals(3, CellSet.of(80, 3, 60).size)
        assertEquals(c(3), CellSet.of(80, 3, 60).first)
        assertEquals(c(60), CellSet.of(80, 60).first)
        assertFailsWith<NoSuchElementException> { CellSet.EMPTY.first }
        assertFalse(CellSet.of(80).isEmpty())
    }

    @Test fun `iteration is ascending across bands`() {
        val s = CellSet.of(80, 54, 53, 0, 20)
        val seen = mutableListOf<Int>()
        for (x in s) seen += x.k
        assertEquals(listOf(0, 20, 53, 54, 80), seen)
        val fe = mutableListOf<Int>(); s.forEach { fe += it.k }
        assertEquals(seen, fe)
        assertEquals(seen, s.toList().map { it.k })
        assertTrue(s.any { it.k == 80 }); assertFalse(s.any { it.k == 1 })
        assertTrue(s.all { it.k >= 0 }); assertFalse(s.all { it.k < 80 })
        assertEquals(3, s.count { it.k >= 53 })
        assertEquals((0..80).toList(), CellSet.ALL.toList().map { it.k })
        assertFalse(CellSet.EMPTY.iterator().hasNext())
        assertFailsWith<NoSuchElementException> { CellSet.EMPTY.iterator().next() }
    }

    @Test fun `equality hash toString`() {
        assertEquals(CellSet.of(1, 70), CellSet.of(70, 1))
        assertEquals(CellSet.of(1, 70).hashCode(), CellSet.of(70, 1).hashCode())
        assertNotEquals(CellSet.of(1), CellSet.of(2))
        assertNotEquals(CellSet.of(0), CellSet.of(54))
        assertEquals(setOf(CellSet.of(5)), setOf(CellSet.of(5), CellSet.of(5)))
        assertEquals("{r1c1,r3c5}", CellSet.of(0, 22).toString())
        assertEquals("{}", CellSet.EMPTY.toString())
    }

    @Test fun `spare bits stay zero over every operation`() {
        val sets = listOf(CellSet.EMPTY, CellSet.ALL, CellSet.of(0, 53, 54, 80), CellSet.of(1, 27, 60), CellSet(loMask, 0), CellSet(0, hiMask))
        for (a in sets) {
            assertClean(a)
            for (b in sets) for (r in listOf(a + b, a - b, a and b)) assertClean(r)
            for (k in 0..80) for (r in listOf(a + c(k), a - c(k), CellSet.of(c(k)))) assertClean(r)
        }
    }
}
