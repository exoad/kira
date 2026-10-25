package net.exoad.kira.types.rules

import net.exoad.kira.compiler.analysis.types.Effect
import net.exoad.kira.compiler.analysis.types.FieldInit
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.rules.CallReach
import net.exoad.kira.compiler.analysis.types.suppliedByCpp
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
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
            pub Plain: Struct {
                pub a: Int32 = 0
                pub s: Str = ""
            }
            pub WithFx: Struct {
                pub a: Int32 = 0
                pub f: Maybe<Fx<Tuple0, Void>> = null
            }
            pub Deep: Mutable<Any> {
                pub w: List<WithFx> = List<WithFx> { }
            }
            pub Gen<T>: Struct {
                pub t: Maybe<T> = null
            }
            pub C: Any {
                pub a: Int32 = 0
            }
            pub T: Any {
                pub m: () Int32;
            }
            pub table<X>: (a: Int32, b: Str, c: Bool, d: Int32, e: List<Int32>, f: Map<Str, List<Int32>>, g: Arr<UInt8, 4>, h: Maybe<Str>, i: Tuple2<Int32, Str>, j: Plain, k: View<UInt8>, l: StrBuf<8>, m: CStr, n: Fx<Tuple0, Void>, o: List<Fx<Tuple0, Void>>, q: Maybe<Fx<Tuple0, Void>>, r: Tuple2<Int32, Fx<Tuple0, Void>>, s: WithFx, t: Deep, u: C, v: T, w: Ref<Int32>, x: X, y: View<Fx<Tuple0, Void>>, z: Gen<Int32>, zz: Gen<Fx<Tuple0, Void>>) Int32 {
                0
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
        pub @_extern(cpp = "ext::ext", header = "ext.hxx") ext: (p: Unsafe<Int32>) Int32;
        pub proto: (p: Unsafe<Int32>) Int32;
        pub @_opaque Handle: Any {
            pub op: (p: Unsafe<Int32>) Int32;
        }
        pub Slot: Any {
            pub slot: (p: Unsafe<Int32>) Int32;
        }
        pub kira: (p: Unsafe<Int32>) Int32 {
            0
        }
    """

    @Test
    fun theReadersOfSuppliedByCppAgree() {
        // R-G: one callee of each kind. ViewPass lets an Unsafe parameter stand only on what C++ supplies (1.4), and
        // EffectsPass makes every call of it IMPURE; CallReach counts its body unknown. A Kira body, a slot a
        // construction fills and a stdlib binding are none of these.
        val p = snippet(
            callees + """
            pub caller: (h: Handle, s: Slot, xs: List<Int32>) Int32 {
                ys: Mutable<List<Int32>> = List<Int32> { values = @arrOf(1) }
                ext(ys.view()) + proto(ys.view()) + h.op(ys.view()) + s.slot(ys.view()) + kira(ys.view()) + xs.get(0)
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
            pub Box: Any {
                pub f: Maybe<Fx<Tuple0, Void>> = null
            }
            pub @_extern(cpp = "ext::a", header = "ext.hxx") a: (n: Int32, s: Str) Int32;
            pub @_extern(cpp = "ext::b", header = "ext.hxx") b: (fs: List<Fx<Tuple0, Void>>) Int32;
            pub @_extern(cpp = "ext::c", header = "ext.hxx") c: (x: Box) Int32;
            pub @_extern(cpp = "ext::d", header = "ext.hxx") d: (f: Fx<Tuple0, Void>) Int32;
            pub k: (x: Box) Int32 {
                0
            }
            pub caller: (bx: Box) Int32 {
                fs: List<Fx<Tuple0, Void>> = List<Fx<Tuple0, Void>> { }
                a(1, "s") + b(fs) + c(bx) + d(() Void { }) + k(bx)
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
    fun anFxGivenForATypeParameterIsOnlyKeptAndAGenericThatRunsItsTIsNotConfined() {
        // 2.3 row 1 (w2-5 round-4 #0, round 5b): an Fx argument is charged where the callee declares the parameter Fx,
        // and not where it takes it as a T (add's value, keepG's x): storing an Fx never runs it (7.1's r4d/p3, p5). A
        // call of an Fx value charges its T argument, since a T may be an Fx (mayHoldFx): pass and Box.run hand their T
        // to their own Fx parameter, so neither is CONFINED, whatever its caller gives. keepG only keeps its T, and
        // stays CONFINED.
        val p = snippet(
            """
            pub G: Mutable<Int32> = 0
            pub pass<T>: (f: Fx<Tuple1<T>, Void>, x: T) Void {
                f(x)
            }
            pub keepG<T>: (xs: List<T>, x: T) Void {
                xs.add(x)
            }
            pub Box<T>: Any {
                pub v: T
                pub run: (f: Fx<Tuple1<T>, Void>) Void {
                    f(this.v)
                }
            }
            pub drive: () Void {
                fs: Mutable<List<Fx<Tuple0, Void>>> = @listOf()
                ns: Mutable<List<Int32>> = @listOf()
                keepG<Fx<Tuple0, Void>>(fs, () Void {
                    G += 1
                })
                keepG<Fx<Tuple0, Void>>(fs, () Void {
                    trace(1)
                })
                keepG<Int32>(ns, 1)
                fs.add(() Void {
                    G += 1
                })
                fs.add(() Void {
                    trace(1)
                })
                pass<Int32>((n: Int32) Void {
                    trace(n)
                }, 1)
            }
            """,
        )
        assertTrue(p.diagnostics.none { it.isError }, TyperTestSupport.render(p))
        assertEquals(
            // add: keepG's own xs.add(x), then the two in drive; each is a T, which a binding only keeps.
            mapOf("keepG" to listOf(true, true, true), "add" to listOf(true, true, true), "pass" to listOf(false)),
            confined(p, "keepG", "add", "pass"),
        )
        assertEquals(
            mapOf("pass" to false, "keepG" to true),
            listOf("pass", "keepG").associateWith { p.model.fnConfined[RulesTestSupport.fn(p, it)] },
        )
        assertEquals(false, p.model.fnConfined[RulesTestSupport.method(p, "Box", "run")])
    }

    @Test
    fun aConstructionThatMayRunCodeIsNotConfined() {
        // A class construction runs its chain's initially and the defaults it leaves out, which may write anything.
        val p = snippet(
            """
            pub G: Mutable<Int32> = 0
            pub Init: Any {
                pub n: Int32 = 0
                initially {
                    G += 1
                }
            }
            pub Kid: Init {
                pub k: Int32 = 0
            }
            pub Plain: Any {
                pub n: Int32 = 0
            }
            pub bumped: () Int32 {
                G += 1
                G
            }
            pub Defaulted: Any {
                pub n: Int32 = bumped()
            }
            pub count: (xs: List<Int32>) Void {
                xs.add(1)
            }
            """,
        )
        assertEquals(emptyList(), p.diagnostics.filter { it.isError }.map { it.code }, TyperTestSupport.render(p))
        val built = listOf("Init", "Kid", "Plain", "Defaulted").associateWith { CallReach.construction(RulesTestSupport.cls(p, it), emptyMap(), p.model) }
        assertEquals(mapOf("Init" to false, "Kid" to false, "Plain" to true, "Defaulted" to false), built)
        assertEquals(mapOf("add" to listOf(true)), confined(p, "add"))
        assertEquals(mapOf("count" to true), listOf("count").associateWith { p.model.fnConfined[RulesTestSupport.fn(p, it)] })
    }

    @Test
    fun whichFxFieldsAConstructionRunsIsDecidedFromTheClass() {
        // Round 6: CallReach.runsAtConstruction, read by the construction row of CONFINED and by EffectsPass. A magic class
        // runs every field it declares Fx (C++ supplies the constructor; kira:sync's Thread starts body there), and keeps a
        // field of a type parameter (Mutex's value). A user class runs its chain's Fx fields only when an initially is in
        // the chain; one that only stores an Fx field runs nothing until a later call of it, charged where it runs.
        val p = snippet(
            """
            kira.sync as sync
            pub Runner: Any {
                pub f: Fx<Tuple0, Void>
                initially {
                    f()
                }
            }
            pub Kid: Runner {
                pub g: Fx<Tuple0, Void>
                pub n: Int32 = 0
            }
            pub Keeper: Any {
                pub f: Fx<Tuple0, Void>
                pub n: Int32 = 0
            }
            pub Counted: Mutable<Any> {
                pub n: Mutable<Int32> = 0
                initially {
                    n = 1
                }
            }
            """,
        )
        assertTrue(p.diagnostics.none { it.isError }, TyperTestSupport.render(p))
        val names = { cls: String, uri: String -> CallReach.runsAtConstruction(RulesTestSupport.cls(p, cls, uri)).map { it.name } }
        assertEquals(listOf("body"), names("Thread", "kira:sync"))
        assertEquals(emptyList(), names("Mutex", "kira:sync"))
        assertEquals(emptyList(), names("Atomic", "kira:sync"))
        assertEquals(listOf("f"), names("Runner", "test:main"))
        assertEquals(listOf("g", "f"), names("Kid", "test:main"))
        assertEquals(emptyList(), names("Keeper", "test:main"))
        assertEquals(emptyList(), names("Counted", "test:main"))
        // The construction row still refuses any initially, and charges what a Thread is given for body as row 1 does: a
        // lambda literal or a named function whose body is CONFINED passes, a hook from a List never does.
        assertEquals(true, CallReach.construction(RulesTestSupport.cls(p, "Keeper"), emptyMap(), p.model))
        assertEquals(false, CallReach.construction(RulesTestSupport.cls(p, "Runner"), emptyMap(), p.model))
        val t = snippet(
            """
            kira.sync as sync
            pub G: Mutable<Int32> = 0
            pub hooks: Mutable<List<Fx<Tuple0, Void>>> = @listOf()
            pub quiet: () Void {
            }
            pub loud: () Void {
                G = 1
            }
            pub drive: () Void {
                a: Thread = Thread { name = "a", body = () Void { } }
                b: Thread = Thread { name = "b", body = () Void { G = 1 } }
                c: Thread = Thread { name = "c", body = hooks[0] }
                d: Thread = Thread { name = "d", body = quiet }
                e: Thread = Thread { name = "e", body = loud }
            }
            """,
        )
        assertTrue(t.diagnostics.none { it.isError }, TyperTestSupport.render(t))
        val built = BodyTestSupport.every<ObjectInitExpr>(t).mapNotNull { e ->
            val ri = t.model.inits[e]?.takeIf { it.cls?.name == "Thread" } ?: return@mapNotNull null
            CallReach.construction(ri.cls, ri.fields.filterIsInstance<FieldInit.Given>().associate { it.field to it.expr }, t.model)
        }
        assertEquals(listOf(true, false, false, true, false), built)
    }

    @Test
    fun noOtherFileDefinesWhetherACallMayRunCode() {
        // 50-round4 7.3: CallReach.confined is the one answer; EffectsPass's fixpoint fills the tables it reads.
        val main = File("src/main/kotlin")
        val defs = main.walkTopDown().filter { it.name.endsWith(".kt") }.filter { f -> Regex("""fun\s+(confined|isConfined|fnConfined)\b""").containsMatchIn(f.readText()) }
        assertEquals(listOf("RuleSupport.kt"), defs.map { it.name }.toList())
    }
}
