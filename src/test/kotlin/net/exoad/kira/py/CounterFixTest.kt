package net.exoad.kira.py

import net.exoad.kira.compiler.fix.CounterFix
import net.exoad.kira.compiler.fix.CounterFix.Reason
import net.exoad.kira.types.TyperTestSupport
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `kira --fix-counters`: a hand-written counter whose loop means the same over a range becomes `while i: T in a..<n { }`, and
 * every other counter loop is left and listed with why. Each accepted shape is rewritten and runs as it ran; each reason a loop
 * is left has its own case, so a fixer that drops one of its checks fails here.
 */
class CounterFixTest {
    private fun fix(body: String): CounterFix.Outcome = CounterFix.fixSnippet(body.trimIndent() + "\n")

    /** [body] has one counter loop and it is rewritten: the new text. */
    private fun rewritten(body: String): String {
        val o = fix(body)
        assertNull(o.refused)
        assertEquals(emptyList(), o.left.map { it.toString() })
        assertEquals(1, o.fixed)
        return o.text!!
    }

    /** [body] has one counter loop and it is left for [reason]: nothing is rewritten. */
    private fun left(body: String, reason: Reason): CounterFix.Left {
        val o = fix(body)
        assertNull(o.refused)
        assertEquals(0, o.fixed, o.text)
        assertEquals(body.trimIndent() + "\n", o.text, "a loop that is left keeps its text")
        assertEquals(listOf(reason), o.left.map { it.reason }, o.left.joinToString("\n"))
        return o.left.single()
    }

    /** [kira]'s stdout on py. */
    private fun runPy(kira: String): String {
        assumeTrue(PyTestSupport.python != null, "no Python on PATH (set KIRA_PYTHON)")
        val file = Files.createTempFile("counterfix-run", ".py").toFile()
        try {
            file.writeText(PyTestSupport.emit(kira).python())
            val r = PyTestSupport.run(listOf(PyTestSupport.python!!, file.absolutePath), file.parentFile)
            assertEquals(0, r.exitCode, r.all)
            return r.stdout.replace("\r", "")
        } finally {
            file.delete()
        }
    }

    /** A function whose one loop is `while i < xs.size()` over [xs] as [before] declares it, with [body] in the loop; [top] stands before it. */
    private fun over(before: String, body: String, top: String = "", params: String = ""): String =
        "${top}pub run: ($params) Void {\n$before    i: Mutable<Size> { 0 }\n    while i < xs.size() {\n        $body\n        i += 1\n    }\n}\n"

    private val freshList = "    xs: Mutable<List<Int32>> { @listOf(1, 2) }\n"

    /** `log` reaches past what it is given (a global list), so nothing is known of what a call of it changes. */
    private val logTop = "LOG: Mutable<List<Str>> { }\n\nlog: (v: Int32) Void {\n    LOG.add(\"x\")\n    trace(v)\n}\n\n"

    // ---- the shapes it rewrites -------------------------------------------------------------------

    @Test
    fun aCounterBelowALiteralBecomesTheHalfOpenRange() {
        val text = rewritten(
            """
            pub main: () Void {
                i: Mutable<Int32> { 0 }
                while i < 4 {
                    trace(i)
                    i += 1
                }
            }
            """,
        )
        assertEquals("pub main: () Void {\n    while i: Int32 in 0..<4 {\n        trace(i)\n    }\n}\n", text)
    }

    @Test
    fun aCounterUpToAndWithItsEndBecomesTheRangeThatHoldsIt() {
        val text = rewritten(
            """
            pub main: () Void {
                n: Int64 { 3 }
                k: Mutable<Int64> { 1 }
                while k <= n {
                    trace(k)
                    k += 1
                }
            }
            """,
        )
        assertTrue(text.contains("    n: Int64 { 3 }\n    while k: Int64 in 1..n {\n        trace(k)\n    }\n"), text)
    }

    @Test
    fun theStepMayBeWrittenAsAnAssignmentEitherWayRound() {
        for (step in listOf("i = i + 1", "i = 1 + i")) {
            val text = rewritten("pub main: () Void {\n    i: Mutable<Size> { 2 }\n    while i < 5 {\n        trace(i)\n        $step\n    }\n}\n")
            assertEquals("pub main: () Void {\n    while i: Size in 2..<5 {\n        trace(i)\n    }\n}\n", text)
        }
    }

    @Test
    fun aCounterBelowTheSizeOfALocalListWhoseElementsTheBodyReadsAndWrites() {
        val text = rewritten(
            """
            pub main: () Void {
                xs: Mutable<List<Int32>> { @listOf(1, 2, 3) }
                i: Mutable<Size> { 0 }
                while i < xs.size() {
                    xs[i] = xs[i] * 2
                    trace(xs[i])
                    i += 1
                }
            }
            """,
        )
        assertTrue(text.contains("    while i: Size in 0..<xs.size() {\n        xs[i] = xs[i] * 2\n        trace(xs[i])\n    }\n"), text)
    }

    @Test
    fun aCounterBelowTheLengthOfAStr() {
        val text = rewritten(
            """
            pub main: () Void {
                s: Str { "abc" }
                n: Mutable<Int32> { 0 }
                i: Mutable<Size> { 0 }
                while i < s.length() {
                    n += 1
                    i += 1
                }
                trace(n)
            }
            """,
        )
        assertTrue(text.contains("    while i: Size in 0..<s.length() {\n        n += 1\n    }\n"), text)
    }

    @Test
    fun aStartOrAnEndThatIsNotOneOperandIsParenthesised() {
        val text = rewritten(
            """
            pub main: () Void {
                a: Int32 { 1 }
                n: Int32 { 6 }
                i: Mutable<Int32> { a + 1 }
                while i < n - 1 {
                    trace(i)
                    i += 1
                }
            }
            """,
        )
        assertTrue(text.contains("    while i: Int32 in (a + 1)..<(n - 1) {\n"), text)
    }

