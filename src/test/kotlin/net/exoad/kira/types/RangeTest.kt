package net.exoad.kira.types

import net.exoad.kira.TestCompileSupport
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.KiraUnparser
import net.exoad.kira.compiler.analysis.types.LoopKind
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.display
import net.exoad.kira.compiler.frontend.parser.ast.UnsupportedConstruct
import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.RangeExpr
import net.exoad.kira.cpp.decls.DeclTestSupport
import net.exoad.kira.types.TyperTestSupport.expectDiagnostic
import net.exoad.kira.types.TyperTestSupport.expectNoErrors
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `a..b` as a value of kira:range's Range<T>, inclusive at both ends, and `x in r` anywhere. */
class RangeTest {
    private fun typed(body: String): TypedProgram = TyperTestSupport.snippet(body)

    /** Each `x in r` of [p]'s module as `text: type`, in source order. */
    private fun tests(p: TypedProgram): List<String> {
        val root = p.modules.first { it.uri == "test:main" }.source.ast
        return TyperTestSupport.nodes(root).filterIsInstance<BinaryExpr>().filter { it.operator == BinaryOp.IN }
            .map { "${KiraUnparser.text(it)}: ${p.model.types[it]?.display()}" }
    }

    private fun rangeTypes(p: TypedProgram): List<String> {
        val root = p.modules.first { it.uri == "test:main" }.source.ast
        return TyperTestSupport.nodes(root).filterIsInstance<RangeExpr>().map { "${KiraUnparser.text(it)}: ${p.model.types[it]?.display()}" }
    }

    private fun only(p: TypedProgram, code: String) {
        expectDiagnostic(p, code)
        assertEquals(listOf(code), p.diagnostics.filter { it.isError }.map { it.code }.distinct(), TyperTestSupport.render(p))
    }

    private fun cpp(body: String): String = DeclTestSupport.emit(DeclTestSupport.module("app:main", body)).source("app:main") ?: ""

    // ---- the probe: fails on main, where `in` belongs to `for` alone --------------------------

    @Test
    fun xInALiteralRangeIsABoolAnywhere() {
        val p = typed(
            """
            pub link: (code: Int32) Bool {
                code in 9..13
            }

            pub either: (code: Int32, up: Bool) Bool {
                up && code in 9..13 || code in 20..29
            }
            """
        )
        expectNoErrors(p)
        assertEquals(listOf("code in 9..13: Bool", "code in 9..13: Bool", "code in 20..29: Bool"), tests(p))
        assertEquals(listOf("9..13: Range<Int32>", "9..13: Range<Int32>", "20..29: Range<Int32>"), rangeTypes(p))
    }

    // ---- typing --------------------------------------------------------------------------------

    @Test
    fun aLiteralEndTakesTheOtherSidesTypeAndAFloatMakesAnAllLiteralRangeFloat64() {
        val p = typed(
            """
            pub small: (x: UInt8) Bool {
                x in 0..200
            }

            pub half: (f: Float64) Bool {
                f in 0..2.5
            }

            pub bounded: (n: Int64) Bool {
                5 in 0..n
            }

            pub held: () Range<UInt8> {
                0..200
            }

            pub floats: () Bool {
                r: Range<Float64> = 0..2.5
                1.5 in r
            }
            """
        )
        expectNoErrors(p)
        assertEquals(
            listOf("0..200: Range<UInt8>", "0..2.5: Range<Float64>", "0..n: Range<Int64>", "0..200: Range<UInt8>", "0..2.5: Range<Float64>"),
            rangeTypes(p),
        )
    }

    @Test
    fun aRangeHeldInAVariableAnswersThroughItsInOperatorAndIteratesBothEnds() {
        val p = typed(
            """
            pub held: (x: Int32) Bool {
                r: Range<Int32> = 9..13
                x in r && r.contains(x) && r.begin <= r.end
            }

            pub walk: (r: Range<Int32>) Int32 {
                t: Mutable<Int32> = 0
                for i: Int32 in r {
                    t += i
                }
                for j: Int32 in 0..3 {
                    t += j
                }
                t
            }
            """
        )
        expectNoErrors(p)
        val calls = p.model.opCalls.values.map { "${it.kind} ${it.fn?.owner?.name}.${it.fn?.name}" }
        assertEquals(listOf("OP_OVERLOAD Range._op_in_"), calls)
        assertEquals(setOf(LoopKind.RANGE_VALUE, LoopKind.RANGE), p.model.loops.values.map { it.kind }.toSet())
    }

