package gridlight.engine

// @PublishedApi on the constructors of Digit, CellIndex and RegionSlot: the sets' inline helpers build them.

// Scalar value classes (D9, D12, D14). Range checked once, in `of`; internal code that already knows a value
// is valid uses the unchecked constructor.

@JvmInline
value class Digit @PublishedApi internal constructor(val value: Int) { // 1..9
    override fun toString(): String = value.toString()

    companion object {
        fun of(n: Int): Digit {
            require(n in 1..9) { "digit out of range 1..9: $n" }
            return Digit(n)
        }
    }
}

@JvmInline
value class Row internal constructor(val value: Int) { // 1..9
    override fun toString(): String = "r$value"

    companion object {
        fun of(n: Int): Row {
            require(n in 1..9) { "row out of range 1..9: $n" }
            return Row(n)
        }
    }
}

@JvmInline
value class Column internal constructor(val value: Int) { // 1..9
    override fun toString(): String = "c$value"

    companion object {
        fun of(n: Int): Column {
            require(n in 1..9) { "column out of range 1..9: $n" }
            return Column(n)
        }
    }
}

@JvmInline
value class CellIndex @PublishedApi internal constructor(val k: Int) { // 0..80, k = (row-1)*9 + (column-1)
    val row: Row get() = Row(k / 9 + 1)
    val column: Column get() = Column(k % 9 + 1)

    override fun toString(): String = "r${k / 9 + 1}c${k % 9 + 1}"

    companion object {
        fun of(k: Int): CellIndex {
            require(k in 0..80) { "cell index out of range 0..80: $k" }
            return CellIndex(k)
        }

        fun at(row: Row, column: Column): CellIndex = CellIndex((row.value - 1) * 9 + (column.value - 1))
    }
}

@JvmInline
value class RegionSlot @PublishedApi internal constructor(val i: Int) { // 0..8
    override fun toString(): String = i.toString()

    companion object {
        fun of(i: Int): RegionSlot {
            require(i in 0..8) { "region slot out of range 0..8: $i" }
            return RegionSlot(i)
        }
    }
}
