package net.exoad.kira.suite

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.frontend.parser.DialectGates
import net.exoad.kira.dialect.ProjectUnits
import net.exoad.kira.dialect.ReturnProof
import net.exoad.kira.dialect.ReturnRewrite
import net.exoad.kira.dialect.Stage3
import net.exoad.kira.py.PyTestSupport
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** kira-dialect stage3's return rewrites: each shape's text, its proof, and the programs it keeps. */
class Stage3ReturnTest {
    /** [old] (stage 2's text) converted: the new text, which must be all of stage 3's dialect. */
    private fun convert(old: String): String {
        val r = Stage3.convertText(old, "probe.kira", "test:main")
        assertNull(r.problem, r.problem)
        assertEquals(emptyList(), r.hand, "left for a hand fix")
        val text = r.text ?: old
        assertTrue(r.full, "not all stage 3:\n$text")
        return text
    }

    @Test
    fun aLastReturnIsTheValue() {
        assertEquals(
            "f: (a: Int32) Int32 {\n    b: Int32 = a + 1\n    b * 2\n}\n",
            convert("f: (a: Int32) Int32 {\n    b: Int32 = a + 1\n    return b * 2\n}\n"),
        )
        // A Void function's last bare return goes with its line.
        assertEquals("f: () Void {\n    trace(1)\n}\n", convert("f: () Void {\n    trace(1)\n    return\n}\n"))
    }

    @Test
    fun aGuardTakesTheRestAsItsElse() {
        val old = """
            f: (a: Int32) Int32 {
                if a < 0 {
                    trace("negative")
                    return 0
                }
                // the rest, one level deeper
                b: Int32 = a * 2
                return b + 1
            }
        """.trimIndent() + "\n"
        val want = """
            f: (a: Int32) Int32 {
                if a < 0 {
                    trace("negative")
                    0
                } else {
                    // the rest, one level deeper
                    b: Int32 = a * 2
                    b + 1
                }
            }
        """.trimIndent() + "\n"
        assertEquals(want, convert(old))
    }

    @Test
    fun guardsInARowAreOneElseIfChain() {
        val old = "f: (a: Int32) Str {\n    if a < 0 {\n        trace(a)\n        return \"neg\"\n    }\n    if a == 0 {\n        trace(a)\n        return \"zero\"\n    }\n    trace(a)\n    return \"pos\"\n}\n"
        val want = "f: (a: Int32) Str {\n    if a < 0 {\n        trace(a)\n        \"neg\"\n    } else if a == 0 {\n        trace(a)\n        \"zero\"\n    } else {\n        trace(a)\n        \"pos\"\n    }\n}\n"
        assertEquals(want, convert(old))
    }

    @Test
    fun oneExpressionArmsAreATernary() {
        // Guards whose arms and rest are one value each.
        assertEquals(
            "sign: (a: Int32) Int32 {\n    a < 0 ? -1 : a > 0 ? 1 : 0\n}\n",
            convert("sign: (a: Int32) Int32 {\n    if a < 0 {\n        return -1\n    }\n    if a > 0 {\n        return 1\n    }\n    return 0\n}\n"),
        )
        // A last if/else of returns, and an if value.
        assertEquals("f: (b: Bool) Str {\n    b ? \"y\" : \"n\"\n}\n", convert("f: (b: Bool) Str {\n    if b {\n        return \"y\"\n    } else {\n        return \"n\"\n    }\n}\n"))
        assertEquals(
            "f: (b: Bool) Int32 {\n    x: Int32 = b ? 1 : 2\n    x\n}\n",
            convert("f: (b: Bool) Int32 {\n    x: Int32 = if b {\n        1\n    } else {\n        2\n    }\n    return x\n}\n"),
        )
        // In an operand the ternary is parenthesized; in a first arm too.
        assertEquals(
            "f: (b: Bool, c: Bool) Int32 {\n    1 + (b ? (c ? 2 : 3) : 4)\n}\n",
            convert("f: (b: Bool, c: Bool) Int32 {\n    return 1 + (if b { if c { 2 } else { 3 } } else { 4 })\n}\n"),
        )
    }

    @Test
    fun aCommentBetweenArmsMovesToItsOwnLine() {
        val old = "f: (a: Int32) Int32 {\n    if a < 0 {\n        return 0\n    }\n    // otherwise the double\n    return a * 2\n}\n"
        assertEquals("f: (a: Int32) Int32 {\n    a < 0 ? 0\n        // otherwise the double\n        : a * 2\n}\n", convert(old))
    }