    @Test
    fun aMutableNumberTheBodyOnlyReadsIsAnEnd() {
        val text = rewritten(
            """
            pub main: () Void {
                n: Mutable<Int32> { 2 }
                n = n + 1
                t: Mutable<Int32> { 0 }
                i: Mutable<Int32> { 0 }
                while i < n {
                    t += n * i
                    i += 1
                }
                trace(t)
            }
            """,
        )
        assertTrue(text.contains("    while i: Int32 in 0..<n {\n        t += n * i\n    }\n"), text)
    }

    @Test
    fun aParametersListIsAnEndWhereTheBodyCallsNothingOfTheProgramsOwn() {
        val text = rewritten(
            """
            sum: (xs: List<Int32>) Int32 {
                t: Mutable<Int32> { 0 }
                i: Mutable<Size> { 0 }
                while i < xs.size() {
                    t += xs[i]
                    trace(xs[i])
                    i += 1
                }
                t
            }

            pub main: () Void {
                trace(sum(@listOf(1, 2, 3)))
            }
            """,
        )
        assertTrue(text.contains("    while i: Size in 0..<xs.size() {\n        t += xs[i]\n        trace(xs[i])\n    }\n    t\n"), text)
    }

    @Test
    fun aListThisFunctionAloneHoldsIsAnEndWhateverTheBodyCalls() {
        // Made here and never handed on: no other name reaches it, so a call of the body cannot change its size.
        assertEquals(1, fix(over(freshList, "log(xs[i])", logTop)).fixed)
        assertEquals(1, fix(over(freshList + "    xs.add(3)\n    xs = @listOf(7)\n", "log(xs[i])", logTop)).fixed)
    }

    @Test
    fun nestedCountersAreBothRewritten() {
        val o = fix(
            """
            pub main: () Void {
                i: Mutable<Int32> { 0 }
                while i < 2 {
                    j: Mutable<Int32> { 0 }
                    while j < 3 {
                        trace(i * 10 + j)
                        j += 1
                    }
                    i += 1
                }
            }
            """,
        )
        assertEquals(2, o.fixed, o.left.joinToString("\n"))
        assertEquals(
            "pub main: () Void {\n    while i: Int32 in 0..<2 {\n        while j: Int32 in 0..<3 {\n            trace(i * 10 + j)\n        }\n    }\n}\n",
            o.text,
        )
    }

    @Test
    fun commentsTabsAndCrlfStayAndTheBlankLineAboveTheStepGoesWithIt() {
        val src = "pub main: () Void {\n\t// every index\n\ti: Mutable<Int32> { 0 }\n\t// below four\n\twhile i < 4 { // head\n\t\ttrace(i) // body\n\n\t\ti += 1\n\t} // end\n}\n"
            .replace("\n", "\r\n")
        val o = CounterFix.fixSnippet(src)
        assertEquals(1, o.fixed, o.left.joinToString("\n"))
        assertEquals(
            "pub main: () Void {\n\t// every index\n\t// below four\n\twhile i: Int32 in 0..<4 { // head\n\t\ttrace(i) // body\n\t} // end\n}\n".replace("\n", "\r\n"),
            o.text,
        )
    }

    private val shapes = """
        sum: (xs: List<Int32>) Int32 {
            t: Mutable<Int32> { 0 }
            i: Mutable<Size> { 0 }
            while i < xs.size() {
                t += xs[i]
                i += 1
            }
            t
        }

        pub main: () Void {
            i: Mutable<Int32> { 0 }
            while i < 4 {
                trace(i)
                i += 1
            }
            n: Int64 { 3 }
            k: Mutable<Int64> { 1 }
            while k <= n {
                trace(k)
                k = k + 1
            }
            xs: Mutable<List<Int32>> { @listOf(1, 2, 3) }
            at: Mutable<Size> { 0 }
            while at < xs.size() {
                xs[at] = xs[at] * 2
                at += 1
            }
            trace(sum(xs))
            a: Int32 { 1 }
            m: Mutable<Int32> { 6 }
            j: Mutable<Int32> { a + 1 }
            while j < m - 1 {
                trace(j * m)
                j += 1
            }
            // An empty range and a backwards one run no step.
            e: Mutable<Int32> { 5 }
            while e < 5 {
                trace("never")
                e += 1
            }
            b: Mutable<Int32> { 5 }
            while b <= 2 {
                trace("never")
                b += 1
            }
            u: Mutable<UInt8> { 250 }
            while u < 255 {
                trace(u)
                u += 1
            }
            p: Mutable<Int32> { 0 }
            while p < 2 {
                q: Mutable<Int32> { 0 }
                while q < 2 {
                    trace(p * 10 + q)
                    q += 1
                }
                p += 1
            }
        }
    """.trimIndent() + "\n"

    @Test
    fun theRewrittenProgramPrintsWhatItPrintedAndASecondRunChangesNothing() {
        val o = CounterFix.fixSnippet(shapes)
        assertNull(o.refused)
        assertEquals(emptyList(), o.left.map { it.toString() })
        assertEquals(10, o.fixed)
        val text = o.text!!
        assertFalse(text.contains("Mutable<Size>") || Regex("while \\w+ <").containsMatchIn(text), text)
        TyperTestSupport.expectNoErrors(TyperTestSupport.snippet(text))
        val before = runPy(shapes)
        assertTrue(before.lines().size >= 20, before)
        assertEquals(before, runPy(text))
        val again = CounterFix.fixSnippet(text)
        assertEquals(0, again.fixed)
        assertEquals(emptyList(), again.left.map { it.toString() })
        assertEquals(text, again.text)
    }

