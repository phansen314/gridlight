package gridlight.engine

import kotlin.test.*

class RegionSlotSetTest {
    private val valid = 0x1FF
    private fun d(n: Int) = RegionSlot.of(n)

    @Test fun `of rejects out of range`() {
        for (n in listOf(9, 10, -1)) assertFailsWith<IllegalArgumentException> { RegionSlotSet.of(n) }
        assertFailsWith<IllegalArgumentException> { RegionSlotSet.of(1, 2, 9) }
        assertEquals(RegionSlotSet.NONE, RegionSlotSet.of())
    }

    @Test fun `algebra`() {
        val a = RegionSlotSet.of(0, 4, 8)
        val b = RegionSlotSet.of(4, 5)
        assertEquals(RegionSlotSet.of(0, 4, 5, 8), a + b)
        assertEquals(RegionSlotSet.of(0, 8), a - b)
        assertEquals(RegionSlotSet.of(4), a and b)
        assertEquals(RegionSlotSet.of(0, 3, 4, 8), a + d(3))
        assertEquals(RegionSlotSet.of(0, 8), a - d(4))
        assertEquals(a, a - d(2))
        assertEquals(a, a + d(4))
        assertTrue(d(4) in a); assertFalse(d(6) in a)
        assertEquals(RegionSlotSet.NONE, RegionSlotSet.ALL - RegionSlotSet.ALL)
    }

    @Test fun `size first isEmpty`() {
        assertEquals(0, RegionSlotSet.NONE.size); assertTrue(RegionSlotSet.NONE.isEmpty())
        assertEquals(9, RegionSlotSet.ALL.size); assertFalse(RegionSlotSet.ALL.isEmpty())
        assertEquals(d(3), RegionSlotSet.of(8, 3, 7).first)
        assertFailsWith<NoSuchElementException> { RegionSlotSet.NONE.first }
    }

    @Test fun `iteration is ascending and all helpers agree`() {
        val s = RegionSlotSet.of(8, 1, 4)
        val seen = mutableListOf<Int>()
        for (x in s) seen += x.i
        assertEquals(listOf(1, 4, 8), seen)
        val fe = mutableListOf<Int>(); s.forEach { fe += it.i }
        assertEquals(seen, fe)
        assertEquals(seen, s.toList().map { it.i })
        assertTrue(s.any { it.i == 4 }); assertFalse(s.any { it.i == 3 })
        assertTrue(s.all { it.i >= 1 }); assertFalse(s.all { it.i > 1 })
        assertEquals(2, s.count { it.i > 1 })
        assertFalse(RegionSlotSet.NONE.iterator().hasNext())
        assertFailsWith<NoSuchElementException> { RegionSlotSet.NONE.iterator().next() }
    }

    @Test fun `equality hash toString`() {
        assertEquals(RegionSlotSet.of(0, 1), RegionSlotSet.of(1, 0))
        assertEquals(RegionSlotSet.of(0, 1).hashCode(), RegionSlotSet.of(1, 0).hashCode())
        assertNotEquals(RegionSlotSet.of(0), RegionSlotSet.of(1))
        assertEquals("{0,4,8}", RegionSlotSet.of(8, 0, 4).toString())
        assertEquals("{}", RegionSlotSet.NONE.toString())
    }

    @Test fun `spare bits stay zero over every operation`() {
        val sets = (0..valid step 7).map { RegionSlotSet(it) } + RegionSlotSet.ALL + RegionSlotSet.NONE
        for (a in sets) {
            assertEquals(0, a.bits and valid.inv())
            for (b in sets) for (r in listOf(a + b, a - b, a and b)) assertEquals(0, r.bits and valid.inv())
            for (n in 0..8) for (r in listOf(a + d(n), a - d(n), RegionSlotSet.of(d(n)))) assertEquals(0, r.bits and valid.inv())
        }
    }
}