    @Test
    fun aUserTypeAnswersInThroughItsMemberOperator() {
        val p = typed(
            """
            pub Box: Struct {
                pub lo: Int32
                pub hi: Int32

                pub @_op_in_: (v: Int32) Bool {
                    v >= lo && v <= hi
                }
            }

            pub f: (b: Box) Bool {
                4 in b
            }
            """
        )
        expectNoErrors(p)
        val rc = p.model.opCalls.values.single()
        assertEquals(CallKind.OP_OVERLOAD, rc.kind)
        assertEquals("Box", rc.fn?.owner?.name)
        assertEquals("b", KiraUnparser.text(rc.receiver))
    }

    @Test
    fun inBindsAsAComparisonDoesBelowArithmeticAndAboveAnd() {
        val p = typed(
            """
            pub f: (x: Int32, n: Int32, ok: Bool) Bool {
                x + 1 in 0..n && ok
            }
            """
        )
        expectNoErrors(p)
        assertEquals(listOf("x + 1 in 0..n: Bool"), tests(p))
    }

    @Test
    fun aTypeParameterBoundedByNumIsOrderedAndOneThatIsNotIsNot() {
        expectNoErrors(typed("pub lo<T: Num>: (a: T, b: T) Bool {\n    a <= b\n}"))
        expectDiagnostic(typed("pub lo<T>: (a: T, b: T) Bool {\n    a <= b\n}"), "types.op.mismatch")
    }

    // ---- one negative test per new diagnostic ------------------------------------------------

    @Test
    fun theTwoEndsOfARangeHaveOneType() {
        only(typed("pub f: (n: Int32, m: Int64) Range<Int32> {\n    n..m\n}"), "types.range.mismatch")
    }

    @Test
    fun aRangeHoldsIntegersOrFloatsOnly() {
        only(typed("pub f: (s: Str) Bool {\n    s in \"a\"..\"z\"\n}"), "types.range.element")
        only(typed("pub f: (c: Char) Bool {\n    c in 'a'..'z'\n}"), "types.range.element")
    }

    @Test
    fun theValueInTestsHasTheRangesType() {
        only(typed("pub f: (x: Int64, n: Int32) Bool {\n    x in 0..n\n}"), "types.in.mismatch")
        only(typed("pub f: (i: Size, n: Int32) Bool {\n    i in 0..n\n}"), "types.in.mismatch")
        only(typed("pub f: (m: Int64, r: Range<Int32>) Bool {\n    m in r\n}"), "types.in.mismatch")
        assertTrue(typed("pub f: (i: Size, n: Int32) Bool {\n    i in 0..n\n}").diagnostics.single().message.contains("Size mixes with no other integer type"))
    }

    @Test
    fun aRightSideWithNoInOperatorIsRefused() {
        only(typed("pub f: (x: Int32, s: Str) Bool {\n    x in s\n}"), "types.in.unsupported")
        only(typed("pub V: Struct {\n    pub x: Int32\n}\n\npub f: (v: V) Bool {\n    1 in v\n}"), "types.in.unsupported")
    }

    @Test
    fun anInOperatorTakesOneValueAndReturnsBool() {
        val p = typed(
            """
            pub V: Struct {
                pub x: Int32

                pub @_op_in_: (v: Int32) Int32 {
                    v
                }
            }

            pub f: (v: V) Bool {
                1 in v
            }
            """
        )
        only(p, "types.in.shape")
    }

