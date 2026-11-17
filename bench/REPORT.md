# Board representation benchmark: draft for the board-representation research

Status: **draft**. Code builds, the tests pass, and JMH smoke runs finished. A full run has not been done.
Four boards are compared:
- `PackedBoard`: the decided layout, using `k/6` arithmetic.
- `PackedTableBoard`: the same layout and bits, with table-driven locations and one word-at-a-time scan.
- `PlanesBoard`: PackedTable plus nine digit planes kept in step. It's the design decided in the project's scope notes;
  see "Fourth board" below.
- `ShortBoard`: the baseline.
Branch `research/bench-board-repr`, not committed.

## What's implemented

Standalone Gradle project (`bench/`): Gradle 9.8.2 wrapper, Kotlin 2.4.21, JDK 25 toolchain,
`me.champeau.jmh` 0.7.4, JMH 1.37, JOL 0.17, JUnit 5.

| File | Contents |
|---|---|
| `src/main/.../Cell.kt` | `@JvmInline value class Cell(bits)`: the decided 10-bit cell. `mask` is bits 0–8, `isPlaced` is bit 9, `digit` is ntz. |
| `src/main/.../Board.kt` | `MutableBoard<B>` interface: `cell(k)`, `get(r,c)`, `candidates`, `isPlaced`, `isGiven`, `place`, `placeGiven`, `setCandidates`, `eliminate`, `eliminateFromPeers`, `copy`, `copyFrom`, `contentEquals` (plus `equals`/`hashCode` on each class). |
| `src/main/.../PackedBoard.kt` | **The decided design**: `private val w = LongArray(16)`. Cell k is in word k/6 at shift (k%6)*10. Words 14–15 hold the given mask. Spare bits stay 0 (a test checks this). `eliminateFromPeers` is SWAR: one masked AND-NOT per word with a precomputed `PEER_LANES[k*14+i]` table, skipping placed lanes without branching. |
| `src/main/.../PackedTableBoard.kt` | **Same storage and bit layout as PackedBoard** (tests check the 16 raw words are identical after every op). Cell locations come from tables, not `k/6`, `(k%6)*10`. Each location is a single bit position `pos = word*64 + shift`, so a read is `w[pos ushr 6] ushr pos`: the JVM's long shift already masks the count to 6 bits. Tables: `POS[81]`, `UNIT_POS[243]`, `PEER_POS[1620]` as `ShortArray` (about 3.9 KB). It also has a word-at-a-time naked-single scan (described below). |
| `src/main/.../PlanesBoard.kt` | **Digit planes kept in step**, ported from the research sketch `board-with-planes.kt`. One `LongArray(34)`: words 0–13 are cells (bit-identical to PackedBoard), 14–15 the given mask, 16–33 nine digit planes (2 longs per digit; cells 0..53 in the low word and 54..80 in the high word, so every row and box sits inside one long). The planes hold unplaced candidates only, and every write updates cells and planes together. `equals`/`hashCode` cover words 0–15 only, using the same 31-polynomial as the others. Native overrides: hidden singles from `popcount(plane & unit mask)`, `placesInUnit`, plus PackedTable's word-at-a-time naked scan and cell elimination (with two plane ANDs added). |
| `src/main/.../ShortBoard.kt` | Baseline: `ShortArray(81)` with the same cell encoding, and the given mask in **two `Long` fields**. |
| `src/main/.../Board.kt` (hooks) | `unitCell(u, i)`, `peerCell(k, j)`, `scanNakedSingles(out)`, `scanHiddenSingles(out)` (the singles solver now calls this one; the default is the bit-sliced scan, so the other boards' solve is unchanged) and `placesInUnit(u, d)` are interface methods with default implementations that read cell by cell, so a board can override them with table or word-level versions. Packed and Short use the defaults, which inline to exactly what they did before. |
| `src/main/.../Algorithms.kt` | Written once, independent of representation: parse plus initial candidates, unit "missing digits" masks, naked-single scan, hidden-single scan in two forms (literal per-unit/per-digit count, and bit-sliced once/twice), and a singles-only solver. |
| `src/main/.../Puzzles.kt` | 8 published puzzles: Euler #96 grids 01–03, Norvig hard1, Inkala, one 17-clue puzzle, two from Norvig's "hardest" list. With singles only, 4 are solved and 4 get stuck. |
| `src/main/.../MemoryReport.kt` | JOL footprint (`./gradlew :bench:memoryReport`). |
| `src/test/.../BoardAgreementTest.kt` | Correctness checks (below). |
| `src/jmh/.../BoardBench.kt` | All benchmarks, in one abstract class inherited by `PackedBench` and `ShortBench`. |

**Why two Longs for the given mask, not a BooleanArray:** there's no second array object to allocate,
copy, compare or hash. The bits line up with the packed layout. It costs 16 bytes against about 104 for
`BooleanArray(81)` (16-byte header plus 81 bytes, padded). The cost is one predictable `k < 64` branch in `isGiven`.

**Semantics I chose (worth confirming in the board-representation research):**
- `eliminate` on a placed cell does nothing and returns false. That keeps tag=1 ⇒ popcount==1 true without a check.
- `eliminateFromPeers` leaves placed peers alone. So a user-entered conflicting digit stays placed. The `conflictingUserDigitSurvivesBulkElimination` test covers this.
- `place`/`setCandidates` don't change the given bit, and `place` doesn't propagate.
  Locking givens is left to the engine layer, not the board.
- `equals`/`hashCode` include the given mask. They compare only within one class.

### Dispatch (how I kept virtual calls from dominating)
The algorithms and benchmarks go through the `MutableBoard` interface. They're written once, in
`Algorithms.kt` and the abstract `BoardBench<B>`, with subclasses `PackedBench`, `PackedTableBench` and `ShortBench`. JMH runs each benchmark in its own forked JVM, and a fork
only ever creates one board class. So every interface call site has a monomorphic type profile, and C2
inlines it behind a cheap class guard. Usually the other class is never loaded at all, which means
class-hierarchy analysis can devirtualize outright.

The alternative was to copy every benchmark body per concrete type. I didn't, because it doubles the code
and gains nothing while forks stay isolated.

**Checked with the JIT's inlining log**, since `perf` and hsdis aren't installed and `-prof perfasm`
doesn't work. I ran `-jvmArgsAppend "-XX:+UnlockDiagnosticVMOptions -XX:+PrintCompilation -XX:+PrintInlining"`
on `PackedTableBench.unitMissingMasks`, `PackedBench.eliminatePeersLoop` and `ShortBench.solveSinglesE2E`.
At every board call site the profile was 100% one class (e.g.
`TypeProfile (1583388/1583388 counts) = PackedTableBoard`), and the final compile shows `inline (hot)`.
The `failed to inline: callee is too large` lines come from earlier, quicker compiles that get replaced.
The generated machine code itself hasn't been inspected; that needs perfasm.

### Correctness tests (`./gradlew test`, 11 tests, all pass)
- A naive reference model (`RefBoard`: `IntArray` + `BooleanArray`, peers worked out from r/c/box
  arithmetic, not the shared tables). It runs 40 seeds × 1500 random ops (place, placeGiven,
  setCandidates, eliminate, eliminateFromPeers) against **both** boards. After every op it compares all
  81 cells via `cell`/`get(r,c)`/`candidates`/`isPlaced`/`isGiven` and the return values. It also checks
  that the packed spare bits stay 0 and the tag invariant holds.
- Parsing matches the reference for all puzzles, and the givens are checked for conflicts. That check
  caught one puzzle I'd misremembered, which I replaced.
- The bulk `eliminateFromPeers` matches a loop of 20 `eliminate` calls, for every (puzzle, k, d).
- `copy` is independent of the original. Equal boards have equal hashes. `copyFrom` works.
  Changing any one cell, or a given bit, makes `equals` false.
- Unit-missing, naked and hidden scans agree across both representations, and the naive and bit-sliced
  hidden scans agree, over several solve steps.
- `PackedTableBoard` is in every test above. On top of that, its 16 raw words must match `PackedBoard`'s
  after every random op (same layout, bit for bit). `unitCell`/`peerCell` must match `cell(UNITS/PEERS[...])`
  on all boards. The word-at-a-time naked scan must match the cell-by-cell one, including on the
  arbitrary, invalid states the random ops produce (empty masks, cells with every digit set, placed lanes).
- `PlanesBoard` is in every test. After every random op, its words 0–15 must equal PackedBoard's, and its
  planes must equal planes rebuilt from the cells by a separate naive function in the test. Its hidden scan
  and `placesInUnit` must match the generic versions on arbitrary, unpropagated states too: a digit placed
  in a unit while still a candidate elsewhere in it. The tests do catch a missing "already placed in this
  unit" guard; I checked by temporarily removing it. `buildPlanes` (on-demand planes) must equal the
  kept planes after a solve. 11 tests in total, all passing.
- The singles solver gives identical status, placement count and final board on both. Its output is sound
  (no conflicting placed peers, givens kept). Euler 01 solves to the known solution.

## How to run

```sh
./gradlew :bench:test                     # correctness first
./gradlew :bench:memoryReport             # JOL footprint
./gradlew :bench:jmh -Pjmh.smoke          # quick run of all benchmarks (1 fork, short iterations)
./gradlew :bench:jmh -Pjmh.includes=solveSinglesE2E    # filter by regex
./gradlew :bench:jmh                      # full run using the class annotations (2 forks × 5×1 s), ~5 min

# Or drive JMH directly (more control):
./gradlew :bench:jmhJar
java -jar bench/build/libs/bench-jmh.jar -l
java -jar bench/build/libs/bench-jmh.jar -f 1 -wi 2 -i 3 -w 300ms -r 300ms -prof gc
java -jar bench/build/libs/bench-jmh.jar 'eliminatePeers' -prof perfasm   # codegen (needs perf + hsdis)
# inlining/type-profile check without perf:
java -jar bench/build/libs/bench-jmh.jar PackedTableBench.unitMissingMasks -f 1 -wi 2 -i 1 \
  -jvmArgsAppend "-XX:+UnlockDiagnosticVMOptions -XX:+PrintCompilation -XX:+PrintInlining" | grep -E 'Board::|TypeProfile'
```

All scores are **ns per operation**, where one operation is one read, one board, or one elimination step
(see `@OperationsPerInvocation`).

## Smoke-run numbers: ROUGH, do not quote

Command: `-f 1 -wi 2 -i 3 -w 300ms -r 300ms -prof gc`. One fork, about 2 s per benchmark.
Machine: AMD Ryzen 9 7900X, OpenJDK 25.0.4.1, default GC, compressed oops.
Raw output is in `build/results/smoke2.{txt,json}` (all three boards) and `smoke.{txt,json}` (first
run, Packed vs Short); both are gitignored. `python3 build/results/table.py build/results/smoke2.json`
reprints the table. Packed and Short came out within a few percent of the first run, so the setup looks
repeatable even at these short settings.

ns per op. Ratio columns: below 1.00 means PackedTable is faster.

| benchmark | Packed | PackedTable | Short | Table/Packed | Table/Short |
|---|---:|---:|---:|---:|---:|
| readRandomK (`cell(k)`) | 1.69 | 1.35 | 0.71 | 0.79 | 1.89 |
| readRandomRC (`[r,c]`) | 1.99 | 1.31 | 1.15 | 0.66 | 1.13 |
| unitMissingMasks (27 units) | 321 | 196 | 118 | 0.61 | 1.67 |
| nakedSingleScan (generic, cell by cell) | 79 | 45 | 33 | 0.57 | 1.38 |
| **nakedSingleScanNative** (SWAR for Table) | 79 | **20.8** | 31 | **0.26** | **0.68** |
| hiddenSingleScanNaive | 2397 | 1423 | 1292 (±1145!) | 0.59 | 1.10 |
| hiddenSingleScanBitSliced | 477 | 297 | 233 | 0.62 | 1.28 |
| copyFromOnly (baseline for elims) | 3.66 | 3.80 | 4.24 | 1.04 | 0.90 |
| eliminatePeersBulk (+copyFrom) | 13.8 | 13.7 | 21.5 | 0.99 | 0.64 |
| eliminatePeersLoop (+copyFrom) | 43.4 | 33.3 | 22.4 | 0.77 | 1.49 |
| copy (allocating) | 6.5 (±25) | 6.8 (±27) | 8.3 | 1.04 | 0.81 |
| hashCodeOnly | 7.8 | 7.9 | 11.5 | 1.02 | 0.69 |
| equalsHit | 4.1 | 4.1 | 5.7 | 0.99 | 0.72 |
| equalsMissLastCell | 4.2 | 4.3 | 5.3 | 1.02 | 0.80 |
| hashThenEquals | 18.7 | 18.6 | 28.9 | 0.99 | 0.64 |
| **solveSinglesE2E** (+copyFrom) | 2168 | **1492** | 2298 | **0.69** | **0.65** |
| parseAndInit | 1397 | 785 | 665 | 0.56 | 1.18 |

**Memory (JOL, JDK 25, compressed oops, no compact headers):** PackedBoard **160 B** (object 16 +
`long[16]` 144). ShortBoard **216 B** (object 32 + `short[81]` 184). PackedTableBoard has the same
fields as PackedBoard, so it's also 160 B; its tables are static and shared, about 3.9 KB in total.
With `-XX:+UseCompactObjectHeaders` all three should get a little smaller. I haven't measured that.

**What the numbers say so far:**
- **Tables fix most of the per-read cost.** PackedTable is 1.3–1.8× faster than Packed on every
  cell-by-cell benchmark, with no loss anywhere else. The storage is identical, so copy, hash, equals,
  bulk elimination and memory are unchanged.
- **It's still slower than ShortArray at cell-by-cell reads** (1.1–1.9×). Even with the table, a read is
  a table load, then the word load (which depends on it), then a 64-bit shift and a narrowing. ShortArray
  is one `movzx`. Tables can't remove that extra dependent load.
- **The real win is working a whole word at a time.** The word-at-a-time naked scan is 3.8× faster than
  Packed and 1.5× faster than Short. Together with the bulk peer elimination (1.6× faster than Short),
  this shows that bulk operations are where the layout pays off.
- **End to end, PackedTable now wins: 1.49 µs per puzzle vs 2.17 (Packed) and 2.30 (Short).** That's
  without the solver using the fast naked scan; its own scan loop still reads through `cell(k)`.
  Switching it to `scanNakedSingles`, and moving hidden singles to word-level operations, are the
  obvious next steps.
- **Recommendation for the board-representation research:** keep the decided layout, and make table-driven locations the standard way
  to read a cell. Push hot scans into the board as word-level operations rather than generic per-cell
  loops. The data so far doesn't support needing a ShortArray scratch copy for solving.

### The word-at-a-time naked-single scan, briefly
The scan handles all 6 cells in a word at once ("SWAR": SIMD within a register). Per word, with
`G` = bit 9 of every cell and `L` = bit 0 of every cell:
`x = word & DIGITS`, `y = (x | G) - L` (setting `G` first means a cell that is 0 can't borrow from the
next cell up; bit 9 survives only if x ≠ 0), `z = x & y` (0 iff at most one bit), and
`hits = (y & G) & ~((z | G) - L) & G & ~(word & G)`. It visits only the cells that hit. That's about 10
ALU ops per 6 cells, against 6 × (load + shift + popcount + branch).

## Fourth board: PlanesBoard (digit planes kept in step)

Commands:
- Smoke run over all four boards: `-f 1 -wi 2 -i 3 -w 300ms -r 300ms -prof gc` →
  `build/results/smoke3.{txt,json}`.
- Re-run of the noisy and key write benchmarks: `-f 3 -wi 2 -i 10` → `build/results/noisy3.json`.
- Plane-query benchmarks: `-f 3 -i 5` → `build/results/planes-ondemand.json`.

Rows marked † use the 3-fork runs; the others are the smoke run. `python3 build/results/table.py <json>`
reprints any of them. ns per op. **Still rough.**

| benchmark | Packed | PackedTable | **Planes** | Short | Planes/Table | Planes/Short |
|---|---:|---:|---:|---:|---:|---:|
| **Writes** | | | | | | |
| copyFromOnly † | 5.48* | 3.52 | **5.15** | 4.12 | 1.46 | 1.25 |
| copy (allocating) † | 6.25 | 5.99 | **11.29** | 8.53 | 1.88 | 1.32 |
| eliminatePeersBulk (+copyFrom) † | 13.81 | 13.56 | **17.25** | 22.38 | 1.27 | 0.77 |
| eliminatePeersLoop (+copyFrom) † | 41.75 | 32.54 | **33.54** | 23.28 | 1.03 | 1.44 |
| parseAndInit | 1375 | 793 | **1140** | 659 | 1.44 | 1.73 |
| **Plane queries** | | | | | | |
| hiddenSingleScanNative † | 457 | 305 | **185** | 233 | 0.61 | 0.79 |
| placesAllUnits (27×9 counts) † | 2974 | 1711 | **95** | 1224 | 0.06 | 0.08 |
| buildPlanesOnDemand † | 250 | 229 | 227 | 179 | (n/a) | (n/a) |
| **End to end** | | | | | | |
| **solveSinglesE2E (+copyFrom)** † | 2239 | 1475 | **1367** | 2322 | **0.93** | **0.59** |
| **Unchanged (cell reads/scans)** | | | | | | |
| readRandomK | 1.72 | 1.29 | 1.29 | 0.70 | 1.00 | 1.85 |
| unitMissingMasks | 326 | 194 | 196 | 117 | 1.01 | 1.67 |
| nakedSingleScanNative | 76 | 20.1 | 20.1 | 33.6 | 1.00 | 0.60 |
| hiddenSingleScanBitSliced | 463 | 295 | 299 | 231 | 1.01 | 1.30 |
| hiddenSingleScanNaive † | 2396 | 1407 | 1402 | 1351 | 1.00 | 1.04 |
| equalsHit / hashCodeOnly / hashThenEquals | 4.3 / 7.9 / 18.5 | 4.1 / 8.1 / 18.3 | 3.8 / 7.4 / 17.2 | 5.8 / 11.0 / 28.8 | ~0.93 | ~0.63 |

\* Packed `copyFromOnly` was bimodal: forks ran at 3.70, 9.08 and 3.66 ns. See "suspicious" below.

**Memory (JOL):** PlanesBoard **304 B** (16 + `long[34]` 288), vs 160 B for both packed boards and 216 B
for ShortBoard. GC allocation per op (`-prof gc`) matches: 304 B per `copy`/`parse`, and 0 for every
in-place op.

### Answers to the key questions

**What the planes cost on writes**
- **Copy:** `copyFrom` costs +1.6 ns (5.15 vs 3.52; 272 vs 128 bytes). An allocating `copy` costs +5.3 ns
  (11.3 vs 6.0), roughly in proportion to size: 304 B vs 160 B. That's the price of every snapshot used
  for undo and the trace.
- **Bulk peer elimination:** +2.1 ns once `copyFrom` is subtracted (about 12.1 vs 10.0 ns, +21%), for the
  two extra plane AND-NOTs plus their loads and stores. It's still faster than ShortArray (about 18 ns).
- **Single-cell eliminate:** the extra plane write is invisible. `eliminatePeersLoop` is the same as
  PackedTable once copy is subtracted (28.4 vs 29.0 ns). That fits the earlier guess that this loop is
  limited by the wait on each cell word (the store-to-load chain), so the plane store fits alongside it
  for free. It's consistent with that guess, but doesn't prove it.
- **Writes that touch all nine planes** (`place` and `setCandidates` each do 9 read-modify-writes):
  `parseAndInit` is 1.44× PackedTable (1140 vs 793 ns), which is about 7 ns extra per cell that has its
  candidates set. `place` alone isn't benchmarked separately, but it does the same 9 RMWs.

**What the planes save**
- **Hidden singles:** 185 ns vs 305 (PackedTable's bit-sliced scan) and 233 (Short): 40% less than
  PackedTable, 21% less than Short.
- **`placesInUnit` for all 27×9 unit/digit pairs** (the fish/subset query): **95 ns** vs 1711
  (PackedTable, naive per-query reads), so 18×. A fairer on-demand comparison is building all planes once
  from the cells (`buildPlanesOnDemand`, about 229 ns on PackedTable) and then doing the same popcounts
  (about 95 ns), about 325 ns in total. **So keeping planes saves about 230 ns per full strategy pass
  against the best on-demand approach (3.4×),** not 18×.
- **End to end (singles solve):** 1367 vs 1475 ns, **7% faster than PackedTable and 41% faster than
  ShortArray.** The hidden-single savings slightly outweigh the extra cost of 9 plane writes per
  placement. The solver's naked-single phase still reads `cell(k)` one cell at a time on every board.

**Overall:** the planes roughly pay for themselves in the singles solver, and the real payoff comes from
strategies above singles. Every fish, subset or locked-candidates pass is mostly `placesInUnit` /
position queries, where kept planes are 3.4× faster than the best on-demand build and 18× faster than
naive reads. The costs are memory (1.9× per board) and copy time (+1.6 to +5 ns per snapshot).

## Things that look suspicious / caveats
0. **Some forks are bimodal (new with the 3-fork runs).** Packed `copyFromOnly` ran at 3.70 / 9.08 /
   3.66 ns across its three forks, and Planes `eliminatePeersBulk` at 16.6 / 18.5 / 16.6. One-fork smoke
   runs can therefore land on the slow mode: smoke3 showed Short `copyFromOnly` at 8.98 vs 4.1 in the
   3-fork run, and Packed `eliminatePeersBulk` at 17.95 vs 13.8. Possible causes, all **unverified**: the
   JIT picking a different array-copy routine or code alignment per JVM, or the 7900X's two core
   complexes (a fork landing on the other die). Use ≥3 forks for anything that matters, and consider
   pinning with `taskset`.
1. **Very wide error bars**: `copy` (±24 on 6.5 ns, which is allocation and GC noise at 300 ms
   iterations), Short `hiddenSingleScanNaive` (±640), and Packed `eliminatePeersLoop` (±14). One fork with
   3 short iterations isn't enough. Re-run these with `-f 3 -i 10`.
2. **`eliminatePeersLoop` (20 single-cell `eliminate` calls) is still slow on both packed boards:
   Packed 43 ns, PackedTable 33 ns, Short 22 ns.** The inlining log rules out dispatch. My **unverified**
   guess is a store-to-load chain. The 8 row peers sit in 2–3 neighbouring words, so each `eliminate` reads
   the word the previous one just wrote, and those steps can't overlap. ShortArray writes to different
   elements, so its steps run in parallel. If that's right, single-cell elimination in a loop is the wrong
   pattern for packed, and the bulk version (13.7 ns) is the one to use. Confirming it needs perfasm.
3. Monomorphic dispatch is now confirmed from the inlining log (see "Dispatch"), but the actual machine
   code hasn't been read. `perf` and hsdis would need installing (with sudo) for `-prof perfasm`.
4. **`hashCode` uses `contentHashCode`** (31-polynomial). On JDK 21+ `Arrays.hashCode(short[])` is
   vectorized but `long[]` is not, so Packed's win here comes from 16 elements vs 81, not from a better
   hash. The 31-poly over `Long.hashCode` (hi^lo) is also a fairly weak mix for dedup tables. A real
   board should use a proper mixer (for example a wyhash/xxhash-style fold of the 16 words) and probably
   cache it on the immutable board.
5. **`copyFrom` is in the elimination and solve timings.** The elimination step needs a fresh board each
   time, and `@Setup(Level.Invocation)` is useless at the ns scale. Subtract `copyFromOnly` (about 4 ns
   for both).
6. The hidden-single scan needs a cell→unit search after the bit-sliced count. Both boards do this the
   same way, so it doesn't change the comparison.
7. Only 8 puzzles, and the boards are all at their initial-candidate state. Boards mid-solve have
   different branch profiles. Fine for a first comparison, not for final numbers.
8. `parseAndInit` allocates 1 board per puzzle, and its error bar is wide too.

## Open questions for the board-representation research
1. **Word-level scans for the packed board.** Tables (done) and one word-at-a-time scan (naked
   singles, done) together put packed ahead end to end. What's left to try:
   (a) Have the solver use `scanNakedSingles` instead of its own `cell(k)` loop. That's cheap and probably
   worth a good share of the remaining scan time.
   (b) Word-level unit questions using per-unit word masks (`UNIT_LANES[u*14+i]`, 3 KB). For example,
   placed digits in a row, or "cells in unit u that can hold d". Rows are contiguous (2–3 words), but
   columns and boxes are spread across many words, so they gather from up to 9 words. That's still cheap
   with the masks.
   (c) Hidden singles from digit planes (question 4). This is probably where the rest of the solver's
   time goes.
   (d) A per-cell iterator over the words, for code that really does need every cell in order.
   The "solve on an IntArray scratch copy" variant now looks unnecessary, but it's cheap to add if the
   word-level scans stall.
2. **Bulk ops and user-entered conflicting digits.** As written, bulk elimination skips placed lanes, so
   a conflicting user digit stays placed and is never cleared. Does the engine want that, so conflicts
   are found by a separate conflict scan? Or should propagation (a) refuse, (b) report the conflict, or
   (c) never run from user entries, only from solver placements? Also: when a user *clears* a digit,
   should candidates be recomputed for that cell and all its peers? That's a full `initCandidates`-style
   pass, since elimination can't be undone locally.
3. **Should bulk elimination skip placed lanes at all?** With one-hot it's harmless to AND-NOT a placed
   lane *unless* it holds the same digit (the conflict case), and then it would produce tag=1, mask=0,
   breaking the invariant. So skipping is needed for safety, at a cost of 2 extra ops per word. Keep it,
   or accept a conflict-free precondition and assert it?
4. **Digit-plane views.** *Now benchmarked: see "Fourth board". Kept planes win on queries (3.4× vs
   building on demand) and cost 1.9× memory plus +1.6 to +5 ns per copy.* Originally: an 81-bit plane per
   digit ("where can d go"), which is what hidden singles, pointing/claiming, fish and the ML tensor
   need. Choices: derive on demand from the cell-major words
   (a 9×14 transpose, which should be benchmarked), or keep digit planes as a second, redundant
   representation updated on every write (writes get dearer, plane queries become one AND with a unit
   mask). The comparison list in the scope notes already mentions "digit-major 81-bit planes", and this benchmark
   is set up to add a third board class easily.
5. **Hash quality and caching** (see caveat 4). Should the immutable `Board` cache its hash? It's
   128 bytes and hashed often during dedup.
6. **Interface shape.** `cell(k)` returning a value class compiles to an unboxed `int` here, which is good.
   But `MutableBoard<B>` is F-bounded, to keep `copy()`/`copyFrom()` typed. Is that the shape we want in
   the real API, or do we want a concrete final `MutableBoard` class with no interface? The concrete class
   would make the dispatch question go away entirely.
7. **Should equality include givens?** Here it does. For dedup of solver states from the same puzzle it
   doesn't matter. Across puzzles, two boards with identical cells but different givens are currently
   unequal.
8. **Wire format**: not touched. 81 × u16 maps straight onto the ShortArray layout, which is a minor
   point in favor of keeping a ShortArray view.

## The real `Board` against the prototypes (@176)

`RealBoardBench` (`src/jmh/.../RealBoardBench.kt`) runs the engine's public `Board` / `Transient` through the
same workloads as the prototypes, so the rows line up with PackedTableBench (the prototype without planes, the
D18 reference) and PlanesBench. Costs of the real API are *in* the numbers: `CellIndex.of` / `Digit.of` range
checks, the immutable `Board`'s array copy, `Geometry` passed as a parameter (D11), value classes and own
iterators (D9), `place` followed by an explicit `eliminateFromPeers` (D19), and an `LongArray(18)` (D17, D18).
In setup, both real-board solvers must reproduce the prototype solver's placement count and status on all 8
puzzles, or the run aborts.

Command: `-f 2 -wi 3 -i 5 -w 500ms -r 500ms` (2 forks, 5 × 0.5 s), same machine as above (Ryzen 9 7900X,
OpenJDK 25.0.4.1). Raw: `build/results/real.{txt,json}` (gitignored). ns per op, one op as in the tables above.
Errors are ≤ 3 % except the ones marked.

| benchmark | PackedTable | Planes | **Board** | Board / PackedTable |
|---|---:|---:|---:|---:|
| readRandomK | 1.31 | 1.29 | 1.16 | 0.89 |
| readRandomRC | 1.30 | 1.30 | 1.52 | 1.17 |
| nakedSingleScanNative | 19.96 | 20.27 | 18.36 | 0.92 |
| hiddenSingleScanNative (Board: from the cells) | 301 | 181 | **560** (±36) | **1.86** |
| placesAllUnits (Board: `slotsOf(cellsWithCandidate(d))`) | 1666 (generic) | **92.7** | 246 | 0.15 (2.65× Planes) |
| eliminatePeersBulk (+ copy) | 13.74 | 16.57 | 17.35 | 1.26 (copy-adjusted 0.99) |
| copy (allocating) | 5.70 | 10.95 | 7.30 | **1.28** |
| equalsHit | 4.09 | 3.77 | 3.29 | 0.80 |
| equalsMissLastCell | 4.43 | 3.83 | 3.49 | 0.79 |
| hashCode (Board: cached field) / hashThenEquals | 18.30 | 17.14 | 3.96 | 0.22 (cached) |
| parseAndInit | 785 | 1100 | 391 | 0.50 |
| **solveSinglesE2E** (same cell-by-cell algorithm, + copy) | 1472 | 1392 | **2242** (±136) | **1.52** |
| solveSinglesE2E using `scanNakedSingles` | – | – | 1842 (±130) | 1.25 |

Only on the real board (no prototype equivalent): `editPlaceAndEliminatePeers` 18.2 (one singles step through
`edit {}`: copy, place, bulk elimination, wrap), `placeThenEliminatePeersImmutable` 25.0 (two allocating
changes), `eliminateSingleImmutable` 7.6, `editNoChange` 6.8 (copy, no-op writes, original returned),
`editThenHash` 18.0 (a fresh board's first `hashCode` costs ≈ 10 ns on top of the edit), `hashCodeCached` 0.26,
`HashSet<Board>` over 1024 distinct boards: insert 6.2, lookup hit 9.3 (hash + equals on equal, different
objects), lookup miss 1.25 (hashes are cached after the first iteration, so a never-hashed board adds ≈ 10 ns).
`cellsWithCandidate` for all 9 digits 48 per board (≈ 5.4 each); `filledIn` + `missingIn` + `duplicatesIn` over 27
regions 419 per board (≈ 5 per call). Iterating the open cells (≈ 55 per puzzle): `for (c in cells)` 61 per
board, `cells.forEach { }` 18.6.

### Verdict

- **Fine, at or better than PackedTable:** reads (`readRandomRC` 17 % slower from the two range checks),
  the naked-single scan, equality (`Arrays.equals` over 16 words beats the prototype's), hashing (cached),
  parse + initial candidates (half, from the bulk peer elimination), and the write itself: bulk peer
  elimination is 10.05 ns on top of the copy against the prototype's 10.17.
- **Regressions against PackedTable, reported not redesigned (D18):**
  1. **The hidden-single scan computed from the cells is 1.86× the prototype's generic bit-sliced scan and
     3.1× PlanesBoard's.** It is the likely main cause of `solveSinglesE2E` being 1.52× slower (2242 vs 1472 ns); I
     did not profile the loop to split the 770 ns gap between the scan and the per-cell reads and writes. The scan does 9 `cellsWithCandidate`
     computations plus `filledIn` for all 27 regions plus 243 `CellSet` intersections and sizes, and allocates.
     Using `scanNakedSingles` in the loop recovers 400 ns (1842, still 1.25× PackedTable). The solver state's planes
     (later) are the intended cure for the scan.
  2. **`copy` is 1.28× the prototype's** (7.3 vs 5.7): 18 words instead of 16, and `Transient` is a second
     object around the array. It disappears next to a single write (the copy-adjusted bulk elimination is equal).
  3. `for (c in cellSet)` is 3.3× slower than `cellSet.forEach { }` (61 vs 18.6 per board of ≈ 55 cells): the own
     iterator object isn't always scalar-replaced. Use `forEach` / `any` / `all` in hot loops; `for` is fine elsewhere.
- **Distance to the planes (input for the solver state):** `slotsOf(cellsWithCandidate(d))` for all regions and
  digits takes 246 ns per board against PlanesBoard's 92.7 (2.65× slower), and is 6.8× faster than the
  prototypes' generic cell-by-cell `placesInUnit`. About 48 ns of the 246 is the nine `cellsWithCandidate` calls
  (what planes make free); the rest is 243 `slotsOf` calls (`Long.compress` ×2 each), which planes don't avoid.
- **Cost of the D11 `geometry` parameter, D9 value classes, D19 explicit elimination:** not separately
  measurable here beyond what the rows above include. Bulk peer elimination (geometry table read through a
  parameter, no extra cost over the prototype's static table), reads (value classes, equal or faster) and the
  place + eliminate step (18.2 ns including its copy) show no regression from them.
- Memory is unchanged by design: 16 more bytes per board for the open mask (D17), no planes (D18). Not
  re-measured with JOL (`memoryReport` still covers only the prototypes).
