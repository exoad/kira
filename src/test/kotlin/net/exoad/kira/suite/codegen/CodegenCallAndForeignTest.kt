package net.exoad.kira.suite.codegen

import net.exoad.kira.suite.codegen.CodegenEmit.emit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** C backend shape: named arguments, extern declarations and magic classes. */
class CodegenCallAndForeignTest {
    @Test
    fun namedArgumentsAreOrderedByTheCalleeNotByTheCall() {
        val output = emit(
            """
            sub: (a: Int32, b: Int32) Int32 { a - b }

            main: () Void {
                trace(sub(b = 3, a = 10))
            }
            """
        )
        assertTrue(output.contains("sub(10, 3)"), output)
        // Without the analyzer in front, a name the callee lacks is refused
        // at emit time rather than reordered by guesswork.
        val e = assertThrows<IllegalStateException> {
            emit(
                """
                sub: (a: Int32, b: Int32) Int32 { a - b }

                main: () Void {
                    trace(sub(a = 10, c = 3))
                }
                """
            )
        }
        assertTrue(e.message!!.contains("no parameter named 'c'"), e.message)
    }

    @Test
    fun externLowersToLiteralCNamePlaceholder() {
        // A marker keeps its argument on the declaration it marks (design
        // 2.5); it used to spill into a stray `"fopen";` statement, and a
        // bare marker with no declaration after it is now a parse error
        // (ParserSuiteTest). The C symbol is the mark's string (design 7.3):
        // the prototype and every call spell `fopen`, never the Kira name,
        // on this semantic-free emit path as on the CLI's (the mark is read
        // from the parser's stored invocation, not from apply()).
        val output = emit(
            """
            @_opaque FileHandle: Any { }

            @_extern("fopen") openFile: (path: Str) FileHandle;

            main: () Int32 {
                h: FileHandle = openFile("x")
                0
            }
            """
        )
        assertTrue(output.contains("extern FileHandle* fopen(Str path);"), output)
        assertTrue(output.contains("fopen(\"x\")"), output)
        assertFalse(output.contains("openFile"), output)
        assertFalse(output.contains("\"fopen\";"), output)
    }

    @Test
    fun magicClassesAreNotEmittedAsUserStructs() {
        val output = emit("x: Int32 = 1")
        // The stdlib declares @_magic classes; they must not appear as structs.
        assertFalse(output.contains("struct Int32"), output)
        assertFalse(output.contains("struct Str"), output)
        assertFalse(output.contains("struct Bool"), output)
    }
}
