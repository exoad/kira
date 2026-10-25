package net.exoad.kira.suite

import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.display
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.WithExpr
import net.exoad.kira.cpp.decls.DeclTestSupport
import net.exoad.kira.py.PyTestSupport
import net.exoad.kira.types.TyperTestSupport
import net.exoad.kira.types.TyperTestSupport.expectDiagnostic
import net.exoad.kira.types.TyperTestSupport.expectNoErrors
import net.exoad.kira.types.TyperTestSupport.module
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The builder operator `value with { ... }` (stage 2a of the clean dialect), and `copy(...)` retired. */
class WithBuilderTest {
    private val types = """
        P: Struct {
            pub x: Int32
            pub y: Int32
        }

        Trim: Any {
            pub servoCenterUs: Int64
            pub escMinUs: Int64
        }

        Counter: Mutable<Any> {
            pub frames: Mutable<Int64> = 0
            pub label: Str = ""

            pub count: () Void {
                frames += 1
            }
        }
    """.trimIndent()

    private fun snippet(body: String) = TyperTestSupport.snippet("$types\n\n${body.trimIndent()}")

    private fun parseError(body: String): DiagnosticsException =
        assertThrows { TyperTestSupport.unitOf(module("test:main", "$types\n\n${body.trimIndent()}")) }

    private fun withs(p: net.exoad.kira.compiler.analysis.types.TypedProgram) = p.model.withs.entries.sortedBy { it.value.temp.name }

    // ---- the form ----------------------------------------------------------------------------

    @Test
    fun aWithIsAnExpressionWhoseValueIsTheReceiver() {
        val p = snippet(
            """
            main: () Void {
                p: P = P { 1, 2 }
                q: P = p with {
                    x = 10
                }
            }
            """,
        )
        expectNoErrors(p)
        val (e, info) = withs(p).single()
        assertEquals("P", p.model.types[e]?.display())
        assertTrue(info.copy, "a value type is copied")
    }

    @Test
    fun aClassWithAMutFieldIsEditedInPlaceAndOneWithoutIsCopied() {
        val p = snippet(
            """
            main: () Void {
                c: Counter = Counter { }
                c with {
                    count()
                }
                t: Trim = Trim { 1, 2 } with {
                    escMinUs = 3
                }
            }
            """,
        )
        expectNoErrors(p)
        val (inPlace, copied) = withs(p).map { it.value }
        assertFalse(inPlace.copy)
        assertTrue(copied.copy)
        assertTrue(copied.cls in p.model.withCopied)
    }

    @Test
    fun aCopysFieldsAreAllAssignableAndAnInPlaceClassKeepsItsRules() {
        expectNoErrors(snippet("main: () Void {\n    t: Trim = Trim { 1, 2 } with {\n        servoCenterUs = 5\n    }\n}"))
        val p = snippet("main: () Void {\n    c: Counter = Counter { }\n    c with {\n        label = \"x\"\n    }\n}")
        expectDiagnostic(p, "rules.mutability.field")
    }

    @Test
    fun aWithBlockSpansLines() {
        assertEquals("parse.with.one-line", parseError("main: () Void {\n    q: P = P { 1, 2 } with { x = 3 }\n}").tag)
        assertEquals("parse.with.one-line", parseError("main: () Void {\n    q: P = P { 1, 2 } with {\n        x = 3 }\n}").tag)
    }

    @Test
    fun theOldPrefixWithAndOneInABareConditionAreRefused() {
        assertEquals("parse.with.receiver", parseError("main: () Void {\n    q: P = with { x = 3 }\n}").tag)
        val e = parseError("main: () Void {\n    p: P = P { 1, 2 }\n    if p with {\n        x = 3\n    }.x > 0 {\n    }\n}")
        assertEquals("parse.with.condition", e.tag)
    }

    // ---- names -------------------------------------------------------------------------------

    @Test
    fun aLocalOrParameterNamedLikeAMemberIsAnError() {
        expectDiagnostic(snippet("main: () Void {\n    p: P = P { 1, 2 }\n    x: Int32 = 3\n    q: P = p with {\n        y = x\n    }\n}"), "types.with.clash")
        expectDiagnostic(snippet("main: () Void {\n    p: P = P { 1, 2 }\n    q: P = p with {\n        x: Int32 = 4\n    }\n}"), "types.with.clash")
        expectDiagnostic(snippet("f: (x: Int32, p: P) P {\n    p with {\n        y = x\n    }\n}"), "types.with.clash")
    }

    @Test
    fun nestedReceiversThatShareAMemberNameAreAnError() {
        val p = snippet(
            """
            Q: Struct {
                pub x: Int32
                pub z: Int32
            }

            main: () Void {
                p: P = P { 1, 2 }
                q: Q = Q { 1, 2 }
                r: P = p with {
                    y = (q with {
                        z = 3
                    }).z
                }
            }
            """,
        )
        assertTrue(expectDiagnostic(p, "types.with.nested").message.contains("'x'"))
    }

    @Test
    fun aMagicReceiverIsRefused() {
        val p = snippet("main: () Void {\n    xs: List<Int32> = List<Int32> { }\n    ys: List<Int32> = xs with {\n        add(1)\n    }\n}")
        expectDiagnostic(p, "types.with.receiver")
    }

    // ---- where it runs -----------------------------------------------------------------------

