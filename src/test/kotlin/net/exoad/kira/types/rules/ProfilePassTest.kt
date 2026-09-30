package net.exoad.kira.types.rules

import net.exoad.kira.compiler.analysis.types.TyperMode
import net.exoad.kira.compiler.analysis.types.TyperOptions
import net.exoad.kira.types.TyperTestSupport
import net.exoad.kira.types.rules.RulesTestSupport.errors
import net.exoad.kira.types.rules.RulesTestSupport.expectClean
import net.exoad.kira.types.rules.RulesTestSupport.expectExactly
import net.exoad.kira.types.rules.RulesTestSupport.message
import net.exoad.kira.types.rules.RulesTestSupport.modules
import net.exoad.kira.types.rules.RulesTestSupport.snippet
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfilePassTest {
    private val pico = TyperOptions(freestanding = listOf("test:main"))

    // ---- positive ----------------------------------------------------------------------------

    @Test
    fun theSubsetIsAllowed() {
        expectClean(
            snippet(
                """
                pub enum Mode: Int32 {
                    MODE_A = 0
                }
                pub struct Frame {
                    pub bytes: Arr<UInt8, 8> = [0, 0, 0, 0, 0, 0, 0, 0]
                    pub mode: Mode = Mode.MODE_A
                    pub mut fx clear: () Void {
                        bytes[0] = 0
                    }
                }
                pub mut STATE: Int32 = 0
                pub trait Show {
                    pub fx code: () Int32;
                }
                pub @_const fx twice: (v: Int32) Int32 {
                    return v * 2
                }
                pub fx each: (buf: View<UInt8>, f: Fx<Tuple1<UInt8>, Void>) Void {
                    for b: UInt8 in buf {
                        f(b)
                    }
                }
                pub fx reply: (servo: Int32, mut out: StrBuf<32>) Maybe<Tuple2<Int32, Int32>> {
                    out.set("OK drive servo=${'$'}{servo}")
                    if servo < 0 {
                        return null
                    }
                    return Tuple2<Int32, Int32> { servo, servo }
                }
                pub fx pick<T: Show>: (s: T) Int32 {
                    return s.code()
                }
                pub fx fail: () Void {
                    throw "boom"
                }
                """,
                options = pico,
            ),
        )
    }

    @Test
    fun theTextGoldenIsFreestanding() {
        // lib:text is the corpus's freestanding module: View<Char> and StrBuf in place of Str.
        val dir = File("src/test/resources/cpp-golden/text")
        val p = TyperTestSupport.project(dir, TyperMode.STRICT, TyperOptions(freestanding = listOf("lib:text")))
        expectClean(p)
    }

    @Test
    fun aHostedModuleIsNotChecked() {
        expectClean(
            snippet(
                """
                use "kira:sync"
                pub fx f: (s: Str, xs: List<Int32>) Str {
                    return "${'$'}{s} ${'$'}{xs.size()}"
                }
                """,
                options = TyperOptions(freestanding = listOf("other:module")),
            ),
        )
    }

    // ---- negative ----------------------------------------------------------------------------

    @Test
    fun heapTypesAreRefusedWithTheirReplacement() {
        val p = snippet(
            """
            pub class Node {
                pub n: Int32 = 0
            }
            pub trait Show {
                pub fx code: () Int32;
            }
            pub fx f: (s: Str, xs: List<Int32>, m: Maybe<Str>, a: Arr<Int32>, r: Ref<Int32>, sh: Show, node: Node) Void {
            }
            """,
            options = pico,
        )
        expectExactly(
            p,
            "rules.profile.class",
            "rules.profile.type", "rules.profile.type", "rules.profile.type", "rules.profile.type", "rules.profile.type", "rules.profile.type", "rules.profile.type",
        )
        val messages = p.diagnostics.map { it.message }
        assertTrue(messages.any { it.contains("Str allocates; use View<Char>") && it.contains("StrBuf<N>") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.contains("List<T> allocates; use Arr<T, N>") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.contains("Arr<T> without a count") }, messages.joinToString("\n"))
        assertTrue(messages.any { it.contains("trait-typed value") }, messages.joinToString("\n"))
        assertTrue(messages.none { it.contains("Maybe<Str>") }, "Maybe<Str> is reported at its Str, not at the Maybe")
        assertEquals(2, messages.count { it.startsWith("Str is not in the freestanding subset") }, "s: Str and the Str of m: Maybe<Str>")
    }

    @Test
    fun hostedConstructsAreRefused() {
        val p = snippet(
            """
            use "kira:os"
            pub fx f: (g: Fx<Tuple1<Int32>, Int32>) Fx<Tuple1<Int32>, Int32> {
                try {
                    trace(1)
                } on e: Str {
                }
                t: Str = "n=${'$'}{1}"
                return g
            }
            pub fx g: () Int64 {
                return monoNowMs()
            }
            """,
            options = pico,
        )
        val codes = errors(p)
        assertEquals(2, codes.count { it == "rules.profile.module" }, "the use and the ambient monoNowMs: $codes")
        assertTrue("rules.profile.fx" in codes, codes.toString())
        assertTrue("rules.profile.try" in codes, codes.toString())
        assertTrue("rules.profile.interpolation" in codes, codes.toString())
        assertEquals(3, codes.count { it == "rules.profile.type" }, "the Fx return, the handler's Str and the Str local: $codes")
        assertTrue(codes.all { it.startsWith("rules.profile.") }, codes.toString())
        assertTrue(message(p, "rules.profile.fx").contains("template parameter"))
    }

    @Test
    fun aStrConcatenationAllocatesWithoutSpellingStr() {
        val p = snippet(
            """
            pub fx g: () Void {
                trace("a" + "b")
                trace("a" + "b" + "c")
            }
            """,
            options = pico,
        )
        expectExactly(p, "rules.profile.type", "rules.profile.type")
        assertTrue(message(p, "rules.profile.type").contains("Str concatenation allocates"))
    }

    @Test
    fun whatAFreestandingModuleUsesIsCheckedToo() {
        val p = modules(
            "test:util" to """
                pub fx label: (n: Int32) Str {
                    return n as Str
                }
            """,
            "test:main" to """
                use "test:util"
                pub fx f: () Int32 {
                    return 1
                }
            """,
            options = pico,
        )
        assertEquals(listOf("rules.profile.type", "rules.profile.type"), errors(p), TyperTestSupport.render(p))
        assertTrue(p.diagnostics.all { it.source?.file?.endsWith("util.kira") == true }, TyperTestSupport.render(p))
    }
}
