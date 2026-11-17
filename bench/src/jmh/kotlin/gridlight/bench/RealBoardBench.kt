package gridlight.bench

import gridlight.engine.Board
import gridlight.engine.CellIndex
import gridlight.engine.CellSet
import gridlight.engine.Digit
import gridlight.engine.Geometry
import gridlight.engine.Transient
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The real engine `Board` / `Transient` (public API only), measured with the same workloads as [BoardBench]
 * so the numbers line up with PackedTableBench (the prototype without planes) and PlanesBench. Differences
 * that are part of the real API and therefore of what is measured: values are checked in `CellIndex.of` /
 * `Digit.of`, writes copy the 18-word array (immutable `Board`) or go through a `Transient`, and
 * `Geometry` is a parameter (D11). Hash is cached in the board after its first use.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class RealBoardBench {
    private val g = Geometry.STANDARD
    private lateinit var boards: Array<Board>        // initial candidates (givens eliminated from peers)
    private lateinit var transients: Array<Transient> // read-only use: scans, never persistent()
    private lateinit var copies: Array<Board>        // equal content, distinct arrays
    private lateinit var lastCellDiff: Array<Board>
    private lateinit var variants: Array<Board>      // 1024 distinct boards
    private lateinit var variantCopies: Array<Board> // equal to variants, distinct objects
    private val out = IntArray(256)

    private val readK = IntArray(READS); private val readR = IntArray(READS)
    private val readC = IntArray(READS); private val readB = IntArray(READS)
    private val opBoard = IntArray(OPS); private val opK = IntArray(OPS); private val opD = IntArray(OPS) // d is 1..9

    private fun initial(p: String): Board = Board.parse(p).eliminateFilledFromPeers(g)

    private fun candidatesOf(b: Board, k: Int): Int =
        b[CellIndex.of(k)].let { if (it.isFilled) 0 else it.candidates.size }

    /** A random open cell with >= 2 candidates, and one of them (1..9). */
    private fun pick(b: Board, rnd: Random): Pair<Int, Int> {
        while (true) {
            val k = rnd.nextInt(81)
            val c = b[CellIndex.of(k)]
            if (c.isFilled || c.candidates.size < 2) continue
            val ds = c.candidates.toList()
            return k to ds[rnd.nextInt(ds.size)].value
        }
    }

    /** Distinct boards derived from puzzle [i]: eliminations while they last, else placements on open cells, else clears. */
    private fun chain(i: Int, seed: Int): List<Board> {
        val rnd = Random(seed)
        var b = initial(Puzzles.ALL[i])
        val list = ArrayList<Board>(VARIANTS / NBOARDS)
        while (list.size < VARIANTS / NBOARDS) {
            val open = b.openCells().toList()
            val next = if (open.any { b[it].candidates.size >= 2 } && rnd.nextInt(4) != 0) {
                val (k, d) = pick(b, rnd)
                b.eliminate(CellIndex.of(k), Digit.of(d))
            } else if (open.isNotEmpty()) {
                b.place(open[rnd.nextInt(open.size)], Digit.of(rnd.nextInt(1, 10)))
            } else {
                val entered = b.filledCells().toList().filter { !b.isGiven(it) }
                b.clear(entered[rnd.nextInt(entered.size)])
            }
            if (next != b) { b = next; list += b }
        }
        return list
    }

    @Setup(Level.Trial)
    fun setup() {
        val n = Puzzles.ALL.size
        check(n == NBOARDS)
        boards = Array(n) { initial(Puzzles.ALL[it]) }
        transients = Array(n) { boards[it].transient() }
        copies = Array(n) { initial(Puzzles.ALL[it]) }
        lastCellDiff = Array(n) { i ->
            val b = boards[i]
            var k = 80
            while (b.isGiven(CellIndex.of(k))) k--
            b.place(CellIndex.of(k), Digit.of(1)).also { check(it != b) }
        }
        variants = (0 until n).flatMap { chain(it, 100 + it) }.toTypedArray()
        variantCopies = (0 until n).flatMap { chain(it, 100 + it) }.toTypedArray()
        check(variants.toSet().size == VARIANTS) { "variants must be distinct" }
        check(variants.indices.all { variants[it] == variantCopies[it] && variants[it] !== variantCopies[it] })
        val rnd = Random(42)
        for (j in 0 until READS) { readK[j] = rnd.nextInt(81); readR[j] = rnd.nextInt(1, 10); readC[j] = rnd.nextInt(1, 10); readB[j] = rnd.nextInt(n) }
        for (j in 0 until OPS) {
            val bi = rnd.nextInt(n); val (k, d) = pick(boards[bi], rnd)
            opBoard[j] = bi; opK[j] = k; opD[j] = d
        }
        // The real-board solvers must reach the same results as the prototype solver on PackedTableBoard.
        for (i in 0 until n) {
            val expected = solveSingles(parseInto(PackedTableBoard(), Puzzles.ALL[i]), IntArray(256))
            val cell = solveCellByCell(boards[i].transient(), IntArray(256), g)
            val scan = solveWithScans(boards[i].transient(), IntArray(256), g)
            check(cell == expected) { "puzzle $i: cell-by-cell ${cell.toString(16)} vs prototype ${expected.toString(16)}" }
            check(scan == expected) { "puzzle $i: scans ${scan.toString(16)} vs prototype ${expected.toString(16)}" }
        }
    }

    // ---- reads --------------------------------------------------------------------------------

    @Benchmark @OperationsPerInvocation(READS)
    fun readRandomK(): Int {
        var acc = 0
        for (j in 0 until READS) { val c = boards[readB[j]][CellIndex.of(readK[j])]; acc += if (c.isFilled) c.filled.value else 0 }
        return acc
    }

    @Benchmark @OperationsPerInvocation(READS)
    fun readRandomRC(): Int {
        var acc = 0
        for (j in 0 until READS) {
            val c = boards[readB[j]][gridlight.engine.Row.of(readR[j]), gridlight.engine.Column.of(readC[j])]
            acc += if (c.isFilled) c.filled.value else 0
        }
        return acc
    }

    // ---- scans (per board) -------------------------------------------------------------------

    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun nakedSingleScanNative(bh: Blackhole) { for (t in transients) bh.consume(t.scanNakedSingles(out)) }

    /** Computed from the cells (D18): no planes. Compare PlanesBench (plane popcounts) and PackedTableBench (generic bit-sliced). */
    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun hiddenSingleScanNative(bh: Blackhole) { for (t in transients) bh.consume(t.scanHiddenSingles(g, out)) }

    /** 27 regions x 9 digits of slotsOf(cellsWithCandidate(d)): what PlanesBoard's placesInUnit gives from planes. */
    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun placesAllUnits(bh: Blackhole) {
        for (b in boards) {
            var acc = 0
            for (d in 1..9) {
                val cs = b.cellsWithCandidate(Digit.of(d))
                for (r in g.regions) acc += r.slotsOf(cs).size shl (d and 3)
            }
            bh.consume(acc)
        }
    }

    /** Only the 9 `cellsWithCandidate` calls of [placesAllUnits] (the part planes would make free). */
    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun cellsWithCandidateAllDigits(bh: Blackhole) {
        for (b in boards) { var acc = 0; for (d in 1..9) acc += b.cellsWithCandidate(Digit.of(d)).size; bh.consume(acc) }
    }

    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun filledMissingDuplicatesAllRegions(bh: Blackhole) {
        for (b in boards) {
            var acc = 0
            for (r in g.regions) acc += b.filledIn(r).size + b.missingIn(r).size + b.duplicatesIn(r).size
            bh.consume(acc)
        }
    }

    // ---- iteration (D9: own non-generic iterators, value classes) ---------------------------

    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun iterateOpenCellsFor(bh: Blackhole) {
        for (b in boards) { var acc = 0; for (c in b.openCells()) acc += c.k; bh.consume(acc) }
    }

    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun iterateOpenCellsForEach(bh: Blackhole) {
        for (b in boards) { var acc = 0; b.openCells().forEach { acc += it.k }; bh.consume(acc) }
    }

    // ---- writes: immutable Board (each op allocates the new array) and edit {} ---------------

    /** place + explicit eliminateFromPeers (D19) through edit {}: one singles step. Copy + write + wrap. */
    @Benchmark @OperationsPerInvocation(OPS)
    fun editPlaceAndEliminatePeers(bh: Blackhole) {
        for (j in 0 until OPS) {
            val c = CellIndex.of(opK[j]); val d = Digit.of(opD[j])
            bh.consume(boards[opBoard[j]].edit { place(c, d); eliminateFromPeers(c, d, g) })
        }
    }

    /** Immutable single change: place, then eliminateFromPeers, each allocating (the naive path without edit {}). */
    @Benchmark @OperationsPerInvocation(OPS)
    fun placeThenEliminatePeersImmutable(bh: Blackhole) {
        for (j in 0 until OPS) {
            val c = CellIndex.of(opK[j]); val d = Digit.of(opD[j])
            bh.consume(boards[opBoard[j]].place(c, d).eliminateFromPeers(c, d, g))
        }
    }

    /** Comparable to eliminatePeersBulk (copyFrom + bulk) on the prototypes: copy + bulk elimination. */
    @Benchmark @OperationsPerInvocation(OPS)
    fun eliminatePeersBulk(bh: Blackhole) {
        for (j in 0 until OPS) bh.consume(boards[opBoard[j]].eliminateFromPeers(CellIndex.of(opK[j]), Digit.of(opD[j]), g))
    }

    @Benchmark @OperationsPerInvocation(OPS)
    fun eliminateSingleImmutable(bh: Blackhole) {
        for (j in 0 until OPS) bh.consume(boards[opBoard[j]].eliminate(CellIndex.of(opK[j]), Digit.of(opD[j])))
    }

    /** An edit that changes nothing: copy, no-op write, returns the original board. */
    @Benchmark @OperationsPerInvocation(OPS)
    fun editNoChange(bh: Blackhole) {
        for (j in 0 until OPS) {
            val c = CellIndex.of(opK[j]); val d = Digit.of(opD[j])
            bh.consume(boards[opBoard[j]].edit { clear(c); eliminate(c, CellSet.EMPTY, d) })
        }
    }

    private fun Transient.eliminate(c: CellIndex, cells: CellSet, d: Digit) = eliminate(cells, d)

    // ---- copy / dedup -------------------------------------------------------------------------

    /** Board.transient(): the allocating copy of LongArray(18). Compare `copy` on the prototypes (16 words). */
    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun copy(bh: Blackhole) { for (b in boards) bh.consume(b.transient()) }

    /** Cached after the first call, so this is the cost of reading the cached field. */
    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun hashCodeCached(bh: Blackhole) { for (b in boards) bh.consume(b.hashCode()) }

    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun equalsHit(bh: Blackhole) { for (i in 0 until NBOARDS) bh.consume(boards[i] == copies[i]) }

    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun equalsMissLastCell(bh: Blackhole) { for (i in 0 until NBOARDS) bh.consume(boards[i] == lastCellDiff[i]) }

    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun hashThenEquals(bh: Blackhole) {
        for (i in 0 until NBOARDS) { val a = boards[i]; val b = copies[i]; bh.consume(a.hashCode() == b.hashCode() && a == b) }
    }

    /** Fresh boards each time: includes computing the hash (a new board's first hashCode). One edit + hash. */
    @Benchmark @OperationsPerInvocation(OPS)
    fun editThenHash(bh: Blackhole) {
        for (j in 0 until OPS) bh.consume(boards[opBoard[j]].eliminate(CellIndex.of(opK[j]), Digit.of(opD[j])).hashCode())
    }

    // ---- HashSet<Board> (1024 distinct boards; hashes are cached after the first iteration) --------

    @Benchmark @OperationsPerInvocation(VARIANTS)
    fun hashSetInsert(bh: Blackhole) {
        val s = HashSet<Board>(2 * VARIANTS)
        for (b in variants) s.add(b)
        bh.consume(s)
    }

    private val set: HashSet<Board> by lazy { HashSet<Board>(2 * VARIANTS).also { it.addAll(variants) } }

    /** Probe with equal boards that are different objects: hash then equals on a hit. */
    @Benchmark @OperationsPerInvocation(VARIANTS)
    fun hashSetLookupHit(bh: Blackhole) { for (b in variantCopies) bh.consume(set.contains(b)) }

    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun hashSetLookupMiss(bh: Blackhole) { for (b in lastCellDiff) bh.consume(set.contains(b)) }

    // ---- end to end ---------------------------------------------------------------------------

    /** Same algorithm and same work as `solveSinglesE2E` on the prototypes: cell-by-cell naked singles, scan for hidden. Includes the copy. */
    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun solveSinglesE2E(bh: Blackhole) {
        for (b in boards) bh.consume(solveCellByCell(b.transient(), out, g))
    }

    /** The same solve using `scanNakedSingles` instead of reading every cell (what the Transient API is for). */
    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun solveSinglesE2EScan(bh: Blackhole) {
        for (b in boards) bh.consume(solveWithScans(b.transient(), out, g))
    }

    /** Per puzzle: Board.parse (81 chars) + eliminateFilledFromPeers: the load path. */
    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun parseAndInit(bh: Blackhole) { for (p in Puzzles.ALL) bh.consume(initial(p)) }

    companion object {
        const val NBOARDS = 8
        const val READS = 1024
        const val OPS = 64
        const val VARIANTS = 1024
    }
}

