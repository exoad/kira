package net.exoad.kira.types

import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.display
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.WithExpr
import net.exoad.kira.cpp.decls.DeclTestSupport
import net.exoad.kira.py.PyTestSupport
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

/** The builder operator `value with { ... }` and `copy(...)` retired. */
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
            pub frames: Mutable<Int64> { 0 }
            pub label: Str { "" }

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
                p: P { 1, 2 }
                q: P { p with {
                    x = 10
                } }
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
                c: Counter { }
                c with {
                    count()
                }
                t: Trim { 1, 2 } with {
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
        expectNoErrors(snippet("main: () Void {\n    t: Trim { 1, 2 } with {\n        servoCenterUs = 5\n    }\n}"))
        val p = snippet("main: () Void {\n    c: Counter { }\n    c with {\n        label = \"x\"\n    }\n}")
        expectDiagnostic(p, "rules.mutability.field")
    }

    @Test
    fun aWithBlockSpansLines() {
        assertEquals("parse.with.one-line", parseError("main: () Void {\n    q: P { 1, 2 } with { x = 3 }\n}").tag)
        assertEquals("parse.with.one-line", parseError("main: () Void {\n    q: P { 1, 2 } with {\n        x = 3 }\n}").tag)
    }

    @Test
    fun theOldPrefixWithAndOneInABareConditionAreRefused() {
        assertEquals("parse.with.receiver", parseError("main: () Void {\n    q: P { with { x = 3 } }\n}").tag)
        val e = parseError("main: () Void {\n    p: P { 1, 2 }\n    if p with {\n        x = 3\n    }.x > 0 {\n    }\n}")
        assertEquals("parse.with.condition", e.tag)
    }

    // ---- names -------------------------------------------------------------------------------

    @Test
    fun aLocalOrParameterNamedLikeAMemberIsAnError() {
        expectDiagnostic(snippet("main: () Void {\n    p: P { 1, 2 }\n    x: Int32 { 3 }\n    q: P { p with {\n        y = x\n    } }\n}"), "types.with.clash")
        expectDiagnostic(snippet("main: () Void {\n    p: P { 1, 2 }\n    q: P { p with {\n        x: Int32 { 4 }\n    } }\n}"), "types.with.clash")
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
                p: P { 1, 2 }
                q: Q { 1, 2 }
                r: P { p with {
                    y = (q with {
                        z = 3
                    }).z
                } }
            }
            """,
        )
        assertTrue(expectDiagnostic(p, "types.with.nested").message.contains("'x'"))
    }

    @Test
    fun aMutableStdlibValueIsEditedInPlaceByItsMethodsAndAStdlibValueTypeIsRefused() {
        val p = snippet(
            """
            main: () Void {
                xs: List<Int32> { }
                ys: List<Int32> { xs with {
                    add(1)
                    addAll(@arrOf(2, 3))
                    set(0, 4)
                } }
                m: Map<Str, Int32> { } with {
                    put("a", 1)
                    put("b", 2)
                }
                j: Json { Json.obj() }
                j with {
                    put("n", Json.of(m.size() as Int64))
                }
                seen: Set<Int32> { }
                seen with {
                    add(1)
                }
            }
            """,
        )
        expectNoErrors(p)
        // Each is edited in place, and a local receiver or a declaration's own needs no temporary.
        assertTrue(p.model.withs.values.none { it.copy })
        assertEquals(listOf("j", "seen", "xs"), p.model.withs.values.mapNotNull { it.direct?.name }.sorted())
        assertEquals(listOf("m"), p.model.withs.values.filter { it.declares }.map { it.temp.name })
        val refused = snippet("main: () Void {\n    s: Str { \"a\" }\n    t: Str { s with {\n        trim()\n    } }\n}")
        assertTrue(expectDiagnostic(refused, "types.with.receiver").message.contains("a stdlib value type"))
        val arr = snippet("main: () Void {\n    a: Arr<Int32, 2> { @arrOf(1, 2) }\n    a with {\n        set(0, 3)\n    }\n}")
        expectDiagnostic(arr, "types.with.receiver")
    }

    @Test
    fun aStdlibReceiversFieldsAreNotReachableAndAMethodNameIsNeverAValue() {
        // List's own storage is named `values`: the block's `values` is the parameter.
        expectNoErrors(snippet("f: (xs: List<Int32>, values: Arr<Int32>, size: Int32) Void {\n    xs with {\n        addAll(values)\n        add(size)\n    }\n}"))
        // A name that is only a method of the receiver means what it means outside the block: a field of the class, a module value.
        expectNoErrors(
            snippet(
                """
                LIMIT: Int32 { 3 }

                Bag: Mutable<Any> {
                    items: List<Int32> { }
                    pub size: Mutable<Int32> { 0 }

                    pub fill: () Void {
                        items with {
                            add(size)
                            add(LIMIT)
                        }
                    }
                }
                """,
            ),
        )
        // With nothing else of the name, a method read as a value says so.
        val p = snippet("f: (xs: List<Int32>) Void {\n    xs with {\n        add(size)\n    }\n}")
        assertTrue(expectDiagnostic(p, "types.member.method-value").message.contains("size(...)"))
    }

    @Test
    fun aBareNameThatIsBothALocalAndAMemberIsRefusedWithTheWayToNameEach() {
        // A call: the parameter and the List's method.
        val call = snippet("f: (xs: List<Int32>, size: Fx<T0, Int32>) Void {\n    xs with {\n        add(size())\n    }\n}")
        val m = expectDiagnostic(call, "types.with.clash").message
        assertTrue(m.contains("both a parameter and a method") && m.contains("write the method through the receiver, `xs.size(...)`") && m.contains("rename the parameter"), m)
        // A call of a method while a plain local has its name: still refused, never the local's value called.
        val plain = snippet("f: (xs: List<Int32>) Void {\n    size: Int32 { 3 }\n    xs with {\n        add(size() as Int32)\n        add(size)\n    }\n}")
        assertEquals(1, plain.diagnostics.count { it.code == "types.with.clash" }, plain.diagnostics.joinToString("\n") { it.message })
        // A value: the local and the class's field.
        val field = snippet("main: () Void {\n    c: Counter { }\n    frames: Int64 { 3 }\n    c with {\n        frames = frames\n    }\n}")
        val fm = expectDiagnostic(field, "types.with.clash").message
        assertTrue(fm.contains("both a local and a field") && fm.contains("write the field through the receiver, `c.frames`"), fm)
        // Named through the receiver, neither is ambiguous.
        expectNoErrors(snippet("f: (xs: List<Int32>, size: Int32) Void {\n    xs with {\n        add(size)\n        add(xs.size() as Int32)\n    }\n}"))
        // A local named like a method only, of a class too, is the local.
        expectNoErrors(snippet("main: () Void {\n    c: Counter { }\n    count: Int64 { 3 }\n    c with {\n        frames = count\n    }\n}"))
    }

    // ---- where it runs -----------------------------------------------------------------------

    @Test
    fun aWithThatWouldRunAtAnotherTimeIsRefused() {
        val loop = snippet("main: () Void {\n    p: P { 1, 2 }\n    i: Mutable<Int32> { 0 }\n    while (p with {\n        x = 1\n    }).x > i {\n        i += 1\n    }\n}")
        assertTrue(expectDiagnostic(loop, "types.with.position").message.contains("while condition"))
        val shortCircuit = snippet("main: () Void {\n    p: P { 1, 2 }\n    ok: Bool { p.x > 0 && (p with {\n        x = 2\n    }).x > 0 }\n}")
        assertTrue(expectDiagnostic(shortCircuit, "types.with.position").message.contains("&&"))
        val sibling = snippet(
            "pick: (a: P, b: P) Int32 {\n    a.x + b.x\n}\n\n" +
                "main: () Void {\n    r: Mutable<P> { 0, 0 }\n    n: Int32 { pick(r, r with {\n        r = P { 5, 5 }\n    }) }\n}",
        )
        assertTrue(expectDiagnostic(sibling, "types.with.position").message.contains("evaluates r first"))
        val defaulted = snippet("Holder: Any {\n    pub p: P { 1, 2 } with {\n        x = 3\n    }\n}")
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
                k: Int32 { 1 }
                p: P { 1, 2 }
                n: Int32 { sum(k, p with {
                    x = 3
                }) }
                c: Counter { }
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
            BENCH_TRIM: Trim { 1500, 1541 } with {
                escMinUs = 1620
            }
            """,
        )
        expectNoErrors(p)
        val g = p.module("test:main")!!.members["BENCH_TRIM"] as GlobalSymbol
        val init = assertIs<ObjectInitExpr>(g.init)
        assertEquals(listOf("servoCenterUs", "escMinUs"), init.namedArgs.map { it.name.value })
        val bad = snippet("BAD: P { 1, 2 } with {\n    x = y + 1\n}")
        assertTrue(expectDiagnostic(bad, "types.with.constant").message.contains("'y'"))
    }

    @Test
    fun theFoldedConstantCompilesOnCppAndAnyOtherWithIsRefusedThere() {
        val src = DeclTestSupport.module("app:main", "$types\n\nBENCH_TRIM: Trim { 1500, 1541 } with {\n    escMinUs = 1620\n}")
        val out = DeclTestSupport.emit(src).source("app:main") ?: ""
        assertTrue(out.contains("BENCH_TRIM = std::make_shared<Trim>(std::int64_t{1500}, std::int64_t{1620})"), out)
        val fn = DeclTestSupport.module("app:main", "$types\n\nmain: () Void {\n    p: P { 1, 2 }\n    q: P { p with {\n        x = 3\n    } }\n}")
        val emitted = DeclTestSupport.emit(fn)
        assertTrue(emitted.diagnostics("app:main").any { it.code == "cpp.unsupported" && it.message.contains("`with`") }, emitted.render(emitted.diagnostics("app:main")))
    }

    // ---- py ----------------------------------------------------------------------------------

    @Test
    fun onPyAWithIsItsLinesBeforeItsStatement() {
        val py = PyTestSupport.emit(
            "$types\n\n" +
                "main: () Void {\n    p: P { 1, 2 }\n    q: P { p with {\n        x = 10\n    } }\n    c: Counter { }\n    c with {\n        count()\n    }\n}",
        ).python()
        assertTrue(py.contains("    _k_w0 = p._k_clone()\n    _k_w0.x = 10\n    q = _k_w0\n"), py)
        // A local edited in place is used by its own name.
        assertTrue(py.contains("    c = _Counter()\n    c.count()\n") && !py.contains("_k_w1"), py)
    }

    @Test
    fun onPyAStdlibWithNeedsNoTemporaryForANameOrADeclarationAndHoldsAnyOtherReceiverOnce() {
        val py = PyTestSupport.emit(
            "Bag: Mutable<Any> {\n    pub items: List<Int32> { }\n    pub log: Mutable<List<Int32>> { }\n\n" +
                "    pub fill: () Void {\n        items with {\n            add(1)\n        }\n        log with {\n            add(2)\n        }\n    }\n}\n\n" +
                "make: () List<Int32> {\n    List<Int32> { }\n}\n\n" +
                "run: (xs: List<Int32>) Void {\n    xs with {\n        add(1)\n    }\n    ys: List<Int32> { } with {\n        add(2)\n        add(3)\n    }\n" +
                "    zs: List<Int32> { make() with {\n        add(4)\n    } }\n    make() with {\n        add(5)\n    }\n}",
        ).python()
        assertTrue(py.contains("    xs.append(1)\n    ys = []\n    ys.append(2)\n    ys.append(3)\n"), py)
        // The whole value of a declaration builds in the local; a call as a statement's receiver is held once.
        assertTrue(py.contains("    zs = _make()\n    zs.append(4)\n"), py)
        assertTrue(py.contains("    _k_w1 = _make()\n    _k_w1.append(5)\n"), py)
        // A field that is no Mutable binding by its name; a Mutable one through a temporary, read once.
        assertTrue(py.contains("        self.items.append(1)\n"), py)
        assertTrue(py.contains("        _k_w0 = self.log\n        _k_w0.append(2)\n"), py)
    }

    // ---- copy() ------------------------------------------------------------------------------

    @Test
    fun copyIsRetiredForWith() {
        val p = snippet("main: () Void {\n    p: P { 1, 2 }\n    q: P { p.copy(x = 3) }\n}")
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
