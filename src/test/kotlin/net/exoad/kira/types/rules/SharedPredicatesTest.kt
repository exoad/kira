package net.exoad.kira.types.rules

import net.exoad.kira.compiler.analysis.types.Effect
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.rules.CallReach
import net.exoad.kira.compiler.analysis.types.suppliedByCpp
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.types.TyperTestSupport
import net.exoad.kira.types.body.BodyTestSupport
import net.exoad.kira.types.rules.RulesTestSupport.snippet
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The predicates one package writes and the others read (40-round3 R-C, R-G): which `Fx` a call
 * may run ([CallReach]) and whether C++ supplies a function's body ([suppliedByCpp]). Each is a
 * table here, and each reader in this package is checked to agree with it.
 */
class SharedPredicatesTest {
    @Test
    fun mayHoldFxOverEveryKindOfType() {
        val p = snippet(
            """
            pub struct Plain {
                pub a: Int32 = 0
                pub s: Str = ""
            }
            pub struct WithFx {
                pub a: Int32 = 0
                pub f: Maybe<Fx<Tuple0, Void>> = null
            }
            pub struct Deep {
                pub w: List<WithFx> = List<WithFx> { }
            }
            pub struct Gen<T> {
                pub t: Maybe<T> = null
            }
            pub class C {
                pub a: Int32 = 0
            }
            pub trait T {
                pub fx m: () Int32;
            }
            pub enum E: Int32 { E_A = 0, E_B = 1 }
            pub fx table<X>: (a: Int32, b: Str, c: Bool, d: E, e: List<Int32>, f: Map<Str, List<Int32>>, g: Arr<UInt8, 4>, h: Maybe<Str>, i: Tuple2<Int32, Str>, j: Plain, k: View<UInt8>, l: StrBuf<8>, m: CStr, n: Fx<Tuple0, Void>, o: List<Fx<Tuple0, Void>>, q: Maybe<Fx<Tuple0, Void>>, r: Tuple2<Int32, Fx<Tuple0, Void>>, s: WithFx, t: Deep, u: C, v: T, w: Ref<Int32>, x: X, y: View<Fx<Tuple0, Void>>, z: Gen<Int32>, zz: Gen<Fx<Tuple0, Void>>) Int32 {
                return 0
            }
            """,
        )
        assertTrue(p.diagnostics.none { it.isError && !it.code.startsWith("rules.") }, TyperTestSupport.render(p))
        val holds = RulesTestSupport.fn(p, "table").params.associate { it.name to CallReach.mayHoldFx(it.type) }
        val want = mapOf(
            "a" to false, "b" to false, "c" to false, "d" to false, "e" to false, "f" to false, "g" to false, "h" to false,
            "i" to false, "j" to false, "k" to false, "l" to false, "m" to false,
            "n" to true, "o" to true, "q" to true, "r" to true, "s" to true, "t" to true, "u" to true, "v" to true, "w" to true,
            "x" to true, "y" to true, "z" to false, "zz" to true,
        )
        assertEquals(want, holds)
    }

    @Test
    fun noOtherPackageFileDefinesItsOwnFxPredicate() {
        // R-C's static check on this branch: EffectsPass asks CallReach, and no rules file defines another answer.
        val rules = File("src/main/kotlin/net/exoad/kira/compiler/analysis/types/rules")
        val defs = rules.listFiles().orEmpty().filter { it.name.endsWith(".kt") }.filter { f -> Regex("""fun\s+(mayHoldFx|fxArgs|fxArguments)\b""").containsMatchIn(f.readText()) }
        assertEquals(listOf("RuleSupport.kt"), defs.map { it.name })
    }

    private val callees = """
        @_extern(cpp = "ext::ext", header = "ext.hxx")
        pub fx ext: (p: Unsafe<Int32>) Int32;
        pub fx proto: (p: Unsafe<Int32>) Int32;
        pub @_opaque class Handle {
            pub fx op: (p: Unsafe<Int32>) Int32;
        }
        pub class Slot {
            pub fx slot: (p: Unsafe<Int32>) Int32;
        }
        pub fx kira: (p: Unsafe<Int32>) Int32 {
            return 0
        }
    """

