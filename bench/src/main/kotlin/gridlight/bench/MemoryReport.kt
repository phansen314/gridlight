package gridlight.bench

import org.openjdk.jol.info.GraphLayout
import org.openjdk.jol.vm.VM

fun main() {
    println(VM.current().details())
    val s = Puzzles.ALL[0]
    val p = parseInto(PackedBoard(), s)
    val q = parseInto(ShortBoard(), s)
    val t = parseInto(PackedTableBoard(), s)
    val pl = parseInto(PlanesBoard(), s)
    for ((name, b) in listOf("PackedBoard" to p, "PackedTableBoard" to t, "PlanesBoard" to pl, "ShortBoard" to q)) {
        val g = GraphLayout.parseInstance(b)
        println("== $name: ${g.totalSize()} bytes in ${g.totalCount()} objects")
        println(g.toFootprint())
    }
}
