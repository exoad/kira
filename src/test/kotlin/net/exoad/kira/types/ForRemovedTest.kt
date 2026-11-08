package net.exoad.kira.types

import net.exoad.kira.TestCompileSupport
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.fix.ForFix
import net.exoad.kira.compiler.frontend.parser.ast.UnsupportedConstruct
import net.exoad.kira.cpp.decls.DeclTestSupport
import net.exoad.kira.py.PyTestSupport
import net.exoad.kira.types.TyperTestSupport.expectDiagnostic
import net.exoad.kira.types.TyperTestSupport.expectNoErrors
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Every loop is `while <condition> { }`: what `kira --fix-for` rewrites, what it leaves, and what the loops it writes compile to. */
class ForRemovedTest {
    private fun typed(body: String): TypedProgram = TyperTestSupport.snippet(body)

    private fun py(body: String): String = PyTestSupport.emit(body).python()

    private fun cpp(body: String): String = DeclTestSupport.emit(DeclTestSupport.module("app:main", body)).source("app:main") ?: ""

    private fun only(p: TypedProgram, code: String) {
        expectDiagnostic(p, code)
        assertEquals(listOf(code), p.diagnostics.filter { it.isError }.map { it.code }.distinct(), TyperTestSupport.render(p))
    }

    private fun fix(src: String): ForFix.Result = ForFix.rewrite(src.trimIndent() + "\n")

    // ---- the fixer: each shape --------------------------------------------------------------------

    @Test
    fun anInclusiveRangeBecomesACounterBeforeAndAStepLast() {
        val r = fix(
            """
            f: (n: Int32) Int32 {
                t: Mutable<Int32> { 0 }
                for i: Int32 in 0..n {
                    t += i
                }
                t
            }
            """
        )
        assertEquals(
            """
            f: (n: Int32) Int32 {
                t: Mutable<Int32> { 0 }
                i: Mutable<Int32> { 0 }
                while i <= n {
                    t += i
                    i += 1
                }
                t
            }

            """.trimIndent(),
            r.text,
        )
        assertEquals(1, r.fixed)
        assertEquals(emptyList(), r.skipped)
    }

    @Test
    fun aStringLiteralInTheHeadKeepsItsSpacesAndTabs() {
        val r = fix(
            """
            f: () Void {
                for s: Str in @arrOf("a  b", "  tok  ", "x	y", "q \" z", '"') {
                    trace(s)
                }
            }
            """
        )
        assertEquals(1, r.fixed)
        assertTrue(r.text.contains("sItems: Arr<Str> { @arrOf(\"a  b\", \"  tok  \", \"x\ty\", \"q \\\" z\", '\"') }"), r.text)
        assertEquals(ForFix.rewrite(r.text).text, r.text)
    }

    @Test
    fun untilAndIndicesLeaveTheEndOut() {
        val r = fix(
            """
            f: (xs: List<Int32>, s: Str, n: Size) Int32 {
                t: Mutable<Int32> { 0 }
                for i: Size in 0.until(n) {
                    t += 1
                }
                for j: Size in xs.indices() {
                    t += xs[j]
                }
                for k: Size in s.indices() {
                    t += 1
                }
                t
            }
            """
        )
        assertEquals(
            """
            f: (xs: List<Int32>, s: Str, n: Size) Int32 {
                t: Mutable<Int32> { 0 }
                i: Mutable<Size> { 0 }
                while i < n {
                    t += 1
                    i += 1
                }
                j: Mutable<Size> { 0 }
                while j < xs.size() {
                    t += xs[j]
                    j += 1
                }
                k: Mutable<Size> { 0 }
                while k < s.length() {
                    t += 1
                    k += 1
                }
                t
            }

            """.trimIndent(),
            r.text,
        )
        assertEquals(3, r.fixed)
    }

