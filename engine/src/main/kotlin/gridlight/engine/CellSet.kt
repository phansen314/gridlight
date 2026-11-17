package gridlight.engine

/**
 * Set of cells in the 54 | 27 band split (D7): [lo] = cells 0..53, [hi] = cells 54..80. Bits above each part
 * (lo bits 54..63, hi bits 27..63) are always zero. Not [Iterable] (D9).
 */
class CellSet internal constructor(
    @PublishedApi internal val lo: Long, // @PublishedApi: read by the inline helpers
    @PublishedApi internal val hi: Long,
) {
    val size: Int get() = java.lang.Long.bitCount(lo) + java.lang.Long.bitCount(hi)
    fun isEmpty(): Boolean = (lo or hi) == 0L
    val first: CellIndex
        get() {
            if (lo != 0L) return CellIndex(java.lang.Long.numberOfTrailingZeros(lo))
            if (hi != 0L) return CellIndex(BAND + java.lang.Long.numberOfTrailingZeros(hi))
            throw NoSuchElementException("empty CellSet")
        }

    operator fun contains(c: CellIndex): Boolean =
        if (c.k < BAND) (lo ushr c.k) and 1L != 0L else (hi ushr (c.k - BAND)) and 1L != 0L

    operator fun plus(other: CellSet): CellSet = CellSet(lo or other.lo, hi or other.hi)
    operator fun plus(c: CellIndex): CellSet =
        if (c.k < BAND) CellSet(lo or (1L shl c.k), hi) else CellSet(lo, hi or (1L shl (c.k - BAND)))

    operator fun minus(other: CellSet): CellSet = CellSet(lo and other.lo.inv(), hi and other.hi.inv())
    operator fun minus(c: CellIndex): CellSet =
        if (c.k < BAND) CellSet(lo and (1L shl c.k).inv(), hi) else CellSet(lo, hi and (1L shl (c.k - BAND)).inv())

    infix fun and(other: CellSet): CellSet = CellSet(lo and other.lo, hi and other.hi)

    operator fun iterator(): CellSetIterator = CellSetIterator(lo, hi)

    inline fun forEach(action: (c: CellIndex) -> Unit) {
        var b = lo
        while (b != 0L) {
            val n = java.lang.Long.numberOfTrailingZeros(b)
            b = b and (b - 1)
            action(CellIndex(n))
        }
        b = hi
        while (b != 0L) {
            val n = java.lang.Long.numberOfTrailingZeros(b)
            b = b and (b - 1)
            action(CellIndex(n + BAND))
        }
    }

    inline fun any(predicate: (c: CellIndex) -> Boolean): Boolean {
        var b = lo
        while (b != 0L) {
            val n = java.lang.Long.numberOfTrailingZeros(b)
            b = b and (b - 1)
            if (predicate(CellIndex(n))) return true
        }
        b = hi
        while (b != 0L) {
            val n = java.lang.Long.numberOfTrailingZeros(b)
            b = b and (b - 1)
            if (predicate(CellIndex(n + BAND))) return true
        }
        return false
    }

    inline fun all(predicate: (c: CellIndex) -> Boolean): Boolean {
        var b = lo
        while (b != 0L) {
            val n = java.lang.Long.numberOfTrailingZeros(b)
            b = b and (b - 1)
            if (!predicate(CellIndex(n))) return false
        }
        b = hi
        while (b != 0L) {
            val n = java.lang.Long.numberOfTrailingZeros(b)
            b = b and (b - 1)
            if (!predicate(CellIndex(n + BAND))) return false
        }
        return true
    }

    inline fun count(predicate: (c: CellIndex) -> Boolean): Int {
        var c = 0
        forEach { if (predicate(it)) c++ }
        return c
    }

    fun toList(): List<CellIndex> {
        val out = ArrayList<CellIndex>(size)
        forEach { out.add(it) }
        return out
    }

    override fun equals(other: Any?): Boolean = other is CellSet && lo == other.lo && hi == other.hi
    override fun hashCode(): Int = (lo * -7046029254386353131L + hi).let { (it xor (it ushr 32)).toInt() }

    override fun toString(): String = buildString {
        append('{')
        var sep = false
        this@CellSet.forEach { x -> if (sep) append(','); append(x.toString()); sep = true }
        append('}')
    }

    companion object {
        @PublishedApi internal const val BAND = 54 // inline helpers use it
        val EMPTY: CellSet = CellSet(0L, 0L)
        val ALL: CellSet = CellSet((1L shl BAND) - 1, (1L shl (81 - BAND)) - 1)
        fun of(c: CellIndex): CellSet = EMPTY + c
        fun of(vararg ks: Int): CellSet {
            var s = EMPTY
            for (k in ks) s += CellIndex.of(k)
            return s
        }
    }
}

class CellSetIterator internal constructor(private var lo: Long, private var hi: Long) {
    operator fun hasNext(): Boolean = (lo or hi) != 0L
    operator fun next(): CellIndex {
        if (lo != 0L) {
            val n = java.lang.Long.numberOfTrailingZeros(lo)
            lo = lo and (lo - 1)
            return CellIndex(n)
        }
        if (hi != 0L) {
            val n = java.lang.Long.numberOfTrailingZeros(hi)
            hi = hi and (hi - 1)
            return CellIndex(n + CellSet.BAND)
        }
        throw NoSuchElementException()
    }
}
