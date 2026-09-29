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
 * The predicates one package writes and the others read (40-round3 R-C, R-G; 50-round4 2.3): which
 * `Fx` a call may run ([CallReach]), whether it is CONFINED ([CallReach.confined]) and whether C++
 * supplies a function's body ([suppliedByCpp]). Each is a table here, and each reader in this
 * package is checked to agree with it.
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

    /** CONFINED of every call of each callee [names] in [p] (the whole program), in source order. */
    private fun confined(p: TypedProgram, vararg names: String): Map<String, List<Boolean>> = names.associateWith { name ->
        BodyTestSupport.every<FunctionCallExpr>(p).mapNotNull { call ->
            val rc = p.model.calls[call]?.takeIf { it.fn?.name == name } ?: return@mapNotNull null
            CallReach.confined(rc, p.model)
        }
    }

    @Test
    fun confinedOverOneCalleeOfEachKindBothAnswers() {
        // 50-round4 2.3's table, each kind with both answers where it has two: an Fx argument (a lambda literal or a
        // function named as a value, CONFINED or not; a variable never), a Kira body (EffectsPass's fixpoint, mutual
        // recursion included, a call of its own Fx parameter charged at its call site), a stdlib binding (a mutator on a
        // local; RUNS_OPERATORS over a user class), trace, what C++ supplies (given nothing; a lambda, CONFINED or not,
        // which C++ may call with what it chooses; a class that may hold an Fx), and the dispatched kinds, which never are.
        val p = snippet(
            """
            pub mut G: Int32 = 0
            pub class K {
                pub n: Int32 = 0
                pub fx get: () Int32 {
                    return n
                }
            }
            pub class Base {
                pub fx act: () Int32 {
                    return 0
                }
            }
            pub class Sub: Base {
                override pub fx act: () Int32 {
                    G += 1
                    return 1
                }
            }
            pub trait T {
                pub fx m: () Int32;
            }
            pub fx pure: () Int32 {
                return 1
            }
            pub fx writesG: () Int32 {
                G += 1
                return 1
            }
            pub fx writesOwn: (mut x: Int32) Int32 {
                x += 1
                return x
            }
            pub fx apply: (f: Fx<Tuple0, Int32>) Int32 {
                return f()
            }
            pub fx swapRun: (mut f: Fx<Tuple0, Int32>) Int32 {
                f = writesG
                return f()
            }
            pub fx ping: (n: Int32) Int32 {
                if n > 0 {
                    return pong(n - 1)
                }
                return 0
            }
            pub fx pong: (n: Int32) Int32 {
                mut k: Int32 = n
                k += 1
                return ping(k - 2)
            }
            pub fx virt: (b: Base) Int32 {
                return b.act()
            }
            pub fx ext: (n: Int32, s: Str) Int32;
            pub fx extFx: (f: Fx<Tuple0, Int32>) Int32;
            pub fx extK: (k: K) Int32;
            pub fx caller: (f: Fx<Tuple0, Int32>, t: T, k: K, b: Base) Int32 {
                mut x: Int32 = 0
                mut xs: List<Int32> = List<Int32> { values = [1] }
                ks: List<K> = List<K> { values = [k] }
                a: Int32 = pure() + writesG() + writesOwn(mut x)
                c: Int32 = apply(fx() Int32 {
                    return 1
                }) + apply(fx() Int32 {
                    G += 1
                    return 1
                }) + apply(pure) + apply(writesG) + apply(f)
                d: Int32 = f() + t.m() + virt(b) + ping(3) + k.get()
                xs.add(1)
                e: Bool = ks.contains(k) || xs.contains(1)
                trace(a)
                g: Int32 = ext(1, "s") + extFx(fx() Int32 {
                    return 1
                }) + extFx(fx() Int32 {
                    G += 1
                    return 1
                }) + extK(k)
                return a + c + d + g
            }
            """,
        )
        assertTrue(p.diagnostics.none { it.isError }, TyperTestSupport.render(p))
        val want = mapOf(
            "pure" to listOf(true), "writesG" to listOf(false), "writesOwn" to listOf(true),
            "apply" to listOf(true, false, true, false, false),
            "m" to listOf(false), "act" to listOf(false), "virt" to listOf(false), "ping" to listOf(true, true), "pong" to listOf(true), "get" to listOf(true),
            "add" to listOf(true), "contains" to listOf(false, true), "trace" to emptyList(),
            "ext" to listOf(true), "extFx" to listOf(false, false), "extK" to listOf(false),
        )
        assertEquals(want, confined(p, *want.keys.toTypedArray()))
        // trace has no FnSymbol (PRINT), and f() none either (FN_VALUE): judged by kind. apply's own f() is never
        // CONFINED at a call site either; only apply's fixpoint counts it, charged where apply is handed its Fx.
        val byKind = BodyTestSupport.every<FunctionCallExpr>(p).mapNotNull { call ->
            val rc = p.model.calls[call]?.takeIf { it.fn == null } ?: return@mapNotNull null
            rc.kind.name to CallReach.confined(rc, p.model)
        }
        assertEquals(listOf("FN_VALUE" to false, "FN_VALUE" to false, "FN_VALUE" to false, "PRINT" to true), byKind)
        // A mut Fx parameter is no charge of the call site's: swapRun reassigns it before it runs it.
        assertEquals(
            mapOf("apply" to true, "writesOwn" to true, "writesG" to false, "virt" to false, "swapRun" to false),
            listOf("apply", "writesOwn", "writesG", "virt", "swapRun").associateWith { p.model.fnConfined[RulesTestSupport.fn(p, it)] },
        )
    }

    @Test
    fun anFxGivenForATypeParameterIsChargedAndAGenericThatRunsItsTIsNotConfined() {
        // 2.3 row 1 (w2-5 round-4 #0): every Fx argument is charged, one given for a T included (add's value, keepG's
        // x). A call of an Fx value charges its T argument too, since a T may be an Fx (mayHoldFx): pass and Box.run
        // hand their T to their own Fx parameter, so neither is CONFINED, whatever its caller gives. keepG only keeps
        // its T, and stays CONFINED.
        val p = snippet(
            """
            pub mut G: Int32 = 0
            pub fx pass<T>: (f: Fx<Tuple1<T>, Void>, x: T) Void {
                f(x)
            }
            pub fx keepG<T>: (mut xs: List<T>, x: T) Void {
                xs.add(x)
            }
            pub class Box<T> {
                require pub v: T
                pub fx run: (f: Fx<Tuple1<T>, Void>) Void {
                    f(this.v)
                }
            }
            pub fx drive: () Void {
                mut fs: List<Fx<Tuple0, Void>> = []
                mut ns: List<Int32> = []
                keepG<Fx<Tuple0, Void>>(mut fs, fx () Void {
                    G += 1
                })
                keepG<Fx<Tuple0, Void>>(mut fs, fx () Void {
                    trace(1)
                })
                keepG<Int32>(mut ns, 1)
                fs.add(fx () Void {
                    G += 1
                })
                fs.add(fx () Void {
                    trace(1)
                })
                pass<Int32>(fx (n: Int32) Void {
                    trace(n)
                }, 1)
            }
            """,
        )
        assertTrue(p.diagnostics.none { it.isError }, TyperTestSupport.render(p))
        assertEquals(
            // add: keepG's own xs.add(x) (a T, which a binding only keeps), then the two in drive.
            mapOf("keepG" to listOf(false, true, true), "add" to listOf(true, false, true), "pass" to listOf(false)),
            confined(p, "keepG", "add", "pass"),
        )
        assertEquals(
            mapOf("pass" to false, "keepG" to true),
            listOf("pass", "keepG").associateWith { p.model.fnConfined[RulesTestSupport.fn(p, it)] },
        )
        assertEquals(false, p.model.fnConfined[RulesTestSupport.method(p, "Box", "run")])
    }

    @Test
    fun aConstructionOrADropThatMayRunCodeIsNotConfined() {
        // A class construction runs its chain's initially and the defaults it leaves out; a drop may run an IMPURE
        // finally (the local d in keep, the elements clear drops), which may write anything.
        val p = snippet(
            """
            pub mut G: Int32 = 0
            pub class Init {
                pub n: Int32 = 0
                initially {
                    G += 1
                }
            }
            pub class Kid: Init {
                pub k: Int32 = 0
            }
            pub class Plain {
                pub n: Int32 = 0
            }
            pub fx bumped: () Int32 {
                G += 1
                return G
            }
            pub class Defaulted {
                pub n: Int32 = bumped()
            }
            pub class Dropper {
                pub n: Int32 = 0
                finally {
                    G += 1
                }
            }
            pub fx keep: () Int32 {
                d: Dropper = Dropper {}
                return d.n
            }
            pub fx clearAll: (mut ds: List<Dropper>) Void {
                ds.clear()
            }
            pub fx count: (mut xs: List<Int32>) Void {
                xs.add(1)
            }
            """,
        )
        assertTrue(p.diagnostics.none { it.isError }, TyperTestSupport.render(p))
        val built = listOf("Init", "Kid", "Plain", "Defaulted").associateWith { CallReach.construction(RulesTestSupport.cls(p, it), emptySet(), p.model) }
        assertEquals(mapOf("Init" to false, "Kid" to false, "Plain" to true, "Defaulted" to false), built)
        assertEquals(mapOf("clear" to listOf(false), "add" to listOf(true)), confined(p, "clear", "add"))
        assertEquals(mapOf("keep" to false, "clearAll" to false, "count" to true), listOf("keep", "clearAll", "count").associateWith { p.model.fnConfined[RulesTestSupport.fn(p, it)] })
    }

    @Test
    fun noOtherFileDefinesWhetherACallMayRunCode() {
        // 50-round4 7.3: CallReach.confined is the one answer; EffectsPass's fixpoint fills the tables it reads.
        val main = File("src/main/kotlin")
        val defs = main.walkTopDown().filter { it.name.endsWith(".kt") }.filter { f -> Regex("""fun\s+(confined|isConfined|fnConfined)\b""").containsMatchIn(f.readText()) }
        assertEquals(listOf("RuleSupport.kt"), defs.map { it.name }.toList())
    }
}
