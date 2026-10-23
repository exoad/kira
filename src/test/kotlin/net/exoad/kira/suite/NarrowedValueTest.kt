package net.exoad.kira.suite

import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.analysis.types.KiraUnparser
import net.exoad.kira.compiler.analysis.types.display
import net.exoad.kira.compiler.frontend.parser.DialectGates
import net.exoad.kira.compiler.frontend.parser.ast.RootASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TernaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TypeCheckExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.ReturnStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.cpp.decls.DeclTestSupport
import net.exoad.kira.py.PyTestSupport
import net.exoad.kira.types.TyperTestSupport
import net.exoad.kira.types.TyperTestSupport.expectDiagnostic
import net.exoad.kira.types.TyperTestSupport.expectNoErrors
import net.exoad.kira.types.TyperTestSupport.module
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Stage 3's two lanes together: a conformance test narrowing a ternary's arms, a block value's arms and a body's tail. */
class NarrowedValueTest {
    private val types = """
        Shape: Any {
            pub area: () Int32;
        }

        Circle: Shape {
            pub radius: Int32

            override pub area: () Int32 {
                radius
            }
        }
    """.trimIndent()

    private fun text(body: String) = "$types\n\n${body.trimIndent()}"

    private fun snippet(body: String) = TyperTestSupport.snippet(text(body))

    /** The narrowed reads of [body], as `name:from->to`, sorted. */
    private fun narrowed(body: String): List<String> {
        val p = snippet(body)
        expectNoErrors(p)
        return p.model.narrowings.entries.map { (id, n) -> "${id.value}:${n.from.display()}->${n.to.display()}" }.sorted()
    }

    /** The last statement of the function f in [body], as parsed. */
    private fun tailOf(body: String): Statement {
        val unit = TyperTestSupport.unitOf(module("test:main", text(body)))
        val root = unit.allSources().first { runCatching { it.getModuleUri() }.getOrNull() == "test:main" }.ast as RootASTNode
        val f = root.statements.mapNotNull { (it as? Statement)?.expr }.filterIsInstance<FunctionDecl>().first { KiraUnparser.text(it.name) == "f" }
        return f.def.body!!.last()
    }

    private fun cpp(body: String): String = DeclTestSupport.emit(DeclTestSupport.module("app:main", text(body))).source("app:main") ?: ""

    // ---- the ternary -------------------------------------------------------------------------

    @Test
    fun aTernaryWhoseConditionIsATestNarrowsItsFirstArm() {
        val tail = assertIs<ReturnStatement>(tailOf("f: (s: Shape) Int32 {\n    s == Circle ? s.radius : 0\n}"))
        assertTrue(tail.implicit)
        val t = assertIs<TernaryExpr>(tail.expr)
        assertIs<TypeCheckExpr>(t.condition)
        assertEquals("s == Circle ? s.radius : 0", KiraUnparser.text(t))
        assertEquals(listOf("s:Shape->Circle"), narrowed("f: (s: Shape) Int32 {\n    s == Circle ? s.radius : 0\n}"))
        // `!=` narrows the second arm; the rest of an `&&` and the first arm both see its left side.
        assertEquals(listOf("s:Shape->Circle"), narrowed("f: (s: Shape) Int32 {\n    s != Circle ? 0 : s.radius\n}"))
        assertEquals(List(2) { "s:Shape->Circle" }, narrowed("f: (s: Shape) Int32 {\n    s == Circle && s.radius > 1 ? s.radius : 0\n}"))
    }

    @Test
    fun aTernaryNarrowsOnlyTheArmItsTestProves() {
        expectDiagnostic(snippet("f: (s: Shape) Int32 {\n    s == Circle ? 0 : s.radius\n}"), "types.member.unknown")
        expectDiagnostic(snippet("f: (s: Shape) Int32 {\n    s != Circle ? s.radius : 0\n}"), "types.member.unknown")
        expectDiagnostic(snippet("f: (s: Shape, k: Int32) Int32 {\n    s == Circle || k > 0 ? s.radius : 0\n}"), "types.member.unknown")
    }

    @Test
    fun aTernaryInTheSecondArmSeesWhatTheFirstConditionFailingProved() {
        assertEquals(List(3) { "m:Maybe<Int32>->Int32" }, narrowed("f: (m: Maybe<Int32>) Int32 {\n    m != Int32 ? -1 : m > 1 ? m * 100 : m\n}"))
    }

    @Test
    fun aTestInAnArmUnparsesAndParsesBack() {
        val t = assertIs<TernaryExpr>(assertIs<ReturnStatement>(tailOf("f: (c: Bool, s: Shape) Bool {\n    c ? s == Circle : s != Circle\n}")).expr)
        assertIs<TypeCheckExpr>(t.yes)
        assertTrue(assertIs<TypeCheckExpr>(t.no).negated)
        assertEquals("c ? s == Circle : s != Circle", KiraUnparser.text(t))
    }