    @Test
    fun theReadersOfSuppliedByCppAgree() {
        // R-G: one callee of each kind. ViewPass lets an Unsafe parameter stand only on what C++ supplies (1.4), and
        // EffectsPass makes every call of it IMPURE; CallReach counts its body unknown. A Kira body, a slot a
        // construction fills and a stdlib binding are none of these.
        val p = snippet(
            callees + """
            pub fx caller: (h: Handle, s: Slot, xs: List<Int32>) Int32 {
                mut ys: List<Int32> = List<Int32> { values = [1] }
                return ext(ys.view()) + proto(ys.view()) + h.op(ys.view()) + s.slot(ys.view()) + kira(ys.view()) + xs.get(0)
            }
            """,
        )
        val unsafeRefused = p.diagnostics.filter { it.code == "rules.view.unsafe" }.map { it.message.substringAfter("of '").substringBefore("'") }.toSet()
        val want = mapOf("ext" to true, "proto" to true, "op" to true, "slot" to false, "kira" to false, "get" to false)
        val got = mutableMapOf<String, Boolean>()
        for (name in want.keys) {
            val call = BodyTestSupport.every<FunctionCallExpr>(p).firstOrNull { callee(p, it)?.name == name } ?: error("no call of $name")
            val rc = p.model.calls[call]!!
            val fn = rc.fn!!
            val supplied = fn.suppliedByCpp
            got[name] = supplied
            if (name != "get") {
                assertEquals(supplied, name !in unsafeRefused, "ViewPass on $name: ${TyperTestSupport.render(p)}")
            }
            if (supplied) {
                assertEquals(Effect.IMPURE, p.model.effect(call), "EffectsPass on $name")
            }
            if (name != "slot") {
                assertEquals(supplied, CallReach.bodyUnknown(rc, p.model), "CallReach on $name")
            }
        }
        assertEquals(want, got)
        assertEquals(Effect.PURE, p.model.effect(RulesTestSupport.fn(p, "kira")))
    }

    private fun callee(p: TypedProgram, call: FunctionCallExpr): FnSymbol? = p.model.calls[call]?.fn

    @Test
    fun whatACallMayRunFollowsTheFxItsArgumentsAndReceiverMayHold() {
        // R-C: an extern given no argument that may hold an Fx runs only its named writes; given a lambda, a List of
        // them, or a class (whose field may hold one), it may run anything. A Kira function's body is known.
        val p = snippet(
            """
            pub class Box {
                pub f: Maybe<Fx<Tuple0, Void>> = null
            }
            @_extern(cpp = "ext::a", header = "ext.hxx")
            pub fx a: (n: Int32, s: Str) Int32;
            @_extern(cpp = "ext::b", header = "ext.hxx")
            pub fx b: (fs: List<Fx<Tuple0, Void>>) Int32;
            @_extern(cpp = "ext::c", header = "ext.hxx")
            pub fx c: (x: Box) Int32;
            @_extern(cpp = "ext::d", header = "ext.hxx")
            pub fx d: (f: Fx<Tuple0, Void>) Int32;
            pub fx k: (x: Box) Int32 {
                return 0
            }
            pub fx caller: (bx: Box) Int32 {
                fs: List<Fx<Tuple0, Void>> = List<Fx<Tuple0, Void>> { }
                return a(1, "s") + b(fs) + c(bx) + d(fx () Void { }) + k(bx)
            }
            """,
        )
        assertTrue(p.diagnostics.none { it.isError }, TyperTestSupport.render(p))
        val runs = BodyTestSupport.every<FunctionCallExpr>(p).mapNotNull { call ->
            val rc = p.model.calls[call] ?: return@mapNotNull null
            rc.fn?.name?.takeIf { it in setOf("a", "b", "c", "d", "k") }?.let { it to CallReach.mayRunAnything(rc, p.model) }
        }.toMap()
        assertEquals(mapOf("a" to false, "b" to true, "c" to true, "d" to true, "k" to false), runs)
    }
}
