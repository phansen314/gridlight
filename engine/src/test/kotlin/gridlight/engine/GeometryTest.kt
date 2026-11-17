package gridlight.engine

import kotlin.random.Random
import kotlin.test.*

class GeometryTest {
    private val g = Geometry.STANDARD
    private fun c(k: Int) = CellIndex.of(k)

    @Test fun `27 regions of 9 cells in index order`() {
        assertEquals(27, g.regions.size)
        assertEquals(9, g.rows.size); assertEquals(9, g.columns.size); assertEquals(9, g.boxes.size)
        for ((i, r) in g.regions.withIndex()) {
            assertEquals(i, r.index)
            assertEquals(9, r.cells.size)
            assertEquals(9, r.cellIndices.size)
            assertEquals(r.cells.toList().map { it.k }, r.cellIndices.toList(), "ascending cells of $r")
        }
        assertTrue(g.rows.all { it.kind == RegionKind.ROW }); assertTrue(g.columns.all { it.kind == RegionKind.COLUMN })
        assertTrue(g.boxes.all { it.kind == RegionKind.BOX })
    }

    @Test fun `every cell lies in exactly three regions, one of each kind`() {
        for (k in 0..80) {
            val inRegions = g.regions.filter { c(k) in it.cells }
            assertEquals(3, inRegions.size)
            assertEquals(inRegions, g.regionsOf(c(k)))
            assertEquals(listOf(RegionKind.ROW, RegionKind.COLUMN, RegionKind.BOX), g.regionsOf(c(k)).map { it.kind })
        }
    }

    @Test fun `region lists are read-only even when cast`() {
        val lists = listOf(g.regions, g.rows, g.columns, g.boxes) + g.regionsOf(c(0)).let { listOf(g.regionsOf(c(0))) }
        for (l in lists) {
            @Suppress("UNCHECKED_CAST") val m = l as MutableList<Region>
            assertFailsWith<UnsupportedOperationException> { m.add(l[0]) }
            assertFailsWith<UnsupportedOperationException> { m[0] = l[1] }
            assertFailsWith<UnsupportedOperationException> { m.removeAt(0) }
        }
    }

    @Test fun `known regions`() {
        assertEquals((0..8).toList(), g.rows[0].cellIndices.toList())
        assertEquals((0..8).map { it * 9 + 4 }, g.columns[4].cellIndices.toList())
        assertEquals(listOf(30, 31, 32, 39, 40, 41, 48, 49, 50), g.boxes[4].cellIndices.toList())
        assertEquals(listOf(60, 61, 62, 69, 70, 71, 78, 79, 80), g.boxes[8].cellIndices.toList())
        assertEquals("row 3", g.rows[2].toString()); assertEquals("column 5", g.columns[4].toString())
        assertEquals("box 7", g.boxes[6].toString())
        assertEquals("row 9", g.rows[8].toString()); assertEquals("column 1", g.columns[0].toString())
        assertEquals("box 1", g.boxes[0].toString()); assertEquals("box 9", g.boxes[8].toString())
        assertEquals(c(31), g.boxes[4][RegionSlot.of(1)])
    }

    @Test fun `20 peers per cell, symmetric, irreflexive`() {
        for (a in 0..80) {
            assertEquals(20, g.peers(c(a)).size)
            assertFalse(c(a) in g.peers(c(a)))
            for (b in 0..80) {
                assertEquals(g.sees(c(a), c(b)), g.sees(c(b), c(a)))
                assertEquals(g.sees(c(a), c(b)), c(b) in g.peers(c(a)))
                val sameRegion = a != b && g.regions.any { c(a) in it.cells && c(b) in it.cells }
                assertEquals(sameRegion, g.sees(c(a), c(b)))
            }
        }
    }

    @Test fun `commonPeers excludes both cells and matches the naive answer`() {
        for (a in 0..80) for (b in 0..80) {
            val naive = (0..80).filter { it != a && it != b && g.sees(c(a), c(it)) && g.sees(c(b), c(it)) }
            assertEquals(naive, g.commonPeers(c(a), c(b)).toList().map { it.k })
        }
    }

    @Test fun `intersection of a box and a line is 3 cells or empty`() {
        for (box in g.boxes) for (line in g.rows + g.columns) {
            val n = g.intersection(box, line).size
            assertTrue(n == 3 || n == 0, "$box ∩ $line = $n")
        }
        assertEquals(CellSet.of(0, 1, 2), g.intersection(g.boxes[0], g.rows[0]))
        assertEquals(CellSet.EMPTY, g.intersection(g.boxes[0], g.rows[8]))
        assertEquals(CellSet.of(40), g.intersection(g.rows[4], g.columns[4]))
    }

    @Test fun `slotsOf agrees with a naive version on random sets`() {
        val rnd = Random(172)
        repeat(300) {
            var s = CellSet.EMPTY
            for (k in 0..80) if (rnd.nextInt(3) == 0) s += c(k)
            if (it == 0) s = CellSet.ALL
            if (it == 1) s = CellSet.EMPTY
            for (r in g.regions) {
                val naive = (0..8).filter { slot -> c(r.cellIndices[slot]) in s }
                val got = r.slotsOf(s)
                assertEquals(naive, got.toList().map { x -> x.i }, "$r of $s")
                assertEquals(0, got.bits and 0x1FF.inv())
            }
        }
    }

    @Test fun `forEachCell visits the cells in slot order`() {
        for (r in g.regions) {
            val seen = mutableListOf<Int>(); r.forEachCell { seen += it.k }
            assertEquals(r.cellIndices.toList(), seen)
        }
    }

    @Test fun `kernel tables agree with the peer sets`() {
        for (k in 0..80) {
            val peers = g.peers(c(k)).toList().map { it.k }
            val fromLanes = mutableListOf<Int>()
            for (i in 0 until 14) for (slot in 0 until 6) {
                val bit = (g.peerLanes[k * 14 + i] ushr (slot * 10)) and 0x3FFL
                if (bit != 0L) { assertEquals(1L, bit, "cell $k word $i slot $slot"); fromLanes += i * 6 + slot }
            }
            assertEquals(peers, fromLanes, "peerLanes of cell $k")
        }
        for (r in g.regions) assertEquals(r.cellIndices.map { Layout.POS[it] }, r.bitPos.toList())
    }
}
