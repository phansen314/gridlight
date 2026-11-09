package gridlight.bench

/**
 * 10-bit cell (decided encoding, see the scope notes): bit 9 = placed tag, bits 0..8 = digit mask.
 * tag=0: bits 0..8 are candidates. tag=1: exactly one bit set; its index is the digit (0..8 = digits 1..9).
 */
@JvmInline
value class Cell(val bits: Int) {
    /** Low 9 bits: candidates if unplaced, one-hot digit if placed. Never branches. */
    inline val mask: Int get() = bits and DIGITS
    inline val isPlaced: Boolean get() = bits and TAG != 0
    /** Placed digit 0..8; meaningful only if [isPlaced]. */
    inline val digit: Int get() = Integer.numberOfTrailingZeros(bits)

    override fun toString(): String =
        if (isPlaced) "Placed(${digit + 1})"
        else "Cands(" + (0 until 9).filter { mask shr it and 1 != 0 }.joinToString("") { "${it + 1}" } + ")"

    companion object {
        const val TAG = 0x200
        const val DIGITS = 0x1FF
        fun placed(d: Int) = Cell(TAG or (1 shl d))
    }
}
