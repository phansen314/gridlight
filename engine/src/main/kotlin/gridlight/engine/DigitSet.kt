package gridlight.engine

/** Set of digits 1..9; bit n-1 = digit n. Bits 9..31 are always zero. Not [Iterable] (D9). */
@JvmInline
value class DigitSet internal constructor(@PublishedApi internal val bits: Int) { // @PublishedApi: bits is read by the inline helpers
    val size: Int get() = Integer.bitCount(bits)
    fun isEmpty(): Boolean = bits == 0
    val first: Digit
        get() {
            if (bits == 0) throw NoSuchElementException("empty DigitSet")
            return Digit(Integer.numberOfTrailingZeros(bits) + 1)
        }

    operator fun contains(d: Digit): Boolean = bits and (1 shl (d.value - 1)) != 0
    operator fun plus(other: DigitSet): DigitSet = DigitSet(bits or other.bits)
    operator fun plus(d: Digit): DigitSet = DigitSet(bits or (1 shl (d.value - 1)))
    operator fun minus(other: DigitSet): DigitSet = DigitSet(bits and other.bits.inv())
    operator fun minus(d: Digit): DigitSet = DigitSet(bits and (1 shl (d.value - 1)).inv())
    infix fun and(other: DigitSet): DigitSet = DigitSet(bits and other.bits)

    operator fun iterator(): DigitSetIterator = DigitSetIterator(bits)

    inline fun forEach(action: (d: Digit) -> Unit) {
        var b = bits
        while (b != 0) {
            val n = Integer.numberOfTrailingZeros(b)
            b = b and (b - 1)
            action(Digit(n + 1))
        }
    }

    inline fun any(predicate: (d: Digit) -> Boolean): Boolean {
        var b = bits
        while (b != 0) {
            val n = Integer.numberOfTrailingZeros(b)
            b = b and (b - 1)
            if (predicate(Digit(n + 1))) return true
        }
        return false
    }

    inline fun all(predicate: (d: Digit) -> Boolean): Boolean {
        var b = bits
        while (b != 0) {
            val n = Integer.numberOfTrailingZeros(b)
            b = b and (b - 1)
            if (!predicate(Digit(n + 1))) return false
        }
        return true
    }

    inline fun count(predicate: (d: Digit) -> Boolean): Int {
        var c = 0
        forEach { if (predicate(it)) c++ }
        return c
    }

    fun toList(): List<Digit> {
        val out = ArrayList<Digit>(size)
        forEach { out.add(it) }
        return out
    }

    override fun toString(): String = buildString {
        append('{')
        var sep = false
        this@DigitSet.forEach { x -> if (sep) append(','); append(x.value); sep = true }
        append('}')
    }

    companion object {
        val NONE: DigitSet = DigitSet(0)
        val ALL: DigitSet = DigitSet(0x1FF)
        fun of(d: Digit): DigitSet = DigitSet(1 shl (d.value - 1))
        fun of(vararg ns: Int): DigitSet {
            var b = 0
            for (n in ns) b = b or (1 shl (Digit.of(n).value - 1))
            return DigitSet(b)
        }
    }
}

class DigitSetIterator internal constructor(private var bits: Int) {
    operator fun hasNext(): Boolean = bits != 0
    operator fun next(): Digit {
        if (bits == 0) throw NoSuchElementException()
        val n = Integer.numberOfTrailingZeros(bits)
        bits = bits and (bits - 1)
        return Digit(n + 1)
    }
}
