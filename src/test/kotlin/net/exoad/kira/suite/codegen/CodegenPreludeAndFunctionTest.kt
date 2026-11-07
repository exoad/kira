package net.exoad.kira.suite.codegen

import net.exoad.kira.suite.codegen.CodegenEmit.emit
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/** C backend shape: the prelude, functions, globals and trace. */
class CodegenPreludeAndFunctionTest {
    @Test
    fun emitsPreludeSubstrateAndFacade() {
        val output = emit("x: Int32 { 1 }")
        assertTrue(output.contains("KIRA_COMPILER_BUNDLE_H"), output)
        assertTrue(output.contains("KIRA_RUNTIME_H"), output)
        assertTrue(output.contains("#include <stdio.h>"), output)
        assertTrue(output.contains("#include <stdlib.h>"), output)
        assertTrue(output.contains("typedef kira_i32   Int32;"), output)
        assertTrue(output.contains("typedef kira_f64   Float64;"), output)
        assertTrue(output.contains("typedef CharSeq Str;"), output)
        assertTrue(output.contains("#define print(...)"), output)
    }

    @Test
    fun emitsArcRuntimeHooks() {
        val output = emit("x: Int32 { 1 }")
        assertTrue(output.contains("kira_rc_alloc_with"), output)
        assertTrue(output.contains("kira_rc_retain"), output)
        assertTrue(output.contains("kira_rc_release"), output)
        assertTrue(output.contains("kira_rc_store"), output)
    }

    @Test
    fun functionsLowerToCSignatures() {
        val output = emit(
            """
            add: (a: Int32, b: Int32) Int32 {
                a + b
            }

            greet: (name: Str) Str {
                name
            }

            main: () Void {
                trace("hi")
            }
            """
        )
        assertTrue(output.contains("Int32 add(Int32 a, Int32 b)"), output)
        assertTrue(output.contains("Str greet(Str name)"), output)
        assertTrue(output.contains("Int32 main(Void)"), output)
        // return value
        assertTrue(output.contains("return (a + b);"), output)
    }

    @Test
    fun globalsLowerToFileScopeVariables() {
        val output = emit(
            """
            x: Int32 { 10 }
            y: Str { "hello" }
            """
        )
        assertTrue(output.contains("Int32 x = 10;"), output)
        assertTrue(output.contains("Str y = \"hello\";"), output)
    }

    @Test
    fun traceLowersToPrintWithCorrectFormat() {
        val output = emit(
            """
            main: () Void {
                trace("text")
                trace(7)
            }
            """
        )
        assertTrue(output.contains("print(\"%s\\n\", \"text\");"), output)
        assertTrue(output.contains("print(\"%d\\n\", 7);"), output)
    }
}
