package net.exoad.kira.cpp.oop

import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleEmitterFactory
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lowering's references into storage an object holds (CppClassLifetimes): a `const&`
 * parameter an effect of the body may reach is copied at entry, a class method that reads its
 * receiver after an effect that may free an object holds itself, and what no guard makes safe
 * is refused by name (a trait default that would need a hold, a range-for over an object's
 * storage its body may change, a `mut` argument into such storage the callee may change, a
 * construction that leaves a handle or an `Fx` without a value). Each shape is the reduced form
 * of a probe that printed freed heap bytes, crashed, or read the wrong value on g++ and zig
 * c++ before (convergence round 2).
 */
class CppClassLifetimesTest {
    private val uri = "test:main"

    private fun emit(body: String) = OopTestSupport.emit(OopTestSupport.module(uri, body))

    private fun both(body: String): Pair<String, String> {
        val e = emit(body)
        return e.header(uri) to e.source(uri)
    }

    private fun unsupported(body: String): List<String> =
        emit(body).module(uri).diagnostics.filter { it.code == CppModuleEmitterFactory.UNSUPPORTED_CODE }.map { it.message }

    private fun assertContains(text: String, vararg wanted: String) {
        wanted.forEach { assertTrue(text.contains(it), "expected to find:\n$it\nin:\n$text") }
    }

    private fun assertLacks(text: String, vararg unwanted: String) {
        unwanted.forEach { assertTrue(!text.contains(it), "expected not to find:\n$it\nin:\n$text") }
    }

    // ---- a const& parameter an effect may reach is copied at entry -----------------------------------

    @Test
    fun aStrParameterReadAfterTheMethodGrowsAListIsCopiedAtEntry() {
        // uaflist: `a.append(b.lines[0])` with b = a, and append growing lines (g++: std::bad_alloc).
        val (h, s) = both(
            """
            pub class Log {
                pub mut lines: List<Str> = List<Str> {}

                pub mut fx append: (s: Str) Str {
                    lines.add("one long line that is not in the small string buffer")
                    return s
                }
            }
            """
        )
        // the prototype is the design's; only the definition renames the reference and copies it
        assertContains(h, "      [[nodiscard]] kira::Str append(const kira::Str& s);")
        assertContains(s, "  kira::Str Log::append(const kira::Str& sRef_)\n  {\n      const kira::Str s = sRef_;\n")
    }

    @Test
    fun aParameterReadOnlyBeforeAnyEffectIsNotCopied() {
        val (_, s) = both(
            """
            pub class Log {
                pub mut lines: List<Str> = List<Str> {}

                pub mut fx put: (s: Str) Void {
                    lines.add(s)
                }
            }
            """
        )
        assertContains(s, "  void Log::put(const kira::Str& s)\n")
        assertLacks(s, "sRef_")
    }

    @Test
    fun aParameterOfATypeNoObjectHoldsIsNotCopied() {
        // No class, Ref or held Fx holds a Str by value: the reference cannot point into an object.
        val (_, s) = both(
            """
            pub class Counter {
                pub mut n: Int32 = 0

                pub mut fx bump: (label: Str) Str {
                    n = n + 1
                    return label
                }
            }
            """
        )
        assertContains(s, "  kira::Str Counter::bump(const kira::Str& label)\n")
        assertLacks(s, "labelRef_")
    }

    @Test
    fun aWriteThroughAHandleDoesNotCopyTheHandle() {
        // c.value = ... changes the Box c points to, never the handle c: bump keeps its const& (the classes golden).
        val (_, s) = both(
            """
            pub class Keep<T> {
                require pub value: T
            }

            pub fx bump: (c: Ref<Int32>) Int32 {
                c.value = c.value + 1
                return c.value
            }
            """
        )
        assertContains(s, "  std::int32_t bump(const kira::Rc<kira::Box<std::int32_t>>& c)\n")
        assertLacks(s, "cRef_")
    }

