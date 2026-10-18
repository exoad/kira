package net.exoad.kira.types.rules

import net.exoad.kira.compiler.analysis.types.Effect
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TypeCastExpr
import net.exoad.kira.types.body.BodyTestSupport
import net.exoad.kira.types.rules.RulesTestSupport.expectClean
import net.exoad.kira.types.rules.RulesTestSupport.fn
import net.exoad.kira.types.rules.RulesTestSupport.method
import net.exoad.kira.types.rules.RulesTestSupport.snippet
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class EffectsPassTest {
    // ---- positive: pure ------------------------------------------------------------------------

    @Test
    fun arithmeticLocalsAndPureCalleesArePure() {
        val p = snippet(
            """
            pub LIMIT: Int32 = 3
            pub P: Struct {
                pub x: Int32 = 0
                pub doubled: () Int32 {
                    return x * 2
                }
            }
            pub twice: (v: Int32) Int32 {
                return v * 2
            }
            pub quad: (v: Int32) Int32 {
                mut acc: Int32 = twice(v)
                acc += twice(v)
                mut p: P = P {}
                p.x = acc
                mut xs: Arr<Int32, 2> = @arrOf(1, 2)
                xs[0] = p.x
                return xs[0] + LIMIT + p.doubled()
            }
            pub len: (s: Str, xs: List<Int32>) Size {
                mut ys: List<Int32> = List<Int32> {}
                return s.length() + xs.size() + ys.size()
            }
            """,
        )
        expectClean(p)
        assertEquals(Effect.PURE, p.model.effect(fn(p, "twice")))
        // Round 3, issue 9: a struct method's `this` and a Str or List parameter are `const&` to the caller's
        // storage (design 5.1), so reading them is READS, and a caller of such a function is READS too; a
        // local's own List is the function's alone.
        assertEquals(Effect.READS, p.model.effect(fn(p, "quad")), "quad calls a struct method, which reads through its const S& this")
        assertEquals(Effect.READS, p.model.effect(fn(p, "len")), "Str.length and List.size are marked pure, but s and xs are the caller's storage")
        assertEquals(Effect.PURE, p.model.effect(BodyTestSupport.node<FunctionCallExpr>(p, "ys.size()")), "a pure binding on a local receiver")
        assertEquals(Effect.PURE, p.model.effect(BodyTestSupport.node<FunctionCallExpr>(p, "twice(v)")), "a pure callee on a scalar")
        assertEquals(Effect.READS, p.model.effect(method(p, "P", "doubled")), "a struct reads its fields through the reference its receiver is")
    }

    @Test
    fun mutualRecursionStaysPure() {
        val p = snippet(
            """
            pub even: (n: Int32) Bool {
                if n == 0 {
                    return true
                }
                return odd(n - 1)
            }
            pub odd: (n: Int32) Bool {
                if n == 0 {
                    return false
                }
                return even(n - 1)
            }
            """,
        )
        expectClean(p)
        assertEquals(Effect.PURE, p.model.effect(fn(p, "even")))
        assertEquals(Effect.PURE, p.model.effect(fn(p, "odd")))
    }

    @Test
    fun expressionsCarryTheirOwnEffect() {
        val p = snippet(
            """
            pub mut STATE: Int32 = 0
            pub bump: () Int32 {
                STATE += 1
                return STATE
            }
            pub twice: (v: Int32) Int32 {
                return v * 2
            }
            pub f: (a: Int32) Int32 {
                g: Fx<Tuple0, Int32> = () Int32 {
                    return bump()
                }
                return twice(a) + bump() + a * 2
            }
            """,
        )
        expectClean(p)
        val model = p.model
        assertEquals(Effect.PURE, model.effect(BodyTestSupport.node<FunctionCallExpr>(p, "twice(a)")))
        assertEquals(Effect.IMPURE, model.effect(BodyTestSupport.node<FunctionCallExpr>(p, "bump()")))
        assertEquals(Effect.PURE, model.effect(BodyTestSupport.node<BinaryExpr>(p, "a * 2")))
        assertEquals(Effect.IMPURE, model.effect(BodyTestSupport.node<BinaryExpr>(p, "twice(a) + bump() + a * 2")))
        assertEquals(Effect.PURE, model.effect(BodyTestSupport.every<LambdaExpr>(p).single()), "creating a closure is pure")
    }

    // ---- negative: impure ----------------------------------------------------------------------

    @Test
    fun writesOutsideTheLocalsAreImpure() {
        val p = snippet(
            """
            pub mut STATE: Int32 = 0
            pub C: Any {
                pub mut n: Int32 = 0
                pub mut bump: () Void {
                    n += 1
                }
                pub peek: () Int32 {
                    return n
                }
            }
            pub global: () Void {
                STATE = 1
            }
            pub param: (mut out: Int32) Void {
                out = 1
            }
            pub through: (c: C) Void {
                c.n = 2
            }
            pub viewed: (v: MutView<UInt8>) Void {
                v[0] = 1
            }
            pub ref: () Int32 {
                r: Ref<Int32> = Ref<Int32> { value = 0 }
                r.value = 1
                return r.value
            }
            """,
        )
        expectClean(p)
        val model = p.model
        assertEquals(Effect.IMPURE, model.effect(fn(p, "global")))
        assertEquals(Effect.IMPURE, model.effect(fn(p, "param")))
        assertEquals(Effect.IMPURE, model.effect(fn(p, "through")))
        assertEquals(Effect.IMPURE, model.effect(fn(p, "viewed")))
        assertEquals(Effect.IMPURE, model.effect(fn(p, "ref")))
        assertEquals(Effect.IMPURE, model.effect(method(p, "C", "bump")), "a mut fx writing its receiver")
        assertEquals(Effect.READS, model.effect(method(p, "C", "peek")), "a class reads its field through a reference a sibling could write: no effect, but READS")
    }

    @Test
    fun readsOfSharedStateAreReadsAndThrowsAreImpure() {
        // D33: an operand that observes what a sibling's effect could change is READS (two of them need no
        // ordering); one a try could catch is IMPURE.
        val p = snippet(
            """
            pub mut STATE: Int32 = 0
            pub C: Any {
                pub mut n: Int32 = 0
            }
            pub readG: () Int32 {
                return STATE
            }
            pub byRef: (mut x: Int32) Int32 {
                return x
            }
            pub element: (v: View<UInt8>) Int32 {
                return v[0] as Int32
            }
            pub through: (v: View<UInt8>) Size {
                return v.size()
            }
            pub field: (c: C) Int32 {
                return c.n
            }
            pub parse: (s: Str) Int32 {
                if s.isEmpty() {
                    throw "empty"
                }
                return 1
            }
            """,
        )
        expectClean(p)
        for (name in listOf("readG", "byRef", "element", "through", "field")) {
            assertEquals(Effect.READS, p.model.effect(fn(p, name)), name)
        }
        assertEquals(Effect.IMPURE, p.model.effect(fn(p, "parse")), "a throw")
    }

    @Test
    fun twoReadsNeedNoOrderButAReadBesideAnEffectDoes() {
        // The unilidar shape: `p[0] | p[1] << 8` on a View parameter is two READS, so the emitter leaves it bare;
        // `p[0] + bump()` is READS beside IMPURE, so it spills both; and READS flows up the call graph like IMPURE.
        val p = snippet(
            """
            pub mut STATE: Int32 = 0
            pub bump: () UInt32 {
                STATE += 1
                return 1
            }
            pub readU16: (p: View<UInt8>) UInt32 {
                return (p[0] as UInt32) | ((p[1] as UInt32) << 8)
            }
            pub mixed: (p: View<UInt8>) UInt32 {
                return (p[0] as UInt32) + bump()
            }
            pub viaCall: (p: View<UInt8>) UInt32 {
                return readU16(p) + 1
            }
            pub sizes: (out: Size, buf: View<UInt8>) Bool {
                return out == buf.size()
            }
            """,
        )
        expectClean(p)
        val model = p.model
        assertEquals(Effect.READS, model.effect(BodyTestSupport.node<TypeCastExpr>(p, "p[0] as UInt32")))
        assertEquals(Effect.READS, model.effect(BodyTestSupport.node<BinaryExpr>(p, "p[1] as UInt32 << 8")))
        assertEquals(Effect.READS, model.effect(BodyTestSupport.node<BinaryExpr>(p, "p[0] as UInt32 | p[1] as UInt32 << 8")))
        assertEquals(Effect.IMPURE, model.effect(BodyTestSupport.node<BinaryExpr>(p, "p[0] as UInt32 + bump()")))
        assertEquals(Effect.READS, model.effect(BodyTestSupport.node<FunctionCallExpr>(p, "readU16(p)")), "a call of a READS function on a view")
        assertEquals(Effect.READS, model.effect(BodyTestSupport.node<FunctionCallExpr>(p, "buf.size()")), "a pure binding on a view receiver reads what it borrows")
        assertEquals(Effect.READS, model.effect(fn(p, "readU16")))
        assertEquals(Effect.READS, model.effect(fn(p, "viaCall")))
        assertEquals(Effect.READS, model.effect(fn(p, "sizes")))
        assertEquals(Effect.IMPURE, model.effect(fn(p, "mixed")))
    }

    @Test
    fun impurityFlowsUpTheCallGraph() {
        val p = snippet(
            """
            pub mut STATE: Int32 = 0
            pub bump: () Int32 {
                STATE += 1
                return STATE
            }
            pub uses: (v: Int32) Int32 {
                return v + bump()
            }
            pub usesUses: (v: Int32) Int32 {
                return uses(v)
            }
            pub builds: () Size {
                mut xs: List<Int32> = List<Int32> {}
                xs.add(1)
                return xs.size()
            }
            """,
        )
        expectClean(p)
        assertEquals(Effect.IMPURE, p.model.effect(fn(p, "bump")))
        assertEquals(Effect.IMPURE, p.model.effect(fn(p, "uses")))
        assertEquals(Effect.IMPURE, p.model.effect(fn(p, "usesUses")))
        assertEquals(Effect.IMPURE, p.model.effect(fn(p, "builds")), "List.add is a mutator without a pure flag")
    }

    @Test
    fun aByValueParameterPassedByConstReferenceReadsTheCallersStorage() {
        // Round 3, issue 9: design 5.1 lowers a struct, Str, container, Maybe, tuple or class parameter to
        // `const T&`, which aliases the caller's argument, so a read of it is READS beside a sibling's effect
        // (`pair(p.a, bumpGS())` spills p.a first). A scalar or view parameter is copied and stays PURE.
        val p = snippet(
            """
            pub S: Struct {
                pub a: Int32 = 0
            }
            pub st: (p: S) Int32 {
                return p.a
            }
            pub str: (s: Str) Size {
                return s.size()
            }
            pub arr: (xs: Arr<Int32, 2>) Int32 {
                return xs[0]
            }
            pub sc: (n: Int32, v: View<Int32>) Int32 {
                return n + (v.size() as Int32)
            }
            """,
        )
        expectClean(p)
        val model = p.model
        assertEquals(Effect.READS, model.effect(fn(p, "st")), "a struct parameter is a const S&")
        assertEquals(Effect.READS, model.effect(fn(p, "str")), "a Str parameter is a const kira::Str&")
        assertEquals(Effect.READS, model.effect(fn(p, "arr")), "an Arr parameter is a const std::array&")
        assertEquals(Effect.READS, model.effect(fn(p, "sc")), "a view reads what it borrows")
        assertEquals(Effect.PURE, model.effect(BodyTestSupport.node<BinaryExpr>(p, "n + v.size() as Int32").leftExpr), "a scalar parameter is copied")
    }

    @Test
    fun printsExternsVirtualsAndFxValuesAreImpure() {
        val p = snippet(
            """
            pub @_extern(cpp = "ext::read", header = "ext.hxx") read: () Int32;
            pub Shape: Any {
                pub area: () Int32;
            }
            pub prints: (v: Int32) Void {
                trace(v)
            }
            pub externs: () Int32 {
                return read()
            }
            pub virtuals: (s: Shape) Int32 {
                return s.area()
            }
            pub values: (f: Fx<Tuple0, Int32>) Int32 {
                return f()
            }
            """,
        )
        expectClean(p)
        for (name in listOf("prints", "externs", "virtuals", "values")) {
            assertEquals(Effect.IMPURE, p.model.effect(fn(p, name)), name)
        }
    }

    // ---- conservative where the view rule needs it (decision 4b read literally) --------------------

    @Test
    fun externsPrototypesDispatchInitializersAndMutViewWritesAreImpure() {
        // Round 1's other shapes: an extern given an Fx (nested in an Arr), a bodiless prototype, a MutView of a
        // global formed in a callee that writes through it (P12), a construction whose initially writes a global
        // (P2f's Maker); and a pure binding that compares a user class's elements runs its operators.
        val p = snippet(
            """
            mut GXSS: List<List<Int32>> = List<List<Int32>> {}
            mut GD: Int32 = 0
            pub @_extern(cpp = "ext::applyAll", header = "ext.hxx") applyAll: (fs: Arr<Fx<Tuple0, Void>>) Int32;
            pub proto: () Int32;
            pub Maker: Any {
                pub mut n: Int32 = 0
                initially {
                    GD = 1
                }
            }
            pub Id: Any {
                pub k: Int32 = 0
            }
            pub clobber: (mv: MutView<List<Int32>>) Int32 {
                mv[0] = List<Int32> {}
                return 0
            }
            pub formsAndLends: () Int32 {
                return clobber(GXSS.view())
            }
            pub nested: () Int32 {
                return applyAll(@arrOf(() Void {}))
            }
            pub prototype: () Int32 {
                return proto()
            }
            pub made: () Int32 {
                return (Maker {}).n
            }
            pub finds: (ids: List<Id>, id: Id, ns: List<Int32>) Bool {
                return ids.contains(id)
            }
            pub findsInts: (ns: List<Int32>) Bool {
                return ns.contains(3)
            }
            """,
        )
        for (name in listOf("clobber", "formsAndLends", "nested", "prototype", "made", "finds")) {
            assertEquals(Effect.IMPURE, p.model.effect(fn(p, name)), name)
        }
        assertEquals(Effect.READS, p.model.effect(fn(p, "findsInts")), "List.contains over Int32 runs no user operator")
    }

    @Test
    fun aLentResultReadsTheStorageItIsLentFrom() {
        // R-A in EffectsPass's READS: GL.get(0) is GL[0], a read of a mut global (READS) whichever spelling; an
        // accessor on a local is PURE either way.
        val p = snippet(
            """
            mut GL: List<Int32> = List<Int32> { values = @arrOf(1) }
            pub g: () Int32 {
                return GL.get(0)
            }
            pub i: () Int32 {
                return GL[0]
            }
            pub l: () Int32 {
                xs: List<Int32> = List<Int32> { values = @arrOf(1) }
                return xs.get(0) + xs[0]
            }
            """,
        )
        assertEquals(Effect.READS, p.model.effect(fn(p, "g")))
        assertEquals(Effect.READS, p.model.effect(fn(p, "i")))
        assertEquals(Effect.READS, p.model.effect(BodyTestSupport.node<net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr>(p, "GL.get(0)")))
        assertEquals(Effect.PURE, p.model.effect(fn(p, "l")))
    }

    @Test
    fun aConstructionThatRunsAnFxFieldIsImpure() {
        // Round 6, scratchpad w25r5c x/t3 as the verifier wrote it: bump's only write is the body of a Thread it
        // constructs (which starts it) and drops (which joins it). Round 5b ranked the construction PURE, so D33 left
        // `gi + bump()` unspilled (30 on gcc and MSVC for OQ-1's 29) and both(gs, bump()) lent gs (3 for 47).
        val p = snippet(
            """
            kira.sync as sync

            mut gs: Str = "old-text-long-enough-to-live-on-the-heap-000000"
            mut gi: Int32 = 29

            bump: () Int32 {
                t: Thread = Thread { name = "w", body = () Void {
                    gs = "new"
                    gi = 30
                } }
                return 0
            }

            pub both: (s: Str, n: Int32) Int32 {
                return (s.length() as Int32) + n
            }

            main: () Void {
                trace(both(gs, bump()))
                gs = "old-text-long-enough-to-live-on-the-heap-000000"
                gi = 29
                trace(gi + bump())
                gi = 29
                trace((gs.length() as Int32) + bump())
            }
            """,
        )
        assertEquals(Effect.IMPURE, p.model.effect(fn(p, "bump")))
        assertEquals(Effect.IMPURE, p.model.effect(BodyTestSupport.node<BinaryExpr>(p, "gi + bump()")))
        assertEquals(Effect.IMPURE, p.model.effect(BodyTestSupport.node<FunctionCallExpr>(p, "both(gs, bump())")))
        // Decided from the class: a Thread starts whatever it is given, even a body that writes nothing (it runs on
        // another thread); a user class whose initially calls its Fx field runs it (x/t4's control, IMPURE since round
        // 2 by its initially); a class that only stores an Fx field runs nothing at its construction.
        val c = snippet(
            """
            kira.sync as sync
            pub mut G: Int32 = 0
            pub Runner: Any {
                pub f: Fx<Tuple0, Void>
                initially {
                    f()
                }
            }
            pub Keeper: Any {
                pub f: Fx<Tuple0, Void>
            }
            pub idle: () Int32 {
                t: Thread = Thread { name = "w", body = () Void { } }
                return 0
            }
            pub viaInitially: () Int32 {
                r: Runner = Runner { f = () Void {
                    G = 30
                } }
                return 0
            }
            pub kept: () Int32 {
                k: Keeper = Keeper { f = () Void {
                    G = 30
                } }
                return 0
            }
            """,
        )
        assertEquals(Effect.IMPURE, c.model.effect(fn(c, "idle")))
        assertEquals(Effect.IMPURE, c.model.effect(fn(c, "viaInitially")))
        assertEquals(Effect.PURE, c.model.effect(fn(c, "kept")))
    }
}
