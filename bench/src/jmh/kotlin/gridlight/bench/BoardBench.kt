package gridlight.bench

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * All benchmarks are defined once here and inherited by [PackedBench] and [ShortBench]. JMH runs
 * every benchmark in its own forked JVM, and a fork only ever creates boards of one class, so every
 * MutableBoard call site below has a monomorphic type profile (the other board class is typically
 * never even loaded) and C2 inlines it. Results are reported per operation (see
 * @OperationsPerInvocation) in ns.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
abstract class BoardBench<B : MutableBoard<B>> {
    protected abstract fun newBoard(): B
    protected abstract fun newArray(n: Int): Array<B>

    private lateinit var boards: Array<B>      // initial-candidate state of each puzzle
    private lateinit var copies: Array<B>      // equal content, distinct arrays
    private lateinit var lastCellDiff: Array<B> // differs only in cell 80 (worst case for equals)
    private lateinit var scratch: B
    private val out = IntArray(256)

    private val readK = IntArray(READS)
    private val readR = IntArray(READS)
    private val readC = IntArray(READS)
    private val readB = IntArray(READS)

    private val elimBoard = IntArray(ELIMS)
    private val elimK = IntArray(ELIMS)
    private val elimD = IntArray(ELIMS)

    @Setup(Level.Trial)
    fun setup() {
        val n = Puzzles.ALL.size
        check(n == NBOARDS)
        boards = newArray(n); copies = newArray(n); lastCellDiff = newArray(n)
        for (i in 0 until n) {
            boards[i] = parseInto(newBoard(), Puzzles.ALL[i])
            copies[i] = boards[i].copy()
            lastCellDiff[i] = boards[i].copy().also {
                if (it.isPlaced(80)) it.setCandidates(80, 0x1FF) else it.place(80, 0)
            }
        }
        scratch = newBoard()
        val rnd = Random(42)
        for (j in 0 until READS) {
            readK[j] = rnd.nextInt(81); readR[j] = rnd.nextInt(9); readC[j] = rnd.nextInt(9); readB[j] = rnd.nextInt(n)
        }
        // Realistic propagation steps: an unplaced cell with >= 2 candidates and one of them.
        var j = 0
        while (j < ELIMS) {
            val bi = rnd.nextInt(n); val k = rnd.nextInt(81); val b = boards[bi]
            val m = b.candidates(k)
            if (b.isPlaced(k) || Integer.bitCount(m) < 2) continue
            var pick = rnd.nextInt(Integer.bitCount(m)); var mm = m
            while (pick-- > 0) mm = mm and (mm - 1)
            elimBoard[j] = bi; elimK[j] = k; elimD[j] = Integer.numberOfTrailingZeros(mm); j++
        }
    }

    // ---- reads --------------------------------------------------------------------------------

    @Benchmark @OperationsPerInvocation(READS)
    fun readRandomK(): Int {
        var acc = 0
        for (j in 0 until READS) acc += boards[readB[j]].cell(readK[j]).bits
        return acc
    }

    @Benchmark @OperationsPerInvocation(READS)
    fun readRandomRC(): Int {
        var acc = 0
        for (j in 0 until READS) acc += boards[readB[j]][readR[j], readC[j]].bits
        return acc
    }

    // ---- scans (per board) -------------------------------------------------------------------

    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun unitMissingMasks(bh: Blackhole) {
        for (b in boards) bh.consume(unitMissing(b, out))
    }

    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun nakedSingleScan(bh: Blackhole) {
        for (b in boards) bh.consume(nakedSingles(b, out))
    }

    /** Board's own scan: cell-by-cell default for Packed/Short, word-at-a-time (SWAR) for PackedTable. */
    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun nakedSingleScanNative(bh: Blackhole) {
        for (b in boards) bh.consume(b.scanNakedSingles(out))
    }

    /** Board's own hidden-single scan: bit-sliced by default, plane popcounts for PlanesBoard. */
    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun hiddenSingleScanNative(bh: Blackhole) {
        for (b in boards) bh.consume(b.scanHiddenSingles(out))
    }

    /** All 27 units x 9 digits of placesInUnit(u, d), per board: the fish/subset query. */
    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun placesAllUnits(bh: Blackhole) {
        for (b in boards) {
            var acc = 0
            for (u in 0 until 27) for (d in 0 until 9) acc += b.placesInUnit(u, d) shl (d and 3)
            bh.consume(acc)
        }
    }