    @Test
    fun aFreeFunctionsParameterAnEffectMayReachIsCopiedAtEntry() {
        // uaf3: consume(h, h.item.unwrap().label) with consume clearing h first (g++: freed heap bytes).
        val (_, s) = both(
            """
            pub class Holder {
                pub mut item: Maybe<Item> = null

                pub mut fx clear: () Void {
                    item = null
                }
            }

            pub class Item {
                require pub label: Str
            }

            pub fx consume: (h: Holder, s: Str) Str {
                h.clear()
                return s
            }
            """
        )
        assertContains(s, "  kira::Str consume(const kira::Rc<Holder>& h, const kira::Str& sRef_)\n  {\n      const kira::Str s = sRef_;\n")
    }

    @Test
    fun aTemplatesTParameterIsCopiedBeforeTheBodyWritesAT() {
        // genalias4: b.take(g.n) with g = b printed 7 where Kira gives 1.
        val h = emit(
            """
            pub class Box<T> {
                pub mut n: T
                require pub m: T

                pub mut fx take: (v: T) T {
                    n = m
                    return v
                }
            }
            """
        ).header(uri)
        assertContains(h, "  template<typename T>\n  T Box<T>::take(const T& vRef_)\n  {\n      const T v = vRef_;\n")
    }

    // ---- a class method that reads itself after a release holds itself ------------------------------

    @Test
    fun aMethodThatReadsItselfAfterDroppingItsOwnerHoldsItself() {
        // selfkill2: t.kids[0].leave() with leave calling tree.clear(), then reading name.
        val (h, s) = both(
            """
            pub class Tree {
                pub mut kids: List<Kid> = List<Kid> {}

                pub mut fx clear: () Void {
                    kids = List<Kid> {}
                }
            }

            pub class Kid {
                require pub name: Str
                require pub tree: Tree

                pub fx leave: () Str {
                    tree.clear()
                    return name
                }
            }
            """
        )
        assertContains(h, "  class Kid final : public kira::Shared<Kid>\n", "  class Tree final\n")
        assertContains(s, "  kira::Str Kid::leave() const\n  {\n      [[maybe_unused]] const auto keepAlive_ = weak_from_this().lock();\n")
        assertLacks(s, "Tree::clear()\n  {\n      [[maybe_unused]]")
    }

    @Test
    fun aClassTemplateHoldsItselfThroughThis() {
        val h = emit(
            """
            pub class Node<T> {
                require pub value: T
                pub mut next: Maybe<Node<T>> = null

                pub fx detach: (owner: Node<T>) T {
                    owner.drop()
                    return value
                }

                pub mut fx drop: () Void {
                    next = null
                }
            }
            """
        ).header(uri)
        assertContains(h, "  class Node final : public kira::Shared<Node<T>>\n", "      [[maybe_unused]] const auto keepAlive_ = this->weak_from_this().lock();\n")
    }

    @Test
    fun aMethodThatReleasesNothingDoesNotHoldItself() {
        val (h, s) = both(
            """
            pub class Dog {
                mut tricks: Int32 = 0

                pub mut fx learn: () Int32 {
                    tricks += 1
                    return tricks
                }
            }
            """
        )
        assertContains(h, "  class Dog final\n")
        assertLacks(h + s, "kira::Shared", "keepAlive_")
    }

    @Test
    fun aVirtualCallHoldsWhenSomeOverrideMayRelease() {
        // What a call through a trait may run is every body of the name the program has.
        val h = emit(
            """
            pub trait Step {
                pub mut fx step: () Void;
            }

            pub class Dropper: Step {
                pub mut spare: Maybe<Dropper> = null

                override pub mut fx step: () Void {
                    spare = null
                }
            }

            pub class Runner {
                require pub s: Step
                pub n: Int32 = 1

                pub fx run: () Int32 {
                    s.step()
                    return n
                }
            }
            """
        ).header(uri)
        assertContains(h, "  class Runner final : public kira::Shared<Runner>\n")
    }

    @Test
    fun aTraitMethodOnlyCppImplementsIsTakenToLeaveKiraObjectsAlone() {
        // The FFI contract (ledger OD-2): a C++ override is not analysed, and is not taken to free a Kira object.
        val h = emit(
            """
            pub trait Poke {
                pub mut fx poke: () Void;
            }

            pub class Runner {
                require pub p: Poke
                pub n: Int32 = 1

                pub fx run: () Int32 {
                    p.poke()
                    return n
                }
            }
            """
        ).header(uri)
        assertContains(h, "  class Runner final\n")
        assertLacks(h, "kira::Shared")
    }