    // ---- the loops it leaves, each with its reason ------------------------------------------------

    @Test
    fun aConditionThatIsMoreThanTheComparisonIsLeft() {
        left(
            """
            pub main: () Void {
                ok: Mutable<Bool> { true }
                i: Mutable<Int32> { 0 }
                while i < 4 && ok {
                    ok = i < 2
                    i += 1
                }
            }
            """,
            Reason.CONDITION,
        )
    }

    @Test
    fun aStepThatIsNotOneIsLeft() {
        for (step in listOf("i += 2", "i = i + 2", "i -= 1", "i *= 2", "i = 1")) {
            left("pub main: () Void {\n    i: Mutable<Int32> { 1 }\n    while i < 4 {\n        trace(i)\n        $step\n    }\n}\n", Reason.STEP_NOT_ONE)
        }
    }

    @Test
    fun aBodyThatDoesNotEndWithTheStepIsLeft() {
        left(
            """
            pub main: () Void {
                i: Mutable<Int32> { 0 }
                while i < 4 {
                    i += 1
                    trace(i)
                }
            }
            """,
            Reason.NO_STEP,
        )
    }

    @Test
    fun aCounterNotDeclaredDirectlyBeforeTheLoopIsLeft() {
        val between = left(
            """
            pub main: () Void {
                i: Mutable<Int32> { 0 }
                trace("go")
                while i < 4 {
                    trace(i)
                    i += 1
                }
            }
            """,
            Reason.NOT_DECLARED_BEFORE,
        )
        assertEquals(4, between.line)
    }

    @Test
    fun aCounterTheBodyWritesIsLeft() {
        for (write in listOf("if i == 1 {\n            i += 1\n        }", "if i == 1 {\n            i = 3\n        }")) {
            left("pub main: () Void {\n    i: Mutable<Int32> { 0 }\n    while i < 4 {\n        $write\n        i += 1\n    }\n}\n", Reason.COUNTER_WRITTEN)
        }
    }

    @Test
    fun aCounterAClosureCapturesIsLeft() {
        left(
            """
            apply: (f: Fx<T1<Int32>, Int32>, x: Int32) Int32 {
                f(x)
            }

            pub main: () Void {
                i: Mutable<Int32> { 0 }
                while i < 4 {
                    trace(apply((v: Int32) Int32 {
                        v + i
                    }, 1))
                    i += 1
                }
            }
            """,
            Reason.COUNTER_CAPTURED,
        )
    }

    @Test
    fun aCounterReadAfterTheLoopIsLeft() {
        left(
            """
            pub main: () Void {
                i: Mutable<Int32> { 0 }
                while i < 4 {
                    i += 1
                }
                trace(i)
            }
            """,
            Reason.COUNTER_AFTER,
        )
    }

    @Test
    fun aContinueOfTheLoopIsLeftAndOneOfAnInnerLoopIsNot() {
        left("pub main: () Void {\n    i: Mutable<Int32> { 0 }\n    while i < 4 {\n        if i == 5 {\n            continue\n        }\n        i += 1\n    }\n}\n", Reason.CONTINUE)
        val text = rewritten(
            """
            pub main: () Void {
                i: Mutable<Int32> { 0 }
                while i < 3 {
                    while x: Int32 in 0..<5 {
                        if x == 1 {
                            continue
                        }
                        if x == 3 {
                            break
                        }
                        trace(x + i)
                    }
                    i += 1
                }
            }
            """,
        )
        assertTrue(text.contains("    while i: Int32 in 0..<3 {\n        while x: Int32 in 0..<5 {\n"), text)
    }

    @Test
    fun anEndThatIsMoreThanNumbersNamesAndSizesIsLeft() {
        val top = "limit: () Int32 {\n    trace(\"asked\")\n    3\n}\n\nhalf: (n: Int32) Int32 {\n    n / 2\n}\n\nBox: Mutable<Any> {\n    pub n: Mutable<Int32> { 3 }\n}\n\n"
        // A call with an effect, a call of the program's own, an element, a field of another value, and a float.
        for (end in listOf("limit()", "half(8)", "xs[0]", "box.n", "(f as Int32)")) {
            left(
                "${top}pub main: () Void {\n    xs: List<Int32> { @listOf(3) }\n    box: Box { }\n    f: Float64 { 2.5 }\n    i: Mutable<Int32> { 0 }\n    while i < $end {\n        i += 1\n    }\n}\n",
                Reason.BOUND_SHAPE,
            )
        }
    }

    @Test
    fun anEndTheBodyAssignsIsLeft() {
        for (write in listOf("n += 1", "n = n - 1", "if i == 0 {\n            n = 2\n        }")) {
            val l = left(
                "pub main: () Void {\n    n: Mutable<Int32> { 3 }\n    i: Mutable<Int32> { 0 }\n    while i < n {\n        $write\n        i += 1\n    }\n}\n",
                Reason.BOUND_WRITTEN,
            )
            assertTrue(l.message.contains("`n`, which is assigned"), l.message)
        }
        // A part of the end, a Str whose length it reads, and the counter itself in it.
        left("pub main: () Void {\n    n: Mutable<Int32> { 3 }\n    i: Mutable<Int32> { 0 }\n    while i < 10 - n {\n        n += 1\n        i += 1\n    }\n}\n", Reason.BOUND_WRITTEN)
        left("pub main: () Void {\n    s: Mutable<Str> { \"ab\" }\n    i: Mutable<Size> { 0 }\n    while i < s.length() {\n        s = s + \"c\"\n        i += 1\n    }\n}\n", Reason.BOUND_WRITTEN)
        left("pub main: () Void {\n    i: Mutable<Int32> { 0 }\n    while i < 10 - i {\n        trace(i)\n        i += 1\n    }\n}\n", Reason.BOUND_WRITTEN)
    }

