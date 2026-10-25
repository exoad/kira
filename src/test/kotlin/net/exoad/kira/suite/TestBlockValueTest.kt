package net.exoad.kira.suite

import net.exoad.kira.compiler.analysis.types.display
import net.exoad.kira.cpp.decls.DeclTestSupport
import net.exoad.kira.py.PyTestSupport
import net.exoad.kira.types.TyperTestSupport
import net.exoad.kira.types.TyperTestSupport.expectDiagnostic
import net.exoad.kira.types.TyperTestSupport.expectNoErrors
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Lane C: a test block narrowing a block value. */
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

    /** The narrowed reads of [body], as `name:from->to`, sorted. */
    private fun narrowed(body: String, classes: Boolean): List<String> {
        val p = TyperTestSupport.snippet(text(body, classes))
        expectNoErrors(p)
        return p.model.narrowings.entries.map { (id, n) -> "${id.value}:${n.from.display()}->${n.to.display()}" }.sorted()
    }

    /** [kira]'s printed lines on py. */
    private fun runPy(kira: String): String {
        val py = PyTestSupport.emit(kira).python()
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
        assertEquals("probe\n5\nprobe\n3\nprobe\n-1\n", runPy(text(program)))
    }
}