    @Test
    fun aCollectionIsWalkedByAnIndexAndASetOrMapByItsArr() {
        val r = fix(
            """
            f: (xs: List<Int32>, s: Set<Str>, m: Map<Str, Int32>) Void {
                for x: Int32 in xs {
                    trace(x)
                }
                for a: Str in s {
                    trace(a)
                }
                for e: T2<Str, Int32> in m {
                    trace(e)
                }
            }
            """
        )
        assertEquals(
            """
            f: (xs: List<Int32>, s: Set<Str>, m: Map<Str, Int32>) Void {
                xIndex: Mutable<Size> { 0 }
                while xIndex < xs.size() {
                    x: Int32 { xs.get(xIndex) }
                    trace(x)
                    xIndex += 1
                }
                aItems: Arr<Str> { s.toArr() }
                aIndex: Mutable<Size> { 0 }
                while aIndex < aItems.size() {
                    a: Str { aItems.get(aIndex) }
                    trace(a)
                    aIndex += 1
                }
                eItems: Arr<T2<Str, Int32>> { m.entries() }
                eIndex: Mutable<Size> { 0 }
                while eIndex < eItems.size() {
                    e: T2<Str, Int32> { eItems.get(eIndex) }
                    trace(e)
                    eIndex += 1
                }
            }

            """.trimIndent(),
            r.text,
        )
        assertEquals(3, r.fixed)
    }

    @Test
    fun aLoopBeforeInTheSameBlockLendsItsCounterAndALiteralIsHeldOnce() {
        val r = fix(
            """
            f: (xs: List<Int32>) Void {
                for x: Int32 in xs {
                    trace(x)
                }
                for x: Int32 in xs {
                    trace(x)
                }
                for v: Int32 in @arrOf(1, 2) {
                    trace(v)
                }
                for i: Int32 in 0..2 {
                    trace(i)
                }
                for i: Int32 in 5..6 {
                    trace(i)
                }
            }
            """
        )
        assertEquals(5, r.fixed, r.text)
        assertTrue(r.text.contains("xIndex: Mutable<Size> { 0 }") && r.text.contains("xIndex = 0"), r.text)
        assertEquals(1, Regex("xIndex: Mutable<Size>").findAll(r.text).count(), r.text)
        assertTrue(r.text.contains("vItems: Arr<Int32> { @arrOf(1, 2) }"), r.text)
        assertTrue(r.text.contains("i: Mutable<Int32> { 0 }") && r.text.contains("i = 5"), r.text)
        expectNoErrors(typed(r.text.replace("f: (xs", "pub f: (xs")))
    }

    @Test
    fun anEndThatTheBodyChangesOrThatRunsCodeIsReadOnceBeforeTheLoop() {
        val r = fix(
            """
            f: (n: Int32) Void {
                m: Mutable<Int32> { n }
                for i: Int32 in 0.until(m) {
                    m += 1
                }
                for j: Int32 in start(1).until(end(4)) {
                    trace(j)
                }
            }
            """
        )
        assertEquals(
            """
            f: (n: Int32) Void {
                m: Mutable<Int32> { n }
                i: Mutable<Int32> { 0 }
                iLimit: Int32 { m }
                while i < iLimit {
                    m += 1
                    i += 1
                }
                j: Mutable<Int32> { start(1) }
                jLimit: Int32 { end(4) }
                while j < jLimit {
                    trace(j)
                    j += 1
                }
            }

            """.trimIndent(),
            r.text,
        )
    }

    @Test
    fun aContinueStepsTheCounterFirstAndNestedLoopsKeepTheirOwn() {
        val r = fix(
            """
            f: (xs: List<Int32>) Int32 {
                t: Mutable<Int32> { 0 }
                for x: Int32 in xs {
                    if x < 0 {
                        continue
                    }
                    for j: Int32 in 0.until(3) {
                        if j == 1 {
                            continue
                        }
                        t += j
                    }
                    t += x
                }
                t
            }
            """
        )
        assertEquals(
            """
            f: (xs: List<Int32>) Int32 {
                t: Mutable<Int32> { 0 }
                xIndex: Mutable<Size> { 0 }
                while xIndex < xs.size() {
                    x: Int32 { xs.get(xIndex) }
                    if x < 0 {
                        xIndex += 1
                        continue
                    }
                    j: Mutable<Int32> { 0 }
                    while j < 3 {
                        if j == 1 {
                            j += 1
                            continue
                        }
                        t += j
                        j += 1
                    }
                    t += x
                    xIndex += 1
                }
                t
            }

            """.trimIndent(),
            r.text,
        )
        expectNoErrors(typed(r.text.replace("f: (xs", "pub f: (xs")))
    }

