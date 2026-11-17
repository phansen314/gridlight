package gridlight.engine

import kotlin.test.*

class ParserTest {
    private val g = Geometry.STANDARD
    private val solution = "534678912672195348198342567859761423426853791713924856961537284287419635345286179"
    private val puzzle = "53..7....6..195....98....6.8...6...34..8.3..17...2...6.6....28....419..5....8..79"
    private val expected: Board = Board.build {
        for (k in 0..80) if (puzzle[k] != '.') addGiven(CellIndex.of(k).row, CellIndex.of(k).column, Digit.of(puzzle[k] - '0'))
    }
    private fun rows(s: String = puzzle) = (0..8).map { s.substring(it * 9, it * 9 + 9) }

    @Test fun `nine compact lines`() = assertEquals(expected, Board.parse(rows().joinToString("\n")))

    @Test fun `one 81 character line`() {
        assertEquals(expected, Board.parse(puzzle))
        assertEquals(expected, Board.parse(puzzle + "\n"))
    }

    @Test fun `81 character line where a space is a blank`() {
        val spaced = puzzle.replace('.', ' ')
        assertEquals(81, spaced.length)
        assertEquals(expected, Board.parse(spaced))
    }

    @Test fun `spaced grid with box separators`() = assertEquals(expected, Board.parse(formatGrid(expected)))

    @Test fun `spaced grid without separators, tabs and pipes as layout`() {
        val text = rows().joinToString("\n") { r -> r.chunked(3).joinToString("\t|\t") { it.toList().joinToString(" ") } }
        assertEquals(expected, Board.parse(text))
    }

    @Test fun `boxed grid with plus and star borders is skipped`() {
        val plus = buildString {
            append("+-------+-------+-------+\n")
            for ((i, r) in rows().withIndex()) {
                append("| ").append(r.chunked(3).joinToString(" | ") { it.toList().joinToString(" ") }).append(" |\n")
                if (i % 3 == 2) append("+-------+-------+-------+\n")
            }
        }
        assertEquals(expected, Board.parse(plus))
        val star = "*-----------*\n" + rows().joinToString("\n") { "|" + it.chunked(3).joinToString("|") + "|" } + "\n*-----------*\n"
        assertEquals(expected, Board.parse(star))
    }

    @Test fun `every non-digit character is a blank`() {
        for (blank in listOf('.', '0', 'X', '*', '_', 'x', '?')) {
            val text = rows().joinToString("\n") { it.replace('.', blank) }
            assertEquals(expected, Board.parse(text), "blank '$blank'")
        }
        // a row of nine dashes is a row of blanks, not a separator
        val dashes = (rows().take(2) + "---------" + rows().drop(3)).joinToString("\n")
        val b = Board.parse(dashes)
        assertEquals(CellSet.of(18, 19, 20, 21, 22, 23, 24, 25, 26), CellSet.of(18, 19, 20, 21, 22, 23, 24, 25, 26) and b.openCells())
    }

    @Test fun `comments blank lines and CRLF`() {
        val text = "# my puzzle\r\n\r\n" + rows().take(4).joinToString("\r\n") + "\r\n   # indented comment\r\n" +
            rows().drop(4).joinToString("\r\n") + "\r\n\r\n# end\r\n"
        assertEquals(expected, Board.parse(text))
        assertEquals(expected, Board.parse("# header\n\n$puzzle\n# footer"))
    }

    @Test fun `givens are locked and open cells keep all candidates`() {
        val b = Board.parse(puzzle)
        assertTrue(b.isGiven(CellIndex.of(0))); assertEquals(Digit.of(5), b[CellIndex.of(0)].filled)
        assertFalse(b.isGiven(CellIndex.of(2))); assertEquals(DigitSet.ALL, b[CellIndex.of(2)].candidates)
        assertEquals(30, b.givenCells().size)
        assertEquals(51, b.openCells().size)
    }

    @Test fun `an empty grid`() = assertEquals(Board.EMPTY, Board.parse(".".repeat(81)))

    private fun failure(text: String): ParseException = assertFailsWith<ParseException> { Board.parse(text) }

    @Test fun `too few cells in a line`() {
        val e = failure((listOf(rows()[0].dropLast(1)) + rows().drop(1)).joinToString("\n"))
        assertEquals(1, e.line); assertContains(e.message!!, "9 cells")
    }

    @Test fun `too many cells in a line`() {
        val e = failure((rows().take(3) + (rows()[3] + "1") + rows().drop(4)).joinToString("\n"))
        assertEquals(4, e.line)
    }

    @Test fun `too few lines`() {
        val e = failure(rows().take(8).joinToString("\n"))
        assertNull(e.line); assertContains(e.message!!, "found 8")
        failure("")
        failure("# only a comment\n")
    }

    @Test fun `a tenth grid line`() {
        val e = failure(rows().joinToString("\n") + "\n.........")
        assertEquals(10, e.line); assertContains(e.message!!, "more than 9")
        failure(rows().joinToString("\n") + "\n123456789")
    }

    @Test fun `a trailing section is not supported yet`() {
        for (tail in listOf("info:", "info:\n  title: x", "solution: $solution", "regions:", "constraints:")) {
            val e = failure(rows().joinToString("\n") + "\n" + tail)
            assertContains(e.message!!, "not supported yet", message = tail); assertEquals(10, e.line)
        }
        failure("$puzzle\nsolution: $solution")
        // comments and blank lines between grid and section are fine; the section still fails
        assertEquals(4, failure("$puzzle\n\n# c\ninfo:").line)
    }

    @Test fun `conflicting givens parse and are reported`() {
        val b = Board.parse("55" + ".".repeat(79))
        assertEquals(CellSet.of(0, 1), conflicts(b, g))
        assertFalse(isSolved(b, g))
    }

    @Test fun `writing the grid and parsing it gives an equal board`() {
        for (b in listOf(expected, Board.EMPTY, Board.parse(solution), Board.parse("55" + ".".repeat(79)))) {
            assertEquals(b, Board.parse(formatGrid(b)))
        }
        assertEquals(puzzle.replace('.', '.'), formatGrid(expected).filter { it in "123456789." })
    }
}
