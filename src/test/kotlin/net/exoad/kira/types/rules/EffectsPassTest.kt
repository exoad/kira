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
            pub struct P {
                pub x: Int32 = 0
                pub fx doubled: () Int32 {
                    return x * 2
                }
            }
            pub fx twice: (v: Int32) Int32 {
                return v * 2
            }
            pub fx quad: (v: Int32) Int32 {
                mut acc: Int32 = twice(v)
                acc += twice(v)
                mut p: P = P {}
                p.x = acc
                mut xs: Arr<Int32, 2> = [1, 2]
                xs[0] = p.x
                return xs[0] + LIMIT + p.doubled()
            }
            pub fx len: (s: Str, xs: List<Int32>) Size {
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
            pub fx even: (n: Int32) Bool {
                if n == 0 {
                    return true
                }
                return odd(n - 1)
            }
            pub fx odd: (n: Int32) Bool {
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
            pub fx bump: () Int32 {
                STATE += 1
                return STATE
            }
            pub fx twice: (v: Int32) Int32 {
                return v * 2
            }
            pub fx f: (a: Int32) Int32 {
                g: Fx<Tuple0, Int32> = fx() Int32 {
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
            pub class C {
                pub mut n: Int32 = 0
                pub mut fx bump: () Void {
                    n += 1
                }
                pub fx peek: () Int32 {
                    return n
                }
            }
            pub fx global: () Void {
                STATE = 1
            }
            pub fx param: (mut out: Int32) Void {
                out = 1
            }
            pub fx through: (c: C) Void {
                c.n = 2
            }
            pub fx viewed: (v: MutView<UInt8>) Void {
                v[0] = 1
            }
            pub fx ref: () Int32 {
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
            pub class C {
                pub mut n: Int32 = 0
            }
            pub fx readG: () Int32 {
                return STATE
            }
            pub fx byRef: (mut x: Int32) Int32 {
                return x
            }
            pub fx element: (v: View<UInt8>) Int32 {
                return v[0] as Int32
            }
            pub fx through: (v: View<UInt8>) Size {
                return v.size()
            }
            pub fx field: (c: C) Int32 {
                return c.n
            }
            pub fx parse: (s: Str) Int32 {
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
            pub fx bump: () UInt32 {
                STATE += 1
                return 1
            }
            pub fx readU16: (p: View<UInt8>) UInt32 {
                return (p[0] as UInt32) | ((p[1] as UInt32) << 8)
            }
            pub fx mixed: (p: View<UInt8>) UInt32 {
                return (p[0] as UInt32) + bump()
            }
            pub fx viaCall: (p: View<UInt8>) UInt32 {
                return readU16(p) + 1
            }
            pub fx sizes: (out: Size, buf: View<UInt8>) Bool {
                return out == buf.size()
            }
            """,
        )
        expectClean(p)
        val model = p.model
        assertEquals(Effect.READS, model.effect(BodyTestSupport.node<TypeCastExpr>(p, "p[0] as UInt32")))
        assertEquals(Effect.READS, model.effect(BodyTestSupport.node<BinaryExpr>(p, "(p[1] as UInt32) << 8")))
        assertEquals(Effect.READS, model.effect(BodyTestSupport.node<BinaryExpr>(p, "p[0] as UInt32 | (p[1] as UInt32) << 8")))
        assertEquals(Effect.IMPURE, model.effect(BodyTestSupport.node<BinaryExpr>(p, "(p[0] as UInt32) + bump()")))
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
            pub fx bump: () Int32 {
                STATE += 1
                return STATE
            }
            pub fx uses: (v: Int32) Int32 {
                return v + bump()
            }
            pub fx usesUses: (v: Int32) Int32 {
                return uses(v)
            }
            pub fx builds: () Size {
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
        // (`pair(p.a, bumpGS())` spills p.a first). A scalar, enum or view parameter is copied and stays PURE.
        val p = snippet(
            """
            pub struct S {
                pub a: Int32 = 0
            }
            pub enum E: Int32 { E_A = 0 }
            pub fx st: (p: S) Int32 {
                return p.a
            }
            pub fx str: (s: Str) Size {
                return s.size()
            }
            pub fx arr: (xs: Arr<Int32, 2>) Int32 {
                return xs[0]
            }
            pub fx sc: (n: Int32, e: E, v: View<Int32>) Int32 {
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
        assertEquals(Effect.PURE, model.effect(BodyTestSupport.node<BinaryExpr>(p, "n + (v.size() as Int32)").leftExpr), "a scalar parameter is copied")
    }

    @Test
    fun printsExternsVirtualsAndFxValuesAreImpure() {
        val p = snippet(
            """
            @_extern(cpp = "ext::read", header = "ext.hxx")
            pub fx read: () Int32;
            pub trait Shape {
                pub fx area: () Int32;
            }
            pub fx prints: (v: Int32) Void {
                trace(v)
            }
            pub fx externs: () Int32 {
                return read()
            }
            pub fx virtuals: (s: Shape) Int32 {
                return s.area()
            }
            pub fx values: (f: Fx<Tuple0, Int32>) Int32 {
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
    fun whatDropsTheLastHandleOfAnObjectWithAnImpureFinallyIsImpure() {
        // w2-5 round 1's first finding: a finally run by a drop. A local, a by-value parameter, a construction or a
        // call result whose value may be such a handle is dropped by the body, and the finally writes GL. A
        // trait-typed or generic value may be any class, and a closure holds what it captured. Quiet's finally
        // writes only its own local, so dropping a Quiet is pure.
        val p = snippet(
            """
            mut GL: List<Int32> = List<Int32> {}
            pub trait Shape {
                pub fx area: () Int32;
            }
            pub class Dropper: Shape {
                pub mut n: Int32 = 0
                override pub fx area: () Int32 {
                    return n
                }
                finally {
                    GL = List<Int32> {}
                }
            }
            pub class Owner {
                pub d: Maybe<Dropper> = null
            }
            pub class Quiet {
                pub mut n: Int32 = 0
                finally {
                    mut k: Int32 = 1
                    k += 1
                }
            }
            pub fx local: () Int32 {
                d: Dropper = Dropper {}
                return d.n
            }
            pub fx param: (d: Dropper) Int32 {
                return 1
            }
            pub fx owner: () Bool {
                return (Owner {}).d.isSome()
            }
            pub fx shape: (s: Maybe<Shape>) Int32 {
                return 1
            }
            pub fx generic<T>: (x: T) Int32 {
                return 1
            }
            pub fx quiet: () Int32 {
                q: Quiet = Quiet {}
                return q.n
            }
            """,
        )
        for (name in listOf("local", "param", "owner", "shape", "generic")) {
            assertEquals(Effect.IMPURE, p.model.effect(fn(p, name)), name)
        }
        assertEquals(Effect.READS, p.model.effect(fn(p, "quiet")), "a finally that writes only its own locals runs nothing a caller can see; q.n is read through a class reference")
        assertEquals(Effect.IMPURE, p.model.effect(BodyTestSupport.node<net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr>(p, "Dropper { }")))
    }

    @Test
    fun externsPrototypesDispatchInitializersAndMutViewWritesAreImpure() {
        // Round 1's other shapes: an extern given an Fx (nested in an Arr), a bodiless prototype, a MutView of a
        // global formed in a callee that writes through it (P12), a construction whose initially writes a global
        // (P2f's Maker); and a pure binding that compares a user class's elements runs its operators.
        val p = snippet(
            """
            mut GXSS: List<List<Int32>> = List<List<Int32>> {}
            mut GD: Int32 = 0
            @_extern(cpp = "ext::applyAll", header = "ext.hxx")
            pub fx applyAll: (fs: Arr<Fx<Tuple0, Void>>) Int32;
            pub fx proto: () Int32;
            pub class Maker {
                pub mut n: Int32 = 0
                initially {
                    GD = 1
                }
            }
            pub class Id {
                pub k: Int32 = 0
            }
            pub fx clobber: (mv: MutView<List<Int32>>) Int32 {
                mv[0] = List<Int32> {}
                return 0
            }
            pub fx formsAndLends: () Int32 {
                return clobber(GXSS.view())
            }
            pub fx nested: () Int32 {
                return applyAll([fx() Void {}])
            }
            pub fx prototype: () Int32 {
                return proto()
            }
            pub fx made: () Int32 {
                return (Maker {}).n
            }
            pub fx finds: (ids: List<Id>, id: Id, ns: List<Int32>) Bool {
                return ids.contains(id)
            }
            pub fx findsInts: (ns: List<Int32>) Bool {
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
    fun aGenericClassIsJudgedUnderEachSubstitutionItIsReachedWith() {
        // Round 2's minor (w2-5, w2-3 #1): Drops kept one `seen` set across a whole walk, so PairAB, whose Box<Int32>
        // came first, was judged unable to hold a Dropper (fAB PURE, emitted with no D33 spill) while PairBA, the
        // same fields swapped, was not. Both field orders are judged alike now: a by-value parameter that may be the
        // last handle of a Dropper is dropped in the callee.
        val p = snippet(
            """
            mut GI: Int32 = 0
            pub class Dropper {
                pub n: Int32 = 0
                finally {
                    GI = 5
                }
            }
            pub class Box<T> {
                pub v: Maybe<T> = null
            }
            pub struct PairAB {
                pub a: Box<Int32> = Box<Int32> {}
                pub b: Box<Dropper> = Box<Dropper> {}
            }
            pub struct PairBA {
                pub b: Box<Dropper> = Box<Dropper> {}
                pub a: Box<Int32> = Box<Int32> {}
            }
            pub struct Ints {
                pub a: Box<Int32> = Box<Int32> {}
                pub c: Box<Int32> = Box<Int32> {}
            }
            pub fx fAB: (p: PairAB) Int32 {
                return 0
            }
            pub fx fBA: (p: PairBA) Int32 {
                return 0
            }
            pub fx fInts: (p: Ints) Int32 {
                return 0
            }
            """,
        )
        assertEquals(Effect.IMPURE, p.model.effect(fn(p, "fAB")))
        assertEquals(Effect.IMPURE, p.model.effect(fn(p, "fBA")))
        assertEquals(Effect.PURE, p.model.effect(fn(p, "fInts")), "a Box<Int32> holds no Dropper")
    }

    @Test
    fun aLentResultReadsTheStorageItIsLentFrom() {
        // R-A in EffectsPass's READS: GL.get(0) is GL[0], a read of a mut global (READS) whichever spelling; an
        // accessor on a local is PURE either way.
        val p = snippet(
            """
            mut GL: List<Int32> = List<Int32> { values = [1] }
            pub fx g: () Int32 {
                return GL.get(0)
            }
            pub fx i: () Int32 {
                return GL[0]
            }
            pub fx l: () Int32 {
                xs: List<Int32> = List<Int32> { values = [1] }
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
            use "kira:sync"

            mut gs: Str = "old-text-long-enough-to-live-on-the-heap-000000"
            mut gi: Int32 = 29

            fx bump: () Int32 {
                t: Thread = Thread { name = "w", body = fx () Void {
                    gs = "new"
                    gi = 30
                } }
                return 0
            }

            pub fx both: (s: Str, n: Int32) Int32 {
                return (s.length() as Int32) + n
            }

            fx main: () Void {
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
            use "kira:sync"
            pub mut G: Int32 = 0
            pub class Runner {
                require pub f: Fx<Tuple0, Void>
                initially {
                    f()
                }
            }
            pub class Keeper {
                require pub f: Fx<Tuple0, Void>
            }
            pub fx idle: () Int32 {
                t: Thread = Thread { name = "w", body = fx () Void { } }
                return 0
            }
            pub fx viaInitially: () Int32 {
                r: Runner = Runner { f = fx () Void {
                    G = 30
                } }
                return 0
            }
            pub fx kept: () Int32 {
                k: Keeper = Keeper { f = fx () Void {
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
