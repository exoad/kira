package net.exoad.kira.suite

import net.exoad.kira.compiler.analysis.types.display
import net.exoad.kira.compiler.frontend.parser.DialectGates
import net.exoad.kira.cpp.decls.DeclTestSupport
import net.exoad.kira.dialect.Stage3
import net.exoad.kira.py.PyTestSupport
import net.exoad.kira.types.TyperTestSupport
import net.exoad.kira.types.TyperTestSupport.expectDiagnostic
import net.exoad.kira.types.TyperTestSupport.expectNoErrors
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stage 3b and lane C together: kira-dialect stage3 on test blocks, and a test block narrowing a block value. */
class TestBlockValueTest {
    private val structs = """
        Pt: Struct {
            pub x: Int32 = 0
            pub y: Int32 = 0
        }

        Line: Struct {
            pub start: Pt
            pub end: Pt
        }
    """.trimIndent()

    // Classes for the typer and C++ (py has no traits yet).
    private val shapes = """
        Shape: Any {
            pub area: () Int32;
        }

        Circle: Shape {
            pub radius: Int32

            override pub area: () Int32 {
                radius
            }
        }

        Square: Shape {
            pub side: Int32

            override pub area: () Int32 {
                side * side
            }
        }
    """.trimIndent()

    private fun text(body: String, classes: Boolean = false) = "${if (classes) "$shapes\n\n" else ""}$structs\n\n${body.trimIndent()}\n"

    /** [old] (stage 2's text) converted by stage3: proved, nothing left for a hand fix, all stage 3. */
    private fun convert(old: String): String {
        val r = Stage3.convertText(old, "probe.kira", "test:main")
        assertNull(r.problem, r.problem)
        assertEquals(emptyList(), r.hand, "left for a hand fix")
        val out = r.text ?: old
        assertTrue(r.full, "not all stage 3:\n$out")
        return out
    }

    /** The narrowed reads of [body], as `name:from->to`, sorted. */
    private fun narrowed(body: String, classes: Boolean): List<String> {
        val p = TyperTestSupport.snippet(text(body, classes))
        expectNoErrors(p)
        return p.model.narrowings.entries.map { (id, n) -> "${id.value}:${n.from.display()}->${n.to.display()}" }.sorted()
    }

    /** [kira]'s printed lines on py, with stage 3's refusals off when it is [old] text. */
    private fun runPy(kira: String, old: Boolean): String {
        val py = if (old) DialectGates.stage3(false) { PyTestSupport.emit(kira).python() } else PyTestSupport.emit(kira).python()
        val file = Files.createTempFile("testblock-run", ".py").toFile()
        try {
            file.writeText(py)
            val r = PyTestSupport.run(listOf(PyTestSupport.python!!, file.absolutePath), file.parentFile)
            assertEquals(0, r.exitCode, r.all)
            return r.stdout
        } finally {
            file.delete()
        }
    }

    // ---- kira-dialect stage3 on a test block -------------------------------------------------

    @Test
    fun aGuardOnATestBlockIsATernaryOrAnElseAndItsBlockStays() {
        // The block's brace is no body: the guard's body is the brace after it.
        assertEquals(
            text("describe: (s: Shape) Str {\n    s != Circle { radius > 2 } ? \"other\" : \"circle\"\n}", classes = true),
            convert(text("describe: (s: Shape) Str {\n    if s != Circle { radius > 2 } {\n        return \"other\"\n    }\n    return \"circle\"\n}", classes = true)),
        )
        assertEquals(
            text("size: (s: Shape) Int32 {\n    if s == Square { side > 0 } {\n        s.side\n    } else {\n        trace(\"no square\")\n        0\n    }\n}", classes = true),
            convert(text("size: (s: Shape) Int32 {\n    if s == Square { side > 0 } {\n        return s.side\n    }\n    trace(\"no square\")\n    return 0\n}", classes = true)),
        )
        // A nested field pattern, and a block in the guard after it.
        assertEquals(
            text("flat: (line: Line, m: Maybe<Pt>) Int32 {\n    line == Line { start == Pt { y == 0 }, end.y == 0 } ? 0 : m == Pt { x > 0 } ? m.x : -1\n}"),
            convert(text("flat: (line: Line, m: Maybe<Pt>) Int32 {\n    if line == Line { start == Pt { y == 0 }, end.y == 0 } {\n        return 0\n    }\n    if m == Pt { x > 0 } {\n        return m.x\n    }\n    return -1\n}")),
        )
    }

    @Test
    fun aVoidGuardOnATestBlockNegatesItAndKeepsItsChecks() {
        assertEquals(
            text("say: (p: Pt) Void {\n    if p != Pt { x == 0 } {\n        trace(p.y)\n    }\n}"),
            convert(text("say: (p: Pt) Void {\n    if p == Pt { x == 0 } {\n        return\n    }\n    trace(p.y)\n}")),
        )
        // A Maybe subject: the rest the guard narrowed is the new if's body, narrowed by the same block.
        assertEquals(
            text("sayMaybe: (m: Maybe<Pt>) Void {\n    if m == Pt { y > 0 } {\n        trace(m.y)\n    }\n}"),
            convert(text("sayMaybe: (m: Maybe<Pt>) Void {\n    if m != Pt { y > 0 } {\n        return\n    }\n    trace(m.y)\n}")),
        )
    }

    @Test
    fun aLoopWhoseConditionHoldsATestBlockReturnsByBreak() {
        val new = convert(
            text(
                """
                climb: (start: Pt) Int32 {
                    mut p: Pt = start
                    while p == Pt { x < 10 } {
                        if p.y > 3 {
                            return -1
                        }
                        p = Pt { p.x + 1, p.y + 1 }
                    }
                    return p.x
                }
                """,
            ),
        )
        assertTrue(!new.contains("return") && new.contains("    while p == Pt { x < 10 } {\n") && new.contains("break"), new)
    }