    @Test
    fun existingDiagnosticsCoverTheRestOfARangesRules() {
        // A float range is a value and a test, never a loop; a float literal is no integer end.
        only(typed("pub f: (r: Range<Float64>) Int32 {\n    t: Mutable<Int32> = 0\n    for i: Float64 in r {\n        t += 1\n    }\n    t\n}"), "types.for.range-type")
        only(typed("pub f: (x: Int32) Bool {\n    x in 0..2.5\n}"), "types.literal.float-to-int")
        expectDiagnostic(typed("pub f: (r: Range<Str>) Bool {\n    true\n}"), "rules.generics.bound")
        // A range is a value now, so the old position error is gone and a wrong slot is a mismatch.
        val p = typed("pub f: () Int32 {\n    r: Int32 = 0..3\n    r\n}")
        expectDiagnostic(p, "types.assign.mismatch")
        assertFalse(p.diagnostics.any { it.code == "types.range.position" }, TyperTestSupport.render(p))
    }

    // ---- C++ ---------------------------------------------------------------------------------

    @Test
    fun onCppALiteralRangeIsTwoComparisonsAndAnEndAtTheTypesLimitIsLeftOut() {
        val out = cpp(
            """
            pub link: (code: Int32) Bool {
                code in 9..13
            }

            pub small: (x: UInt8) Bool {
                x in 0..200
            }
            """
        )
        assertTrue(out.contains("return 9 <= code && code <= 13;"), out)
        // gcc's -Wtype-limits refuses `0u <= x` on an unsigned x, and it always holds.
        assertTrue(out.contains("return x <= 200u;"), out)
        assertFalse(out.contains("Range"), "a literal range under `in` builds no Range:\n$out")
    }

    @Test
    fun onCppTheTestedValueRunsOnceAndFirst() {
        val out = cpp(
            """
            next: () Int32 {
                println("next")
                5
            }

            pub f: () Bool {
                next() in 1..9
            }
            """
        )
        assertTrue(out.contains("const std::int32_t t0_ = next();"), out)
        assertTrue(out.contains("return 1 <= t0_ && t0_ <= 9;"), out)
    }

    @Test
    fun onCppARangeValueIsTheStructAndItsLoopStopsAtItsEnd() {
        val out = cpp(
            """
            pub f: (x: Int32) Int32 {
                r: Range<Int32> = 1..4
                t: Mutable<Int32> = 0
                for i: Int32 in r {
                    t += i
                }
                x in r ? t : 0
            }
            """
        )
        assertTrue(out.contains("const ::kira::range::Range<std::int32_t> r = ::kira::range::Range<std::int32_t>{.begin = 1, .end = 4};"), out)
        assertTrue(out.contains("bool i_more = i_r.begin <= i_r.end;"), out)
        assertTrue(out.contains("for(std::int32_t i = i_r.begin; i_more; i_more = i != i_r.end && (++i, true))"), out)
        assertTrue(out.contains("r._op_in_(x)"), out)
    }

    // ---- C and JS ----------------------------------------------------------------------------

    @Test
    fun cAndJsRefuseInAndARangeValueButKeepTheRangeLoop() {
        val tested = "f: (k: Int32) Bool {\n    k in 9..13\n}\n"
        val held = "f: () Int32 {\n    r: Range<Int32> = 1..4\n    0\n}\n"
        for ((source, what) in listOf(tested to "`in` outside a for loop", held to "a range as a value")) {
            val refused = listOf(
                assertThrows<UnsupportedConstruct> { TestCompileSupport.transpileSnippetToC(source, "test/main.kira") },
                assertThrows<UnsupportedConstruct> { TestCompileSupport.transpileSnippetToJS(source, "test/main.kira") },
            )
            refused.forEach { assertTrue(it.message.orEmpty().contains(what), it.message) }
        }
        val loop = "f: () Int32 {\n    t: Mutable<Int32> = 0\n    for i: Int32 in 0..3 {\n        t += i\n    }\n    t\n}\n"
        assertTrue(TestCompileSupport.transpileSnippetToC(loop, "test/main.kira").contains(" < 3; ++i"))
        assertNull(runCatching { TestCompileSupport.transpileSnippetToJS(loop, "test/main.kira") }.exceptionOrNull())
    }
}
