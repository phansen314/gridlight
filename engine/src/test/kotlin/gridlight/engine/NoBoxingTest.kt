package gridlight.engine

import kotlin.test.*

/** `for (d in set)` must not box value classes (D9): checked in the bytecode of compiled fixtures. */
class NoBoxingTest {
    private fun classBytes(name: String): String =
        String(javaClass.classLoader.getResourceAsStream("gridlight/engine/$name.class")!!.readBytes(), Charsets.ISO_8859_1)

    @Test fun `detector flags a function that boxes`() {
        assertTrue("box-impl" in classBytes("BoxControlKt"))
    }

    @Test fun `for loops over sets do not box`() {
        val code = classBytes("NoBoxFixturesKt")
        assertTrue("sumDigits" in code)
        for (marker in listOf("box-impl", "unbox-impl", "java/lang/Integer", "valueOf")) {
            assertFalse(marker in code, "fixture bytecode mentions $marker")
        }
    }

    @Test fun `iterator next returns a primitive int`() {
        for (cls in listOf(DigitSetIterator::class.java, CellSetIterator::class.java, RegionSlotSetIterator::class.java)) {
            val next = cls.declaredMethods.single { it.name.startsWith("next") }
            assertEquals(Int::class.javaPrimitiveType, next.returnType, cls.simpleName)
        }
    }
}
