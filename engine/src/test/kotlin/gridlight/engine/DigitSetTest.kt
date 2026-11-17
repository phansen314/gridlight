package gridlight.engine

import kotlin.test.*

class DigitSetTest {
    private val valid = 0x1FF
    private fun d(n: Int) = Digit.of(n)

    @Test fun `of rejects out of range`() {
        for (n in listOf(0, 10, -1)) assertFailsWith<IllegalArgumentException> { DigitSet.of(n) }
        assertFailsWith<IllegalArgumentException> { DigitSet.of(1, 2, 10) }
        assertEquals(DigitSet.NONE, DigitSet.of())
    }

    @Test fun `algebra`() {
        val a = DigitSet.of(1, 5, 9)
        val b = DigitSet.of(5, 6)
        assertEquals(DigitSet.of(1, 5, 6, 9), a + b)
        assertEquals(DigitSet.of(1, 9), a - b)
        assertEquals(DigitSet.of(5), a and b)
        assertEquals(DigitSet.of(1, 3, 5, 9), a + d(3))
        assertEquals(DigitSet.of(1, 9), a - d(5))
        assertEquals(a, a - d(2))
        assertEquals(a, a + d(5))
        assertTrue(d(5) in a); assertFalse(d(6) in a)
        assertEquals(DigitSet.NONE, DigitSet.ALL - DigitSet.ALL)
    }

    @Test fun `size first isEmpty`() {
        assertEquals(0, DigitSet.NONE.size); assertTrue(DigitSet.NONE.isEmpty())
        assertEquals(9, DigitSet.ALL.size); assertFalse(DigitSet.ALL.isEmpty())
        assertEquals(d(4), DigitSet.of(9, 4, 7).first)
        assertFailsWith<NoSuchElementException> { DigitSet.NONE.first }
    }

    @Test fun `iteration is ascending and all helpers agree`() {
        val s = DigitSet.of(9, 2, 5)
        val seen = mutableListOf<Int>()
        for (x in s) seen += x.value
        assertEquals(listOf(2, 5, 9), seen)
        val fe = mutableListOf<Int>(); s.forEach { fe += it.value }
        assertEquals(seen, fe)
        assertEquals(seen, s.toList().map { it.value })
        assertTrue(s.any { it.value == 5 }); assertFalse(s.any { it.value == 3 })
        assertTrue(s.all { it.value >= 2 }); assertFalse(s.all { it.value > 2 })
        assertEquals(2, s.count { it.value > 2 })
        assertFalse(DigitSet.NONE.iterator().hasNext())
        assertFailsWith<NoSuchElementException> { DigitSet.NONE.iterator().next() }
    }

    @Test fun `equality hash toString`() {
        assertEquals(DigitSet.of(1, 2), DigitSet.of(2, 1))
        assertEquals(DigitSet.of(1, 2).hashCode(), DigitSet.of(2, 1).hashCode())
        assertNotEquals(DigitSet.of(1), DigitSet.of(2))
        assertEquals("{1,5,9}", DigitSet.of(9, 1, 5).toString())
        assertEquals("{}", DigitSet.NONE.toString())
    }

    @Test fun `spare bits stay zero over every operation`() {
        val sets = (0..valid step 7).map { DigitSet(it) } + DigitSet.ALL + DigitSet.NONE
        for (a in sets) {
            assertEquals(0, a.bits and valid.inv())
            for (b in sets) for (r in listOf(a + b, a - b, a and b)) assertEquals(0, r.bits and valid.inv())
            for (n in 1..9) for (r in listOf(a + d(n), a - d(n), DigitSet.of(d(n)))) assertEquals(0, r.bits and valid.inv())
        }
    }
}
