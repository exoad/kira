package net.exoad.kira.suite

import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.analysis.types.KiraUnparser
import net.exoad.kira.compiler.analysis.types.Severity
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TypeCheckExpr
import net.exoad.kira.compiler.frontend.parser.ast.statements.IfSelectionStatement
import net.exoad.kira.cpp.decls.DeclTestSupport
import net.exoad.kira.py.PyTestSupport
import net.exoad.kira.types.TyperTestSupport
import net.exoad.kira.types.TyperTestSupport.expectNoErrors
import net.exoad.kira.types.TyperTestSupport.module
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Test blocks (stage 3b of the clean dialect): `value == T { check, ... }`, named checks ANDed. */
class TestBlockTest {
    private val types = """
        Pt: Struct {
            pub x: Int32 = 0
            pub y: Int32 = 0
        }

        Line: Struct {
            pub start: Pt
            pub end: Pt
        }

        Flags: Struct {
            pub lit: Bool = false
        }

        Seg: Struct {
            pub tip: Maybe<Pt>
        }
    """.trimIndent()

    private fun text(body: String) = "$types\n\n${body.trimIndent()}"

    private fun parseError(body: String): DiagnosticsException =
        assertThrows { TyperTestSupport.unitOf(module("test:main", text(body))) }

    private fun parsed(body: String) = TyperTestSupport.unitOf(module("test:main", text(body))).allSources().first { it.file == "test/main.kira" }.ast

    /** The initializer of the local `a` in a function with a Pt, a Line, a Maybe and an Int32 at hand. */
    private fun init(test: String): Expr =
        TyperTestSupport.nodes(parsed("f: (p: Pt, line: Line, m: Maybe<Pt>, k: Int32) Void {\n    a: Bool = $test\n}"))
            .filterIsInstance<VariableDecl>().first { it.name.value == "a" }.value!!

    // ---- the parser --------------------------------------------------------------------------

    @Test
    fun aBraceAfterAnIdentityIsATestBlockOfCommaSeparatedChecks() {
        val e = assertIs<TypeCheckExpr>(init("p == Pt { x == 1, y > k }"))
        assertEquals(2, e.checks!!.size)
        val nested = assertIs<TypeCheckExpr>(init("line == Line { start == Pt { x == 0 }, end.x > 1 }"))
        assertEquals(1, assertIs<TypeCheckExpr>(nested.checks!![0]).checks!!.size)
        assertEquals("line == Line { start == Pt { x == 0 }, end.x > 1 }", KiraUnparser.text(nested))
        assertTrue(assertIs<TypeCheckExpr>(init("m != Pt { x == 1 }")).negated)
        // Without a brace it is the plain test, and a construction to compare with goes in parentheses.
        assertNull(assertIs<TypeCheckExpr>(init("p == Pt")).checks)
        assertIs<BinaryExpr>(init("p == (Pt { 1, 2 })"))
        // It binds as `==` does: the rest of the condition follows it.
        val and = assertIs<BinaryExpr>(init("p == Pt { x == 1 } && k > 2"))
        assertNotNull(assertIs<TypeCheckExpr>(and.leftExpr).checks)
    }

    @Test
    fun inAConditionTheBraceIsATestBlockOnlyWhenTheConditionGoesOn() {
        val ifs = TyperTestSupport.nodes(
            parsed(
                """
                f: (p: Pt, k: Int32) Int32 {
                    r: Mutable<Int32> = 0
                    if p == Pt { x > 0 } {
                        r = 1
                    }
                    if p == Pt { x > 0 } && k > 1 {
                        r = 2
                    }
                    if p != Pt { x > 0 } || k > 1 {
                        r = 3
                    }
                    if p == Pt {
                        r = 4
                    }
                    r
                }
                """,
            ),
        ).filterIsInstance<IfSelectionStatement>()
        assertEquals(4, ifs.size)
        assertNotNull(assertIs<TypeCheckExpr>(ifs[0].expr).checks)
        assertEquals(1, ifs[0].thenStatements.size)
        assertNotNull(assertIs<TypeCheckExpr>(assertIs<BinaryExpr>(ifs[1].expr).leftExpr).checks)
        assertNotNull(assertIs<TypeCheckExpr>(assertIs<BinaryExpr>(ifs[2].expr).leftExpr).checks)
        // No condition follows the block: it is the body (stage 3's reading).
        assertNull(assertIs<TypeCheckExpr>(ifs[3].expr).checks)
    }

