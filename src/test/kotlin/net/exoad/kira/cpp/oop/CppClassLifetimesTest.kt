package net.exoad.kira.cpp.oop

import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.TypedProgram
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

    /**
     * [unsupported], or null when the typer refuses [body] first by one of W2.5's [rules]: on a
     * tree with W2.5 merged its ExclusivityPass and EscapePass refuse some shapes the classes
     * part refuses too, which is the same outcome.
     */
    private fun unsupportedUnlessW25(vararg rules: String, body: () -> String): List<String>? = try {
        unsupported(body())
    } catch (e: AssertionError) {
        if (e.message?.contains("the typer refused") == true && rules.any { e.message!!.contains(it) }) null else throw e
    }

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

    // ---- a parameter a returned view may point into stays the caller's (convergence round 3) ---------

    private val bag = """
        pub class Bag {
            pub mut items: List<Int32> = List<Int32> {}

            pub mut fx grow: () Void {
                items.add(9)
            }
        }
    """.trimIndent()

    @Test
    fun aParameterAReturnedViewPointsIntoIsNotCopied() {
        // viewlend2 and viewlend3: a copy at entry was what the returned view pointed into (MSVC ASan: heap-use-after-free).
        val (_, s) = both(
            """
            $bag

            pub fx firstOf: (xs: List<Int32>, b: Bag) View<Int32> {
                b.grow()
                return xs.view()
            }

            pub class Picker {
                pub mut items: List<Int32> = List<Int32> {}

                pub mut fx firstOf: (xs: List<Int32>) View<Int32> {
                    items.add(9)
                    return xs.view()
                }
            }
            """
        )
        assertContains(s, "  kira::View<std::int32_t> firstOf(const kira::List<std::int32_t>& xs, const kira::Rc<Bag>& b)\n")
        assertContains(s, "  kira::View<std::int32_t> Picker::firstOf(const kira::List<std::int32_t>& xs)\n")
        assertLacks(s, "xsRef_")
    }

    @Test
    fun aViewTakenOnlyForTheBodyLeavesTheCopyInPlace() {
        // No view can leave the call (the result is an Int32), so the copy at entry is still the value passed.
        val (_, s) = both(
            """
            $bag

            pub fx total: (v: View<Int32>) Int32 {
                return v.get(0)
            }

            pub fx sumAfter: (xs: List<Int32>, b: Bag) Int32 {
                b.grow()
                return total(xs.view())
            }
            """
        )
        assertContains(s, "  std::int32_t sumAfter(const kira::List<std::int32_t>& xsRef_, const kira::Rc<Bag>& b)\n  {\n      const kira::List<std::int32_t> xs = xsRef_;\n")
    }

    @Test
    fun aLentParameterReadAfterAnEffectThatMayReachItIsRefused() {
        val value = unsupported(
            """
            $bag

            pub fx firstOf: (xs: List<Int32>, b: Bag) View<Int32> {
                b.grow()
                trace(xs.size())
                return xs.view()
            }
            """
        )
        assertEquals(1, value.size, value.toString())
        assertContains(value.single(), "the parameter xs, read after the call to grow, which may change it", "a view the function hands back may point into it", "Read what you need from xs before the call to grow")
        val freed = unsupported(
            """
            pub class Holder {
                pub mut item: Maybe<Item> = null

                pub mut fx clear: () Void {
                    item = null
                }
            }

            pub class Item {
                pub mut labels: List<Int32> = List<Int32> {}
            }

            pub fx firstOf: (xs: List<Int32>, h: Holder) View<Int32> {
                h.clear()
                return xs.view()
            }
            """
        )
        assertEquals(1, freed.size, freed.toString())
        assertContains(freed.single(), "the parameter xs, read after the call to clear, which may free or move what it names", "the read would see freed memory")
    }

    @Test
    fun aViewOfAnObjectsStorageReadAfterAnEffectThatMayMoveItIsRefused() {
        // viewbefore: v = xs.view(), then b.grow() through c = b, then return v (g++: freed heap bytes).
        val messages = unsupported(
            """
            $bag

            pub fx firstOf: (xs: List<Int32>, b: Bag) View<Int32> {
                v: View<Int32> = xs.view()
                b.grow()
                return v
            }

            pub fx firstOfField: (b: Bag, c: Bag) Int32 {
                v: View<Int32> = b.items.view()
                c.grow()
                return v.get(0)
            }

            pub fx after: (b: Bag, c: Bag) Int32 {
                c.grow()
                v: View<Int32> = b.items.view()
                return v.get(0)
            }
            """
        )
        assertEquals(2, messages.size, messages.toString())
        assertContains(messages[0], "the view v, taken of xs, read after the call to grow, which may change or free xs", "Take the view after the call to grow")
        assertContains(messages[1], "the view v, taken of b.items, read after the call to grow")
    }

    @Test
    fun aViewOfALocalReadAfterTheBodyGrowsTheLocalIsRefused() {
        // viewlocalmut: v = items.view(), then items.add(9) a hundred times, then v.get(0) (g++: freed heap bytes).
        val messages = unsupported(
            """
            pub fx grown: () Int32 {
                mut items: List<Int32> = List<Int32> {}
                items.add(1)
                v: View<Int32> = items.view()
                items.add(9)
                return v.get(0)
            }

            pub fx inPlace: () Int32 {
                mut a: Arr<Int32, 3> = [1, 2, 3]
                v: View<Int32> = a.view()
                a[0] = 5
                a = [4, 5, 6]
                return v.get(0)
            }

            pub fx before: () Int32 {
                mut items: List<Int32> = List<Int32> {}
                items.add(1)
                v: View<Int32> = items.view()
                n: Int32 = v.get(0)
                items.add(9)
                return n
            }

            pub class Item {
                pub mut labels: List<Int32> = List<Int32> {}
            }

            pub fx replaced: () Int32 {
                mut it: Item = Item {}
                it.labels.add(1)
                v: View<Int32> = it.labels.view()
                it = Item {}
                return v.get(0)
            }
            """
        )
        assertEquals(2, messages.size, messages.toString())
        assertContains(messages[0], "the view v, taken of items, read after the call to add, which may change or free items")
        assertContains(messages[1], "the view v, taken of it.labels, read after the assignment `it = Item { }`")
    }

    @Test
    fun aViewOfAnObjectsStorageHandedToACallThatMayMoveItIsRefused() {
        val messages = unsupported(
            """
            $bag

            pub fx firstAfter: (v: View<Int32>, c: Bag) Int32 {
                c.grow()
                return v.get(0)
            }

            pub fx first: (v: View<Int32>) Int32 {
                return v.get(0)
            }

            pub fx feed: (b: Bag, c: Bag) Int32 {
                return firstAfter(b.items.view(), c) + first(b.items.view())
            }
            """
        )
        assertEquals(1, messages.size, messages.toString())
        assertContains(messages.single(), "the view of b.items handed to firstAfter, which may change or free b.items while the view is in use")
    }

    // ---- a reference into this or a parameter's object kept while a call runs (convergence round 3) ----

    private val holder = """
        pub class Holder {
            pub mut item: Maybe<Item> = null

            pub mut fx clear: () Void {
                item = null
            }

            pub mut fx take: () Str {
                item = null
                return "a long added line that is not in the small string buffer"
            }
        }

        pub fx setAfter: (h: Holder, mut s: Str) Str {
            h.clear()
            t: Str = s
            s = "y"
            return t
        }
    """.trimIndent()

    @Test
    fun aMethodPassingItsOwnFieldMutToACallThatMayFreeItHoldsItself() {
        // mutthis: setAfter(h, mut label) wrote into the freed Item (g++ segfault, MSVC ASan heap-use-after-free).
        val (h, s) = both(
            """
            $holder

            pub class Item {
                pub mut label: Str = "a long item label"

                pub mut fx relabel: (h: Holder) Str {
                    return setAfter(h, mut label)
                }
            }
            """
        )
        assertContains(h, "  class Item final : public kira::Shared<Item>\n")
        assertContains(s, "  kira::Str Item::relabel(const kira::Rc<Holder>& h)\n  {\n      [[maybe_unused]] const auto keepAlive_ = weak_from_this().lock();\n")
    }

    @Test
    fun aParameterWhoseFieldIsPassedMutToACallThatMayFreeItIsCopied() {
        // mutparam: it was the const kira::Rc<Item>& bound to h->item, which setAfter's h.clear() dropped.
        val (_, s) = both(
            """
            $holder

            pub class Item {
                pub mut label: Str = "a long item label"
            }

            pub fx relabel: (it: Item, h: Holder) Str {
                return setAfter(h, mut it.label)
            }
            """
        )
        assertContains(s, "  kira::Str relabel(const kira::Rc<Item>& itRef_, const kira::Rc<Holder>& h)\n  {\n      const kira::Rc<Item> it = itRef_;\n")
    }

    @Test
    fun aMutArgumentInsideAnotherOneIsRefused() {
        // swap may replace the Item while C++ writes through the reference to its label. With W2.5
        // merged, ExclusivityPass refuses the same call at typing (rules.exclusivity.argument, D37).
        val messages = try {
            unsupported(
                """
            pub class Item {
                pub mut label: Str = "a"
            }

            pub fx swap: (mut it: Item, mut s: Str) Void {
                it = Item {}
                s = "b"
            }

            pub fx feed: () Void {
                mut x: Item = Item {}
                swap(mut x, mut x.label)
            }
            """
            )
        } catch (e: AssertionError) {
            if (e.message?.contains("rules.exclusivity.argument") == true) {
                return
            }
            throw e
        }
        assertTrue(messages.any { it.contains("the mut argument x.label of swap lies inside x, which is passed mut in the same call") }, messages.toString())
    }

    @Test
    fun aMethodWhoseArgumentMayFreeItHoldsItselfForTheCall() {
        // recvfree: labels.add(h.take()) pushed onto the freed Item; implicitcall: put(h.take()) ran put on it.
        val (_, s) = both(
            """
            $holder

            pub class Item {
                pub mut labels: List<Str> = List<Str> {}
                pub mut n: Int32 = 0

                pub mut fx grab: (h: Holder) Void {
                    labels.add(h.take())
                }

                pub mut fx put: (s: Str) Int32 {
                    n = n + 1
                    return n
                }

                pub mut fx grab2: (h: Holder) Int32 {
                    return put(h.take())
                }
            }
            """
        )
        assertContains(s, "  void Item::grab(const kira::Rc<Holder>& h)\n  {\n      [[maybe_unused]] const auto keepAlive_ = weak_from_this().lock();\n")
        assertContains(s, "  std::int32_t Item::grab2(const kira::Rc<Holder>& h)\n  {\n      [[maybe_unused]] const auto keepAlive_ = weak_from_this().lock();\n")
        assertLacks(s, "Item::put(const kira::Str& s)\n  {\n      [[maybe_unused]]")
    }

    // ---- a loop over storage only a temporary keeps alive (convergence round 3) -------------------------

    @Test
    fun aLoopOverAFieldOfATemporaryObjectIsLeftToTheStatementPartsCopy() {
        // loopfresh: for(const kira::Str& s : makeItem()->labels) freed the Item before the first iteration.
        // The statement part (W2.3 9e00cfb) now copies such a range while the temporary lives
        // (kira::List<kira::Str>(makeItem()->labels)), so the loop is its own copy and nothing is
        // refused here, even when the body builds and drops objects whose finally runs: round 3's
        // refusal made evalorder and two CppHoisterTest cases fail on that W2.3.
        val messages = unsupported(
            """
            pub class Item {
                pub mut labels: List<Str> = List<Str> {}

                finally {
                    trace("freed")
                }
            }

            pub class Holder {
                pub mut item: Maybe<Item> = null
            }

            pub fx makeItem: () Item {
                return Item {}
            }

            pub fx makeItems: () List<Item> {
                mut xs: List<Item> = List<Item> {}
                xs.add(makeItem())
                return xs
            }

            pub fx walk: (h: Holder) Int32 {
                mut n: Int32 = 0
                for s: Str in makeItem().labels {
                    other: Item = Item {}
                    n = n + 1
                }
                for s: Str in makeItems()[0].labels {
                    n = n + 1
                }
                for s: Str in makeItem().labels.toArr() {
                    n = n + 1
                }
                return n
            }
            """
        )
        assertEquals(emptyList(), messages)
    }

    // ---- a field's default is checked as a body is (convergence round 3) ----------------------------------

    @Test
    fun aFieldsDefaultLambdaIsCheckedAsABodyIs() {
        // fielddefault: bagloop2's lambda as a field's default compiled, and read freed heap bytes.
        val messages = unsupported(
            """
            pub class Bag {
                pub mut items: List<Str> = List<Str> {}

                pub mut fx grow: () Void {
                    items.add("x")
                }
            }

            pub class Runner {
                pub walk: Fx<Tuple1<Bag>, Int32> = fx(b: Bag) Int32 {
                    c: Bag = b
                    mut n: Int32 = 0
                    for s: Str in b.items {
                        c.grow()
                        n = n + 1
                    }
                    return n
                }
            }
            """
        )
        assertEquals(1, messages.size, messages.toString())
        assertContains(messages.single(), "the loop over b.items, storage an object holds, while its body may change or free it (the call to grow)")
    }

    // ---- a template Fx parameter bound to an Fx an object holds (convergence round 3) ---------------------

    @Test
    fun aTemplateFxParameterTheCodeItRunsMayReplaceIsCopied() {
        // fxparam3: callIt(b.f) with b.f's lambda replacing b.f freed its own capture; fxparam2: callIt(c.f, b)
        // replacing b.f ran the replacement where Kira runs the value passed.
        val source = OopTestSupport.module(
            uri,
            """
            pub class Box {
                require pub mut f: Fx<Tuple0, Str>
            }

            pub fx callIt: (g: Fx<Tuple0, Str>) Str {
                return g()
            }

            pub fx callAfter: (g: Fx<Tuple0, Str>, b: Box) Str {
                b.f = fx() Str {
                    return "other"
                }
                return g()
            }

            pub fx main: () Int32 {
                b: Box = Box { f = fx() Str { return "init" } }
                b.f = fx() Str {
                    b.f = fx() Str {
                        return "other"
                    }
                    return "captured"
                }
                trace(callIt(b.f))
                c: Box = b
                trace(callAfter(c.f, b))
                return 0
            }
            """,
        )
        val fxParams = { program: TypedProgram -> program.modules.flatMap { it.declarations }.filterIsInstance<FnSymbol>().flatMap { it.params }.filter { it.type is KType.Fn } }
        // An Fx parameter that escapes (EscapePass, W2.5) is a kira::Fn, copied as any const& parameter is.
        val escaping = OopTestSupport.emit(source) { program -> fxParams(program).forEach { program.model.fxEscapes[it] = true } }
        assertContains(escaping.source(uri), "  kira::Str callIt(const kira::Fn<kira::Str()>& gRef_)\n  {\n      const kira::Fn<kira::Str()> g = gRef_;\n")
        // With it, g is a template parameter, F_g&&, and the copy is of what it was bound to.
        val h = OopTestSupport.emit(source) { program -> fxParams(program).forEach { program.model.fxEscapes[it] = false } }.header(uri)
        assertContains(h, "  kira::Str callIt(F_g&& gRef_)\n  {\n      const auto g = gRef_;\n")
        assertContains(h, "  kira::Str callAfter(F_g&& gRef_, const kira::Rc<Box>& b)\n  {\n      const auto g = gRef_;\n")
    }

    // ---- a view that leaves the call, and views kept in the caller (convergence round 4) ----------------

    private val bagWithAll = """
        pub class Bag {
            pub mut items: List<Int32> = List<Int32> {}

            pub fx all: () View<Int32> {
                return items.view()
            }

            pub mut fx grow: () Void {
                items.add(9)
            }
        }
    """.trimIndent()

    @Test
    fun aParameterAViewMayLeaveThroughARefBoxIsNotCopied() {
        // lentref: `const kira::List<std::int32_t> xs = xsRef_;` was what `r.value = xs.view()` kept in
        // the caller's box, freed at return (MSVC ASan: heap-use-after-free). A parameter that reaches a
        // box of views is a way out, so xs stays the caller's reference, as a returned view's does.
        val (_, s) = both(
            """
            $bag

            pub fx stash: (xs: List<Int32>, b: Bag, r: Ref<View<Int32>>) Void {
                b.grow()
                r.value = xs.view()
            }
            """
        )
        assertContains(s, "  void stash(const kira::List<std::int32_t>& xs, const kira::Rc<Bag>& b, const kira::Rc<kira::Box<kira::View<std::int32_t>>>& r)\n")
        assertLacks(s, "xsRef_")
    }

    @Test
    fun aViewThatMayLeaveTheCallThroughAHandleOnlyTheCallKeepsIsRefused() {
        // lenthandle: b copied at entry (h.reset() may drop the caller's Bag) was the Bag's last owner, and
        // `return b.items.view()` pointed into it once the call returned; lenthandle2: the same for a method
        // that holds itself (keepAlive_). Both were a heap-use-after-free under MSVC ASan.
        val messages = unsupported(
            """
            pub class Bag {
                pub mut items: List<Int32> = List<Int32> {}

                pub fx viewAfter: (h: Holder) View<Int32> {
                    h.reset()
                    return items.view()
                }

                pub fx sizeAfter: (h: Holder) Size {
                    h.reset()
                    return items.size()
                }
            }

            pub class Holder {
                pub mut bag: Bag = Bag {}

                pub mut fx reset: () Void {
                    bag = Bag {}
                }
            }

            pub fx viewOf: (b: Bag, h: Holder) View<Int32> {
                h.reset()
                return b.items.view()
            }

            pub fx viewOfNow: (b: Bag) View<Int32> {
                return b.items.view()
            }

            pub fx sizeOf: (b: Bag, h: Holder) Size {
                h.reset()
                return b.items.size()
            }
            """
        )
        assertEquals(2, messages.size, messages.toString())
        assertTrue(messages.any { it.contains("the view of items, which may leave viewAfter, points into the object the method runs on") && it.contains("the call to reset may free it") }, messages.toString())
        assertTrue(messages.any { it.contains("the view of b.items, which may leave viewOf, points into storage reached through the parameter b, which the body copies at entry") }, messages.toString())
    }

    @Test
    fun aViewReachedThroughAHandleReadAfterAnEffectThatMayMoveItIsRefused() {
        // viewhandle, viewmethod, viewfn: a view a call returns of storage its receiver or argument reaches
        // (viewOf(b), b.all()) was read after b.grow() (g++ printed garbage, MSVC ASan: heap-use-after-free);
        // viewglobal: the same through a global a function views; viewif: a view an if-expression gives.
        val messages = unsupported(
            """
            $bagWithAll

            mut nums: List<Int32> = List<Int32> {}

            pub fx viewOf: (b: Bag) View<Int32> {
                return b.items.view()
            }

            pub fx allNums: () View<Int32> {
                return nums.view()
            }

            pub fx viaFunction: (b: Bag) Int32 {
                v: View<Int32> = viewOf(b)
                b.grow()
                return v.get(0)
            }

            pub fx viaMethod: (b: Bag) Int32 {
                v: View<Int32> = b.all()
                b.grow()
                return v.get(0)
            }

            pub fx viaGlobal: () Int32 {
                v: View<Int32> = allNums()
                nums.add(9)
                return v.get(0)
            }

            pub fx takenAfter: (b: Bag) Int32 {
                b.grow()
                v: View<Int32> = b.all()
                return v.get(0)
            }

            pub fx readBefore: (b: Bag) Int32 {
                v: View<Int32> = b.all()
                n: Int32 = v.get(0)
                b.grow()
                return n
            }

            pub fx viaIf: (b: Bag, c: Bag, first: Bool) Int32 {
                v: View<Int32> = if first { b.items.view() } else { c.items.view() }
                b.grow()
                return v.get(0)
            }
            """
        )
        assertEquals(4, messages.size, messages.toString())
        assertTrue(messages.any { it.contains("the view v, taken of b.items, read after the call to grow") }, messages.toString())
        assertEquals(2, messages.count { it.contains("the view v, which may point into storage reached through b, read after the call to grow") }, messages.toString())
        assertTrue(messages.any { it.contains("the view v, which may point into storage reached through allNums(), read after the call to add") }, messages.toString())
    }

    @Test
    fun aViewKeptInAListOrCapturedByALambdaIsCheckedAsTheViewIs() {
        // viewlist: vs.add(b.items.view()), b.grow(), vs.get(0).get(1); viewcapture: a lambda capturing v of
        // b.items, then b.grow(), then f(); viewcapture2: the same with a local List (g++ garbage, MSVC ASan:
        // heap-use-after-free on each).
        val messages = unsupported(
            """
            $bag

            pub fx kept: (b: Bag) Int32 {
                mut vs: List<View<Int32>> = List<View<Int32>> {}
                vs.add(b.items.view())
                b.grow()
                return vs.get(0).get(0)
            }

            pub fx captured: (b: Bag) Int32 {
                v: View<Int32> = b.items.view()
                f: Fx<Tuple0, Int32> = fx() Int32 {
                    return v.get(0)
                }
                b.grow()
                return f()
            }

            pub fx capturedLocal: () Int32 {
                mut xs: List<Int32> = List<Int32> {}
                xs.add(1)
                v: View<Int32> = xs.view()
                f: Fx<Tuple0, Int32> = fx() Int32 {
                    return v.get(0)
                }
                xs.add(9)
                return f()
            }

            pub fx capturedAfter: (b: Bag) Int32 {
                b.grow()
                v: View<Int32> = b.items.view()
                f: Fx<Tuple0, Int32> = fx() Int32 {
                    return v.get(0)
                }
                return f()
            }
            """
        )
        assertEquals(3, messages.size, messages.toString())
        assertTrue(messages.any { it.contains("the view vs, taken of b.items, read after the call to grow") }, messages.toString())
        assertTrue(messages.any { it.contains("the view v, captured by a lambda that may run after the call to grow, which may change or free b.items") }, messages.toString())
        assertTrue(messages.any { it.contains("the view v, captured by a lambda that may run after the call to add, which may change or free xs") }, messages.toString())
    }

    @Test
    fun aViewTheCallReadsAfterALaterArgumentOrALoopBodyMovesItIsRefused() {
        // viewrecv, viewrecv2: v.get(b.growAndGet()) and at(v, b.growAndGet()) read v after the argument grew
        // b.items; loopview, loopview2: a range-for over b.items.view() or b.all() whose body grows b.items
        // (g++ printed garbage or segfaulted on each; W2.5 refuses the four as exclusivity, this does not rely on it).
        val messages = unsupportedUnlessW25("rules.exclusivity.order", "rules.exclusivity.loop") {
            """
            pub class Bag {
                pub mut items: List<Int32> = List<Int32> {}

                pub fx all: () View<Int32> {
                    return items.view()
                }

                pub mut fx growAndGet: () Size {
                    items.add(9)
                    return 0
                }
            }

            pub fx at: (v: View<Int32>, i: Size) Int32 {
                return v.get(i)
            }

            pub fx asReceiver: (b: Bag) Int32 {
                v: View<Int32> = b.items.view()
                return v.get(b.growAndGet())
            }

            pub fx asArgument: (b: Bag) Int32 {
                v: View<Int32> = b.items.view()
                return at(v, b.growAndGet())
            }

            pub fx overView: (b: Bag) Int32 {
                mut n: Int32 = 0
                for x: Int32 in b.items.view() {
                    n = n + x
                    b.growAndGet()
                }
                return n
            }

            pub fx overCall: (b: Bag) Int32 {
                mut n: Int32 = 0
                for x: Int32 in b.all() {
                    n = n + x
                    b.growAndGet()
                }
                return n
            }

            pub fx argumentFirst: (b: Bag) Int32 {
                i: Size = b.growAndGet()
                v: View<Int32> = b.items.view()
                return v.get(i)
            }

            pub fx loopLeavesItAlone: (b: Bag) Int32 {
                mut n: Int32 = 0
                for x: Int32 in b.all() {
                    n = n + x
                }
                return n
            }
            """
        } ?: return
        assertEquals(4, messages.size, messages.toString())
        assertTrue(messages.any { it.contains("the view v, read by the call to get after the call to growAndGet among its arguments") }, messages.toString())
        assertTrue(messages.any { it.contains("the view v, read by the call to at after the call to growAndGet among its arguments") }, messages.toString())
        assertTrue(messages.any { it.contains("the loop over b.items.view(), a view of b.items, while its body may change or free that storage") }, messages.toString())
        assertTrue(messages.any { it.contains("the loop over b.all(), a view of storage reached through b") }, messages.toString())
    }

    @Test
    fun aViewReturnedThroughALocalsHandleIsRefused() {
        // lentlocal: `return b.items.view()` with b a local Bag, the object's only owner (freed at return).
        // W2.5's EscapePass refuses the same return (rules.escape.view-return, D5); this does not rely on it.
        val messages = unsupportedUnlessW25("rules.escape.view-return") {
            """
            pub class Bag {
                pub mut items: List<Int32> = List<Int32> {}
            }

            pub fx viewOfFresh: () View<Int32> {
                b: Bag = Bag {}
                b.items.add(1)
                return b.items.view()
            }

            pub fx viewOfParameter: (b: Bag) View<Int32> {
                return b.items.view()
            }
            """
        } ?: return
        assertEquals(1, messages.size, messages.toString())
        assertContains(messages.single(), "the returned view b.items.view() points into storage reached through the local b")
    }

    @Test
    fun aViewOfABlocksLocalReadAfterTheBlockEndsIsRefused() {
        // viewblock: v = x.items.view() inside an if, with x the block's local Bag, then v.get(1) after the
        // block, which destroyed x's object (MSVC ASan: heap-use-after-free; it printed 2 and 2 on g++).
        val messages = unsupportedUnlessW25("rules.escape") {
            """
            pub class Bag {
                pub mut items: List<Int32> = List<Int32> {}
            }

            pub fx makeBag: () Bag {
                return Bag {}
            }

            pub fx afterBlock: (c: Bool) Int32 {
                empty: List<Int32> = List<Int32> {}
                mut v: View<Int32> = empty.view()
                if c {
                    x: Bag = makeBag()
                    v = x.items.view()
                }
                return v.get(0)
            }

            pub fx afterLoop: () Int32 {
                empty: List<Int32> = List<Int32> {}
                mut v: View<Int32> = empty.view()
                mut i: Int32 = 0
                while i < 2 {
                    xs: List<Int32> = List<Int32> {}
                    v = xs.view()
                    i = i + 1
                }
                return v.get(0)
            }

            pub fx insideBlock: (c: Bool) Int32 {
                mut n: Int32 = 0
                if c {
                    x: Bag = makeBag()
                    v: View<Int32> = x.items.view()
                    n = v.get(0)
                }
                return n
            }
            """
        } ?: return
        assertEquals(2, messages.size, messages.toString())
        assertTrue(messages.any { it.contains("the view v, taken of x.items, read after the end of the block that declares x") }, messages.toString())
        assertTrue(messages.any { it.contains("the view v, taken of xs, read after the end of the block that declares xs") }, messages.toString())
    }

    @Test
    fun anObjectGivenAViewIntoATemporaryIsRefused() {
        // refview: a Ref's box given a view into makeList(), destroyed at the end of the statement
        // (g++ printed 2, zig c++ 334). The statement part refuses the same construction when the
        // rewire lets it lower one; this part refuses it itself.
        val messages = unsupported(
            """
            pub fx makeList: () List<Int32> {
                return List<Int32> {}
            }

            pub fx boxed: () Int32 {
                return Ref<View<Int32>> { value = makeList().view() }.value.get(0)
            }

            pub fx boxedLocal: () Int32 {
                xs: List<Int32> = makeList()
                return Ref<View<Int32>> { value = xs.view() }.value.get(0)
            }
            """
        )
        assertEquals(1, messages.size, messages.toString())
        assertContains(messages.single(), "the value of this Ref<View<Int32>> is given a view into makeList(), a temporary")
    }

    // ---- the goldens ------------------------------------------------------------------------------------

    @Test
    fun theOopGoldensNeedNoGuard() {
        // chain, sender and classes stay as expected/ spells them: no copy and no hold in a body
        // (their heads and class bodies, a kira::Shared base among them, are CppOopGoldenShapeTest's).
        for (name in listOf("chain", "sender", "classes")) {
            val files = OopTestSupport.emitCase(OopTestSupport.case(name))
            assertTrue(files.any { it.relative.endsWith(".kira.cxx") } && files.any { it.relative.endsWith(".kira.hxx") }, "$name emitted ${files.map { it.relative }}")
            files.forEach { f -> assertLacks(f.text, "Ref_", "keepAlive_") }
        }
    }
}
