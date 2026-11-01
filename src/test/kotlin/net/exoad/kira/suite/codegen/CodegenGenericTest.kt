package net.exoad.kira.suite.codegen

import net.exoad.kira.suite.codegen.CodegenEmit.emit
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** C backend shape: generics monomorphize. */
class CodegenGenericTest {
    @Test
    fun genericsMonomorphize() {
        val output = emit(
            """
            pub Box<T>: Any {
                pub value: T
            }

            id<T>: (value: T) T {
                value
            }

            main: () Void {
                wrapped: Box<Int32> = Box<Int32> { 7 }
                value: Int32 = id<Int32>(wrapped.value)
                trace(value)
            }
            """
        )
        assertTrue(output.contains("typedef struct Box_Int32 Box_Int32;"), output)
        assertTrue(output.contains("struct Box_Int32"), output)
        assertTrue(output.contains("Int32 id_Int32(Int32 value)"), output)
        assertTrue(output.contains("Box_Int32_new(7)"), output)
        assertFalse(output.contains("T id(T"), output)
    }

    @Test
    fun nestedGenericsLowerToNestedLiteralsAndAccessors() {
        val output = emit(
            """
            main: () Void {
                grid: Arr<Arr<Int32>> = @arrOf(@arrOf(1, 2), @arrOf(3, 4))
                trace(grid[1][0])
            }
            """
        )
        // Outer and inner Arr literals both appear, nested.
        assertTrue(output.contains("Arr_lit((KiraSlot[]){ Arr_lit((KiraSlot[]){ 1, 2 }, 2)"), output)
        // Chained element access composes the get helper. The outer read
        // unslots to the declared element type `Arr` (which is what `cc`
        // rejects); the inner one has no element type to go on.
        assertTrue(output.contains("Arr_get_i32(KIRA_UNSLOT(Arr, Arr_get(grid, 1)), 0)"), output)
    }

    @Test
    fun genericCallReturnTypeNotThreadedIntoPrintFormat() {
        // Pinned gap: monomorphization itself is correct (id_Str returns Str),
        // but the print-format heuristic at a generic call site cannot see the
        // return type and falls back to %d. Int32 works by luck; Int64 should
        // widen to %lld and Str needs %s. A fix flips this test.
        val output = emit(
            """
            id<T>: (value: T) T {
                value
            }

            main: () Void {
                trace(id<Int32>(7))
                trace(id<Int64>(7))
                trace(id<Str>("ada"))
            }
            """
        )
        assertTrue(output.contains("Str id_Str(Str value)"), output)
        assertTrue(output.contains("print(\"%d\\n\", id_Int64(7));"), output)
        assertTrue(output.contains("print(\"%d\\n\", id_Str(\"ada\"));"), output)
        assertFalse(output.contains("id_Str(\"ada\")") && output.contains("print(\"%s\\n\", id_Str"), output)
    }
}
