package net.exoad.kira.suite

import net.exoad.kira.TestCompileSupport
import net.exoad.kira.compiler.fix.DeclRepeatFix
import net.exoad.kira.cpp.decls.DeclTestSupport
import net.exoad.kira.py.PyTestSupport
import net.exoad.kira.types.TyperTestSupport
import net.exoad.kira.types.TyperTestSupport.expectNoErrors
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A primitive's block is its one value typed as `x: T = value` types it, on every target. */
class PrimInitTest {
    /** Every primitive of the language with a value that fits it. */
    private val prims = listOf(
        "Int8" to "7", "Int16" to "7", "Int32" to "7", "Int64" to "7",
        "UInt8" to "7", "UInt16" to "7", "UInt32" to "7", "UInt64" to "7", "Size" to "7",
        "Float32" to "1.5", "Float64" to "1.5", "Bool" to "true", "Char" to "'a'", "Str" to "\"hi\"",
    )

    private fun codes(body: String): List<String> =
        TyperTestSupport.snippet("main: () Void {\n$body\n}").diagnostics.map { it.code }

    private fun errors(body: String) = TyperTestSupport.snippet("main: () Void {\n$body\n}").diagnostics.filter { it.severity.name == "ERROR" }

    private fun src(local: String) = "main: () Void {\n    $local\n}"

    // ---- every primitive types, and is its long form's value ----------------------------------------

    @Test
    fun everyPrimitiveTypesCleanInEveryPlace() {
        for ((t, v) in prims) {
            expectNoErrors(TyperTestSupport.snippet(src("x: $t { $v }")))
            expectNoErrors(TyperTestSupport.snippet(src("x: $t = $t { $v }")))
            expectNoErrors(TyperTestSupport.snippet(src("m: Mutable<$t> { $v }")))
            expectNoErrors(TyperTestSupport.snippet("g: (v: $t) $t {\n    v\n}\nmain: () Void {\n    y: $t = g($t { $v })\n}"))
        }
    }

    @Test
    fun py_cpp_c_andJsEmitTheSameForTheBlockAsForTheEqualsForm() {
        for ((t, v) in prims) {
            val block = src("x: $t { $v }")
            val equals = src("x: $t = $v")
            val pyOf = { x: String -> PyTestSupport.emit(x).let { it.text to it.errors.size } }
            assertEquals(pyOf(equals).first, pyOf(block).first, "py $t")
            assertEquals(pyOf(equals).second, pyOf(block).second, "py errors $t")
            val cppOf = { s: String -> DeclTestSupport.emit(DeclTestSupport.module("test:main", s)).source("test:main") }
            assertEquals(cppOf(equals), cppOf(block), "cpp $t")
            val long = src("x: $t = $t { $v }")
            assertEquals(pyOf(equals).first, pyOf(long).first, "py long $t")
            assertEquals(cppOf(equals), cppOf(long), "cpp long $t")
            val cOf = { s: String -> TestCompileSupport.transpileSnippetToC(TestCompileSupport.wrapModule("test:main", s), "test/main.kira", false) }.let { f -> { s: String -> runCatching { f(s) }.getOrElse { "refused: ${it.message}" } } }
            val jsOf = { s: String -> TestCompileSupport.transpileSnippetToJS(TestCompileSupport.wrapModule("test:main", s), "test/main.kira", false) }.let { f -> { s: String -> runCatching { f(s) }.getOrElse { "refused: ${it.message}" } } }
            assertEquals(cOf(equals), cOf(block), "c $t")
            assertEquals(jsOf(equals), jsOf(block), "js $t")
        }
    }

    @Test
    fun inAnExpressionItIsTheValue() {
        val body = "g: (v: Int32) Int32 {\n    v + 1\n}\nmain: () Void {\n    y: Int32 = g(Int32 { 3 }) + Int32 { 4 }\n}"
        val plain = "g: (v: Int32) Int32 {\n    v + 1\n}\nmain: () Void {\n    y: Int32 = g(3) + 4\n}"
        expectNoErrors(TyperTestSupport.snippet(body))
        assertEquals(PyTestSupport.emit(plain).python(), PyTestSupport.emit(body).python())
    }

    @Test
    fun theBlockFixesTheTypeWhereTheBareLiteralWouldTakeTheContexts() {
        // `3` takes Int64 from the parameter; `Int32 { 3 }` is an Int32 and the parameter refuses it.
        val take = "g: (v: Int64) Int64 {\n    v\n}\nmain: () Void {\n"
        expectNoErrors(TyperTestSupport.snippet("${take}    y: Int64 = g(3)\n}"))
        assertTrue(TyperTestSupport.snippet("${take}    y: Int64 = g(Int32 { 3 })\n}").diagnostics.any { it.severity.name == "ERROR" })
        assertTrue(errors("    y: Int64 { Int32 { 3 } }").isNotEmpty())
    }

    // ---- the same refusals as the equals form ----------------------------------------------------------

    private fun sameRefusal(t: String, v: String) {
        val block = codes(src("x: $t { $v }").substringAfter("{\n").substringBeforeLast("\n}"))
        val equals = codes(src("x: $t = $v").substringAfter("{\n").substringBeforeLast("\n}"))
        assertEquals(equals.filter { !it.startsWith("rules.") }, block.filter { !it.startsWith("rules.") }, "$t { $v }")
        assertTrue(equals.any { it.startsWith("types.") }, "the equals form must refuse $t = $v for this to mean anything: $equals")
    }

