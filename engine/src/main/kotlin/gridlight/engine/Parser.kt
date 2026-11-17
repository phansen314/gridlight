package gridlight.engine

/** A grid file that can't be read. [line] is the 1-based line of the problem, or null when it isn't one line. */
class ParseException(message: String, val line: Int? = null) : IllegalArgumentException(message)

private val HEADER_LIKE = Regex("[A-Za-z][A-Za-z-]*(\\s.*)?:.*")

private fun isLayout(ch: Char) = ch == ' ' || ch == '\t' || ch == '|'
private fun isDigit19(ch: Char) = ch in '1'..'9'

/**
 * Grid-only parser (puzzle format §2.2, §6). Accepts 9 lines of 9 cells or one 81-character first line; a cell
 * is `1`–`9` (a given) or any other character (blank); space, tab and `|` are layout; separator lines are
 * skipped; `#` comment lines and blank lines are ignored. Anything after the grid but blank lines, comments
 * and separators is an error ("not supported yet" for sections). Givens are not checked against the rules
 * (use [conflicts]); open cells get all nine candidates (D19).
 */
internal fun parseBoard(text: String): Board {
    val rows = ArrayList<String>(9) // 9 cell characters each
    var single = false
    var lineNo = 0
    for (line in text.lines()) {
        lineNo++
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

        if (rows.isEmpty() && line.length == 81 && !single) { // one 81-char line: a space is a blank here
            for (r in 0..8) rows += line.substring(r * 9, r * 9 + 9)
            single = true
            continue
        }

        val cells = line.filterNot(::isLayout)
        val hasDigit = cells.any(::isDigit19)
        val separator = (!hasDigit && '+' in cells) || cells.length != 9
        val complete = rows.size == 9

        if (complete) {
            when {
                HEADER_LIKE.matches(trimmed) -> throw ParseException("line $lineNo: sections are not supported yet: '$trimmed'", lineNo)
                !hasDigit && separator -> continue // a trailing border line
                cells.length == 9 -> throw ParseException("line $lineNo: more than 9 grid lines", lineNo)
                else -> throw ParseException("line $lineNo: unexpected text after the grid (sections are not supported yet): '$trimmed'", lineNo)
            }
        }
        if (hasDigit && cells.length != 9) {
            throw ParseException("line $lineNo: a grid line needs exactly 9 cells, found ${cells.length}", lineNo)
        }
        if (separator) continue
        rows += cells
    }
    if (rows.size < 9) throw ParseException("expected 9 grid lines, found ${rows.size}")
    return Board.build {
        for (r in 0..8) for (c in 0..8) {
            val ch = rows[r][c]
            if (isDigit19(ch)) addGiven(Row.of(r + 1), Column.of(c + 1), Digit.of(ch - '0'))
        }
    }
}

/** The canonical grid text: 9 spaced lines with box separators, `.` for every cell that isn't a given. */
internal fun formatGrid(board: Board): String = buildString {
    for (r in 0..8) {
        for (c in 0..8) {
            val k = CellIndex(r * 9 + c)
            if (c > 0) append(if (c % 3 == 0) " | " else " ")
            append(if (board.isGiven(k)) board[k].filled.toString() else ".")
        }
        append('\n')
        if (r % 3 == 2 && r != 8) append("------+-------+------\n")
    }
}
