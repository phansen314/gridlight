package gridlight.engine

// Rule checks (D10): outside Board, region-only. Constraint-aware checks come later with Puzzle.

/** Filled cells whose digit also appears in another cell of one of their regions (givens included). */
fun conflicts(board: Board, geometry: Geometry): CellSet {
    var out = CellSet.EMPTY
    for (region in geometry.regions) {
        val dups = board.duplicatesIn(region)
        if (dups.isEmpty()) continue
        region.forEachCell { c ->
            val cell = board[c]
            if (cell.isFilled && cell.filled in dups) out += c
        }
    }
    return out
}

/** Every cell filled and no conflicts. */
fun isSolved(board: Board, geometry: Geometry): Boolean = board.openCells().isEmpty() && conflicts(board, geometry).isEmpty()