    @Test
    fun aSizeTheBodyMayChangeByItsOwnNameIsLeft() {
        val top = "grow: (xs: List<Int32>) Void {\n    xs.add(1)\n}\n\ncount: (xs: List<Int32>) Size {\n    xs.size()\n}\n\n" +
            "sneak: (xs: List<Int32>) Size {\n    mine: List<Int32> { xs }\n    if mine.size() > 9 {\n        mine.add(1)\n    }\n    mine.size()\n}\n\n" +
            "HOLD: Mutable<List<Int32>> { }\n\nstash: (xs: List<Int32>) Size {\n    HOLD = xs\n    0\n}\n\n"
        val more = freshList + "    ys: Mutable<List<Int32>> { }\n    n: Mutable<Size> { 0 }\n"
        // A call on the list that changes it, the list assigned, the list given to a function of the program's own that
        // resizes it (by its name, and through a local of its own) or that reaches past what it is given (it may keep the
        // list), and the list held by another name.
        val writes = listOf(
            "if i > 5 {\n            xs.add(0)\n        }",
            "if i > 5 {\n            xs.clear()\n        }",
            "if i > 5 {\n            xs = ys\n        }",
            "if i > 5 {\n            grow(xs)\n        }",
            "n = sneak(xs)",
            "n = stash(xs)",
            "ys = xs",
        )
        for (write in writes) {
            left(over(more, write, top), Reason.BOUND_WRITTEN)
        }
        // Another list changed and this one only read, or given to a function that only reads it: its size is an end.
        assertEquals(1, fix(over(more, "ys.add(xs[i])", top)).fixed)
        assertEquals(1, fix(over(more, "n = count(xs)", top)).fixed)
    }

    @Test
    fun anElementWrittenIsLeftWhereThatMayAddOne() {
        left(
            """
            pub main: () Void {
                m: Mutable<Map<Int32, Int32>> { }
                m[100] = 1
                i: Mutable<Size> { 0 }
                while i < m.size() {
                    m[i as Int32] = 0
                    i += 1
                }
            }
            """,
            Reason.BOUND_WRITTEN,
        )
    }

    @Test
    fun aSizeAnotherNameMayChangeIsLeft() {
        val top = logTop + "made: () List<Int32> {\n    @listOf(1, 2)\n}\n\n"
        // A parameter's list is the caller's too: a function that reaches past what it is given may reach it.
        left(over("", "log(xs[i])", top, "xs: List<Int32>"), Reason.BOUND_SHARED)
        // So is a list a call of the program's own answered, and a list another name was given.
        left(over("    xs: List<Int32> { made() }\n", "log(xs[i])", top), Reason.BOUND_SHARED)
        left(over(freshList + "    ys: List<Int32> { xs }\n", "log(xs[i])", top), Reason.BOUND_SHARED)
        // A stdlib call that changes a list of the same type may change this one: another parameter, and the other name of this one.
        left(over("", "if i > 5 {\n            ys.add(1)\n        }", top, "xs: List<Int32>, ys: List<Int32>"), Reason.BOUND_SHARED)
        val shared = left(over(freshList + "    ys: List<Int32> { xs }\n", "if ys.size() < 6 {\n            ys.add(1)\n        }", top), Reason.BOUND_SHARED)
        assertTrue(shared.message.contains("calls `add` on a value that may be the same"), shared.message)
        // One of another type, and one this function alone holds, cannot be it.
        assertEquals(1, fix(over("", "names.add(\"a\")", top, "xs: List<Int32>, names: List<Str>")).fixed)
        assertEquals(1, fix(over("    out: Mutable<List<Int32>> { }\n", "out.add(xs[i])", top, "xs: List<Int32>")).fixed)
        // A function the typer finds free of effects (it writes an element of a local of its own), which grows a global Map
        // through that local: five turns, where a range would run two.
        val quiet = "SHARED: Mutable<Map<Int32, Int32>> { }\n\nquiet: (k: Int32) Int32 {\n    mine: Map<Int32, Int32> { SHARED }\n    if mine.size() < 5 {\n        mine[k + 10] = 1\n    }\n    0\n}\n\n" +
            "pub main: () Void {\n    SHARED[0] = 0\n    SHARED[1] = 1\n    t: Mutable<Int32> { 0 }\n    i: Mutable<Size> { 0 }\n    while i < SHARED.size() {\n        trace(i)\n        t += quiet(i as Int32)\n        i += 1\n    }\n}\n"
        assertEquals("0\n1\n2\n3\n4\n", runPy(quiet))
        left(quiet, Reason.BOUND_SHARED)
        // A list assigned what a call answered is no longer this function's alone.
        left(over(freshList + "    xs = made()\n", "log(xs[i])", top), Reason.BOUND_SHARED)
        // An element written on a Map of the same type may add one to this Map.
        left(
            "pub run: (m: Map<Int32, Int32>, o: Map<Int32, Int32>) Void {\n    i: Mutable<Size> { 0 }\n    while i < m.size() {\n        if i > 5 {\n            o[1] = 1\n        }\n        i += 1\n    }\n}\n",
            Reason.BOUND_SHARED,
        )
        // Making a value whose making runs a block of its own, which changes the list through a name of its own.
        val made = left(
            "LOG: Mutable<List<Int32>> { }\n\nTick: Mutable<Any> {\n    pub n: Mutable<Int32> { 0 }\n\n    initially {\n        mine: List<Int32> { LOG }\n        mine.add(1)\n    }\n}\n\n" +
                "pub main: () Void {\n    i: Mutable<Size> { 0 }\n    while i < LOG.size() {\n        if i > 5 {\n            tk: Tick { }\n            trace(tk.n)\n        }\n        i += 1\n    }\n}\n",
            Reason.BOUND_SHARED,
        )
        assertTrue(made.message.contains("makes a `Tick`, whose making runs code"), made.message)
        // A list given to a closure is no longer this function's alone.
        left(
            over(freshList + "    f: Fx<T0, Void> { () Void {\n        xs.add(9)\n    } }\n", "if i > 5 {\n            f()\n        }", top),
            Reason.BOUND_SHARED,
        )
    }

