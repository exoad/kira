package net.exoad.kira.types

import net.exoad.kira.compiler.analysis.types.Severity
import net.exoad.kira.compiler.analysis.types.TyperOptions
import net.exoad.kira.compiler.fix.WithFix
import net.exoad.kira.types.TyperTestSupport.expectDiagnostic
import net.exoad.kira.types.TyperTestSupport.expectNoErrors
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `x with { ... }` as a scoping block: a bare `with` on a value type is refused, and a run of statements on one Mutable class value is a hint and `kira --fix-with`. */
class WithFoldTest {
    private val types = """
        P: Struct {
            pub x: Int32
            pub y: Int32
        }

        Counter: Mutable<Any> {
            pub frames: Mutable<Int64> { 0 }
            pub label: Mutable<Str> { "" }

            pub count: () Void {
                frames += 1
            }

            pub reset: (to: Int64) Void {
                frames = to
            }

            pub name: (s: Str) Void {
                label = s
            }
        }
    """.trimIndent()

    private fun snippet(body: String, fold: Boolean = false) =
        TyperTestSupport.snippet("$types\n\n${body.trimIndent()}", options = TyperOptions(withFold = fold))

    private fun hints(body: String) = snippet(body, fold = true).diagnostics.filter { it.code == "rules.with.fold" }

    private fun fold(body: String): Pair<String, Int> = WithFix.foldSnippet("$types\n\n${body.trimIndent()}\n")

    // ---- a bare with on a value type ---------------------------------------------------------------

    @Test
    fun aBareWithOnAValueTypeIsRefusedWithTheTwoWaysOut() {
        val p = snippet(
            """
            main: () Void {
                p: P { 1, 2 }
                p with {
                    x = 5
                }
            }
            """,
        )
        expectDiagnostic(p, "types.with.discarded")
        val m = p.diagnostics.first { it.code == "types.with.discarded" }.message
        assertTrue(m.contains("a value type") && m.contains("assign it, `p = p with { ... }`") && m.contains("a Mutable class"), m)
    }

    @Test
    fun aWithOnAMutableClassEditsInPlaceAndAnAssignedOrBoundCopyIsFine() {
        expectNoErrors(
            snippet(
                """
                main: () Void {
                    c: Counter { }
                    c with {
                        count()
                        reset(7)
                        label = "x"
                    }
                    p: Mutable<P> { P { 1, 2 } }
                    p = p with {
                        x = 5
                    }
                    q: P { p with {
                        y = 9
                    } }
                }
                """,
            ),
        )
    }

    // ---- the hint ------------------------------------------------------------------------------------

    @Test
    fun twoOrMoreStatementsOnOneMutableClassValueAreAHintOnlyForTheTargetThatLowersWith() {
        val body = """
            main: () Void {
                c: Counter { }
                c.count()
                c.reset(7)
                c.label = "x"
            }
            """
        val h = hints(body)
        assertEquals(1, h.size, TyperTestSupport.render(snippet(body, fold = true)))
        assertEquals(Severity.NOTE, h.single().severity)
        assertTrue(h.single().message.contains("These 3 statements in a row all act on `c`") && h.single().message.contains("`c with { ... }`"), h.single().message)
        assertEquals(emptyList(), snippet(body).diagnostics.filter { it.code == "rules.with.fold" }, "off unless the target lowers a with")
    }

    @Test
    fun aSingleStatementAnotherReceiverAValueTypeAndAMovedNameAreNoRun() {
        assertEquals(0, hints("main: () Void {\n    c: Counter { }\n    c.count()\n}").size)
        assertEquals(
            0,
            hints("main: () Void {\n    c: Counter { }\n    d: Counter { }\n    c.count()\n    d.count()\n    c.count()\n}").size,
            "statements on two values are not a run",
        )
        assertEquals(0, hints("main: () Void {\n    c: Counter { }\n    c.count()\n    trace(\"x\")\n    c.count()\n}").size, "another statement between")
        // A bare name that is a member of the receiver would read as the member inside the block.
        assertEquals(0, hints("main: (label: Str) Void {\n    c: Counter { }\n    c.count()\n    c.name(label)\n}").size)
        // A parameter named like the member a statement uses.
        assertEquals(0, hints("main: (frames: Int64) Void {\n    c: Counter { }\n    c.count()\n    c.reset(frames)\n}").size)
        // A value type's `with` would build a copy.
        assertEquals(0, hints("main: () Void {\n    p: Mutable<P> { P { 1, 2 } }\n    p.x = 3\n    p.y = 4\n}").size)
    }