    @Test
    fun aVoidGuardNegates() {
        assertEquals(
            "f: (a: Int32) Void {\n    if a != 0 {\n        trace(a)\n    }\n}\n",
            convert("f: (a: Int32) Void {\n    if a == 0 {\n        return\n    }\n    trace(a)\n}\n"),
        )
        assertEquals(
            "f: (ok: Bool) Void {\n    if ok {\n        trace(1)\n    }\n}\n",
            convert("f: (ok: Bool) Void {\n    if !ok {\n        return\n    }\n    trace(1)\n}\n"),
        )
        assertEquals(
            "f: (a: Int32) Void {\n    if !(a < 0) {\n        trace(a)\n    }\n}\n",
            convert("f: (a: Int32) Void {\n    if a < 0 {\n        return\n    }\n    trace(a)\n}\n"),
        )
    }

    @Test
    fun aLoopsReturnIsAResultLocalAndBreak() {
        val old = "has: (xs: List<Int32>, x: Int32) Bool {\n    for y: Int32 in xs {\n        if y == x {\n            return true\n        }\n    }\n    return false\n}\n"
        val want = "has: (xs: List<Int32>, x: Int32) Bool {\n    mut result: Bool = false\n    for y: Int32 in xs {\n        if y == x {\n            result = true\n            break\n        }\n    }\n    result\n}\n"
        assertEquals(want, convert(old))
        // A Void function's is `break`.
        assertEquals(
            "f: (xs: List<Int32>) Void {\n    for y: Int32 in xs {\n        if y < 0 {\n            break\n        }\n        trace(y)\n    }\n}\n",
            convert("f: (xs: List<Int32>) Void {\n    for y: Int32 in xs {\n        if y < 0 {\n            return\n        }\n        trace(y)\n    }\n}\n"),
        )
    }

    @Test
    fun aReturnTwoLoopsDeepBreaksBothThroughAFlag() {
        val old = "first: (rows: List<List<Int32>>) Int32 {\n    for r: List<Int32> in rows {\n        for v: Int32 in r {\n            if v > 9 {\n                return v\n            }\n        }\n    }\n    return -1\n}\n"
        val want = "first: (rows: List<List<Int32>>) Int32 {\n    mut result: Int32 = -1\n    mut done: Bool = false\n    for r: List<Int32> in rows {\n        for v: Int32 in r {\n            if v > 9 {\n                result = v\n                done = true\n                break\n            }\n        }\n        if done {\n            break\n        }\n    }\n    result\n}\n"
        assertEquals(want, convert(old))
    }

    @Test
    fun aReturnNoShapeReachesSetsTheResultAndAFlag() {
        // A try's handler returns and code follows the try.
        val old = "f: (s: Str) Int32 {\n    mut n: Int32 = 0\n    try {\n        n = parse(s)\n    } on e: Str {\n        return -1\n    }\n    trace(n)\n    return n * 2\n}\n\nparse: (s: Str) Int32 {\n    return 7\n}\n"
        val want = "f: (s: Str) Int32 {\n    mut n: Int32 = 0\n    mut result: Int32 = 0\n    mut found: Bool = false\n    try {\n        n = parse(s)\n    } on e: Str {\n        result = -1\n        found = true\n    }\n    if found {\n        result\n    } else {\n        trace(n)\n        n * 2\n    }\n}\n\nparse: (s: Str) Int32 {\n    7\n}\n"
        assertEquals(want, convert(old))
    }

    @Test
    fun aLastValueOpeningWithAParenthesisEndsTheLineBefore() {
        // `(a + 1) * 2` after `trace(a)` would call trace's result: the line before ends in `;`.
        assertEquals(
            "f: (a: Int32) Int32 {\n    trace(a);\n    (a + 1) * 2\n}\n",
            convert("f: (a: Int32) Int32 {\n    trace(a)\n    return (a + 1) * 2\n}\n"),
        )
    }

    @Test
    fun theProofRefusesATextThatReadsOtherwise() {
        val old = "f: (a: Int32) Int32 {\n    if a < 0 {\n        trace(a)\n        return 0\n    }\n    return a\n}\n"
        val (ctx, _) = ProjectUnits.read(CompilationUnit(bootstrap = false), "p.kira", old, "test:main", literals = true)
        val rw = ReturnRewrite(old, assertNotNull(ctx))
        rw.collect()
        val right = rw.apply()
        val read = { t: String -> assertNotNull(ProjectUnits.read(CompilationUnit(bootstrap = false), "p.kira", t, "test:main", literals = false, returns = true).first, t) }
        assertNull(ReturnProof(ctx, read(right), rw).compare(), right)
        // The rest left after the if instead of in its else: the guard's tree is not the one written.
        val wrong = right.replace("    } else {\n        a\n    }\n", "    }\n    a\n")
        assertTrue(wrong != right)
        assertNotNull(ReturnProof(ctx, read(wrong), rw).compare())
    }