    // ---- a break ----------------------------------------------------------------------------------

    @Test
    fun aBreakStaysWhereTheCounterIsNotUsedAfter() {
        val src = "pub main: () Void {\n    i: Mutable<Int32> { 0 }\n    while i < 9 {\n        if i * i > 10 {\n            break\n        }\n        trace(i)\n        i += 1\n    }\n    trace(\"done\")\n}\n"
        val o = CounterFix.fixSnippet(src)
        assertEquals(1, o.fixed, o.left.joinToString("\n"))
        assertEquals(src.replace("    i: Mutable<Int32> { 0 }\n    while i < 9 {", "    while i: Int32 in 0..<9 {").replace("        trace(i)\n        i += 1\n", "        trace(i)\n"), o.text)
        assertEquals("0\n1\n2\n3\ndone\n", runPy(src))
        assertEquals(runPy(src), runPy(o.text!!))
        // Where the counter is read after, the break left it short of the end: the loop stays.
        val after = src.replace("trace(\"done\")", "trace(i)")
        assertEquals("0\n1\n2\n3\n4\n", runPy(after))
        val l = left(after, Reason.COUNTER_AFTER)
        assertEquals(listOf(Reason.COUNTER_AFTER, Reason.BREAK), l.all)
    }

    // ---- a counter set again: a chain -------------------------------------------------------------

    private fun chain(second: String, between: String = ""): String =
        "pub main: () Void {\n    n: Int32 { 4 }\n    i: Mutable<Int32> { 0 }\n    while i < n {\n        if i == 2 {\n            break\n        }\n        trace(i)\n        i += 1\n    }\n$between" +
            "    if n > 3 {\n        $second\n        while i < 5 {\n            trace(i * 10)\n            i += 1\n        }\n    }\n    i = n - 1\n    while i <= n {\n        trace(i * 100)\n        i = i + 1\n    }\n}\n"

    @Test
    fun aCounterSetAgainForLaterLoopsBecomesARangeEach() {
        val src = chain("i = 3")
        val o = CounterFix.fixSnippet(src)
        assertEquals(3, o.fixed, o.left.joinToString("\n"))
        assertEquals(
            "pub main: () Void {\n    n: Int32 { 4 }\n    while i: Int32 in 0..<n {\n        if i == 2 {\n            break\n        }\n        trace(i)\n    }\n" +
                "    if n > 3 {\n        while i: Int32 in 3..<5 {\n            trace(i * 10)\n        }\n    }\n    while i: Int32 in (n - 1)..n {\n        trace(i * 100)\n    }\n}\n",
            o.text,
        )
        assertEquals("0\n1\n30\n40\n300\n400\n", runPy(src))
        assertEquals(runPy(src), runPy(o.text!!))
        val again = CounterFix.fixSnippet(o.text)
        assertEquals(0, again.fixed)
        assertEquals(o.text, again.text)
    }

    @Test
    fun aChainIsLeftWholeWhereTheCounterIsReadBetweenItsLoopsOrOneLoopStays() {
        // Set again from itself: the second loop starts where the first stopped (after its break, at 2), which no range of its own says.
        val carried = chain("i = i + 1")
        assertEquals("0\n1\n30\n40\n300\n400\n", runPy(carried))
        assertEquals(listOf(Reason.COUNTER_AFTER, Reason.NOT_DECLARED_BEFORE, Reason.COUNTER_AFTER), fix(carried).left.map { it.reason })
        assertEquals(0, fix(carried).fixed)
        // Read between two loops.
        val read = fix(chain("i = 3", "    trace(i)\n"))
        assertEquals(0, read.fixed)
        assertEquals(listOf(Reason.COUNTER_AFTER, Reason.COUNTER_AFTER, Reason.COUNTER_AFTER), read.left.map { it.reason })
        // One loop that stays (its step is two) keeps the declaration, so the others stay too.
        val one = fix(chain("i = 3").replace("            trace(i * 10)\n            i += 1\n", "            trace(i * 10)\n            i += 2\n"))
        assertEquals(0, one.fixed)
        assertEquals(listOf(Reason.CHAIN, Reason.STEP_NOT_ONE, Reason.CHAIN), one.left.map { it.reason })
        // No loop directly after the declaration.
        val late = fix("pub main: () Void {\n    i: Mutable<Int32> { 0 }\n    trace(\"go\")\n    i = 1\n    while i < 3 {\n        trace(i)\n        i += 1\n    }\n}\n")
        assertEquals(listOf(Reason.CHAIN), late.left.map { it.reason })
        // A comment on the line that sets it again.
        val noted = fix(chain("i = 3 // from three"))
        assertEquals(0, noted.fixed)
        assertEquals(listOf(Reason.CHAIN, Reason.LAYOUT, Reason.CHAIN), noted.left.map { it.reason })
    }

    // ---- a stdlib result that is a new value ------------------------------------------------------