    @Test
    fun aValueOnePastARangeIsRefusedAsTheEqualsFormRefusesIt() {
        sameRefusal("Int8", "128")
        sameRefusal("Int8", "-129")
        sameRefusal("UInt8", "256")
        sameRefusal("UInt8", "-1")
        sameRefusal("Int16", "32768")
        sameRefusal("UInt16", "65536")
        sameRefusal("Int32", "2147483648")
        sameRefusal("UInt32", "4294967296")
        sameRefusal("Size", "4294967296")
    }

    @Test
    fun theEdgesOfTheNarrowestAndWidestIntegersAreAccepted() {
        for (s in listOf(
            "x: Int8 { -128 }", "x: Int8 { 127 }", "x: UInt8 { 0 }", "x: UInt8 { 255 }",
            "x: Int16 { -32768 }", "x: UInt16 { 65535 }", "x: Int32 { -2147483648 }", "x: Int32 { 2147483647 }",
            "x: UInt32 { 4294967295 }", "x: Int64 { 9223372036854775807 }", "x: Int64 { -9223372036854775807 }",
        )) {
            expectNoErrors(TyperTestSupport.snippet(src(s)))
        }
    }

    @Test
    fun aFloatIntoAnIntegerAndTheOtherWrongKindsAreRefusedAsTheEqualsFormRefusesThem() {
        sameRefusal("Int32", "1.5")
        sameRefusal("Int8", "1.5")
        sameRefusal("Bool", "1")
        sameRefusal("Str", "1")
        sameRefusal("Int32", "\"a\"")
        sameRefusal("Float64", "true")
        sameRefusal("Char", "\"a\"")
    }

    @Test
    fun aVariableOfAnotherTypeIsRefusedAsTheEqualsFormRefusesIt() {
        val pre = "    a: Int32 = 1\n"
        assertEquals(
            codes("$pre    b: Int64 = a").filter { it.startsWith("types.") },
            codes("$pre    b: Int64 { a }").filter { it.startsWith("types.") },
        )
        assertTrue(codes("$pre    b: Int64 { a }").any { it.startsWith("types.") })
        expectNoErrors(TyperTestSupport.snippet(src("a: Int32 = 1\n    b: Int32 { a }")))
    }

    // ---- no implicit zero value --------------------------------------------------------------------------

    @Test
    fun anEmptyBlockAndSeveralValuesAreRefusedWithTheArityMessage() {
        for ((t, s) in listOf(
            "Int32" to "x: Int32 { }", "Str" to "x: Str { }", "Bool" to "x: Bool { }", "Float64" to "x: Float64 { }",
            "Int32" to "x: Int32 { 1, 2 }", "Str" to "x: Str { \"a\", \"b\" }", "Int32" to "x: Int32 = Int32 { }",
            "Int8" to "x: Int8 { value = 1 }",
        )) {
            val e = TyperTestSupport.snippet(src(s)).diagnostics.single { it.code == "types.init.primitive-arity" }
            assertTrue(e.message.contains("takes exactly one value") && e.message.contains("no implicit zero value") && e.message.startsWith("$t { }"), e.message)
        }
    }

    // ---- constants, Mutable, and the hint ----------------------------------------------------------------------

    @Test
    fun aModuleConstantFromABlockIsAsConstantAsTheEqualsForm() {
        val block = TyperTestSupport.snippet("LIMIT: Int32 { 5 }\nWIDTH: Int32 { LIMIT * 2 }\nmain: () Void {\n    a: Arr<Int32, WIDTH> = @arrOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)\n}")
        val equals = TyperTestSupport.snippet("LIMIT: Int32 = 5\nWIDTH: Int32 = LIMIT * 2\nmain: () Void {\n    a: Arr<Int32, WIDTH> = @arrOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)\n}")
        expectNoErrors(equals)
        expectNoErrors(block)
        assertEquals(equals.diagnostics.map { it.code }, block.diagnostics.map { it.code })
    }

    @Test
    fun aModuleConstantFromARunTimeValueIsRefusedAsTheEqualsFormRefusesIt() {
        val pre = "now: () Int32 {
    1
}
"
        val equals = TyperTestSupport.snippet(pre + "LIMIT: Int32 = now()
main: () Void {
}").diagnostics.map { it.code }
        val block = TyperTestSupport.snippet(pre + "LIMIT: Int32 { now() }
main: () Void {
}").diagnostics.map { it.code }
        assertTrue(equals.contains("types.const.not-constant"), equals.toString())
        assertEquals(equals, block)
    }

    @Test
    fun aMutableBlockIsAMutableOfThePrimitive() {
        expectNoErrors(TyperTestSupport.snippet(src("m: Mutable<Int32> { 0 }\n    m += 1")))
        assertEquals(
            PyTestSupport.emit(src("m: Mutable<Int32> = 0\n    m += 1")).python(),
            PyTestSupport.emit(src("m: Mutable<Int32> { 0 }\n    m += 1")).python(),
        )
    }

    @Test
    fun theHintAndTheRewriteCoverAPrimitiveLikeAnyType() {
        val (out, n) = DeclRepeatFix.rewrite("main: () Void {\n    a: Int32 = Int32 { 0 }\n    m: Mutable<Float64> = Float64 { 1.5 }\n    s: Str = Str { \"hi\" }\n}\n")
        assertEquals(3, n)
        assertEquals("main: () Void {\n    a: Int32 { 0 }\n    m: Mutable<Float64> { 1.5 }\n    s: Str { \"hi\" }\n}\n", out)
        val p = TyperTestSupport.snippet(src("a: Int32 = Int32 { 0 }"), options = net.exoad.kira.compiler.analysis.types.TyperOptions(hintDeclRepeat = true))
        assertEquals(1, p.diagnostics.count { it.code == "rules.decl.repeat" })
    }
}
