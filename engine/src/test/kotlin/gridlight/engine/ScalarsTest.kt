package gridlight.engine

import kotlin.test.*

class ScalarsTest {
    @Test fun `Digit of rejects out of range`() {
        for (n in listOf(Int.MIN_VALUE, -1, 0, 10, 100, Int.MAX_VALUE)) assertFailsWith<IllegalArgumentException> { Digit.of(n) }
        for (n in 1..9) assertEquals(n, Digit.of(n).value)
    }

    @Test fun `Row and Column of reject out of range`() {
        for (n in listOf(-1, 0, 10)) {
            assertFailsWith<IllegalArgumentException> { Row.of(n) }
            assertFailsWith<IllegalArgumentException> { Column.of(n) }
        }
        assertEquals(1, Row.of(1).value); assertEquals(9, Column.of(9).value)
    }

    @Test fun `CellIndex of rejects out of range`() {
        for (k in listOf(-1, 81, 1000, Int.MIN_VALUE)) assertFailsWith<IllegalArgumentException> { CellIndex.of(k) }
        assertEquals(0, CellIndex.of(0).k); assertEquals(80, CellIndex.of(80).k)
    }

    @Test fun `RegionSlot of rejects out of range`() {
        for (i in listOf(-1, 9, 81)) assertFailsWith<IllegalArgumentException> { RegionSlot.of(i) }
        assertEquals(8, RegionSlot.of(8).i)
    }

    @Test fun `CellIndex at row and column round trip`() {
        for (k in 0..80) {
            val c = CellIndex.of(k)
            assertEquals(c, CellIndex.at(c.row, c.column))
        }
        assertEquals(0, CellIndex.at(Row.of(1), Column.of(1)).k)
        assertEquals(80, CellIndex.at(Row.of(9), Column.of(9)).k)
        assertEquals(13, CellIndex.at(Row.of(2), Column.of(5)).k)
        assertEquals(Row.of(3), CellIndex.of(22).row)
        assertEquals(Column.of(5), CellIndex.of(22).column)
    }

    @Test fun `toString forms`() {
        assertEquals("5", Digit.of(5).toString())
        assertEquals("r3", Row.of(3).toString())
        assertEquals("c5", Column.of(5).toString())
        assertEquals("r3c5", CellIndex.at(Row.of(3), Column.of(5)).toString())
        assertEquals("r1c1", CellIndex.of(0).toString())
        assertEquals("r9c9", CellIndex.of(80).toString())
    }
}
