package net.exoad.kira.suite

import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.analysis.types.display
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Builder
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BuilderExpr
import net.exoad.kira.cpp.decls.DeclTestSupport
import net.exoad.kira.py.PyTestSupport
import net.exoad.kira.types.TyperTestSupport
import net.exoad.kira.types.TyperTestSupport.expectDiagnostic
import net.exoad.kira.types.TyperTestSupport.expectNoErrors
import net.exoad.kira.types.TyperTestSupport.module
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `[...]` gone, @listOf/@arrOf/@setOf/@mapOf in its place, and no brace type inference (stage 2b of the clean dialect). */
class BuilderTest {
    private fun snippet(body: String) = TyperTestSupport.snippet(body)

    private fun parseError(body: String): DiagnosticsException = assertThrows { TyperTestSupport.unitOf(module("test:main", body.trimIndent())) }

    /** The type each builder of [body]'s `main` got, in source order. */
    private fun builtTypes(body: String): List<String> {
        val p = snippet(body)
        expectNoErrors(p)
        val ctx = p.module("test:main")!!.source
        return TyperTestSupport.nodes(ctx.ast).filterIsInstance<BuilderExpr>().sortedWith(compareBy({ ctx.astOrigins[it]?.lineNumber }, { ctx.astOrigins[it]?.column }))
            .map { p.model.types[it]!!.display() }
    }

    // ---- the parser --------------------------------------------------------------------------

    @Test
    fun aBracketLiteralIsRemovedAndABraceAloneNamesNoType() {
        val removed = parseError("main: () Void {\n    xs: List<Int32> = [1, 2]\n}")
        assertEquals("parse.removed", removed.tag)
        assertTrue(removed.message.contains("@listOf(a, b)"), removed.message)
        assertEquals("parse.brace.type", parseError("P: Struct {\n    pub x: Int32 = 0\n}\n\nmain: () Void {\n    p: P = { }\n}").tag)
        assertEquals("parse.builder.call", parseError("main: () Void {\n    xs: List<Int32> = @listOf\n}").tag)
    }

    @Test
    fun aBuilderTakesTypeArgumentsAndATrailingComma() {
        val p = snippet("main: () Void {\n    xs: List<Int64> = @listOf<Int64>(\n        1,\n        2,\n    )\n}")
        expectNoErrors(p)
        val b = TyperTestSupport.nodes(p.module("test:main")!!.source.ast).filterIsInstance<BuilderExpr>().single()
        assertEquals(Builder.LIST, b.kind)
        assertEquals(2, b.elements.size)
    }

    // ---- the typer ---------------------------------------------------------------------------

    @Test
    fun theElementTypeComesFromTheContextATypeArgumentOrTheElements() {
        val types = builtTypes(
            """
            total: (xs: List<Int64>) Int64 {
                xs.size() as Int64
            }

            main: () Void {
                a: List<Int64> = @listOf(1, 2)
                b: Arr<Int32, 3> = @arrOf(1, 2, 3)
                c: Set<Str> = @setOf("x")
                d: Map<Str, Int32> = @mapOf("k", 1, "j", 2)
                e: Int64 = total(@listOf(5))
                f: Int32 = @listOf<UInt8>(1).size() as Int32
                g: Int32 = @arrOf("a", "b").size() as Int32
                h: Maybe<List<Str>> = @listOf()
            }
            """,
        )
        assertEquals(listOf("List<Int64>", "Arr<Int32, 3>", "Set<Str>", "Map<Str, Int32>", "List<Int64>", "List<UInt8>", "Arr<Str>", "List<Str>"), types)
    }

    @Test
    fun anEmptyBuilderNeedsATypeAndElementsHaveOneTypeWithNoConversion() {
        expectDiagnostic(snippet("main: () Void {\n    n: Int32 = @listOf().size() as Int32\n}"), "types.builder.untyped")
        val mixed = snippet("main: () Void {\n    a: Int64 = 1\n    b: Int32 = 2\n    n: Int32 = @listOf(a, b).size() as Int32\n}")
        assertTrue(expectDiagnostic(mixed, "types.builder.element").message.contains("element 2 is Int32, element 1 Int64"))
        // A literal takes the first element's type, as it takes any context's.
        expectNoErrors(snippet("main: () Void {\n    a: Int64 = 1\n    n: Int32 = @listOf(a, 2, 3).size() as Int32\n}"))
    }

    @Test
    fun aMapTakesKeysAndValuesInTurnAndKeysAreScalarsOrStrs() {
        expectDiagnostic(snippet("main: () Void {\n    m: Map<Str, Int32> = @mapOf(\"a\", 1, \"b\")\n}"), "types.builder.pairs")
        expectDiagnostic(snippet("P: Struct {\n    pub x: Int32 = 0\n}\n\nmain: () Void {\n    n: Int32 = @setOf(P { }).size() as Int32\n}"), "types.type.key")
        expectDiagnostic(snippet("main: () Void {\n    n: Int32 = @listOf<Int32, Int32>(1).size() as Int32\n}"), "types.builder.type-args")
        expectDiagnostic(snippet("main: () Void {\n    a: Arr<Int32, 2> = @arrOf(1, 2, 3)\n}"), "types.const.arr-size")
    }

    @Test
    fun tupleNIsTheBuildersAxiomAndNoVariadicParameterYet() {
        expectDiagnostic(snippet("f: (xs: TupleN<Int32>) Int32 {\n    0\n}"), "types.type.tuplen")
    }

    // ---- the targets -------------------------------------------------------------------------

    @Test
    fun onPyEachBuilderIsItsPythonValue() {
        val py = PyTestSupport.emit(
            "main: () Void {\n    a: List<Int32> = @listOf(1, 2)\n    b: List<UInt8> = @listOf(7)\n    s: Set<Str> = @setOf(\"x\", \"y\")\n" +
                "    m: Map<Str, Int32> = @mapOf(\"k\", 1, \"j\", 2)\n    e: Set<Str> = @setOf<Str>()\n}",
        ).python()
        assertTrue(py.contains("    a = [1, 2]\n    b = bytearray((7,))\n    s = dict.fromkeys([\"x\", \"y\"])\n    m = {\"k\": 1, \"j\": 2}\n    e = {}\n"), py)
    }

    @Test
    fun onCppASetAndAMapAreTheirRuntimeTypesInitializerLists() {
        val src = DeclTestSupport.module(
            "app:main",
            "main: () Void {\n    s: Set<Str> = @setOf(\"x\")\n    m: Map<Str, Int32> = @mapOf(\"k\", 1, \"j\", 2)\n    xs: List<Int32> = @listOf(1, 2)\n}",
        )
        val out = DeclTestSupport.emit(src).source("app:main") ?: ""
        assertTrue(out.contains("const kira::Set<kira::Str> s = kira::Set<kira::Str>{\"x\"};"), out)
        assertTrue(out.contains("const kira::Map<kira::Str, std::int32_t> m = kira::Map<kira::Str, std::int32_t>{{\"k\", 1}, {\"j\", 2}};"), out)
        assertTrue(out.contains("const kira::List<std::int32_t> xs = {1, 2};"), out)
    }

    @Test
    fun theBuildersPyGoldenHoldsEveryBuilder() {
        val src = File("src/test/resources/py-golden/builders/src/lang/builders.kira").readText()
        Builder.entries.forEach { assertTrue(src.contains("@${it.intrinsic}("), "no @${it.intrinsic} in the golden") }
    }
}