    @Test
    fun aConvertedProgramPrintsWhatTheOldOneDid() {
        assumeTrue(PyTestSupport.python != null, "no Python on PATH (set KIRA_PYTHON)")
        val old = """
            sign: (a: Int32) Int32 {
                if a < 0 {
                    return -1
                }
                if a > 0 {
                    return 1
                }
                return 0
            }

            firstOver: (xs: List<Int32>, n: Int32) Int32 {
                for x: Int32 in xs {
                    if x > n {
                        return x
                    }
                }
                return -1
            }

            deep: (rows: List<List<Int32>>) Int32 {
                mut seen: Int32 = 0
                for r: List<Int32> in rows {
                    for v: Int32 in r {
                        seen += 1
                        if v == 5 {
                            return seen
                        }
                    }
                }
                return -seen
            }

            label: (a: Int32) Str {
                if a == 0 {
                    trace("zero")
                    return "z"
                }
                b: Int32 = a * 2
                if b > 10 {
                    return "big"
                }
                return "small ${'$'}{b}"
            }

            say: (m: Maybe<Int32>) Void {
                if m.isNone() {
                    return
                }
                trace(m.value)
            }

            main: () Void {
                trace(sign(-4))
                trace(sign(0))
                trace(sign(9))
                trace(firstOver(@listOf(1, 5, 9, 12), 6))
                trace(firstOver(@listOf(1, 2), 6))
                trace(deep(@listOf(@listOf(1, 2), @listOf(3, 5, 7))))
                trace(deep(@listOf(@listOf(1))))
                trace(label(0))
                trace(label(3))
                trace(label(8))
                say(null)
                say(4)
            }
        """.trimIndent() + "\n"
        val new = convert(old)
        assertTrue(!new.contains("return"), new)
        val python = PyTestSupport.python!!
        // The old text compiles with stage 3's refusals off; the new one as the compiler stands.
        val outs = listOf(old to true, new to false).map { (text, isOld) ->
            val py = if (isOld) DialectGates.stage3(false) { PyTestSupport.emit(text).python() } else PyTestSupport.emit(text).python()
            val file = Files.createTempFile("stage3-run", ".py").toFile()
            try {
                file.writeText(py)
                PyTestSupport.run(listOf(python, file.absolutePath), file.parentFile).also { assertEquals(0, it.exitCode, it.all) }.stdout
            } finally {
                file.delete()
            }
        }
        assertEquals("-1\n0\n1\n9\n-1\n4\n-1\nzero\nz\nsmall 6\nbig\n4\n", outs[0])
        assertEquals(outs[0], outs[1])
    }

    @Test
    fun aFileConvertsToItselfOnceConverted() {
        val dir = Files.createTempDirectory("stage3-returns").toFile()
        try {
            val file = File(dir, "main.kira")
            file.writeText("f: (a: Int32) Int32 {\n    if a < 0 {\n        return 0\n    }\n    return a\n}\n")
            assertEquals(1, Stage3.convertAll(listOf(dir.path), check = true), "--check sees the change")
            assertEquals(0, Stage3.convertAll(listOf(dir.path), check = false))
            assertEquals("f: (a: Int32) Int32 {\n    a < 0 ? 0 : a\n}\n", file.readText())
            assertEquals(0, Stage3.convertAll(listOf(dir.path), check = true), "a converted file converts to itself")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aValueFunctionThatCanEndWithNoValueKeepsItsReturns() {
        // A for may run no time: its result's literal start would hide the typer's "no value on every path".
        val forLoop = Stage3.convertText("first: (xs: List<Int32>) Int32 {\n    for x: Int32 in xs {\n        return x\n    }\n}\n", "p.kira", "test:main")
        assertTrue(forLoop.hand.single().second.contains("can end with no value"), forLoop.hand.toString())
        // `while true` with no break of its own ends only by its return: that one converts.
        val endless = convert("next: (n: Int32) Int32 {\n    mut i: Int32 = n\n    while true {\n        i += 1\n        if i > 9 {\n            return i\n        }\n    }\n}\n")
        assertTrue(endless.contains("result = i\n") && endless.endsWith("    }\n    result\n}\n"), endless)
    }

    @Test
    fun aFileWhoseErrorsWouldChangeIsNotConverted() {
        val dir = Files.createTempDirectory("stage3-typed").toFile()
        try {
            // The else arm's last call would become the result: the old file's missing value turns into another error.
            val text = "f: (b: Bool) Int32 {\n    if b {\n        return 1\n    } else {\n        trace(1)\n    }\n}\n"
            val file = File(dir, "main.kira").apply { writeText(text) }
            assertEquals(1, Stage3.convertAll(listOf(dir.path), check = false))
            assertEquals(text, file.readText(), "left as it was")
        } finally {
            dir.deleteRecursively()
        }
    }
}
