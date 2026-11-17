package gridlight.engine

// Compiled and inspected by NoBoxingTest. Each function iterates a set with `for`.
fun sumDigits(s: DigitSet): Int { var t = 0; for (d in s) t += d.value; return t }
fun sumCells(s: CellSet): Int { var t = 0; for (c in s) t += c.k; return t }
fun sumSlots(s: RegionSlotSet): Int { var t = 0; for (x in s) t += x.i; return t }