    @Test
    fun breakAndContinueWorkInAWhile() {
        val body = """
            pub f: (xs: List<Int32>) Int32 {
                t: Mutable<Int32> { 0 }
                i: Mutable<Size> { 0 }
                while i < xs.size() {
                    x: Int32 { xs.get(i) }
                    i += 1
                    if x == 0 {
                        continue
                    }
                    if x < 0 {
                        break
                    }
                    t += x
                }
                t
            }
            """
        expectNoErrors(typed(body))
        val p = py(body)
        assertTrue(p.contains("continue") && p.contains("break"), p)
    }

    // ---- the fixer: what it keeps and what it leaves ----------------------------------------------

    @Test
    fun aRewrittenFileHasNoForLeftSoASecondRunChangesNothing() {
        val r = fix(
            """
            f: (xs: List<Int32>, n: Int32) Int32 {
                t: Mutable<Int32> { 0 }
                for x: Int32 in xs {
                    t += x
                }
                for i: Int32 in 0..n {
                    t += i
                }
                t
            }
            """
        )
        assertEquals(2, r.fixed)
        val again = ForFix.rewrite(r.text)
        assertEquals(r.text, again.text)
        assertEquals(0, again.fixed)
        assertFalse(Regex("(?m)^\\s*for ").containsMatchIn(r.text), r.text)
    }

    @Test
    fun crlfAndTabsAndCommentsAreKept() {
        val src = "f: (n: Int32) Int32 {\r\n\tt: Mutable<Int32> { 0 }\r\n\t// sum 0 to n\r\n\tfor i: Int32 in 0..n { // each\r\n\t\tt += i // add\r\n\t}\r\n\tt\r\n}\r\n"
        val r = ForFix.rewrite(src)
        assertEquals(
            "f: (n: Int32) Int32 {\r\n\tt: Mutable<Int32> { 0 }\r\n\t// sum 0 to n\r\n\ti: Mutable<Int32> { 0 }\r\n\twhile i <= n { // each\r\n\t\tt += i // add\r\n\t\ti += 1\r\n\t}\r\n\tt\r\n}\r\n",
            r.text,
        )
        assertFalse(Regex("(?<!\r)\n").containsMatchIn(r.text), "every line break stays CRLF")
    }

    @Test
    fun aLoopThatCannotBeRewrittenSafelyIsLeftAndNamedWithWhy() {
        val src = """
            f: (n: Int32, xs: List<Int32>) Void {
                for a: UInt8 in 250..255 {
                    trace(a)
                }
                for b: Int32 in things() {
                    trace(b)
                }
                for c: Int32 in xs { trace(c) }
                for d: Int32 in 0..n { trace(d) }
            }
            """
        val r = fix(src)
        assertEquals(1, r.fixed)
        assertEquals(3, r.skipped.size, r.skipped.toString())
        assertTrue(r.skipped[0].contains("things()"), r.skipped[0])
        assertTrue(r.skipped[1].contains("body starts on the head's line"), r.skipped[1])
        assertTrue(r.text.contains("for b: Int32 in things() {") && r.text.contains("for c: Int32 in xs { trace(c) }"), "a loop it leaves is not touched")
    }

    @Test
    fun aCounterThatClashesWithAnotherNameOrTypeIsLeft() {
        val r = fix(
            """
            f: (i: Int32) Void {
                for i: Int32 in 0..3 {
                    trace(i)
                }
            }

            g: () Void {
                for j: Int32 in 0..3 {
                    trace(j)
                }
                for j: Size in 0..3 {
                    trace(j)
                }
            }
            """
        )
        assertEquals(1, r.fixed, r.text)
        assertEquals(2, r.skipped.size, r.skipped.toString())
        assertTrue(r.skipped[0].contains("`i` is also a name outside its loops"), r.skipped[0])
        assertTrue(r.skipped[1].contains("declared as Mutable<Int32>"), r.skipped[1])
    }

    @Test
    fun aBodyThatChangesWhatItWalksIsLeft() {
        val r = fix(
            """
            f: (xs: List<Int32>) Void {
                for x: Int32 in xs {
                    xs.add(x)
                }
            }
            """
        )
        assertEquals(0, r.fixed)
        assertTrue(r.skipped.single().contains("the body changes `xs`"), r.skipped.toString())
    }

    // ---- the refusal ------------------------------------------------------------------------------