    @Test
    fun aStdlibResultThatIsANewValueIsThisFunctionsAlone() {
        // A split and a Map's keys are made by the call: whatever the body calls cannot reach them.
        val split = "${logTop}pub run: (line: Str) Void {\n    xs: List<Str> { line.split(\" \") }\n    i: Mutable<Size> { 0 }\n    while i < xs.size() {\n        log(xs[i].length() as Int32)\n        i += 1\n    }\n}\n"
        assertEquals(1, CounterFix.fixSnippet(split).fixed, CounterFix.fixSnippet(split).left.joinToString("\n"))
        val keys = "${logTop}pub run: (m: Map<Int32, Int32>) Void {\n    xs: Arr<Int32> { m.keys() }\n    i: Mutable<Size> { 0 }\n    while i < xs.size() {\n        log(xs[i])\n        i += 1\n    }\n}\n"
        assertEquals(1, CounterFix.fixSnippet(keys).fixed, CounterFix.fixSnippet(keys).left.joinToString("\n"))
        // So is an empty Json made here and filled by its own name.
        val json = "${logTop}pub run: () Void {\n    xs: Json { Json.arr() }\n    xs.add(Json.of(\"a\"))\n    i: Mutable<Size> { 0 }\n    while i < xs.size() {\n        log(i as Int32)\n        i += 1\n    }\n}\n"
        assertEquals(1, CounterFix.fixSnippet(json).fixed, CounterFix.fixSnippet(json).left.joinToString("\n"))
        val src = "${logTop}pub main: () Void {\n    xs: List<Str> { \"a b c\".split(\" \") }\n    i: Mutable<Size> { 0 }\n    while i < xs.size() {\n        log(i as Int32)\n        trace(xs[i])\n        i += 1\n    }\n}\n"
        val o = CounterFix.fixSnippet(src)
        assertEquals(1, o.fixed, o.left.joinToString("\n"))
        assertEquals("0\na\n1\nb\n2\nc\n", runPy(src))
        assertEquals(runPy(src), runPy(o.text!!))
    }

    @Test
    fun aStdlibResultThatIsAPartOfItsReceiverIsShared() {
        // Json.get answers the child itself: the document's other names grow it, and the counter sees five turns where a range would see two.
        val src = "pub main: () Void {\n    doc: Json { Json.obj() }\n    doc.put(\"c\", Json.arr())\n    doc.get(\"c\").add(Json.of(\"a\"))\n    doc.get(\"c\").add(Json.of(\"b\"))\n" +
            "    cs: Json { doc.get(\"c\") }\n    i: Mutable<Size> { 0 }\n    while i < cs.size() {\n        trace(i)\n        if doc.get(\"c\").size() < 5 {\n            doc.get(\"c\").add(Json.of(\"z\"))\n        }\n        i += 1\n    }\n}\n"
        assertEquals("0\n1\n2\n3\n4\n", runPy(src))
        val o = CounterFix.fixSnippet(src)
        assertEquals(0, o.fixed)
        assertEquals(listOf(Reason.BOUND_SHARED), o.left.map { it.reason })
    }

    // ---- a function of the program's own that stays within what it is given -------------------------

    private val ownTop = "twice: (v: Int32) Int32 {\n    v * 2\n}\n\nnote: (v: Int32) Void {\n    trace(v)\n}\n\ntotal: (xs: List<Int32>) Int32 {\n    t: Mutable<Int32> { 0 }\n    while x: Int32 in xs {\n        t += x\n    }\n    t\n}\n\n" +
        "push: (names: List<Str>, s: Str) Void {\n    names.add(s)\n}\n\n" +
        "grow: (ys: List<Int32>) Void {\n    mine: List<Int32> { ys }\n    if mine.size() < 5 {\n        mine.add(1)\n    }\n}\n\nouter: (zs: List<Int32>) Void {\n    grow(zs)\n}\n\n" +
        "Box: Mutable<Any> {\n    pub items: Mutable<List<Int32>> { }\n}\n\nfill: (b: Box) Void {\n    if b.items.size() < 5 {\n        b.items.add(1)\n    }\n}\n\n" +
        "viaBox: (ys: List<Int32>) Void {\n    box: Box { }\n    box.items = ys\n    if box.items.size() < 5 {\n        box.items.add(1)\n    }\n}\n\n"

    @Test
    fun aCallThatCannotReachTheEndsCollectionDoesNotBlock() {
        // A parameter's list (the caller's too): the calls take numbers, read the list, print, or resize a list of another type.
        val src = "${ownTop}walk: (xs: List<Int32>, names: List<Str>) Int32 {\n    t: Mutable<Int32> { 0 }\n    i: Mutable<Size> { 0 }\n    while i < xs.size() {\n        t += twice(xs[i]) + total(xs)\n        note(t)\n        push(names, \"n\")\n        i += 1\n    }\n    t\n}\n\n" +
            "pub main: () Void {\n    names: List<Str> { @listOf(\"z\") }\n    trace(walk(@listOf(1, 2, 3), names))\n    trace(names.size())\n}\n"
        val o = CounterFix.fixSnippet(src)
        assertEquals(1, o.fixed, o.left.joinToString("\n"))
        assertTrue(o.text!!.contains("    while i: Size in 0..<xs.size() {\n        t += twice(xs[i]) + total(xs)\n        note(t)\n        push(names, \"n\")\n    }\n"), o.text)
        assertEquals("8\n18\n30\n30\n4\n", runPy(src))
        assertEquals(runPy(src), runPy(o.text))
    }

    private fun shared(call: String, before: String = "    ys: List<Int32> { xs }\n"): String =
        "${ownTop}pub main: () Void {\n    xs: Mutable<List<Int32>> { @listOf(1, 2) }\n$before    i: Mutable<Size> { 0 }\n    while i < xs.size() {\n        trace(i)\n        $call\n        i += 1\n    }\n}\n"