    @Test
    fun theTernaryLaneCWritesForAnIfValueNarrowsAsTheIfDid() {
        val ternary = "f: (s: Shape) Int32 {\n    v: Int32 = s == Circle ? s.radius : 0\n    v\n}"
        val ifValue = "f: (s: Shape) Int32 {\n    v: Int32 = if s == Circle {\n        s.radius\n    } else {\n        0\n    }\n    v\n}"
        DialectGates.ternaryOnly(true) {
            assertEquals(listOf("s:Shape->Circle"), narrowed(ternary))
            assertEquals("parse.if.ternary", assertThrows<DiagnosticsException> { TyperTestSupport.unitOf(module("test:main", text(ifValue))) }.tag)
        }
        assertEquals(narrowed(ifValue), narrowed(ternary))
        // A Maybe test of a ternary binds below it, so py tests the ternary whole.
        val py = PyTestSupport.emit("f: (m: Maybe<Int32>, n: Maybe<Int32>, k: Int32) Bool {\n    e: Bool = (k > 0 ? m : n) != Int32\n    e\n}").python()
        assertTrue(py.contains("    e = (m if k > 0 else n) is None\n"), py)
    }

    // ---- block values ------------------------------------------------------------------------

    @Test
    fun anIfValuesStatementArmsSeeTheirConditionsNarrowing() {
        val src = "f: (s: Shape, m: Maybe<Int32>) Int32 {\n    v: Int32 = if s == Circle {\n        r: Int32 = s.radius\n        r * 2\n    } else if m != Int32 {\n        -1\n    } else {\n        w: Int32 = m\n        w + 1\n    }\n    v\n}"
        assertEquals(listOf("m:Maybe<Int32>->Int32", "s:Shape->Circle"), narrowed(src))
        expectDiagnostic(snippet("f: (s: Shape) Int32 {\n    v: Int32 = if s == Circle {\n        0\n    } else {\n        r: Int32 = s.radius\n        r\n    }\n    v\n}"), "types.member.unknown")
    }

    @Test
    fun aBodysTailAfterAnIfThatLeavesSeesTheNarrowedName() {
        val src = "f: (s: Shape) Int32 {\n    s != Circle ? 0 : s.radius\n}"
        assertTrue(assertIs<ReturnStatement>(tailOf(src)).implicit)
        assertEquals(listOf("s:Shape->Circle"), narrowed(src))
        // An if that can fall through proves nothing for the tail.
        expectDiagnostic(snippet("f: (s: Shape) Int32 {\n    if s != Circle {\n        trace(0)\n    }\n    s.radius\n}"), "types.member.unknown")
    }

    @Test
    fun aBodysLastIfChainNarrowsEachArmItEnds() {
        val src = "f: (s: Shape, m: Maybe<Int32>) Int32 {\n    s == Circle ? s.radius : m == Int32 ? m : -1\n}"
        assertEquals(listOf("m:Maybe<Int32>->Int32", "s:Shape->Circle"), narrowed(src))
    }

    @Test
    fun aTryValueInANarrowedArmSeesTheNarrowedName() {
        val src = "h: (n: Int32) Int32 {\n    if n > 1 {\n        throw \"far\"\n    }\n    n\n}\n\n" +
            "f: (s: Shape) Int32 {\n    s == Circle ? (try {\n        h(s.radius)\n    } on e: Str {\n        -1\n    }) : 0\n}"
        assertEquals(listOf("s:Shape->Circle"), narrowed(src))
        val lambda = "f: (s: Shape) Int32 {\n    g: (x: Shape) Int32 = (x: Shape) Int32 {\n        x == Circle ? x.radius : 7\n    }\n    g(s)\n}"
        assertEquals(listOf("x:Shape->Circle"), narrowed(lambda))
    }

    // ---- lowering ----------------------------------------------------------------------------

    @Test
    fun onPyANarrowingGuardAsATernaryNarrowsItsElseArm() {
        // The guard `if m != Int32 { return -k }` and its tail, as kira-dialect stage3 writes them.
        val src = "f: (m: Maybe<Int32>, k: Int32) Int32 {\n    m != Int32 ? -k : m * k\n}\n\n" +
            "g: (m: Maybe<Int32>) Int32 {\n    m == Int32 ? m + 1 : 0\n}"
        val py = PyTestSupport.emit(src).python()
        assertTrue(py.contains("    return _k_i32(-k) if m is None else _k_i32(m * k)\n"), py)
        assertTrue(py.contains("    return _k_i32(m + 1) if m is not None else 0\n"), py)
    }

    @Test
    fun onPyANarrowedArmWithStatementsIsADefThatReadsTheName() {
        val py = PyTestSupport.emit(
            "f: (m: Maybe<Int32>) Int32 {\n    v: Int32 = if m == Int32 {\n        t: Int32 = m * 10\n        t + 1\n    } else {\n        0\n    }\n    v\n}",
        ).python()
        assertTrue(py.contains("    def _k_t0():\n        if m is not None:\n            t = _k_i32(m * 10)\n            return _k_i32(t + 1)\n        else:\n            return 0\n    v = _k_t0()\n"), py)
    }

    @Test
    fun onCppANarrowedTernaryCastsInItsArmAndATailAfterAGuardToo() {
        val ternary = cpp("pub f: (s: Shape, m: Maybe<Int32>) Int32 {\n    s == Circle ? s.radius : m == Int32 ? m : 0\n}")
        assertTrue(
            ternary.contains("return (dynamic_cast<const Circle*>(s.get()) != nullptr) ? std::dynamic_pointer_cast<Circle>(s)->radius : kira::isSome(m) ? kira::unwrap(m) : 0;"),
            ternary,
        )
        val guard = cpp("pub f: (s: Shape) Int32 {\n    s != Circle ? 0 : s.radius\n}")
        // A guard is a ternary now: its else arm narrows as the code after the guard did.
        assertTrue(guard.contains(" ? 0 : std::dynamic_pointer_cast<Circle>(s)->radius;"), guard)
    }
}