    @Test
    fun aForIsRefusedWhereverItIsWrittenAndTheMessageNamesTheFixer() {
        val e = assertThrows<net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException> {
            typed("pub f: () Void {\n    for i: Int32 in 0..3 {\n        trace(i)\n    }\n}")
        }
        assertEquals("parse.for.removed", e.tag, e.message)
        assertTrue(e.message.contains("`for` is removed: every loop is `while <condition> { }`."), e.message)
        assertTrue(e.message.contains("write 'i: Mutable<Int32> { 0 }', then 'while i <= 3 { ... i += 1 }'"), e.message)
        assertTrue(e.message.contains("`kira --fix-for FILE...` rewrites a file."), e.message)
    }

    // ---- loops the fixer writes, on every backend --------------------------------------------------

    @Test
    fun aCountedWhileOnPyAndCpp() {
        val body = """
            pub f: (n: Int32) Int32 {
                t: Mutable<Int32> { 0 }
                i: Mutable<Int32> { 0 }
                while i <= n {
                    t += i
                    i += 1
                }
                t
            }
            """
        expectNoErrors(typed(body))
        val p = py(body)
        assertTrue(p.contains("while i <= n:"), p)
        assertFalse(p.contains("for i in"), p)
        val c = cpp(body)
        assertTrue(c.contains("while(i <= n)"), c)
    }

    @Test
    fun anIndexWalkOverEachContainerTypesClean() {
        val body = """
            pub f: (xs: List<Int32>, arr: Arr<UInt8, 4>, v: View<UInt8>, m: Map<Str, Int32>, s: Set<Int64>) Void {
                xi: Mutable<Size> { 0 }
                while xi < xs.size() {
                    x: Int32 { xs.get(xi) }
                    xi += 1
                }
                bi: Mutable<Size> { 0 }
                while bi < arr.size() {
                    b: UInt8 { arr.get(bi) }
                    bi += 1
                }
                ci: Mutable<Size> { 0 }
                while ci < v.size() {
                    c: UInt8 { v.get(ci) }
                    ci += 1
                }
                es: Arr<T2<Str, Int32>> { m.entries() }
                ei: Mutable<Size> { 0 }
                while ei < es.size() {
                    e: T2<Str, Int32> { es.get(ei) }
                    ei += 1
                }
                ks: Arr<Int64> { s.toArr() }
                ki: Mutable<Size> { 0 }
                while ki < ks.size() {
                    k: Int64 { ks.get(ki) }
                    ki += 1
                }
            }
            """
        expectNoErrors(typed(body))
        assertTrue(py(body).contains("while xi < len(xs):"))
        assertTrue(cpp(body).contains("xs.size()"))
    }

    @Test
    fun untilAndIndicesAreRemovedWithTheRangeFormInTheMessage() {
        val u = typed("pub f: (n: Int32) Bool {\n    u: Range<Int32> { 0.until(n) }\n    true\n}")
        expectDiagnostic(u, "types.until.removed")
        val um = u.diagnostics.first { it.code == "types.until.removed" }.message
        assertTrue(um.contains("0..(n - 1)") && um.contains("`until` is removed"), um)
        val i = typed("pub f: (xs: List<Int32>, s: Str) Bool {\n    a: Range<Size> { xs.indices() }\n    b: Range<Size> { s.indices() }\n    true\n}")
        val ims = i.diagnostics.filter { it.code == "types.indices.removed" }.map { it.message }
        assertEquals(2, ims.size, ims.toString())
        assertTrue(ims[0].contains("0..(xs.size() - 1)") && ims[1].contains("0..(s.length() - 1)"), ims.toString())
    }

    @Test
    fun theUntilTypeIsRemovedAndNamesRange() {
        val p = typed("pub f: (u: Until<Int32>) Bool {\n    true\n}")
        expectDiagnostic(p, "types.type.unknown")
        val m = p.diagnostics.first { it.code == "types.type.unknown" }.message
        assertTrue(m.contains("`Until` is removed") && m.contains("Range<T>") && m.contains("a..b"), m)
    }

    @Test
    fun aUserTypesOwnUntilAndIndicesStayItsOwn() {
        val p = typed(
            """
            pub Grid: Struct {
                pub n: Int32

                pub indices: () List<Int32> {
                    @listOf(n)
                }
            }

            pub f: (g: Grid) Int32 {
                g.indices()[0]
            }
            """
        )
        expectNoErrors(p)
    }