    private val planes = LongArray(18)

    /** Build all 9 digit planes from the cells (the on-demand alternative to keeping them in step). */
    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun buildPlanesOnDemand(bh: Blackhole) {
        for (b in boards) { buildPlanes(b, planes); bh.consume(planes[0] xor planes[17]) }
    }

    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun hiddenSingleScanNaive(bh: Blackhole) {
        for (b in boards) bh.consume(hiddenSinglesNaive(b, out))
    }

    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun hiddenSingleScanBitSliced(bh: Blackhole) {
        for (b in boards) bh.consume(hiddenSinglesBitSliced(b, out))
    }

    // ---- propagation (each op = copyFrom + eliminate; subtract copyFromOnly) -----------------

    @Benchmark @OperationsPerInvocation(ELIMS)
    fun copyFromOnly(bh: Blackhole) {
        val s = scratch
        for (j in 0 until ELIMS) { s.copyFrom(boards[elimBoard[j]]); bh.consume(s.candidates(elimK[j])) }
    }

    @Benchmark @OperationsPerInvocation(ELIMS)
    fun eliminatePeersBulk(bh: Blackhole) {
        val s = scratch
        for (j in 0 until ELIMS) { s.copyFrom(boards[elimBoard[j]]); bh.consume(s.eliminateFromPeers(elimK[j], elimD[j])) }
    }

    @Benchmark @OperationsPerInvocation(ELIMS)
    fun eliminatePeersLoop(bh: Blackhole) {
        val s = scratch
        val peers = Geo.PEERS
        for (j in 0 until ELIMS) {
            s.copyFrom(boards[elimBoard[j]])
            val base = elimK[j] * 20; val d = elimD[j]
            var changed = false
            for (p in 0 until 20) changed = s.eliminate(peers[base + p], d) or changed
            bh.consume(changed)
        }
    }

    // ---- snapshot / dedup --------------------------------------------------------------------

    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun copy(bh: Blackhole) {
        for (b in boards) bh.consume(b.copy())
    }

    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun hashCodeOnly(bh: Blackhole) {
        for (b in boards) bh.consume(b.hashCode())
    }

    /** equals on equal content (full compare) — the dedup hit path. */
    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun equalsHit(bh: Blackhole) {
        for (i in 0 until NBOARDS) bh.consume(boards[i] == copies[i])
    }

    /** equals that differs only in the last cell — the worst-case miss after a hash collision. */
    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun equalsMissLastCell(bh: Blackhole) {
        for (i in 0 until NBOARDS) bh.consume(boards[i] == lastCellDiff[i])
    }

    /** Typical dedup probe: hash then equals. */
    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun hashThenEquals(bh: Blackhole) {
        for (i in 0 until NBOARDS) {
            val a = boards[i]; val b = copies[i]
            bh.consume(a.hashCode() == b.hashCode() && a == b)
        }
    }

    // ---- end to end --------------------------------------------------------------------------

    /** Per puzzle: copyFrom(initial) + singles-only solve until solved/stuck. */
    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun solveSinglesE2E(bh: Blackhole) {
        val s = scratch
        for (b in boards) { s.copyFrom(b); bh.consume(solveSingles(s, out)) }
    }

    /** Per puzzle: parse 81 chars + initial candidates (load path). */
    @Benchmark @OperationsPerInvocation(NBOARDS)
    fun parseAndInit(bh: Blackhole) {
        for (p in Puzzles.ALL) bh.consume(parseInto(newBoard(), p))
    }

    companion object {
        const val NBOARDS = 8
        const val READS = 1024
        const val ELIMS = 64
    }
}

open class PackedBench : BoardBench<PackedBoard>() {
    override fun newBoard() = PackedBoard()
    override fun newArray(n: Int) = Array(n) { PackedBoard() }
}

open class ShortBench : BoardBench<ShortBoard>() {
    override fun newBoard() = ShortBoard()
    override fun newArray(n: Int) = Array(n) { ShortBoard() }
}

open class PackedTableBench : BoardBench<PackedTableBoard>() {
    override fun newBoard() = PackedTableBoard()
    override fun newArray(n: Int) = Array(n) { PackedTableBoard() }
}

open class PlanesBench : BoardBench<PlanesBoard>() {
    override fun newBoard() = PlanesBoard()
    override fun newArray(n: Int) = Array(n) { PlanesBoard() }
}
