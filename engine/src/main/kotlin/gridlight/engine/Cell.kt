package gridlight.engine

/**
 * One cell's content, an Either (D2, D4, D8): a filled [filled] digit or a set of [candidates]. Each accessor
 * throws [IllegalStateException] when the cell is in the other state; check [isFilled] first. The one-hot
 * encoding stays internal (D1).
 */
@JvmInline
value class Cell internal constructor(internal val bits: Int) { // 10 bits: bit 9 = filled tag, bits 0..8 digit or candidates
    val isFilled: Boolean get() = bits and Kernel.TAG != 0

    val filled: Digit
        get() {
            check(isFilled) { "cell is open, has no filled digit" }
            return Digit(Integer.numberOfTrailingZeros(bits) + 1)
        }

    val candidates: DigitSet
        get() {
            check(!isFilled) { "cell is filled, has no candidates" }
            return DigitSet(bits)
        }

    override fun toString(): String = if (isFilled) filled.toString() else candidates.toString()
}