    @Test
    fun aRunInsideAWithBlockOrAfterAnotherRunStartsAgain() {
        val h = hints(
            """
            main: () Void {
                c: Counter { }
                c.count()
                c.count()
                trace("between")
                c.reset(1)
                c.reset(2)
                c.reset(3)
            }
            """,
        )
        assertEquals(listOf("2 statements", "3 statements"), h.map { Regex("These (\\d+ statements)").find(it.message)!!.groupValues[1] })
    }

    // ---- the fixer -----------------------------------------------------------------------------------

    @Test
    fun theFixerFoldsARunIntoAWithKeepingItsCommentsAndIsIdempotent() {
        val (text, n) = fold(
            """
            main: () Void {
                c: Counter { }
                c.count() // one
                // two
                c.reset(7)

                c.label = "x"
                trace("after")
            }
            """,
        )
        assertEquals(1, n)
        val body = text.substring(text.indexOf("main: () Void"))
        assertEquals(
            """
            main: () Void {
                c: Counter { }
                c with {
                    count() // one
                    // two
                    reset(7)

                    label = "x"
                }
                trace("after")
            }

            """.trimIndent(),
            body,
        )
        val again = WithFix.foldSnippet(text)
        assertEquals(0, again.second)
        assertEquals(text, again.first)
        expectNoErrors(TyperTestSupport.snippet(text.replace("main: () Void", "pub main: () Void")))
    }

    @Test
    fun theFixerKeepsCrlfAndTabsAndFoldsMultiLineStatements() {
        val src = "$types\n\npub main: () Void {\n\tc: Counter { }\n\tc.reset(\n\t\t7\n\t)\n\tc.count()\n}\n".replace("\n", "\r\n")
        val (text, n) = WithFix.foldSnippet(src)
        assertEquals(1, n)
        assertTrue(text.endsWith("pub main: () Void {\r\n\tc: Counter { }\r\n\tc with {\r\n\t\treset(\r\n\t\t\t7\r\n\t\t)\r\n\t\tcount()\r\n\t}\r\n}\r\n"), text)
        assertFalse(Regex("(?<!\r)\n\t").containsMatchIn(text.substringAfter("pub main")), "line ends stay CRLF")
    }

    // ---- stdlib receivers -----------------------------------------------------------------------------

    private val built = """
        Sink: Mutable<Any> {
            pub items: List<Int32> { }
            pub log: Mutable<List<Str>> { }

            pub note: () Str {
                "n"
            }

            pub size: () Size {
                items.size()
            }

            pub fill: (n: Int32) Void {
                items.add(n)
                items.add(n + 1)
                log.add("a")
                log.add(note())
                items.add(size() as Int32)
                items.add(n)
            }
        }

        total: (xs: List<Int32>, size: Int32) Json {
            j: Json { Json.obj() }
            j.put("size", Json.of(size as Int64))
            j.put("n", Json.of(xs.size() as Int64))
            out: List<Int32> { }
            // counted
            out.add(1)
            out.addAll(xs.toArr())
            xs.add(4)
            xs.set(0, 5)
            j
        }

        table: (put: Int32) Map<Str, Int32> {
            m: Map<Str, Int32> { }
            m.put("a", put)
            m.put("b", 2)
            m
        }
    """.trimIndent()

    @Test
    fun theFixerFoldsRunsOnAStdlibContainerWhereTheStatementsKeepTheirMeaning() {
        val o = WithFix.snippet("$built\n")
        assertEquals(4, o.folded, o.text)
        assertEquals(4, o.stdlib)
        assertEquals(mapOf("Json" to 1, "List" to 3), o.types)
        val text = o.text!!
        // A parameter named like a method of the receiver, read as a value, is no obstacle.
        assertTrue(text.contains("    j with {\n        put(\"size\", Json.of(size as Int64))\n        put(\"n\", Json.of(xs.size() as Int64))\n    }\n"), text)
        assertTrue(text.contains("    // counted\n    out with {\n        add(1)\n        addAll(xs.toArr())\n    }\n"), text)
        assertTrue(text.contains("    xs with {\n        add(4)\n        set(0, 5)\n    }\n"), text)
        assertTrue(text.contains("        items with {\n            add(n)\n            add(n + 1)\n        }\n"), text)
        // Left: a local named like the method the statements call, a Mutable field a call could rebind, a bare call the block would take.
        assertTrue(text.contains("    m.put(\"a\", put)\n    m.put(\"b\", 2)\n"), text)
        assertTrue(text.contains("        log.add(\"a\")\n        log.add(note())\n"), text)
        assertTrue(text.contains("        items.add(size() as Int32)\n        items.add(n)\n"), text)
        assertEquals(
            setOf(
                "the receiver is a Mutable field or global, and a call in the statement could give it another value",
                "a bare name in the statement would mean the receiver's member inside its block",
                "a local or a parameter of the function is named like the member the statement uses",
            ),
            o.left.map { it.why }.toSet(),
        )
        // The folded module types, and a second run finds nothing.
        expectNoErrors(TyperTestSupport.snippet(text))
        val again = WithFix.snippet(text)
        assertEquals(0, again.folded)
        assertEquals(text, again.text)
    }