    @Test
    fun aCallThatMayResizeWhatItIsGivenIsLeftWhereThatMayBeTheEndsCollection() {
        // The list's other name given to a function that grows it through a local of its own: five turns, where a range would run two.
        val direct = shared("grow(ys)")
        assertEquals("0\n1\n2\n3\n4\n", runPy(direct))
        val l = left(direct, Reason.BOUND_SHARED)
        assertTrue(l.message.contains("calls `grow`, which may resize what it is given"), l.message)
        // Through a second function, through a value that holds the list, and through a value the function makes to hold it.
        for (src in listOf(shared("outer(ys)"), shared("fill(box)", "    box: Box { }\n    box.items = xs\n"), shared("viaBox(ys)"))) {
            assertEquals("0\n1\n2\n3\n4\n", runPy(src))
            left(src, Reason.BOUND_SHARED)
        }
    }

    @Test
    fun aValueHeldAloneMayStillHoldTheEndsCollectionInsideIt() {
        // `wrap` is this function's alone, but it holds the child whose size the end reads: a function given it grows the child.
        val src = "poke: (j: Json) Void {\n    if j.at(0).size() < 5 {\n        j.at(0).add(Json.of(\"z\"))\n    }\n}\n\n" +
            "pub main: () Void {\n    doc: Json { Json.obj() }\n    doc.put(\"c\", Json.arr())\n    doc.get(\"c\").add(Json.of(\"a\"))\n    doc.get(\"c\").add(Json.of(\"b\"))\n" +
            "    cs: Json { doc.get(\"c\") }\n    wrap: Json { Json.arr() }\n    wrap.add(cs)\n    i: Mutable<Size> { 0 }\n    while i < cs.size() {\n        trace(i)\n        poke(wrap)\n        i += 1\n    }\n}\n"
        assertEquals("0\n1\n2\n3\n4\n", runPy(src))
        val l = left(src, Reason.BOUND_SHARED)
        assertTrue(l.message.contains("calls `poke`, which may resize what it is given"), l.message)
    }

    @Test
    fun aCallThatMayWriteThroughWhatItIsGivenIsLeftWhereTheEndReadsAField() {
        fun bag(body: String): String =
            "zero: (b: Bag) Void {\n    b.n = 0\n}\n\nshow: (b: Bag) Void {\n    trace(b.n)\n}\n\nBag: Mutable<Any> {\n    pub n: Mutable<Int32> { 3 }\n\n" +
                "    pub run: (other: Bag) Void {\n        i: Mutable<Int32> { 0 }\n        while i < n {\n            $body\n            i += 1\n        }\n    }\n}\n"
        assertTrue(left(bag("zero(other)"), Reason.BOUND_SHARED).message.contains("calls `zero`, which may write through what it is given"))
        assertEquals(1, fix(bag("show(other)")).fixed)
    }

    @Test
    fun theLoopOverAListAnotherNameGrowsRunsLongerThanItsRangeWould() {
        // Why a shared list is left: the counter sees the list grow through its other name, and a range would not.
        val src = over(freshList + "    ys: List<Int32> { xs }\n", "trace(i)\n        if ys.size() < 5 {\n            ys.add(1)\n        }").replace("pub run:", "pub main:")
        assertEquals("0\n1\n2\n3\n4\n", runPy(src))
        assertEquals(0, CounterFix.fixSnippet(src).fixed)
    }

    @Test
    fun anEndThatReadsAFieldOrAGlobalIsLeftWhereTheBodyMayChangeIt() {
        fun bag(body: String): String =
            "twice: (v: Int32) Int32 {\n    v * 2\n}\n\nBag: Mutable<Any> {\n    pub n: Mutable<Int32> { 3 }\n    pub t: Mutable<Int32> { 0 }\n\n    pub shrink: () Void {\n        n -= 1\n    }\n\n" +
                "    pub run: (other: Bag) Void {\n        i: Mutable<Int32> { 0 }\n        while i < n {\n            $body\n            i += 1\n        }\n    }\n}\n"
        // A call with an effect, and a field written through another value, which may be this one.
        left(bag("shrink()"), Reason.BOUND_SHARED)
        assertTrue(left(bag("other.n = 0"), Reason.BOUND_SHARED).message.contains("writes a field"))
        // The field assigned by its own name.
        left(bag("n = n - 1"), Reason.BOUND_WRITTEN)
        // A call without an effect cannot assign a field.
        assertEquals(1, fix(bag("trace(twice(i))")).fixed)

        fun global(body: String): String =
            "LIMIT: Mutable<Int32> { 3 }\n\nlower: () Void {\n    LIMIT -= 1\n}\n\npub main: () Void {\n    i: Mutable<Int32> { 0 }\n    while i < LIMIT {\n        $body\n        i += 1\n    }\n}\n"
        left(global("lower()"), Reason.BOUND_SHARED)
        left(global("LIMIT -= 1"), Reason.BOUND_WRITTEN)
        assertEquals(1, fix(global("trace(i)")).fixed)
    }

    @Test
    fun aLoopWhoseLinesAreNotLaidOutForTheRewriteIsLeft() {
        val loops = listOf(
            "    i: Mutable<Int32> { 0 } // the index\n    while i < 4 {\n        trace(i)\n        i += 1\n    }\n",
            "    i: Mutable<Int32> { 0 }\n    while i < 4 {\n        trace(i)\n        i += 1 // next\n    }\n",
            "    i: Mutable<Int32> { 0 }\n    while i < 4 {\n        trace(i)\n        // next\n        i += 1\n    }\n",
            "    i: Mutable<Int32> {\n        0\n    }\n    while i < 4 {\n        trace(i)\n        i += 1\n    }\n",
        )
        for (loop in loops) {
            left("pub main: () Void {\n$loop}\n", Reason.LAYOUT)
        }
    }

    @Test
    fun aLoopThatIsNoCounterIsNotListed() {
        val o = fix(
            """
            pub main: () Void {
                f: Mutable<Float64> { 0.0 }
                while f < 2.0 {
                    f += 1.0
                }
                go: Mutable<Bool> { true }
                while go {
                    go = false
                }
                n: Int32 { 3 }
                while x: Int32 in 0..<n {
                    trace(x)
                }
            }
            """,
        )
        assertEquals(0, o.fixed)
        assertEquals(emptyList(), o.left.map { it.toString() })
    }