    @Test
    fun aTraitDefaultThatWouldNeedAHoldIsRefused() {
        val messages = unsupported(
            """
            pub trait Node {
                pub mut fx drop: () Void;
                pub fx id: () Int32;
                pub fx leave: () Int32 {
                    drop()
                    return id()
                }
            }

            pub class Leaf: Node {
                pub mut next: Maybe<Leaf> = null

                override pub mut fx drop: () Void {
                    next = null
                }

                override pub fx id: () Int32 {
                    return 1
                }
            }
            """
        )
        assertEquals(1, messages.count { it.contains("the default body of Node.leave reads its receiver after the call to drop") }, messages.toString())
        assertTrue(messages.single().contains("override leave in each class that implements Node"), messages.toString())
    }

    // ---- what no guard makes safe is refused ----------------------------------------------------------

    @Test
    fun aLoopOverAnObjectsListWhoseBodyMayGrowItIsRefused() {
        // bagloop2: for s in b.items { c.grow() } with c = b (g++: freed heap bytes).
        val messages = unsupported(
            """
            pub class Bag {
                pub mut items: List<Str> = List<Str> {}

                pub mut fx grow: () Void {
                    items.add("another long string that is not in the small buffer")
                }
            }

            pub fx walk: (b: Bag, c: Bag) Int32 {
                mut n: Int32 = 0
                for s: Str in b.items {
                    c.grow()
                    n = n + 1
                }
                return n
            }
            """
        )
        assertEquals(1, messages.size, messages.toString())
        assertContains(messages.single(), "the loop over b.items, storage an object holds, while its body may change or free it (the call to grow)", "Iterate over a local copy instead")
    }

    @Test
    fun aLoopWhoseBodyLeavesTheListAloneIsKept() {
        // The chain golden's has(): the loop calls a trait method whose bodies change nothing.
        val e = emit(
            """
            pub trait Behaviour {
                pub fx id: () Str;
            }

            class Stop: Behaviour {
                override pub fx id: () Str {
                    return "stop"
                }
            }

            pub class Chain {
                mut loaded: List<Behaviour> = List<Behaviour> {}
                mut hits: Int32 = 0

                pub mut fx has: (wanted: Str) Bool {
                    for b: Behaviour in loaded {
                        hits += 1
                        if b.id() == wanted {
                            return true
                        }
                    }
                    return false
                }
            }
            """
        )
        assertEquals(emptyList(), e.errors(uri).map { it.message })
        assertLacks(e.header(uri), "kira::Shared")
    }

    @Test
    fun aMutArgumentIntoAnObjectTheCalleeMayChangeIsRefused() {
        val messages = unsupported(
            """
            pub class Bag {
                pub mut items: List<Str> = List<Str> {}

                pub mut fx grow: () Void {
                    items.add("x")
                }
            }

            pub fx both: (c: Bag, mut v: List<Str>) Void {
                c.grow()
                v.add("y")
            }

            pub fx put: (mut v: List<Str>) Void {
                v.add("y")
            }

            pub fx feed: (b: Bag, c: Bag) Void {
                both(c, mut b.items)
                put(mut b.items)
            }
            """
        )
        // both() may grow any Bag's items while it writes through v; put() writes only through v.
        assertEquals(1, messages.size, messages.toString())
        assertContains(messages.single(), "the mut argument b.items, storage an object holds, passed to both, which may change or free that object")
    }

