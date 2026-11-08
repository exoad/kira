package net.exoad.kira.suite.codegen

import net.exoad.kira.suite.codegen.CodegenEmit.emit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** C backend shape: if, while, for and compound assignment. */
class CodegenControlFlowTest {
    @Test
    fun ifElseLowersToCIfElse() {
        val output = emit(
            """
            main: () Void {
                x: Int32 { 2 }
                if x == 1 {
                    trace("one")
                } else {
                    trace("other")
                }
            }
            """
        )
        assertTrue(output.contains("if((x == 1))"), output)
        assertTrue(output.contains("} else"), output)
        assertTrue(output.contains("print(\"%s\\n\", \"one\");"), output)
        assertTrue(output.contains("print(\"%s\\n\", \"other\");"), output)
    }

    @Test
    fun whileLowersToCWhile() {
        val output = emit(
            """
            main: () Void {
                i: Mutable<Int32> { 0 }
                while i < 3 {
                    i = i + 1
                }
            }
            """
        )
        assertTrue(output.contains("while((i < 3))"), output)
        assertTrue(output.contains("i = (i + 1);"), output)
    }

    @Test
    fun aCounterWhileLowersToCWhile() {
        val output = emit(
            """
            main: () Void {
                i: Mutable<Int32> { 0 }
                while i < 4 {
                    trace(i)
                    i += 1
                }
            }
            """
        )
        assertTrue(output.contains("while((i < 4))"), output)
        assertTrue(output.contains("print(\"%d\\n\", i);"), output)
    }

    @Test
    fun compoundAssignmentsEmitRealStores() {
        // `a += 2` must lower to a real store, not the old discarded
        // `(a + 2);` expression. Primitives keep the native compound op.
        val output = emit(
            """
            main: () Void {
                a: Mutable<Int32> { 1 }
                a += 2
            }
            """
        )
        assertTrue(output.contains("a += 2;"), output)
        assertFalse(output.contains("(a + 2);"), output)
    }
}