    @Test
    fun everyReasonALoopIsLeftForIsListedAfterTheFirst() {
        val o = fix(
            """
            pub main: () Void {
                i: Mutable<Int32> { 0 }
                while i < 4 {
                    if i == 2 {
                        break
                    }
                    i += 1
                }
                trace(i)
            }
            """,
        )
        assertEquals(listOf(Reason.COUNTER_AFTER, Reason.BREAK), o.left.single().all)
        assertTrue(o.left.single().toString().startsWith("line 3 [counter-after]: `i` is used after the loop (also: break)"), o.left.single().toString())
    }

    // ---- the files it refuses ---------------------------------------------------------------------

    @Test
    fun aFileThatDoesNotTypeIsRefusedWhole() {
        val o = fix(
            """
            pub main: () Void {
                i: Mutable<Int32> { 0 }
                while i < 4 {
                    trace(nothing)
                    i += 1
                }
            }
            """,
        )
        assertEquals(0, o.fixed)
        assertNull(o.text)
        assertTrue(o.refused!!.startsWith("it does not type"), o.refused)
    }

    @Test
    fun aRewriteThatDoesNotTypeLeavesTheFileWhole() {
        val src = "pub main: () Void {\n    i: Mutable<Int32> { 0 }\n    while i < 4 {\n        trace(i)\n        i += 1\n    }\n}\n"
        val sound = "pub main: () Void {\n    while i: Int32 in 0..<4 {\n        trace(i)\n    }\n}\n"
        val unsound = "pub main: () Void {\n    while i: Int32 in 0..<4 {\n        i = 2\n        trace(i)\n    }\n}\n"
        val s = CounterFix.snippetSrc(src)
        val kept = CounterFix.settle(listOf(s), mapOf(s.file to CounterFix.Outcome(sound, 1, emptyList(), null)), bootstrap = true).getValue(s.file)
        assertNull(kept.refused)
        assertEquals(sound, kept.text)
        val refused = CounterFix.settle(listOf(s), mapOf(s.file to CounterFix.Outcome(unsound, 1, emptyList(), null)), bootstrap = true).getValue(s.file)
        assertEquals(0, refused.fixed)
        assertNull(refused.text)
        assertTrue(refused.refused!!.startsWith("the rewritten file does not type, so it is left whole"), refused.refused)
    }

    // ---- the command -------------------------------------------------------------------------------

    private fun project(dir: File, target: String, vararg files: Pair<String, String>) {
        val stdlib = File(PyTestSupport.repoRoot, "kira").absolutePath.replace('\\', '/')
        File(dir, "project.kira").writeText(
            "PROJECT: Project { } with {\n    name = \"fix.counters\"\n    srcDir = \"src\"\n    target = \"$target\"\n    types = \"strict\"\n" +
                "    dependencies = @mapOf(\"kira_stdlib\", \"$stdlib\")\n}\n",
        )
        files.forEach { (name, text) -> File(dir, "src/$name").also { it.parentFile.mkdirs() }.writeText(text) }
    }

    private val two = "pub main: () Void {\n    i: Mutable<Int32> { 0 }\n    while i < 3 {\n        trace(i)\n        i += 1\n    }\n    k: Mutable<Int32> { 0 }\n    while k < 3 {\n        k += 2\n    }\n}\n"

    @Test
    fun theCommandReportsOnADryRunRewritesInPlaceAndASecondRunChangesNothing(@TempDir dir: File) {
        for (target in listOf("py", "cpp")) {
            val root = File(dir, target).also { it.mkdirs() }
            project(root, target, "main.kira" to two, "other.kira" to "pub one: () Int32 {\n    1\n}\n")
            val file = File(root, "src/main.kira")
            // A directory is every .kira under it, the project's own file aside.
            val dry = PyTestSupport.cli(root, "--fix-counters", "--dry-run", root.absolutePath)
            assertEquals(0, dry.exitCode, dry.all)
            assertTrue(dry.stdout.contains("main.kira: would fix 1, left 1"), dry.all)
            assertTrue(dry.stdout.contains("  left: line 8 [step-not-1]: the step is not `k += 1`"), dry.all)
            assertTrue(dry.stdout.contains("counters: would fix 1, left 1, in 2 files (0 refused)\n  left [step-not-1]: 1"), dry.all)
            assertEquals(two, file.readText(), "a dry run writes nothing")
            val ran = PyTestSupport.cli(root, "--fix-counters", file.absolutePath)
            assertTrue(ran.stdout.contains("main.kira: fixed 1, left 1"), ran.all)
            val text = file.readText()
            assertEquals(two.replace("    i: Mutable<Int32> { 0 }\n    while i < 3 {\n        trace(i)\n        i += 1\n", "    while i: Int32 in 0..<3 {\n        trace(i)\n"), text)
            val again = PyTestSupport.cli(root, "--fix-counters", file.absolutePath)
            assertTrue(again.stdout.contains("main.kira: fixed 0, left 1"), again.all)
            assertEquals(text, file.readText())
            // The fixed file is a program: the project builds.
            val built = PyTestSupport.cli(root)
            assertEquals(0, built.exitCode, built.all)
        }
    }

    @Test
    fun aFileOutsideAProjectIsRefused(@TempDir dir: File) {
        val file = File(dir, "lone.kira").also { it.writeText(two) }
        val ran = PyTestSupport.cli(dir, "--fix-counters", file.absolutePath)
        assertTrue(ran.stdout.contains("refused: no project.kira above it"), ran.all)
        assertEquals(two, file.readText())
    }
}