    private val converted = """
        describe: (m: Maybe<Pt>) Str {
            if m != Pt { x > 2 } {
                return "other"
            }
            return "pt ${'$'}{m.x}"
        }

        size: (m: Maybe<Pt>) Int32 {
            if m == Pt { y > 0 } {
                return m.y
            }
            trace("no y")
            return 0
        }

        flat: (line: Line) Int32 {
            if line == Line { start == Pt { y == 0 }, end.y == 0 } {
                return line.end.x - line.start.x
            }
            return -1
        }

        say: (p: Pt) Void {
            if p == Pt { x == 0 } {
                return
            }
            trace(p.y)
        }

        sayMaybe: (m: Maybe<Pt>) Void {
            if m != Pt { y > 0 } {
                return
            }
            trace(m.y)
        }

        climb: (start: Pt) Int32 {
            mut p: Pt = start
            while p == Pt { x < 10 } {
                if p.y > 3 {
                    return -1
                }
                p = Pt { p.x + 1, p.y + 1 }
            }
            return p.x
        }

        main: () Void {
            trace(describe(Pt { 3, 0 }))
            trace(describe(Pt { 1, 0 }))
            trace(describe(null))
            trace(size(Pt { 0, 4 }))
            trace(size(null))
            trace(flat(Line { Pt { 1, 0 }, Pt { 5, 0 } }))
            trace(flat(Line { Pt { 1, 0 }, Pt { 5, 2 } }))
            say(Pt { 0, 5 })
            say(Pt { 1, 5 })
            sayMaybe(null)
            sayMaybe(Pt { 1, 0 })
            sayMaybe(Pt { 1, 7 })
            trace(climb(Pt { 0, 0 }))
            trace(climb(Pt { 8, 0 }))
        }
    """

    @Test
    fun aConvertedProgramOfTestBlocksPrintsWhatTheOldOneDid() {
        assumeTrue(PyTestSupport.python != null, "no Python on PATH (set KIRA_PYTHON)")
        val old = text(converted)
        val new = convert(old)
        assertTrue(!new.contains("return"), new)
        val want = "pt 3\nother\nother\n4\nno y\n0\n4\n-1\n5\n7\n-1\n10\n"
        assertEquals(want, runPy(old, old = true))
        assertEquals(want, runPy(new, old = false))
    }

    // ---- a test block narrowing a block value ------------------------------------------------

    @Test
    fun aTestBlockNarrowsTheIfValueArmItProves() {
        val passed = "f: (s: Shape) Int32 {\n    v: Int32 = if s == Circle { radius > 1 } {\n        t: Int32 = s.radius * 2\n        t\n    } else {\n        0\n    }\n    v\n}"
        // The block's own subject and the arm's read, both as the Circle the test proved.
        assertEquals(List(2) { "s:Shape->Circle" }, narrowed(passed, classes = true))
        val failed = "f: (s: Shape) Int32 {\n    v: Int32 = if s != Circle { radius > 1 } {\n        0\n    } else {\n        t: Int32 = s.radius * 2\n        t\n    }\n    v\n}"
        assertEquals(List(2) { "s:Shape->Circle" }, narrowed(failed, classes = true))
        // A failed block proves nothing of the identity: the other arm sees a Shape.
        val wrong = "f: (s: Shape) Int32 {\n    v: Int32 = if s == Circle { radius > 1 } {\n        0\n    } else {\n        t: Int32 = s.radius\n        t\n    }\n    v\n}"
        expectDiagnostic(TyperTestSupport.snippet(text(wrong, classes = true)), "types.member.unknown")
        // C++ reads the arm through the cast the test made.
        val cpp = DeclTestSupport.emit(DeclTestSupport.module("app:main", text(passed, classes = true)))
        assertTrue(cpp.diagnostics("app:main").isEmpty(), cpp.render(cpp.diagnostics("app:main")))
        assertTrue((cpp.source("app:main") ?: "").contains("std::dynamic_pointer_cast<Circle>(s)->radius * 2"), cpp.source("app:main"))
    }

    @Test
    fun aTestBlockOnAMaybeNarrowsABodysLastIfValue() {
        val body = "f: (m: Maybe<Pt>) Int32 {\n    if m == Pt { x > 0 } {\n        d: Int32 = m.x + m.y\n        d\n    } else {\n        -1\n    }\n}"
        assertEquals(List(3) { "m:Maybe<Pt>->Pt" }, narrowed(body, classes = false))
        val py = PyTestSupport.emit(text(body)).python()
        assertTrue(py.contains("    if m is not None and m.x > 0:\n        d = _k_i32(m.x + m.y)\n        return d\n"), py)
    }

    @Test
    fun aHeldSubjectInAnIfValuesConditionIsEvaluatedOnceOnPy() {
        assumeTrue(PyTestSupport.python != null, "no Python on PATH (set KIRA_PYTHON)")
        val program = """
            probe: (k: Int32) Pt {
                trace("probe")
                Pt { k, k * 2 }
            }

            pick: (m: Maybe<Pt>, k: Int32) Int32 {
                v: Int32 = if m == Pt { x > k } {
                    t: Int32 = m.x * 2
                    t
                } else {
                    0
                }
                w: Int32 = if probe(k) == Pt { y > 2 } {
                    u: Int32 = k + 1
                    u
                } else {
                    -k
                }
                v + w
            }

            main: () Void {
                trace(pick(Pt { 3, 0 }, 1))
                trace(pick(null, 2))
                trace(pick(Pt { 1, 0 }, 1))
            }
        """
        assertEquals("probe\n5\nprobe\n3\nprobe\n-1\n", runPy(text(program), old = false))
    }
}