private fun Transient.step(c: CellIndex, d: Digit, g: Geometry) { place(c, d); eliminateFromPeers(c, d, g) }

/** Mirror of [solveSingles] on the real API. Returns (placements shl 2) or status. [scratch] needs >= 243 slots. */
private fun solveCellByCell(t: Transient, scratch: IntArray, g: Geometry): Int {
    var placements = 0
    while (true) {
        var progress = false
        var unplaced = 0
        for (k in 0 until 81) {
            val ci = CellIndex.of(k)
            val c = t[ci]
            if (c.isFilled) continue
            val cs = c.candidates
            if (cs.isEmpty()) return (placements shl 2) or SolveStatus.CONTRADICTION
            if (cs.size == 1) { t.step(ci, cs.first, g); placements++; progress = true } else unplaced++
        }
        if (progress) continue
        if (unplaced == 0) return (placements shl 2) or SolveStatus.SOLVED
        val n = t.scanHiddenSingles(g, scratch)
        for (i in 0 until n) {
            val ci = CellIndex.of(scratch[i] ushr 4)
            val d = Digit.of((scratch[i] and 15) + 1)
            val c = t[ci]
            if (c.isFilled) continue
            if (d !in c.candidates) return (placements shl 2) or SolveStatus.CONTRADICTION
            t.step(ci, d, g); placements++; progress = true
        }
        if (!progress) return (placements shl 2) or SolveStatus.STUCK
    }
}