    @Test
    fun cAndJsCountWhileAndRefuseTheRemovedUntilAndIndices() {
        val loops = "f: () Int32 {\n    t: Mutable<Int32> { 0 }\n    i: Mutable<Int32> { 0 }\n    while i <= 3 {\n        t += i\n        i += 1\n    }\n    t\n}\n"
        assertTrue(TestCompileSupport.transpileSnippetToC(loops, "test/main.kira").contains("i <= 3"))
        assertTrue(TestCompileSupport.transpileSnippetToJS(loops, "test/main.kira").contains("i <= 3"))
        val held = "f: () Int32 {\n    u: Range<Int32> { 0.until(3) }\n    0\n}\n"
        listOf(
            assertThrows<UnsupportedConstruct> { TestCompileSupport.transpileSnippetToC(held, "test/main.kira") },
            assertThrows<UnsupportedConstruct> { TestCompileSupport.transpileSnippetToJS(held, "test/main.kira") },
        ).forEach { assertTrue(it.message.orEmpty().contains("`until` or `indices` (removed"), it.message) }
    }

    // ---- an end at the type's maximum -------------------------------------------------------------

    @Test
    fun anEndAtTheTypesMaximumTestsBeforeItStepsAndOneBelowKeepsThePlainForm() {
        val r = fix(
            """
            f: () Int32 {
                t: Mutable<Int32> { 0 }
                for a: UInt8 in 250..255 {
                    t += a as Int32
                }
                for b: Int8 in 125..126 {
                    t += b as Int32
                }
                for c: Int8 in 126..127 {
                    t += c as Int32
                }
                t
            }
            """
        )
        assertEquals(3, r.fixed, r.text)
        assertEquals(emptyList(), r.skipped)
        assertEquals(
            """
            f: () Int32 {
                t: Mutable<Int32> { 0 }
                a: Mutable<UInt8> { 250 }
                aMore: Mutable<Bool> { true }
                while aMore {
                    t += a as Int32
                    if a == 255 {
                        aMore = false
                    } else {
                        a += 1
                    }
                }
                b: Mutable<Int8> { 125 }
                while b <= 126 {
                    t += b as Int32
                    b += 1
                }
                c: Mutable<Int8> { 126 }
                cMore: Mutable<Bool> { true }
                while cMore {
                    t += c as Int32
                    if c == 127 {
                        cMore = false
                    } else {
                        c += 1
                    }
                }
                t
            }

            """.trimIndent(),
            r.text,
        )
        expectNoErrors(typed(r.text.replace("f: () Int32", "pub f: () Int32")))
        // One past the maximum is no UInt8: the fixer leaves it, naming why.
        val past = fix("f: () Void {\n    for a: UInt8 in 250..256 {\n        trace(a)\n    }\n}")
        assertEquals(0, past.fixed)
        assertTrue(past.skipped.single().contains("past UInt8's maximum"), past.skipped.toString())
    }

    @Test
    fun aNarrowEndThatIsNotWrittenAsANumberTestsBeforeItStepsToo() {
        val r = fix(
            """
            f: (lo: UInt8, hi: UInt8) Int32 {
                t: Mutable<Int32> { 0 }
                for a: UInt8 in lo..hi {
                    if a == 3 {
                        continue
                    }
                    t += 1
                }
                t
            }
            """
        )
        assertEquals(
            """
            f: (lo: UInt8, hi: UInt8) Int32 {
                t: Mutable<Int32> { 0 }
                a: Mutable<UInt8> { lo }
                aMore: Mutable<Bool> { a <= hi }
                while aMore {
                    if a == 3 {
                        if a == hi {
                            aMore = false
                        } else {
                            a += 1
                        }
                        continue
                    }
                    t += 1
                    if a == hi {
                        aMore = false
                    } else {
                        a += 1
                    }
                }
                t
            }

            """.trimIndent(),
            r.text,
        )
        expectNoErrors(typed(r.text.replace("f: (lo", "pub f: (lo")))
    }

    // ---- the typer's help --------------------------------------------------------------------------

    private fun typedFix(src: String): ForFix.Result = net.exoad.kira.compiler.fix.TypedFix.rewriteSnippet(src.trimIndent() + "\n")

