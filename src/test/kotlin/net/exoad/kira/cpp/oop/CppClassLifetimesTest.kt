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

    // ---- a view of a parameter copied at entry points into the copy, and lives only for the call (decision 4b) ----

    private val bag = """
        pub class Bag {
            pub mut items: List<Int32> = List<Int32> {}

            pub mut fx grow: () Void {
                items.add(9)
            }
        }
    """.trimIndent()

    @Test
    fun aViewOfACopiedParameterIsTakenOfTheCopy() {
        // xs is copied at entry (b.grow() may reach the List it names), and its view is an argument
        // only, used within the one full-expression: it points into the copy, which lives for the
        // whole body. A view that could leave the call is never of a non-view parameter (4b's
        // return rule, ViewPass), so no parameter is ever kept as the caller's reference for one.
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

    // ---- a lambda made after an effect captures what the parameter or this names then (second-class round 1) ----

    private val renaming = """
        pub class C {
            require pub mut name: Str

            pub mut fx set: (s: Str) Fx<Tuple0, Str> {
                name = "b"
                return fx() Str {
                    return s
                }
            }

            pub mut fx setEarly: (s: Str) Fx<Tuple0, Str> {
                g: Fx<Tuple0, Str> = fx() Str {
                    return s
                }
                name = "b"
                return g
            }
        }
    """.trimIndent()

    @Test
    fun aParameterALambdaCapturesAfterAnEffectThatMayReachItIsCopiedAtEntry() {
        // capparam: `c.set(d.name)` with set renaming c, then building `fx() Str { return s }`, printed
        // `b` on g++, zig and MSVC where Kira's parameter is the value passed, `a`: C++ copies s into
        // the capture when the lambda is made, after the rename. A lambda made before it captures `a`.
        val (_, s) = both(renaming)
        assertContains(s, "  kira::Fn<kira::Str()> C::set(const kira::Str& sRef_)\n  {\n      const kira::Str s = sRef_;\n")
        assertContains(s, "  kira::Fn<kira::Str()> C::setEarly(const kira::Str& s)\n")
        assertLacks(s, "C::setEarly(const kira::Str& sRef_)")
    }

    @Test
    fun aParameterALambdaCapturesAfterItsObjectMayBeFreedIsCopiedAtEntry() {
        // capparamfree: s bound to c.item.label, h.reset() freeing that Item through a second handle,
        // then a lambda capturing s: MSVC ASan, heap-use-after-free.
        val (_, s) = both(
            """
            pub class Item {
                require pub label: Str
            }

            pub class Holder {
                pub mut item: Item = Item { "short" }

                pub mut fx reset: () Void {
                    item = Item { "other" }
                }
            }

            pub fx later: (s: Str, h: Holder) Fx<Tuple0, Str> {
                h.reset()
                return fx() Str {
                    return s
                }
            }
            """
        )
        assertContains(s, "  kira::Fn<kira::Str()> later(const kira::Str& sRef_, const kira::Rc<Holder>& h)\n  {\n      const kira::Str s = sRef_;\n")
    }

    @Test
    fun aMethodWhoseLambdaCapturesItselfAfterAReleaseHoldsItself() {
        // capthis: selfkill2 with `name` read by a lambda made after tree.clear(), which freed the Kid:
        // shared_from_this() threw std::bad_weak_ptr on g++ and zig, and MSVC ASan reported a
        // heap-use-after-free. The hold keeps the Kid for the call, so the capture finds its owner.
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

                pub fx leave: () Fx<Tuple0, Str> {
                    tree.clear()
                    return fx() Str {
                        return name
                    }
                }

                pub fx early: () Fx<Tuple0, Str> {
                    g: Fx<Tuple0, Str> = fx() Str {
                        return name
                    }
                    tree.clear()
                    return g
                }
            }
            """
        )
        assertContains(h, "  class Kid final : public kira::Shared<Kid>\n")
        assertContains(s, "  kira::Fn<kira::Str()> Kid::leave() const\n  {\n      [[maybe_unused]] const auto keepAlive_ = weak_from_this().lock();\n")
        assertLacks(s, "Kid::early() const\n  {\n      [[maybe_unused]]")
    }

    // ---- no object holds a view (decision 4b) -------------------------------------------------------------

    @Test
    fun anObjectHoldingAViewIsAnInternalErrorNeverAUserOne() {
        // refview's `Ref<View<Int32>> { value = makeList().view() }` is W2.5's ViewPass's
        // (rules.view.type: a Ref of a view cannot be written). Should one reach the classes part,
        // it is the checker's bug, reported as cpp.internal, and never lowered as it is.
        val program = """
            pub fx makeList: () List<Int32> {
                return List<Int32> {}
            }

            pub fx boxed: () Int32 {
                return Ref<View<Int32>> { value = makeList().view() }.value.get(0)
            }
        """
        val diagnostics = try {
            emit(program).module(uri).diagnostics.filter { it.isError }
        } catch (e: AssertionError) {
            if (e.message?.contains("the typer refused") == true && e.message!!.contains("rules.view.type")) {
                return
            }
            throw e
        }
        assertTrue(diagnostics.any { it.code == CppModuleEmitterFactory.INTERNAL_CODE && it.message.contains("the value of Ref<View<Int32>> holds a view or a pointer") }, OopTestSupport.render(diagnostics))
        assertTrue(diagnostics.none { it.code == CppModuleEmitterFactory.UNSUPPORTED_CODE }, OopTestSupport.render(diagnostics))
    }

    // ---- a type-parameter receiver, read by C++ after the arguments (second-class round 2) ------------

    private val named = """
        pub trait Named {
            pub fx greet: (n: Int32) Str;
        }

        pub class Kid: Named {
            require pub name: Str

            override pub fx greet: (n: Int32) Str {
                return name
            }
        }

        pub class Holder {
            pub mut item: Kid = Kid { "short" }

            pub mut fx reset: () Int32 {
                item = Kid { "other" }
                return 7
            }
        }
    """.trimIndent()

    @Test
    fun aTypeParameterReceiverTheArgumentsMayFreeIsCopiedAtEntry() {
        // genrecv: `x.greet(h.reset())` is kira::deref(x).greet(t0_), x a const T& bound to c.item: C++ reads x
        // after reset replaced the Kid it named, and ran greet on the new one (genrecv printed `other 7`;
        // genrecv2, x = c.kids[0], was a heap-use-after-free under MSVC ASan). The copy keeps the Kid passed.
        // A template's definition is in the header.
        val (h, _) = both(
            """
            $named

            pub fx callIt<T: Named>: (x: T, h: Holder) Str {
                return x.greet(h.reset())
            }
            """
        )
        assertContains(h, "(const T& xRef_, const kira::Rc<Holder>& h)\n  {\n      const T x = xRef_;\n")
    }

    @Test
    fun aTypeParameterReceiverNoArgumentReachesIsNotCopied() {
        val (h, s) = both(
            """
            $named

            pub fx callIt<T: Named>: (x: T, n: Int32) Str {
                return x.greet(n)
            }
            """
        )
        assertLacks(h + s, "xRef_")
    }

    @Test
    fun aTypeParameterReceiverAnObjectHoldsIsRefusedWhenTheArgumentsMayReplaceIt() {
        // The field is the object's, not the body's: no copy at entry reaches it, so the call is refused by name.
        val messages = unsupported(
            """
            $named

            pub class Keep<T: Named> {
                require pub mut item: T

                pub fx poke: (h: Holder) Str {
                    return item.greet(h.reset())
                }
            }
            """
        )
        assertTrue(messages.any { it.startsWith("the call to greet on item, a T an object holds, whose arguments may change or free it") }, messages.joinToString("\n"))
    }

    @Test
    fun aTypeParameterReceiverInALocalIsLeftAlone() {
        val messages = unsupported(
            """
            $named

            pub fx callLocal<T: Named>: (x: T, h: Holder) Str {
                y: T = x
                return y.greet(h.reset())
            }
            """
        )
        assertTrue(messages.none { it.contains("greet") }, messages.joinToString("\n"))
    }

    // ---- a callee C++ supplies runs its Fx arguments during the call (second-class round 2) ------------

    private val externItem = """
        pub class Item {
            require pub label: Str
            pub mut count: Int32 = 0
        }

        pub class Box {
            pub mut item: Item = Item { "short" }

            pub mut fx reset: () Void {
                item = Item { "other" }
            }
        }

        pub fx bump: (mut n: Int32, f: Fx<Tuple0, Void>) Void;
        pub fx measure: (s: Str, f: Fx<Tuple0, Void>) Int32;
        pub fx plain: (s: Str) Int32;
    """.trimIndent()

    @Test
    fun aMutArgumentAnExternsCallbackMayFreeIsRefused() {
        // externmut: bump runs its callback, then writes n, bound into the Item the callback freed (MSVC ASan
        // heap-use-after-free). Its during is what its Fx arguments may do (contract 5.4.3), no longer nothing.
        val messages = unsupported(
            """
            $externItem

            pub fx main: () Int32 {
                h: Box = Box {}
                c: Box = h
                bump(mut h.item.count, fx() Void {
                    c.reset()
                })
                return h.item.count
            }
            """
        )
        assertTrue(messages.any { it.startsWith("the mut argument h.item.count, storage an object holds, passed to bump, which may change or free that object") }, messages.joinToString("\n"))
    }

    @Test
    fun aMutArgumentAnExternsFxValueMayReachIsRefused() {
        // An Fx value the analysis cannot see into may do anything: the callee's during is everything.
        val messages = unsupported(
            """
            $externItem

            pub fx apply: (h: Box, f: Fx<Tuple0, Void>) Void {
                bump(mut h.item.count, f)
            }
            """
        )
        assertTrue(messages.any { it.startsWith("the mut argument h.item.count, storage an object holds, passed to bump") }, messages.joinToString("\n"))
    }

    @Test
    fun aMutArgumentAnExternsHarmlessCallbackCannotReachIsAccepted() {
        val messages = unsupported(
            """
            $externItem

            pub fx main: () Int32 {
                h: Box = Box {}
                bump(mut h.item.count, fx() Void {
                })
                return h.item.count
            }
            """
        )
        assertTrue(messages.none { it.contains("bump") }, messages.joinToString("\n"))
    }

    @Test
    fun aParameterAnExternReadsAfterItsCallbackIsCopiedAtEntry() {
        // externstr: measure runs its callback, then reads s, a const& bound to c.item.label that the callback
        // freed (g++ printed freed heap bytes, MSVC ASan heap-use-after-free). A C++ callee copies nothing.
        val (_, s) = both(
            """
            $externItem

            pub fx later: (s: Str, h: Box) Int32 {
                return measure(s, fx() Void {
                    h.reset()
                })
            }
            """
        )
        assertContains(s, "  std::int32_t later(const kira::Str& sRef_, const kira::Rc<Box>& h)\n  {\n      const kira::Str s = sRef_;\n")
    }

    @Test
    fun aParameterAnExternWithoutAnFxArgumentReadsIsNotCopied() {
        val (_, s) = both(
            """
            $externItem

            pub fx direct: (s: Str, h: Box) Int32 {
                return plain(s)
            }
            """
        )
        assertLacks(s, "sRef_")
    }

    @Test
    fun storageAnObjectHoldsPassedToAnExternWhoseCallbackMayFreeItIsRefused() {
        // externstr with the argument a field: no copy at entry reaches it.
        val messages = unsupported(
            """
            $externItem

            pub fx later: (c: Box, h: Box) Int32 {
                return measure(c.item.label, fx() Void {
                    h.reset()
                })
            }
            """
        )
        assertTrue(messages.any { it.startsWith("the argument c.item.label, storage an object holds, passed by const& to measure") }, messages.joinToString("\n"))
    }

    private val runner = """
        pub trait Runner {
            pub fx run: (f: Fx<Tuple0, Void>) Int32;
        }

        pub class Slot {
            require pub mut r: Runner

            pub mut fx clear: (other: Runner) Void {
                r = other
            }
        }
    """.trimIndent()

    @Test
    fun aTraitReceiverAnObjectHoldsThatTheCallbackMayReplaceIsRefused() {
        // Runner.run may be a C++ override (the chain driver's test double), which runs the callback and then reads
        // its own object: the callback replaced the slot that was that object's last owner.
        val messages = unsupported(
            """
            $runner

            pub fx go: (s: Slot, spare: Runner) Int32 {
                return s.r.run(fx() Void {
                    s.clear(spare)
                })
            }
            """
        )
        assertTrue(messages.any { it.startsWith("the call to run on s.r, storage an object holds, whose body C++ may supply") }, messages.joinToString("\n"))
    }

    @Test
    fun aTraitReceiverParameterTheCallbackMayFreeIsCopiedAtEntry() {
        val (_, s) = both(
            """
            $runner

            pub fx go: (r: Runner, s: Slot, spare: Runner) Int32 {
                return r.run(fx() Void {
                    s.clear(spare)
                })
            }
            """
        )
        assertContains(s, "  std::int32_t go(const kira::Rc<Runner>& rRef_, const kira::Rc<Slot>& s, ", "  {\n      const kira::Rc<Runner> r = rRef_;\n")
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