    @Test
    fun aRunEndsBeforeALineThatStartsWithAnIntrinsic() {
        val o = WithFix.snippet("both: (xs: List<Int32>) List<Int32> {\n    xs.add(1)\n    xs.add(2)\n    @listOf(\n        3,\n        4)\n}\n")
        assertEquals(1, o.folded)
        assertEquals("both: (xs: List<Int32>) List<Int32> {\n    xs with {\n        add(1)\n        add(2)\n    }\n    @listOf(\n        3,\n        4)\n}\n", o.text)
    }

    @Test
    fun atDeclarationARunGoesOntoTheDeclarationAboveItAndABlockAlreadyThereJoinsIt() {
        val src = """
            Counter: Mutable<Any> {
                pub frames: Mutable<Int64> { 0 }

                pub count: () Void {
                    frames += 1
                }
            }

            run: (xs: List<Int32>) Json {
                j: Json { Json.obj() }
                j.put("a", Json.of(1 as Int64))
                j.put("b", Json.of(2 as Int64))
                out: List<Int32> { }
                out.add(1)
                out.add(out.size() as Int32)
                ys: List<Int32> { xs } // the caller's
                ys.add(1)
                ys.add(2)
                c: Counter { }
                c with {
                    count()
                }
                d: Counter { }
                d with {
                    count()
                    frames = d.frames + 1
                }
                j
            }
        """.trimIndent() + "\n"
        val plain = WithFix.snippet(src)
        assertEquals(3, plain.folded)
        assertEquals(0, plain.declared + plain.joined)
        assertTrue(plain.text!!.contains("    j: Json { Json.obj() }\n    j with {\n"), plain.text)
        val o = WithFix.snippet(src, atDeclaration = true)
        assertEquals(3, o.folded)
        assertEquals(1, o.declared)
        assertEquals(1, o.joined)
        val text = o.text!!
        assertTrue(text.contains("    j: Json { Json.obj() } with {\n        put(\"a\", Json.of(1 as Int64))\n        put(\"b\", Json.of(2 as Int64))\n    }\n"), text)
        // The name is not in reach inside its own declaration, and a comment after the declaration keeps its line.
        assertTrue(text.contains("    out: List<Int32> { }\n    out with {\n        add(1)\n        add(out.size() as Int32)\n    }\n"), text)
        assertTrue(text.contains("    ys: List<Int32> { xs } // the caller's\n    ys with {\n"), text)
        assertTrue(text.contains("    c: Counter { } with {\n        count()\n    }\n"), text)
        assertTrue(text.contains("    d: Counter { }\n    d with {\n"), text)
        expectNoErrors(TyperTestSupport.snippet(text))
        val again = WithFix.snippet(text, atDeclaration = true)
        assertEquals(0, again.folded + again.joined)
        assertEquals(text, again.text)
    }

    @Test
    fun aValueTypeAStdlibValueThatIsNotBuiltUpAndAHostTypeStayWithWhy() {
        val o = WithFix.snippet("$types\n\nmain: (t: Suite) Void {\n    p: Mutable<P> { P { 1, 2 } }\n    p.x = 3\n    p.y = 4\n    t.eq(p.x, 3)\n    t.eq(p.y, 4)\n}\n")
        assertEquals(0, o.folded)
        assertEquals(listOf("the receiver is a value type (a Struct), whose `with` builds a copy"), o.left.map { it.why })
        assertEquals(2, o.left.single().statements)
        assertEquals(0, hints("main: (t: Suite) Void {\n    t.eq(1, 1)\n    t.eq(2, 2)\n}").size, "a Suite's checks are no run")
        assertEquals(1, hints("main: (xs: List<Int32>) Void {\n    xs.add(1)\n    xs.add(2)\n}").size)
    }

    @Test
    fun theFixerLeavesWhatItCannotFoldSafely() {
        val src = "main: (label: Str) Void {\n    c: Counter { }\n    c.count()\n    c.name(label)\n    c.count()\n}\n"
        val (text, n) = WithFix.foldSnippet("$types\n\n$src")
        assertEquals(0, n)
        assertTrue(text.endsWith(src), text)
    }
}