    @Test
    fun aCallAFieldChainAndALoopsOwnVariableAreTypedByTheTyper() {
        val r = typedFix(
            """
            Box: Mutable<Any> {
                pub names: List<Str>
            }

            names: () List<Str> {
                @listOf("a", "b")
            }

            f: (b: Box, grid: List<List<Int32>>) Int32 {
                t: Mutable<Int32> { 0 }
                for s: Str in names() {
                    t += s.length() as Int32
                }
                for s: Str in b.names {
                    t += s.length() as Int32
                }
                for row: List<Int32> in grid {
                    for x: Int32 in row {
                        t += x
                    }
                }
                t
            }
            """
        )
        assertEquals(4, r.fixed, r.text + r.skipped)
        assertEquals(emptyList(), r.skipped)
        assertTrue(r.text.contains("sItems: List<Str> { names() }"), r.text)
        assertTrue(r.text.contains("while sIndex < sItems.size() {"), r.text)
        assertTrue(r.text.contains("while sIndex < b.names.size() {"), r.text)
        assertTrue(r.text.contains("while xIndex < row.size() {"), r.text)
        assertFalse(Regex("(?m)^\\s*for ").containsMatchIn(r.text), r.text)
        expectNoErrors(typed(r.text.replace("f: (b", "pub f: (b").replace("names: ()", "pub names: ()")))
    }

    @Test
    fun aRangeValueIsWalkedFromItsBeginToItsEndAndATypeThatIsNoCollectionIsLeft() {
        val r = typedFix(
            """
            span: () Range<Int32> {
                1..3
            }

            f: (r: Range<Int32>, n: Int32) Int32 {
                t: Mutable<Int32> { 0 }
                for i: Int32 in r {
                    t += i
                }
                for j: Int32 in span() {
                    t += j
                }
                for k: Int32 in n {
                    t += k
                }
                t
            }
            """
        )
        assertEquals(2, r.fixed, r.text)
        assertEquals(1, r.skipped.size, r.skipped.toString())
        assertTrue(r.skipped[0].contains("`n` is a Int32, not a collection") || r.skipped[0].contains("Int32, not a collection"), r.skipped[0])
        assertTrue(r.text.contains("i: Mutable<Int32> { r.begin }") && r.text.contains("while i <= r.end {"), r.text)
        assertTrue(r.text.contains("jRange: Range<Int32> { span() }") && r.text.contains("j: Mutable<Int32> { jRange.begin }") && r.text.contains("while j <= jRange.end {"), r.text)
    }

    @Test
    fun aNameWrittenTwoWaysIsTypedWhereTheTyperKnowsWhichOneIsInScope() {
        val src = """
            f: (xs: List<Int32>) Int32 {
                t: Mutable<Int32> { 0 }
                for x: Int32 in xs {
                    t += x
                }
                t
            }

            g: (xs: Set<Int32>) Int32 {
                t: Mutable<Int32> { 0 }
                for x: Int32 in xs {
                    t += x
                }
                t
            }

            h: (n: Int32) Int32 {
                xs: List<Int32> { @listOf(n) }
                t: Mutable<Int32> { 0 }
                for x: Int32 in xs {
                    t += x
                }
                xs2: Set<Int32> { @setOf(n) }
                for x: Int32 in xs2 {
                    t += x
                }
                t
            }
            """
        val r = typedFix(src)
        assertEquals(4, r.fixed, r.text)
        assertEquals(emptyList(), r.skipped)
    }

    @Test
    fun theTypedModeWritesWhatTheUntypedOneDoesWhereBothCanReadTheFile() {
        val src = "f: (xs: List<Int32>, n: Int32) Int32 {\n    t: Mutable<Int32> { 0 }\n    for x: Int32 in xs {\n        t += x\n    }\n    for i: Int32 in 0..n {\n        t += i\n    }\n    t\n}\n"
        assertEquals(ForFix.rewrite(src).text, typedFix(src).text)
    }

    @Test
    fun theTypedModeIsIdempotentAndKeepsCrlf() {
        val src = "names: () List<Str> {\r\n    @listOf(\"a\")\r\n}\r\n\r\nf: () Int32 {\r\n    t: Mutable<Int32> { 0 }\r\n    for s: Str in names() { // each\r\n        t += 1\r\n    }\r\n    t\r\n}\r\n"
        val r = net.exoad.kira.compiler.fix.TypedFix.rewriteSnippet(src)
        assertEquals(1, r.fixed, r.skipped.toString())
        assertFalse(Regex("(?<!\r)\n").containsMatchIn(r.text), "every line break stays CRLF")
        val again = net.exoad.kira.compiler.fix.TypedFix.rewriteSnippet(r.text)
        assertEquals(r.text, again.text)
        assertEquals(0, again.fixed)
    }
}
