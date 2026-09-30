package net.exoad.kira.cpp.oop

import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitParts
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleEmitterFactory
import net.exoad.kira.cpp.exprs.CppExprTestSupport
import net.exoad.kira.cpp.exprs.CppExprTestSupport.Module
import net.exoad.kira.cpp.support.CppToolchain
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Classes under copy by default (50-round4, W2.4's half). A class body never guards: no
 * parameter is copied at entry (`vRef_`), no method holds itself (`keepAlive_`), and nothing
 * is refused because a reference might meet a write. Every caller keeps invariant I instead
 * (W2.3's policy): a `const&` argument is storage nothing changes during the call, or it is
 * copied, `T(e)`; a class handle receiver is held, `kira::Rc<C>(h)->m(...)`; an `Fx` value's
 * callee is copied, `kira::Fn<...>(f)(...)`; a range the body may disturb is iterated as a copy.
 *
 * Each program below is one of round 1-3's CppClassLifetimesTest shapes, the reduced form of a
 * probe that printed freed heap bytes, crashed or read the wrong value before a guard existed.
 * Each now prints Kira's value (written from Kira's semantics before running) on gcc, clang and
 * msvc, with the copy where the design says. What W2.4 used to refuse for a `mut` argument is
 * W2.5's rule M (`rules.exclusivity.mut`); what stays refused, stays refused by the rules.
 */
class CppClassCopyTest {
    private val uri = "test:main"

    /** alb's element: past every small-string buffer, so a read of it after the free reads freed heap. */
    private val ALB = "a long list element, past the small string buffer 000000"

    private fun unsupported(body: String): List<String> =
        OopTestSupport.emit(OopTestSupport.module(uri, body)).module(uri).diagnostics.filter { it.code == CppModuleEmitterFactory.UNSUPPORTED_CODE }.map { it.message }

    private fun typerErrors(body: String): List<String> = OopTestSupport.typerErrors(OopTestSupport.module(uri, body))

    private fun assertRefusedBy(code: String, body: String, what: String = code) {
        val errors = typerErrors(body)
        assertTrue(errors.any { it.contains("[$code]") }, "$what: expected $code, got:\n${errors.joinToString("\n")}")
    }

    private fun assertContains(text: String, vararg wanted: String) {
        wanted.forEach { assertTrue(text.contains(it), "expected to find:\n$it\nin:\n$text") }
    }

    private fun assertLacks(text: String, vararg unwanted: String) {
        unwanted.forEach { assertTrue(!text.contains(it), "expected not to find:\n$it\nin:\n$text") }
    }

    // ---- the programs the lifetimes analysis guarded, each run -------------------------------------

    private val lifetimes = Module(
        "oop:lifetimes",
        """
        // uaflist: a Str element of an object's List, given to a method that grows that List (g++: std::bad_alloc).
        pub class Log {
            pub mut lines: List<Str> = List<Str> {}

            pub mut fx append: (s: Str) Str {
                mut i: Int32 = 0
                while i < 64 {
                    lines.add("one long line that is not in the small string buffer 0000")
                    i += 1
                }
                return s
            }
        }

        pub fx appendOwnLine: () Str {
            a: Log = Log {}
            a.lines.add("the first line, long enough to live on the heap 0000000")
            b: Log = a
            return a.append(b.lines[0])
        }

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

        pub class Item {
            pub mut label: Str = "a long item label that is not in the small string buffer 00"
            pub mut labels: List<Str> = List<Str> {}
            pub mut n: Int32 = 0

            // mutthis: its own field passed mut to a call that frees the Item (a segfault, an ASan heap-use-after-free).
            pub mut fx relabel: (h: Holder) Str {
                return setAfter(h, mut label)
            }

            // recvfree: labels.add(h.take()) pushed onto the freed Item.
            pub mut fx grab: (h: Holder) Int32 {
                labels.add(h.take())
                return labels.size() as Int32
            }

            pub mut fx put: (s: Str) Int32 {
                n = n + 1
                return n
            }

            // implicitcall: put(h.take()) ran put on the freed Item.
            pub mut fx grab2: (h: Holder) Int32 {
                return put(h.take())
            }
        }

        // uaf3: consume(h, h.item.unwrap().label) with consume clearing h first (g++: freed heap bytes).
        pub fx consume: (h: Holder, s: Str) Str {
            h.clear()
            return s
        }

        pub fx consumeOwn: () Str {
            h: Holder = Holder {}
            h.item = Item {}
            return consume(h, h.item.unwrap().label)
        }

        pub fx setAfter: (h: Holder, mut s: Str) Str {
            h.clear()
            t: Str = s
            s = "y"
            return t
        }

        pub fx relabelOwn: () Str {
            h: Holder = Holder {}
            h.item = Item {}
            return h.item.unwrap().relabel(h)
        }

        // mutparam: the handle parameter was a const kira::Rc<Item>& bound to h->item, which h.clear() dropped.
        pub fx relabelParam: (it: Item, h: Holder) Str {
            return setAfter(h, mut it.label)
        }

        pub fx relabelParamOwn: () Str {
            h: Holder = Holder {}
            h.item = Item {}
            return relabelParam(h.item.unwrap(), h)
        }

        pub fx grabOwn: () Int32 {
            h: Holder = Holder {}
            h.item = Item {}
            a: Int32 = h.item.unwrap().grab(h)
            h.item = Item {}
            b: Int32 = h.item.unwrap().grab2(h)
            return a * 10 + b
        }

        // genalias4: b.take(g.n) with g = b printed 7 where Kira gives 1.
        pub class Twin<T> {
            pub mut n: T
            require pub m: T

            pub mut fx take: (v: T) T {
                n = m
                return v
            }
        }

        pub fx twinOwn: () Int32 {
            b: Twin<Int32> = Twin<Int32> { n = 1, m = 7 }
            g: Twin<Int32> = b
            return b.take(g.n)
        }

        // An override taken by const& where its own declaration takes the Int32 by value (was vRef_).
        pub trait Feed<T> {
            pub mut fx take: (v: T) T;
        }

        pub class Alias: Feed<Int32> {
            pub mut n: Int32 = 1

            override pub mut fx take: (v: Int32) Int32 {
                n = 100
                return v
            }
        }

        pub fx aliasOwn: () Int32 {
            a: Alias = Alias {}
            f: Feed<Int32> = a
            g: Alias = a
            x: Int32 = f.take(a.n)
            y: Int32 = a.take(g.n)
            return x * 1000 + y
        }

        // selfkill2 and capthis: a method that drops its object's only owner, then reads itself.
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

            pub fx leaveLater: () Fx<Tuple0, Str> {
                tree.clear()
                return fx() Str {
                    return name
                }
            }
        }

        pub fx leaveOwn: () Str {
            t: Tree = Tree {}
            t.kids.add(Kid { name = "a kid's name long enough to live on the heap 0000", tree = t })
            return t.kids[0].leave()
        }

        pub fx leaveLaterOwn: () Str {
            t: Tree = Tree {}
            t.kids.add(Kid { name = "a kid's name long enough to live on the heap 0000", tree = t })
            g: Fx<Tuple0, Str> = t.kids[0].leaveLater()
            return g()
        }

        // A class template's method that drops its own object's owner.
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

        pub fx detachOwn: () Str {
            head: Node<Str> = Node<Str> { value = "head" }
            head.next = Node<Str> { value = "the next node's value, long enough for the heap 00" }
            return head.next.unwrap().detach(head)
        }

        // A trait call whose override drops the object the calling method runs on.
        pub trait Step {
            pub mut fx step: () Void;
        }

        pub class Dropper: Step {
            pub mut spare: Maybe<Runner> = null

            override pub mut fx step: () Void {
                spare = null
            }
        }

        pub class Runner {
            require pub s: Step
            require pub n: Int32

            pub fx run: () Int32 {
                s.step()
                return n
            }
        }

        pub fx runOwn: () Int32 {
            d: Dropper = Dropper {}
            d.spare = Runner { s = d, n = 42 }
            return d.spare.unwrap().run()
        }

        // A trait default body that drops its object, then calls it (refused as needing a hold, round 2).
        pub trait Leaves {
            pub mut fx drop: () Void;
            pub fx id: () Int32;
            pub fx leave: () Int32 {
                drop()
                return id()
            }
        }

        pub class Home {
            pub mut leaf: Maybe<Leaf> = null
        }

        pub class Leaf: Leaves {
            require pub home: Home
            require pub k: Int32

            override pub mut fx drop: () Void {
                home.leaf = null
            }

            override pub fx id: () Int32 {
                return k
            }
        }

        pub fx leaveHome: () Int32 {
            h: Home = Home {}
            h.leaf = Leaf { home = h, k = 42 }
            return h.leaf.unwrap().leave()
        }

        // The chain golden's has(): a loop over a field of this whose body makes a trait call iterates a copy.
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

            pub mut fx load: () Void {
                loaded.add(Stop {})
            }

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

        pub fx chainHas: () Bool {
            c: Chain = Chain {}
            c.load()
            return c.has("stop")
        }

        // A mut argument in an object a handle parameter holds (STABLE, rule M), the callee growing it through another handle.
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

        pub fx feed: (b: Bag, c: Bag) Int32 {
            both(c, mut b.items)
            return b.items.size() as Int32
        }

        pub fx feedOwn: () Int32 {
            b: Bag = Bag {}
            return feed(b, b)
        }

        // fxself: a lambda assigning b.f while it runs as b.f (g++: freed heap bytes for its capture).
        pub class FxBox {
            require pub mut f: Fx<Tuple0, Int32>

            pub mut fx run: () Int32 {
                return f()
            }
        }

        pub fx fxSelf: () Int32 {
            b: FxBox = FxBox { f = fx() Int32 { return 1 } }
            b.f = fx() Int32 {
                b.f = fx() Int32 { return 2 }
                return 3
            }
            x: Int32 = b.run()
            return x * 10 + b.run()
        }

        // lamparam: a lambda's const& parameter bound to h's Item's label, read after h.clear().
        pub fx callWith: (h: Holder, f: Fx<Tuple1<Str>, Str>) Str {
            return f(h.item.unwrap().label)
        }

        pub fx lamParam: () Str {
            h: Holder = Holder {}
            h.item = Item {}
            return callWith(h, fx(s: Str) Str {
                h.clear()
                return s
            })
        }

        // A view of a List parameter after the body grows the List it was given (decision 4b's W4 positions).
        pub class IntBag {
            pub mut items: List<Int32> = List<Int32> {}

            pub mut fx grow: () Void {
                mut i: Int32 = 0
                while i < 64 {
                    items.add(9)
                    i += 1
                }
            }
        }

        pub fx total: (v: View<Int32>) Int32 {
            return v.get(0)
        }

        pub fx sumAfter: (xs: List<Int32>, b: IntBag) Int32 {
            b.grow()
            return total(xs.view())
        }

        pub fx sumAfterOwn: () Int32 {
            b: IntBag = IntBag {}
            b.items.add(5)
            return sumAfter(b.items, b)
        }

        // loopfresh: loops over storage only a temporary keeps alive, bodies that build and drop objects.
        mut freed: Int32 = 0

        pub class Bin {
            pub mut labels: List<Str> = List<Str> {}

            finally {
                freed += 1
            }
        }

        pub fx makeBin: () Bin {
            b: Bin = Bin {}
            b.labels.add("first label, long enough for the heap 000000000000")
            b.labels.add("second label, long enough for the heap 00000000000")
            return b
        }

        pub fx makeBins: () List<Bin> {
            mut xs: List<Bin> = List<Bin> {}
            xs.add(makeBin())
            return xs
        }

        pub fx walkFresh: () Int32 {
            mut n: Int32 = 0
            for s: Str in makeBin().labels {
                other: Bin = Bin {}
                n = n + 1
            }
            for s: Str in makeBins()[0].labels {
                n = n + 1
            }
            for s: Str in makeBin().labels.toArr() {
                n = n + 1
            }
            return n
        }

        // fxparam3 and fxparam2: a template Fx parameter bound to an Fx an object holds, which the code it runs replaces.
        pub class FBox {
            require pub mut f: Fx<Tuple0, Str>
        }

        pub fx callIt: (g: Fx<Tuple0, Str>) Str {
            return g()
        }

        pub fx callAfter: (g: Fx<Tuple0, Str>, b: FBox) Str {
            b.f = fx() Str {
                return "other"
            }
            return g()
        }

        pub fx fxParams: () Str {
            b: FBox = FBox { f = fx() Str { return "init" } }
            b.f = fx() Str {
                b.f = fx() Str {
                    return "other"
                }
                return "captured"
            }
            first: Str = callIt(b.f)
            b.f = fx() Str {
                return "passed"
            }
            c: FBox = b
            second: Str = callAfter(c.f, b)
            return "${'$'}{first}:${'$'}{second}"
        }

        // capparam: a lambda made after the body renames the object its parameter was bound into.
        pub class Renamer {
            require pub mut name: Str

            pub mut fx set: (s: Str) Fx<Tuple0, Str> {
                name = "b"
                return fx() Str {
                    return s
                }
            }
        }

        pub fx capParam: () Str {
            c: Renamer = Renamer { "a" }
            d: Renamer = c
            g: Fx<Tuple0, Str> = c.set(d.name)
            return g()
        }

        // capparamfree: the same after a call that frees the object (MSVC ASan: heap-use-after-free).
        pub class Label {
            require pub label: Str
        }

        pub class Shelf {
            pub mut item: Label = Label { "short" }

            pub mut fx reset: () Void {
                item = Label { "other" }
            }
        }

        pub fx later: (s: Str, h: Shelf) Fx<Tuple0, Str> {
            h.reset()
            return fx() Str {
                return s
            }
        }

        pub fx capParamFree: () Str {
            c: Shelf = Shelf {}
            c.item = Label { "a long label that is not in the small string buffer 0000000" }
            h: Shelf = c
            g: Fx<Tuple0, Str> = later(c.item.label, h)
            return g()
        }

        // genrecv: a type-parameter receiver the arguments replace (it printed `other`).
        pub trait Greets {
            pub fx greet: (n: Int32) Str;
        }

        pub class Pup: Greets {
            require pub name: Str

            override pub fx greet: (n: Int32) Str {
                return name
            }
        }

        pub class Crib {
            pub mut kid: Pup = Pup { "short" }

            pub mut fx reset: () Int32 {
                kid = Pup { "other" }
                return 7
            }
        }

        pub fx greetIt<T: Greets>: (x: T, h: Crib) Str {
            return x.greet(h.reset())
        }

        pub fx genRecv: () Str {
            c: Crib = Crib {}
            c.kid = Pup { "a long pup name that is not in the small string buffer 0000" }
            return greetIt<Pup>(c.kid, c)
        }

        // A type-parameter field receiver beside arguments that replace another object's field.
        pub class Keep<T: Greets> {
            require pub mut item: T

            pub fx poke: (h: Crib) Str {
                return item.greet(h.reset())
            }
        }

        pub fx keepPoke: () Str {
            c: Crib = Crib {}
            k: Keep<Pup> = Keep<Pup> { item = c.kid }
            return k.poke(c)
        }

        // dispstr: a Str in object storage given to a dispatched call beside a List of Fx that frees it.
        pub class Base {
            pub fx fire: (s: Str, fs: List<Fx<Tuple0, Void>>) Str {
                return "base"
            }
        }

        pub class Sub: Base {
            override pub fx fire: (s: Str, fs: List<Fx<Tuple0, Void>>) Str {
                for f: Fx<Tuple0, Void> in fs {
                    f()
                }
                return s
            }
        }

        pub fx dispStr: () Str {
            c: Holder = Holder {}
            c.item = Item {}
            h: Holder = c
            r: Base = Sub {}
            return r.fire(c.item.unwrap().label, [fx() Void {
                h.clear()
            }])
        }

        // A trait receiver in object storage (and one given as a parameter) whose callback replaces its only owner.
        pub trait Runs {
            pub fx run: (f: Fx<Tuple0, Void>) Int32;
        }

        pub class R1: Runs {
            require pub k: Int32

            override pub fx run: (f: Fx<Tuple0, Void>) Int32 {
                f()
                return k
            }
        }

        pub class Slot {
            require pub mut r: Runs

            pub mut fx clear: (other: Runs) Void {
                r = other
            }
        }

        pub fx go: (s: Slot, spare: Runs) Int32 {
            return s.r.run(fx() Void {
                s.clear(spare)
            })
        }

        pub fx goParam: (r: Runs, s: Slot, spare: Runs) Int32 {
            return r.run(fx() Void {
                s.clear(spare)
            })
        }

        pub fx traitRecv: () Int32 {
            s: Slot = Slot { r = R1 { k = 11 } }
            a: Int32 = go(s, R1 { k = 22 })
            s.r = R1 { k = 33 }
            b: Int32 = goParam(s.r, s, R1 { k = 44 })
            return a * 100 + b
        }

        // w2-5 minor #0 (alb): an element reached through a call result's handle, read by a callee after it frees
        // the List; safe in round 3 only by the entry snapshot, now copied at the call in both spellings.
        pub class H {
            pub mut items: List<Str> = List<Str> {}
        }

        pub fx pick: (h: H) H {
            return h
        }

        pub fx readAfterH: (s: Str, h: H) Int32 {
            h.items = List<Str> {}
            return s.length() as Int32
        }

        pub fx readAfterHOwn: () Int32 {
            h: H = H {}
            h.items.add("$ALB")
            a: Int32 = readAfterH(pick(h).items.get(0), h)
            h.items.add("$ALB")
            b: Int32 = readAfterH(pick(h).items[0], h)
            return a * 1000 + b
        }
        """,
    )

    private val tree by lazy { CppExprTestSupport.emit("oop-lifetimes", listOf(lifetimes)) }

    @Test
    fun noDefinitionGuardsAndEachCallerCopiesWhereTheDesignSays() {
        val text = tree.text(lifetimes)
        // 50-round4 7.3: no entry copy, no self-hold, anywhere.
        assertLacks(text, "Ref_ =", "Ref_)", "keepAlive_", "weak_from_this().lock()")
        assertContains(
            text,
            // a const& argument through a handle, to a callee that is not CONFINED: T(e)
            "return a->append(kira::Str(kira::at(b->lines, 0)));",
            "return consume(h, kira::Str(kira::unwrap(h->item)->label));",
            "return b->take(std::int32_t(g->n));",
            // BYREF from the root declaration: Feed<T>.take's `const T&`, through the trait and the class alike
            "const std::int32_t x{f->take(std::int32_t(a->n))};",
            "const std::int32_t y{a->take(std::int32_t(g->n))};",
            // a class handle receiver held for the call (W5): the method holds nothing itself
            "return kira::Rc<Kid>(kira::at(t->kids, 0))->leave();",
            "return kira::Rc<Item>(kira::unwrap(h->item))->relabel(h);",
            "return kira::Rc<Node<kira::Str>>(kira::unwrap(head->next))->detach(head);",
            "return kira::Rc<Leaf>(kira::unwrap(h->leaf))->leave();",
            "return relabelParam(kira::Rc<Item>(kira::unwrap(h->item)), h);",
            // an Fx value's callee, and an Fx place given to a template parameter
            "return kira::Fn<std::int32_t()>(f)();",
            "const kira::Str first = callIt(kira::Fn<kira::Str()>(b->f));",
            // a range the body may disturb (W6 fails): a copy, once per loop
            "for(const kira::Rc<Behaviour>& b : kira::List<kira::Rc<Behaviour>>(loaded))",
            // a type-parameter argument and a trait handle argument
            "return greetIt<kira::Rc<Pup>>(kira::Rc<Pup>(c->kid), c);",
            "goParam(kira::Rc<Runs>(s->r), s, ",
            // alb: the call and the index spellings name one place (O2), and both are copied at the call
            "const std::int32_t a{readAfterH(kira::Str(kira::at(pick(h)->items, 0)), h)};",
            "const std::int32_t b{readAfterH(kira::Str(kira::at(pick(h)->items, 0)), h)};",
        )
        // Lent: a STABLE mut place (rule M), and a local handle a callee cannot rebind (W2).
        assertContains(text, "      both(c, b->items);", "go(s, std::make_shared<R1>(22))")
        // No class derives kira::Shared to hold itself; Kid does because a lambda captures its this.
        assertContains(text, "  class Tree final\n", "  class Runner final\n", "  class Item final\n", "  class Kid final : public kira::Shared<Kid>\n")
    }

    @TestFactory
    fun eachProgramPrintsKirasValueOnEveryCompiler(): List<DynamicNode> =
        listOf(CppToolchain.GCC, CppToolchain.CLANG, CppToolchain.MSVC).map { tc ->
            DynamicTest.dynamicTest("lifetimes [${tc.id}]") {
                val label = "\"a long item label that is not in the small string buffer 00\""
                val kid = "\"a kid's name long enough to live on the heap 0000\""
                val rows = listOf(
                    "lifetimes::appendOwnLine() == \"the first line, long enough to live on the heap 0000000\"" to "uaflist: a Str element of a List the callee grows",
                    "lifetimes::consumeOwn() == $label" to "uaf3: a Str whose object the callee frees",
                    "lifetimes::relabelOwn() == $label" to "mutthis: a method passing its own field mut to a call that frees the object",
                    "lifetimes::relabelParamOwn() == $label" to "mutparam: a handle parameter whose object's field is passed mut",
                    "lifetimes::grabOwn() == 11" to "recvfree, implicitcall: a method whose argument frees its object",
                    "lifetimes::twinOwn() == 1" to "genalias4: a template's const T& bound to the field the body writes",
                    "lifetimes::aliasOwn() == 1100" to "an override taken by const&: through the trait 1, through the class 100",
                    "lifetimes::leaveOwn() == $kid" to "selfkill2: a method that drops its object's only owner",
                    "lifetimes::leaveLaterOwn() == $kid" to "capthis: a lambda capturing this after the drop",
                    "lifetimes::detachOwn() == \"the next node's value, long enough for the heap 00\"" to "a class template's method that drops its own owner",
                    "lifetimes::runOwn() == 42" to "a trait call whose override drops the caller's object",
                    "lifetimes::leaveHome() == 42" to "a trait default that drops its object, then calls it",
                    "lifetimes::chainHas()" to "a loop over a field of this with a trait call in the body",
                    "lifetimes::feedOwn() == 2" to "a STABLE mut argument the callee grows through another handle",
                    "lifetimes::fxSelf() == 32" to "fxself: an Fx field that replaces itself while it runs",
                    "lifetimes::lamParam() == $label" to "lamparam: a lambda's parameter bound into an object its body frees",
                    "lifetimes::sumAfterOwn() == 5" to "a view of a List parameter the body's callee grows",
                    "lifetimes::walkFresh() == 6" to "loopfresh: loops over storage only a temporary keeps",
                    "lifetimes::fxParams() == \"captured:passed\"" to "fxparam3, fxparam2: a template Fx parameter bound to an Fx an object holds",
                    "lifetimes::capParam() == \"a\"" to "capparam: a lambda made after the rename captures the value passed",
                    "lifetimes::capParamFree() == \"a long label that is not in the small string buffer 0000000\"" to "capparamfree: the same after a free",
                    "lifetimes::genRecv() == \"a long pup name that is not in the small string buffer 0000\"" to "genrecv: a type-parameter receiver the arguments replace",
                    "lifetimes::keepPoke() == \"short\"" to "a type-parameter field receiver beside a replacing argument",
                    "lifetimes::dispStr() == $label" to "dispstr: a Str given to a dispatched call whose List of Fx frees it",
                    "lifetimes::traitRecv() == 1133" to "a trait receiver whose callback replaces its only owner, a field and a parameter",
                    "lifetimes::readAfterHOwn() == ${ALB.length * 1000 + ALB.length}" to "alb: an element through a call result's handle, freed by the callee, in both spellings",
                )
                val driver = buildString {
                    append("#include \"").append(lifetimes.relativePath.removeSuffix(".kira")).append(".kira.hxx\"\n")
                    append(CppExprTestSupport.CHECK_PRELUDE)
                    append("\nint main()\n{\n")
                    rows.forEach { (cond, what) -> append("    check($cond, \"$what\");\n") }
                    append("    std::printf(\"\\n%d checks, %d failed\\n\", checks, failures);\n")
                    append("    return failures == 0 ? 0 : 1;\n}\n")
                }
                val stdout = CppExprTestSupport.compileAndRun(tree, driver, tc) ?: return@dynamicTest
                assertTrue(stdout.contains("\n${rows.size} checks, 0 failed\n"), "${tc.id}:\n$stdout")
            }
        }

    // ---- what a mut argument needs: rule M (W2.5), moved in from this package's mutArgRefusals ------------

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
    fun aMutArgumentAnExternsCallbackMayFreeIsRefusedByRuleM() {
        // externmut: bump runs its callback, then writes n, bound into the Item the callback freed (MSVC ASan
        // heap-use-after-free). h.item.count is reached through a handle step and bump is not CONFINED.
        assertRefusedBy(
            "rules.exclusivity.mut",
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
            """,
        )
    }

    @Test
    fun aMutArgumentAnExternsFxValueMayReachIsRefusedByRuleM() {
        assertRefusedBy(
            "rules.exclusivity.mut",
            """
            $externItem

            pub fx apply: (h: Box, f: Fx<Tuple0, Void>) Void {
                bump(mut h.item.count, f)
            }
            """,
        )
    }

    @Test
    fun aMutArgumentAnExternsHarmlessCallbackCannotReachIsAccepted() {
        // A lambda literal whose body is CONFINED keeps the extern CONFINED (W2.5's reading (c)).
        val body = """
            $externItem

            pub fx main: () Int32 {
                h: Box = Box {}
                bump(mut h.item.count, fx() Void {
                })
                return h.item.count
            }
            """
        assertEquals(emptyList(), typerErrors(body))
        assertTrue(unsupported(body).none { it.contains("bump") })
    }

    /** The five holders of an `Fx` 40-round3 5.5 names, each as a value the call is given, whose callback runs `c.reset()`. */
    private val holders = listOf(
        Triple("a class field", "w: Wrap", "held: Wrap = Wrap { f = fx() Void { c.reset() } }"),
        Triple("an Arr", "w: Arr<Fx<Tuple0, Void>, 1>", "held: Arr<Fx<Tuple0, Void>, 1> = [fx() Void { c.reset() }]"),
        Triple("a List", "w: List<Fx<Tuple0, Void>>", "held: List<Fx<Tuple0, Void>> = [fx() Void { c.reset() }]"),
        Triple("a Maybe", "w: Maybe<Fx<Tuple0, Void>>", "held: Maybe<Fx<Tuple0, Void>> = fx() Void { c.reset() }"),
        Triple("a tuple", "w: Tuple2<Fx<Tuple0, Void>, Int32>", "held: Tuple2<Fx<Tuple0, Void>, Int32> = Tuple2<Fx<Tuple0, Void>, Int32> { fx() Void { c.reset() }, 1 }"),
    )

    private val wrap = """
        pub class Wrap {
            require pub f: Fx<Tuple0, Void>
        }
    """.trimIndent()

    @Test
    fun aMutArgumentAnExternMayFreeThroughAnFxAnArgumentHoldsIsRefusedByRuleM() {
        // externarr: `bumpAll(mut h.item.count, [fx() Void { c.reset() }])` wrote through the int& into the Item the
        // callback freed (MSVC ASan heap-use-after-free). A mut place is never copied (R19): refused, for each holder.
        for ((what, param, build) in holders) {
            assertRefusedBy(
                "rules.exclusivity.mut",
                """
                $externItem

                $wrap

                pub fx bumpHeld: (mut n: Int32, $param) Void;

                pub fx main: () Int32 {
                    h: Box = Box {}
                    c: Box = h
                    $build
                    bumpHeld(mut h.item.count, held)
                    return h.item.count
                }
                """,
                what,
            )
        }
    }

    @Test
    fun aDispatchedCallGivenAHeldFxIsRefusedByRuleM() {
        // A trait method may be a C++ override that runs the Fx it reaches: never CONFINED.
        assertRefusedBy(
            "rules.exclusivity.mut",
            """
            $externItem

            pub trait Bumper {
                pub fx bump: (mut n: Int32, fs: List<Fx<Tuple0, Void>>) Void;
            }

            pub fx bumpVia: (b: Bumper) Int32 {
                h: Box = Box {}
                c: Box = h
                fs: List<Fx<Tuple0, Void>> = [fx() Void { c.reset() }]
                b.bump(mut h.item.count, fs)
                return h.item.count
            }
            """,
        )
    }

    @Test
    fun aCalleeCppSuppliesTakesItsArgumentsAsTheCallerPassesThem() {
        // externstr, externnested (each holder), a holder of no Fx, a handle given to an extern, and a trait receiver
        // parameter: each is accepted, and no definition copies a parameter at entry any more. The copy is the
        // caller's (W2.3's policy), spelled by W2.6 at an extern's call.
        val programs = listOf(
            "pub fx later: (s: Str, h: Box) Int32 {\n    return measure(s, fx() Void {\n        h.reset()\n    })\n}",
            "pub fx direct: (s: Str, h: Box) Int32 {\n    return plain(s)\n}",
            "pub fx sumAll: (s: Str, xs: List<Int32>) Int32;\n\npub fx later: (s: Str, c: Box) Int32 {\n    xs: List<Int32> = [1, 2]\n    return sumAll(s, xs)\n}",
            "pub fx inspect: (it: Item, f: Fx<Tuple0, Void>) Int32;\n\npub fx later: (c: Box, h: Box) Int32 {\n    return inspect(c.item, fx() Void {\n        h.reset()\n    })\n}",
            "pub fx later: (c: Box, h: Box) Int32 {\n    return measure(c.item.label, fx() Void {\n        h.reset()\n    })\n}",
        ) + holders.map { (_, param, build) ->
            "$wrap\n\npub fx measureHeld: (s: Str, $param) Int32;\n\npub fx later: (s: Str, c: Box) Int32 {\n    $build\n    return measureHeld(s, held)\n}"
        }
        for (p in programs) {
            val e = OopTestSupport.emit(OopTestSupport.module(uri, "$externItem\n\n$p"))
            assertEquals(emptyList(), e.errors(uri).map { it.message }, p)
            assertLacks(e.header(uri) + e.source(uri), "Ref_", "keepAlive_")
        }
    }

    // ---- what the rules still refuse (language rules W2.5 keeps) ----------------------------------------

    @Test
    fun aLoopOverAnObjectsListWhoseBodyMayGrowItStaysRefusedByTheLoopRule() {
        // bagloop2 (for s in b.items { c.grow() } with c = b), in a function and in a field's default lambda (fielddefault):
        // D37's loop rule, over the named and HiddenWrites writes, which W2.5 keeps.
        val bag = """
            pub class Bag {
                pub mut items: List<Str> = List<Str> {}

                pub mut fx grow: () Void {
                    items.add("another long string that is not in the small buffer")
                }
            }
        """.trimIndent()
        assertRefusedBy(
            "rules.exclusivity.loop",
            """
            $bag

            pub fx walk: (b: Bag, c: Bag) Int32 {
                mut n: Int32 = 0
                for s: Str in b.items {
                    c.grow()
                    n = n + 1
                }
                return n
            }
            """,
        )
        assertRefusedBy(
            "rules.exclusivity.loop",
            """
            $bag

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
            """,
        )
    }

    @Test
    fun aMutArgumentInsideAnotherOneStaysRefusedByD37() {
        assertRefusedBy(
            "rules.exclusivity.argument",
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
            """,
        )
    }

    @Test
    fun aTypeParameterReceiverItsOwnArgumentReplacesStaysRefusedByTheOrderRule() {
        // `item.greet(replace(v))` with replace writing this.item: Q4's interim clause 3 (W2.5) refuses the order question
        // where round 2's paramReceiverRefusal refused the lifetime one.
        assertRefusedBy(
            "rules.exclusivity.order",
            """
            pub trait Greets {
                pub fx greet: (n: Int32) Str;
            }

            pub class Keep<T: Greets> {
                require pub mut item: T

                pub mut fx replace: (v: T) Int32 {
                    item = v
                    return 7
                }

                pub mut fx poke: (v: T) Str {
                    return item.greet(replace(v))
                }
            }
            """,
        )
    }

    // ---- constructions, and no object holding a view -----------------------------------------------------

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
        val e = OopTestSupport.emit(
            OopTestSupport.module(
                uri,
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
                """,
            ),
        )
        assertEquals(emptyList(), e.errors(uri).map { it.message })
    }

    @Test
    fun anObjectHoldingAViewIsAnInternalErrorNeverAUserOne() {
        // refview's `Ref<View<Int32>> { value = makeList().view() }` is W2.5's ViewPass's (rules.view.type). Should one reach
        // the classes part, it is the checker's bug, reported as cpp.internal, and never lowered as it is.
        val program = """
            pub fx makeList: () List<Int32> {
                return List<Int32> {}
            }

            pub fx boxed: () Int32 {
                return Ref<View<Int32>> { value = makeList().view() }.value.get(0)
            }
        """
        val diagnostics = try {
            OopTestSupport.emit(OopTestSupport.module(uri, program)).module(uri).diagnostics.filter { it.isError }
        } catch (e: AssertionError) {
            if (e.message?.contains("the typer refused") == true && e.message!!.contains("rules.view.type")) {
                return
            }
            throw e
        }
        assertTrue(diagnostics.any { it.code == CppModuleEmitterFactory.INTERNAL_CODE && it.message.contains("the value of Ref<View<Int32>> holds a view or a pointer") }, OopTestSupport.render(diagnostics))
        assertTrue(diagnostics.none { it.code == CppModuleEmitterFactory.UNSUPPORTED_CODE }, OopTestSupport.render(diagnostics))
    }

    // ---- 50-round4 7.3: no emitted file holds itself or copies at entry -------------------------------------

    @Test
    fun noGoldenHoldsItselfOrCopiesAtEntry() {
        // Every expected/ tree (what CppGoldenEmitTest compares byte for byte), and the oop goldens emitted here with
        // the standard parts.
        val patterns = listOf("Ref_ =", "keepAlive_", "weak_from_this().lock()")
        val files = File("src/test/resources").walkTopDown().filter { it.isFile && (it.name.endsWith(".kira.cxx") || it.name.endsWith(".kira.hxx")) }.toList()
        assertTrue(files.size > 30, "found only ${files.size} generated files")
        assertEquals(emptyList(), files.filter { f -> patterns.any { f.readText().contains(it) } }.map { it.path })
        for (name in listOf("chain", "sender", "classes")) {
            val emitted = OopTestSupport.emitCase(OopTestSupport.case(name), CppEmitParts.standard())
            assertTrue(emitted.any { it.relative.endsWith(".kira.cxx") } && emitted.any { it.relative.endsWith(".kira.hxx") }, "$name emitted ${emitted.map { it.relative }}")
            emitted.forEach { f -> assertLacks(f.text, *patterns.toTypedArray()) }
        }
    }
}
