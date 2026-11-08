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

    @Test
    fun theFixerLeavesWhatItCannotFoldSafely() {
        val src = "main: (label: Str) Void {\n    c: Counter { }\n    c.count()\n    c.name(label)\n    c.count()\n}\n"
        val (text, n) = WithFix.foldSnippet("$types\n\n$src")
        assertEquals(0, n)
        assertTrue(text.endsWith(src), text)
    }
}
