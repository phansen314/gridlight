package gridlight.engine

import kotlin.test.*

class RulesTest {
    private val g = Geometry.STANDARD
    private val solution = "534678912672195348198342567859761423426853791713924856961537284287419635345286179"

    @Test fun `solved board`() {
        val b = Board.parse(solution)
        assertEquals(CellSet.EMPTY, conflicts(b, g)); assertTrue(isSolved(b, g))
    }

    @Test fun `entered digits count as well as givens`() {
        var b = Board.parse(solution.replaceRange(0, 1, "."))
        assertFalse(isSolved(b, g))
        b = b.place(CellIndex.of(0), Digit.of(5))
        assertTrue(isSolved(b, g)); assertFalse(b.isGiven(CellIndex.of(0)))
    }

    @Test fun `partial board without conflicts`() {
        val b = Board.parse("53..7....6..195....98....6.8...6...34..8.3..17...2...6.6....28....419..5....8..79")
        assertEquals(CellSet.EMPTY, conflicts(b, g)); assertFalse(isSolved(b, g))
        assertFalse(isSolved(Board.EMPTY, g)); assertEquals(CellSet.EMPTY, conflicts(Board.EMPTY, g))
    }

    @Test fun `conflicts by row, column and box`() {
        // r1c1 and r1c9 share a row; r1c1 and r9c1 a column; r1c1 and r2c2 a box
        fun with(vararg ks: Int) = ks.fold(Board.EMPTY) { b, k -> b.place(CellIndex.of(k), Digit.of(7)) }
        assertEquals(CellSet.of(0, 8), conflicts(with(0, 8), g))
        assertEquals(CellSet.of(0, 72), conflicts(with(0, 72), g))
        assertEquals(CellSet.of(0, 10), conflicts(with(0, 10), g))
        assertEquals(CellSet.EMPTY, conflicts(with(0, 13), g)) // different row, column and box
        assertEquals(CellSet.of(0, 8, 72), conflicts(with(0, 8, 72), g))
    }

    @Test fun `a full grid with one repeated digit is not solved`() {
        val b = Board.parse("." + solution.drop(1)).place(CellIndex.of(0), Digit.of(3)) // r1c1 = 3 repeats r1c2 = 3
        assertTrue(CellIndex.of(0) in conflicts(b, g)); assertTrue(CellIndex.of(1) in conflicts(b, g))
        assertFalse(isSolved(b, g))
    }

    @Test fun `different digits do not conflict`() {
        val b = Board.EMPTY.place(CellIndex.of(0), Digit.of(1)).place(CellIndex.of(1), Digit.of(2))
        assertEquals(CellSet.EMPTY, conflicts(b, g))
    }
}