    @Test
    fun anAssignmentInATestBlockIsRefusedWithTheTestToWrite() {
        for (check in listOf("x = 1", "x += 1", "line.start = p")) {
            val e = parseError("f: (p: Pt, line: Line) Bool {\n    p == Pt { $check }\n}")
            assertEquals("parse.test.assign", e.tag, check)
            assertTrue(e.message.contains("never assigns") && e.message.contains("(T { ... })"), e.message)
        }
        assertTrue(parseError("f: (p: Pt) Bool {\n    p == Pt { x = 1 }\n}").message.contains("'x == value'"))
        assertEquals("parse.test.empty", parseError("f: (p: Pt) Bool {\n    p == Pt { }\n}").tag)
    }

    @Test
    fun aTestWhereAValueIsBuiltIsRefusedWithBothSpellings() {
        val construction = parseError("f: (k: Int32) Flags {\n    Flags { k == 1 }\n}")
        assertEquals("parse.build.test", construction.tag)
        assertTrue(construction.message.contains("A construction builds") && construction.message.contains("'v == T { k == value }'"), construction.message)
        val with = parseError("f: (g: Flags) Flags {\n    g with {\n        lit == true\n    }\n}")
        assertEquals("parse.build.test", with.tag)
        assertTrue(with.message.contains("A `with` block builds"), with.message)
        // A Bool computed by a comparison goes in parentheses; a member path on the left is no test.
        expectNoErrors(TyperTestSupport.snippet(text("f: (k: Int32, p: Pt) Flags {\n    a: Flags = Flags { (k == 1) }\n    Flags { p.x == 1 }\n}")))
    }

    // ---- the typer ---------------------------------------------------------------------------

    @Test
    fun aTestBlockOnAValueOfItsOwnTypeIsNoConstantAndWarnsNothing() {
        val p = TyperTestSupport.snippet(text("f: (p: Pt) Bool {\n    p == Pt { x == 1 }\n}"))
        expectNoErrors(p)
        assertTrue(p.diagnostics.none { it.severity == Severity.WARNING }, TyperTestSupport.render(p))
        // Without the block the test is the types' and says so.
        TyperTestSupport.expectDiagnostic(TyperTestSupport.snippet(text("f: (p: Pt) Bool {\n    p == Pt\n}")), "types.conform.always")
    }

    @Test
    fun aCheckReadsTheMembersBareAndALocalOfAMemberNameIsAClash() {
        val p = TyperTestSupport.snippet(text("f: (p: Pt, k: Int32) Bool {\n    p == Pt { x == k }\n}"))
        expectNoErrors(p)
        assertEquals(1, p.model.testMembers.size)
        val clash = TyperTestSupport.expectDiagnostic(TyperTestSupport.snippet(text("f: (p: Pt, x: Int32) Bool {\n    p == Pt { y == x }\n}")), "types.test.clash")
        assertTrue(clash.message.contains("this test block's Pt"), clash.message)
    }

    // ---- lowering ----------------------------------------------------------------------------

