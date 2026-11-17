package gridlight.engine

/** Set of slots 0..8; bit i = slot i. Bits 9..31 are always zero. Not [Iterable] (D9). */
@JvmInline
value class RegionSlotSet @PublishedApi internal constructor(@PublishedApi internal val bits: Int) {
    val size: Int get() = Integer.bitCount(bits)
    fun isEmpty(): Boolean = bits == 0
    val first: RegionSlot
        get() {
            if (bits == 0) throw NoSuchElementException("empty RegionSlotSet")
            return RegionSlot(Integer.numberOfTrailingZeros(bits))
        }

    operator fun contains(slot: RegionSlot): Boolean = bits and (1 shl (slot.i)) != 0
    operator fun plus(other: RegionSlotSet): RegionSlotSet = RegionSlotSet(bits or other.bits)
    operator fun plus(slot: RegionSlot): RegionSlotSet = RegionSlotSet(bits or (1 shl (slot.i)))
    operator fun minus(other: RegionSlotSet): RegionSlotSet = RegionSlotSet(bits and other.bits.inv())
    operator fun minus(slot: RegionSlot): RegionSlotSet = RegionSlotSet(bits and (1 shl (slot.i)).inv())
    infix fun and(other: RegionSlotSet): RegionSlotSet = RegionSlotSet(bits and other.bits)

    operator fun iterator(): RegionSlotSetIterator = RegionSlotSetIterator(bits)

    inline fun forEach(action: (slot: RegionSlot) -> Unit) {
        var b = bits
        while (b != 0) {
            val n = Integer.numberOfTrailingZeros(b)
            b = b and (b - 1)
            action(RegionSlot(n))
        }
    }

    inline fun any(predicate: (slot: RegionSlot) -> Boolean): Boolean {
        var b = bits
        while (b != 0) {
            val n = Integer.numberOfTrailingZeros(b)
            b = b and (b - 1)
            if (predicate(RegionSlot(n))) return true
        }
        return false
    }

    inline fun all(predicate: (slot: RegionSlot) -> Boolean): Boolean {
        var b = bits
        while (b != 0) {
            val n = Integer.numberOfTrailingZeros(b)
            b = b and (b - 1)
            if (!predicate(RegionSlot(n))) return false
        }
        return true
    }

    inline fun count(predicate: (slot: RegionSlot) -> Boolean): Int {
        var c = 0
        forEach { if (predicate(it)) c++ }
        return c
    }

    fun toList(): List<RegionSlot> {
        val out = ArrayList<RegionSlot>(size)
        forEach { out.add(it) }
        return out
    }

    override fun toString(): String = buildString {
        append('{')
        var sep = false
        this@RegionSlotSet.forEach { x -> if (sep) append(','); append(x.i); sep = true }
        append('}')
    }

    companion object {
        val NONE: RegionSlotSet = RegionSlotSet(0)
        val ALL: RegionSlotSet = RegionSlotSet(0x1FF)
        fun of(slot: RegionSlot): RegionSlotSet = RegionSlotSet(1 shl (slot.i))
        fun of(vararg ns: Int): RegionSlotSet {
            var b = 0
            for (n in ns) b = b or (1 shl (RegionSlot.of(n).i))
            return RegionSlotSet(b)
        }
    }
}

class RegionSlotSetIterator internal constructor(private var bits: Int) {
    operator fun hasNext(): Boolean = bits != 0
    operator fun next(): RegionSlot {
        if (bits == 0) throw NoSuchElementException()
        val n = Integer.numberOfTrailingZeros(bits)
        bits = bits and (bits - 1)
        return RegionSlot(n)
    }
}