    @Test
    fun aWithThatWouldRunAtAnotherTimeIsRefused() {
        val loop = snippet("main: () Void {\n    p: P = P { 1, 2 }\n    i: Mutable<Int32> = 0\n    while (p with {\n        x = 1\n    }).x > i {\n        i += 1\n    }\n}")
        assertTrue(expectDiagnostic(loop, "types.with.position").message.contains("while condition"))
        val shortCircuit = snippet("main: () Void {\n    p: P = P { 1, 2 }\n    ok: Bool = p.x > 0 && (p with {\n        x = 2\n    }).x > 0\n}")
        assertTrue(expectDiagnostic(shortCircuit, "types.with.position").message.contains("&&"))
        val sibling = snippet(
            "pick: (a: P, b: P) Int32 {\n    a.x + b.x\n}\n\n" +
                "main: () Void {\n    r: Mutable<P> = P { 0, 0 }\n    n: Int32 = pick(r, r with {\n        r = P { 5, 5 }\n    })\n}",
        )
        assertTrue(expectDiagnostic(sibling, "types.with.position").message.contains("evaluates r first"))
        val defaulted = snippet("Holder: Any {\n    pub p: P = P { 1, 2 } with {\n        x = 3\n    }\n}")
        expectDiagnostic(defaulted, "types.with.position")
    }

    @Test
    fun aWithMayBeAnArgumentBesideNamesALocalsValueAReturnAndAStatement() {
        val p = snippet(
            """
            sum: (a: Int32, b: P) Int32 {
                a + b.x
            }

            wider: (p: P) P {
                p with {
                    x = x + 1
                }
            }

            main: () Void {
                k: Int32 = 1
                p: P = P { 1, 2 }
                n: Int32 = sum(k, p with {
                    x = 3
                })
                c: Counter = Counter { }
                c with {
                    count()
                }
            }
            """,
        )
        expectNoErrors(p)
    }

    // ---- module constants --------------------------------------------------------------------

    @Test
    fun aModuleConstantsWithFoldsIntoItsConstruction() {
        val p = snippet(
            """
            BENCH_TRIM: Trim = Trim { 1500, 1541 } with {
                escMinUs = 1620
            }
            """,
        )
        expectNoErrors(p)
        val g = p.module("test:main")!!.members["BENCH_TRIM"] as GlobalSymbol
        val init = assertIs<ObjectInitExpr>(g.init)
        assertEquals(listOf("servoCenterUs", "escMinUs"), init.namedArgs.map { it.name.value })
        val bad = snippet("BAD: P = P { 1, 2 } with {\n    x = y + 1\n}")
        assertTrue(expectDiagnostic(bad, "types.with.constant").message.contains("'y'"))
    }

    @Test
    fun theFoldedConstantCompilesOnCppAndAnyOtherWithIsRefusedThere() {
        val src = DeclTestSupport.module("app:main", "$types\n\nBENCH_TRIM: Trim = Trim { 1500, 1541 } with {\n    escMinUs = 1620\n}")
        val out = DeclTestSupport.emit(src).source("app:main") ?: ""
        assertTrue(out.contains("BENCH_TRIM = std::make_shared<Trim>(std::int64_t{1500}, std::int64_t{1620})"), out)
        val fn = DeclTestSupport.module("app:main", "$types\n\nmain: () Void {\n    p: P = P { 1, 2 }\n    q: P = p with {\n        x = 3\n    }\n}")
        val emitted = DeclTestSupport.emit(fn)
        assertTrue(emitted.diagnostics("app:main").any { it.code == "cpp.unsupported" && it.message.contains("`with`") }, emitted.render(emitted.diagnostics("app:main")))
    }

    // ---- py ----------------------------------------------------------------------------------

    @Test
    fun onPyAWithIsItsLinesBeforeItsStatement() {
        val py = PyTestSupport.emit(
            "$types\n\n" +
                "main: () Void {\n    p: P = P { 1, 2 }\n    q: P = p with {\n        x = 10\n    }\n    c: Counter = Counter { }\n    c with {\n        count()\n    }\n}",
        ).python()
        assertTrue(py.contains("    _k_w0 = p._k_clone()\n    _k_w0.x = 10\n    q = _k_w0\n"), py)
        assertTrue(py.contains("    _k_w1 = c\n    _k_w1.count()\n"), py)
    }

    // ---- copy() ------------------------------------------------------------------------------

    @Test
    fun copyIsRetiredForWith() {
        val p = snippet("main: () Void {\n    p: P = P { 1, 2 }\n    q: P = p.copy(x = 3)\n}")
        assertTrue(expectDiagnostic(p, "types.with.copy").message.contains("x = ..."))
    }

    @Test
    fun theWithsPyGoldenIsConvertedText() {
        // The golden is written in the new form; nothing in it is old.
        val src = File("src/test/resources/py-golden/withs/src/lang/withs.kira").readText()
        assertTrue(src.contains(" with {\n") && !src.contains(".copy("))
        val parsed = TyperTestSupport.unitOf(TyperTestSupport.Src("lang/withs.kira", src, "golden.py.withs:lang.withs"))
        val withs = parsed.allSources().first { it.file == "lang/withs.kira" }.let { ctx -> TyperTestSupport.nodes(ctx.ast).filterIsInstance<WithExpr>() }
        assertTrue(withs.size >= 10, "the golden holds ${withs.size} withs")
    }
}