/** Naked singles from `scanNakedSingles` (word at a time), hidden singles from `scanHiddenSingles`. */
private fun solveWithScans(t: Transient, scratch: IntArray, g: Geometry): Int {
    var placements = 0
    while (true) {
        var progress = false
        val n = t.scanNakedSingles(scratch)
        for (i in 0 until n) {
            val ci = CellIndex.of(scratch[i])
            val c = t[ci]
            if (c.isFilled) continue
            val cs = c.candidates
            if (cs.isEmpty()) return (placements shl 2) or SolveStatus.CONTRADICTION
            if (cs.size == 1) { t.step(ci, cs.first, g); placements++; progress = true }
        }
        if (progress) continue
        if (t.openCells().isEmpty()) return (placements shl 2) or SolveStatus.SOLVED
        val m = t.scanHiddenSingles(g, scratch)
        for (i in 0 until m) {
            val ci = CellIndex.of(scratch[i] ushr 4)
            val d = Digit.of((scratch[i] and 15) + 1)
            val c = t[ci]
            if (c.isFilled) continue
            if (d !in c.candidates) return (placements shl 2) or SolveStatus.CONTRADICTION
            t.step(ci, d, g); placements++; progress = true
        }
        if (!progress) return (placements shl 2) or SolveStatus.STUCK
    }
}