    @Test
    fun aCallThroughAnFxFieldTheCalledCodeMayReplaceIsRefused() {
        // fxself: a lambda assigning b.f while it runs as b.f (g++: freed heap bytes for its capture).
        val refused = unsupported(
            """
            pub class Box {
                require pub mut f: Fx<Tuple0, Int32>

                pub mut fx run: () Int32 {
                    return f()
                }
            }

            pub fx make: () Box {
                b: Box = Box { f = fx() Int32 { return 1 } }
                b.f = fx() Int32 {
                    b.f = fx() Int32 { return 2 }
                    return 3
                }
                return b
            }
            """
        )
        assertEquals(1, refused.size, refused.toString())
        assertContains(refused.single(), "the call through f, an Fx an object holds, while the function it runs may replace or free it", "Copy it into a local first (`g: Fx<Tuple0, Int32> = f`")
        // the advice: a local copy of the kira::Fn runs, and the replacement destroys only the field's
        val kept = unsupported(
            """
            pub class Box {
                require pub mut f: Fx<Tuple0, Int32>

                pub mut fx run: () Int32 {
                    g: Fx<Tuple0, Int32> = f
                    return g()
                }
            }

            pub fx make: () Box {
                b: Box = Box { f = fx() Int32 { return 1 } }
                b.f = fx() Int32 {
                    b.f = fx() Int32 { return 2 }
                    return 3
                }
                return b
            }
            """
        )
        assertEquals(emptyList(), kept)
    }

    @Test
    fun aLambdaParameterReadAfterAnEffectThatMayReachItIsRefused() {
        // lamparam: callWith(h, fx(s: Str) Str { h.clear() return s }) with s bound to h's Item's label.
        val body = { lambdaBody: String ->
            """
            pub class Holder {
                pub mut item: Maybe<Item> = null

                pub mut fx clear: () Void {
                    item = null
                }
            }

            pub class Item {
                require pub label: Str
            }

            pub fx callWith: (h: Holder, f: Fx<Tuple1<Str>, Str>) Str {
                return f(h.item.unwrap().label)
            }

            pub fx feed: (h: Holder) Str {
                return callWith(h, fx(s: Str) Str {
                    $lambdaBody
                })
            }
            """
        }
        val refused = unsupported(body("h.clear()\n                    return s"))
        assertEquals(1, refused.size, refused.toString())
        assertContains(refused.single(), "the lambda parameter s, which C++ takes by const&, read after an effect of the lambda's body", "(`v: Str = s`)")
        assertEquals(emptyList(), unsupported(body("v: Str = s\n                    h.clear()\n                    return v")))
    }

    @Test
    fun aConstructionThatLeavesAHandleOrAnFxWithoutAValueIsRefused() {
        // nullfield, nullfield2, nullfx: a null kira::Rc or an empty kira::Fn, for a type Kira calls non-nullable.
        val messages = unsupported(
            """
            pub class Other {
                pub k: Int32 = 3
            }

            pub trait Speaker {
                pub fx say: () Int32;
            }

            pub struct Wrap {
                pub o: Other
            }

            pub class X {
                pub c: Other
                pub s: Speaker
                pub r: Ref<Int32>
                pub f: Fx<Tuple0, Int32>
                pub w: Wrap
                require pub n: Int32
            }

            pub fx make: () X {
                return X { n = 1 }
            }
            """
        )
        listOf("c: Other", "s: Speaker", "r: Ref<Int32>", "f: Fx<Tuple0, Int32>", "w: Wrap").forEach { field ->
            assertTrue(messages.any { it.contains("constructing X leaves the field $field without a value") }, "no refusal for $field in $messages")
        }
        assertTrue(messages.first().contains("give c a value here, declare it with a default, or make it Maybe<Other>"), messages.toString())
    }

    @Test
    fun aConstructionMayLeaveWhatHasAnEmptyValue() {
        val e = emit(
            """
            pub class Other {
                pub k: Int32 = 3
            }

            pub struct Plain {
                pub a: Int32
                pub b: Str
            }

            pub class X {
                pub m: Maybe<Other>
                pub w: Weak<Other>
                pub l: List<Other>
                pub p: Plain
                pub t: Str
                require pub n: Int32
            }

            pub fx make: () X {
                return X { n = 1 }
            }
            """
        )
        assertEquals(emptyList(), e.errors(uri).map { it.message })
    }

    // ---- the goldens ------------------------------------------------------------------------------------

    @Test
    fun theOopGoldensNeedNoGuard() {
        // chain, sender and classes stay as expected/ spells them: no copy and no hold in a body
        // (their heads and class bodies, a kira::Shared base among them, are CppOopGoldenShapeTest's).
        for (name in listOf("chain", "sender", "classes")) {
            OopTestSupport.emitCase(OopTestSupport.case(name)).forEach { f -> assertLacks(f.text, "Ref_ = ", "keepAlive_") }
        }
    }
}