    private val program = """
        probe: () Pt {
            Pt { 1, 2 }
        }

        f: (p: Pt, m: Maybe<Pt>, line: Line, k: Int32) Int32 {
            a: Bool = p == Pt { x == 1, y > k }
            b: Bool = m == Pt { x == 1 }
            c: Bool = m != Pt { x == 1 }
            d: Bool = line == Line { start == Pt { x == 0 }, end.x > 1 }
            e: Bool = probe() == Pt { y == 2 }
            m == Pt { x > 0 } && a ? m.x : 0
        }

        paths: (line: Line, s: Seg) Bool {
            a: Bool = line.start == Pt { x == 0 }
            a && s.tip == Pt { y == 2 }
        }

        hoisted: (k: Int32) Bool {
            probe() == Pt {
                (if x > k {
                    t: Int32 = x * 2
                    t
                } else {
                    0
                }) > 2
            }
        }
    """

    @Test
    fun onPyATestBlockIsItsTestThenItsChecksJoinedByAnd() {
        val py = PyTestSupport.emit(text(program)).python()
        assertTrue(py.contains("    a = p.x == 1 and p.y > k\n"), py)
        assertTrue(py.contains("    b = m is not None and m.x == 1\n"), py)
        assertTrue(py.contains("    c = not (m is not None and m.x == 1)\n"), py)
        // A field read again as the nested block's subject; a call held once.
        assertTrue(py.contains("    d = line.start.x == 0 and line.end.x > 1\n"), py)
        assertTrue(py.contains("    e = (lambda _k_s1: _k_s1.y == 2)(_probe())\n"), py)
        // The ternary's first arm reads m as the Pt the block proved.
        assertTrue(py.contains("    return m.x if m is not None and m.x > 0 and a else 0\n"), py)
        // A subject written as a field path is read again, a Maybe field's too.
        assertTrue(py.contains("    a = line.start.x == 0\n    return a and (s.tip is not None and s.tip.y == 2)\n"), py)
        // A def a check needs is written before the statement, so it takes the held subject, the lambda's parameter.
        assertTrue(py.contains("    def _k_t0(_k_s4):\n        if _k_s4.x > k:\n            t = _k_i32(_k_s4.x * 2)\n"), py)
        assertTrue(py.contains("    return (lambda _k_s4: _k_t0(_k_s4) > 2)(_probe())\n"), py)
    }

    @Test
    fun onCppATestBlockIsItsTestThenItsChecksAndAHeldValueIsRefused() {
        val shapes = """
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
        val src = DeclTestSupport.module(
            "app:main",
            text(
                "$shapes\n\npub f: (s: Shape, m: Maybe<Pt>, line: Line, g: Seg) Int32 {\n    if s == Circle { radius > 1 } && m == Pt { x == 2 } {\n        s.radius + m.x\n    } else {\n" +
                    "        a: Bool = line == Line { start == Pt { x == 0 } }\n        b: Bool = line.end == Pt { y == 3 } && g.tip == Pt { x == 4 }\n        a && b ? 1 : 0\n    }\n}",
            ),
        )
        val emitted = DeclTestSupport.emit(src)
        val out = emitted.source("app:main") ?: ""
        assertTrue(emitted.diagnostics("app:main").isEmpty(), emitted.render(emitted.diagnostics("app:main")))
        assertTrue(out.contains("(dynamic_cast<const Circle*>(s.get()) != nullptr) && std::dynamic_pointer_cast<Circle>(s)->radius > 1"), out)
        assertTrue(out.contains("kira::isSome(m) && kira::unwrap(m).x == 2"), out)
        assertTrue(out.contains("line.start.x == 0"), out)
        // A subject written as a field path is no held value: read again, a Maybe field's as its value.
        assertTrue(out.contains("line.end.y == 3"), out)
        assertTrue(out.contains("kira::isSome(g.tip) && kira::unwrap(g.tip).x == 4"), out)
        val held = DeclTestSupport.emit(DeclTestSupport.module("app:main", text("probe: () Pt {\n    Pt { 1, 2 }\n}\n\npub f: () Bool {\n    probe() == Pt { y == 2 }\n}")))
        assertTrue(
            held.diagnostics("app:main").any { it.code == "cpp.unsupported" && it.message.contains("bind it to a local first") },
            held.render(held.diagnostics("app:main")),
        )
    }
}
