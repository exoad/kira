package net.exoad.kira.cpp.oop

import net.exoad.kira.cpp.support.CppCompileSupport
import net.exoad.kira.cpp.support.CppProfile
import net.exoad.kira.cpp.support.CppToolchain
import net.exoad.kira.cpp.support.CppToolchains
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The class shapes compile under the warning contract (design 8.2) on gcc, clang, MSVC and
 * the aarch64 cross build, and a C++ driver runs them: virtual dispatch through a base and a
 * trait, a trait default body, a diamond of traits, `this` as a value (the chain root's
 * `kira::Shared`), a generic class and a generic bound, `Maybe<Class>`, `Ref<T>`, a stack-
 * constructed class, a skipped middle default of a narrow
 * type, `mut fx` methods that write their receiver (not `const`), field defaults that are not
 * PURE run in Kira's order inside the constructors (R-D and OQ-2: the given values, then the
 * defaults in declaration order, a superclass's and its `initially` first), and bodies that
 * read their parameters and receiver with no guard while a C++ caller keeps invariant I
 * (contract 5.4.4: it copies an argument the call may write and holds the receiver; the Kira
 * callers' copies are CppClassCopyTest's). The bodies are W2.4's
 * fakes ([OopTestSupport.FakeStmtEmitter]), so the Kira bodies here only return values and
 * assign; the shapes around them are the real classes part.
 */
class CppClassCompileTest {
    private val uri = "oop:shapes"

    private val program = """
        // Two traits over one parent: a diamond, so both derive Named virtually.
        pub Named: Any {
            pub id: () Str;
            pub greeting: () Str {
                return "hello"
            }
        }

        pub Tagged: Named {
            pub tag: () Str;
        }

        pub Labelled: Named {
            pub label: () Str;
        }

        pub Sized: Any {
            pub size: () Int32;
        }

        pub Animal: Named {
            pub name: Str
            mut legs: Int32 = 4

            override pub id: () Str {
                return name
            }

            pub sound: () Str {
                return "..."
            }

            pub legCount: () Int32 {
                return legs
            }
        }

        pub Dog: Animal, Sized {
            mut tricks: Int32 = 0

            override pub sound: () Str {
                return "woof"
            }

            override pub size: () Int32 {
                return tricks
            }

            pub me: () Dog {
                return this
            }

            // Lambdas that escape and capture the receiver: [self = ...], cast to Dog.
            pub later: () Fx<Tuple0, Dog> {
                return () Dog {
                    return this
                }
            }

            pub mut laterMut: () Fx<Tuple0, Dog> {
                return () Dog {
                    return this
                }
            }
        }

        pub Both: Tagged, Labelled {
            override pub id: () Str {
                return "both"
            }

            override pub tag: () Str {
                return "t"
            }

            override pub label: () Str {
                return "l"
            }
        }

        // Reaches Named through Animal (which implements id) and through Tagged (which does
        // not): id is inherited by dominance, so Pup forwards it (MSVC C4250 otherwise).
        pub Pup: Animal, Tagged {
            override pub tag: () Str {
                return "pup"
            }
        }

        pub Box<T>: Any {
            pub value: T

            pub get: () T {
                return value
            }
        }

        // A class template whose `this` escapes: its kira::Shared base is dependent.
        pub Cell<T>: Any {
            pub value: T

            pub me: () Cell<T> {
                return this
            }

            pub mut later: () Fx<Tuple0, Cell<T>> {
                return () Cell<T> {
                    return this
                }
            }
        }

        // A trait template with a default body, implemented at one instantiation; take's
        // parameter is a bare T, const T& in C++.
        pub Source<T>: Any {
            pub get: () T;
            pub take: (v: T) T;
            pub again: () T {
                return get()
            }
        }

        pub Five: Source<Int32> {
            override pub get: () Int32 {
                return 5
            }

            override pub take: (v: Int32) Int32 {
                return v
            }
        }

        // The same requirement at Bool, and a generic class's method overridden at Int32:
        // each override says const T& as its base does, or it overrides nothing.
        pub Flag: Source<Bool> {
            override pub get: () Bool {
                return true
            }

            override pub take: (v: Bool) Bool {
                return v
            }
        }

        pub Base<T>: Any {
            pub take: (v: T) Int32 {
                return 0
            }
        }

        pub IntBase: Base<Int32> {
            override pub take: (v: Int32) Int32 {
                return v
            }
        }

        pub makeFlag: () Source<Bool> {
            return Flag {}
        }

        pub makeIntBase: () Base<Int32> {
            return IntBase {}
        }

        pub Holder: Any {
            pet: Animal
            mut spare: Maybe<Animal> = null

            pub getPet: () Animal {
                return pet
            }

            pub getSpare: () Maybe<Animal> {
                return spare
            }
        }

        pub makeDog: (name: Str) Dog {
            return Dog { name }
        }

        pub makePup: (name: Str) Pup {
            return Pup { name }
        }

        pub makeCell: (v: Int32) Cell<Int32> {
            return Cell<Int32> { v }
        }

        pub makeFive: () Source<Int32> {
            return Five {}
        }

        pub makeBox: (v: Int32) Box<Int32> {
            return Box<Int32> { v }
        }

        pub asNamed: (d: Dog) Named {
            return d
        }

        pub sizeOf<T: Sized>: (v: T) Int32 {
            return v.size()
        }

        pub dogSize: (d: Dog) Int32 {
            return sizeOf<Dog>(d)
        }

        pub counter: () Ref<Int32> {
            return Ref<Int32> { value = 7 }
        }

        // A skipped middle default of a narrow type: typed, or MSVC /W4 /WX sees an int
        // narrowed inside make_shared (C4244).
        pub Band: Any {
            pub lo: Int32
            pub mid: UInt8 = 5
            pub hi: Int32
        }

        pub makeBand: () Band {
            return Band { lo = 1, hi = 2 }
        }

        // mut fx methods that write the receiver: not const, callable through a const Rc (D29).
        pub grab: (mut v: Int32) Void {
            v = 7
        }

        pub Counter: Any {
            mut n: Int32 = 0

            pub mut bump: () Int32 {
                n += 1
                return n
            }

            pub mut reset: () Void {
                n = 0
            }

            pub mut again: () Int32 {
                reset()
                return n
            }

            pub mut take: () Int32 {
                grab(mut n)
                return n
            }

            pub peek: () Int32 {
                return n
            }
        }

        pub makeCounter: () Counter {
            return Counter {}
        }

        // A skipped middle default of an Arr type: make_shared deduces nothing from a bare
        // braced list, so the filled-in default carries its type, in a subclass's construction too.
        pub Frame: Any {
            pub mut buf: Arr<UInt8, 4> = [1, 2, 3, 4]
            pub id: Int32
        }

        pub Stamped: Frame {
            pub stamp: Int32
        }

        pub makeFrame: () Frame {
            return Frame { id = 7 }
        }

        pub makeStamped: () Stamped {
            return Stamped { id = 8, stamp = 9 }
        }

        // One override of a method two traits declare, writing its receiver: both pure virtuals lose const.
        pub Left: Any {
            pub mut hit: () Int32;
        }

        pub Right: Any {
            pub mut hit: () Int32;
        }

        pub Target: Left, Right {
            mut hits: Int32 = 0

            override pub mut hit: () Int32 {
                hits += 1
                return hits
            }
        }

        pub makeTarget: () Target {
            return Target {}
        }

        pub hitLeft: (l: Left) Int32 {
            return l.hit()
        }

        pub hitRight: (r: Right) Int32 {
            return r.hit()
        }

        // One override of a superclass method that also implements a trait's, writing: the trait's follows.
        pub Stamper: Any {
            pub mut stamp: () Int32;
        }

        pub Plain: Any {
            pub mut count: Int32 = 0

            pub mut stamp: () Int32 {
                return 1
            }
        }

        pub Counting: Plain, Stamper {
            override pub mut stamp: () Int32 {
                count += 1
                return count
            }
        }

        pub makeCounting: () Counting {
            return Counting {}
        }

        pub stampVia: (s: Stamper) Int32 {
            return s.stamp()
        }

        pub stampPlain: (p: Plain) Int32 {
            return p.stamp()
        }

        // A struct takes a trait's default body as its own member: static dispatch through a bound and directly.
        pub Shape: Any {
            pub area: () Int32;
            pub same: () Int32 {
                return area()
            }
            pub mut grow: () Void;
            pub growOnce: () Void {
                grow()
            }
        }

        pub Square: Struct, Shape {
            pub side: Int32 = 3

            override pub area: () Int32 {
                return side
            }

            override pub mut grow: () Void {
                side = 5
            }
        }

        pub sameOf<T: Shape>: (s: T) Int32 {
            return s.same()
        }

        pub squareSame: (q: Square) Int32 {
            return sameOf<Square>(q)
        }

        pub squareDirect: (q: Square) Int32 {
            return q.same()
        }

        // A subclass's escaping lambda that writes a field and calls a mut fx: the captured
        // self is cast down without const, as the method itself is not const.
        pub Lower: Any {
            mut n: Int32 = 0

            pub get: () Int32 {
                return n
            }
        }

        pub Upper: Lower {
            mut k: Int32 = 0

            pub mut reset: () Void {
                k = 100
            }

            pub later: () Fx<Tuple0, Int32> {
                return () Int32 {
                    k += 1
                    return k
                }
            }

            pub laterReset: () Fx<Tuple0, Int32> {
                return () Int32 {
                    reset()
                    return k
                }
            }

            pub peek: () Fx<Tuple0, Int32> {
                return () Int32 {
                    return k
                }
            }
        }

        pub makeUpper: () Upper {
            return Upper {}
        }

        // One name from two unrelated traits, a requirement beside a default: the struct takes
        // the default as its member, the class declares a forwarder to it (C++ would hold the
        // class abstract and the call ambiguous).
        pub Abs: Any {
            pub f: () Int32;
        }

        pub Def: Any {
            pub f: () Int32 {
                return 7
            }
        }

        pub Sib: Struct, Abs, Def {
            pub v: Int32 = 0
        }

        pub SibClass: Abs, Def {
        }

        pub viaAbs: (a: Abs) Int32 {
            return a.f()
        }

        pub viaAbsBound<T: Abs>: (s: T) Int32 {
            return s.f()
        }

        pub sibBound: (s: Sib) Int32 {
            return viaAbsBound<Sib>(s)
        }

        pub makeSib: () SibClass {
            return SibClass {}
        }

        // An override of an override of a generic root, at Int32: both say the root's const&.
        pub Feed<T>: Any {
            pub mut take: (v: T) T;
        }

        pub Mid: Feed<Int32> {
            override pub mut take: (v: Int32) Int32 {
                return v
            }
        }

        pub Leaf: Mid {
            override pub mut take: (v: Int32) Int32 {
                return 12
            }
        }

        pub makeLeaf: () Leaf {
            return Leaf {}
        }

        // An Fx parameter and an Fx return of a generic base, at Int32: kira::Fn<...(const T&)> stays so.
        pub Each<T>: Any {
            pub each: (f: Fx<Tuple1<T>, Int32>) Int32;
        }

        pub Nums: Each<Int32> {
            override pub each: (f: Fx<Tuple1<Int32>, Int32>) Int32 {
                return f(5)
            }
        }

        pub makeNums: () Each<Int32> {
            return Nums {}
        }

        pub Maker<T>: Any {
            pub make: () Fx<Tuple1<T>, Int32>;
        }

        pub IntMaker: Maker<Int32> {
            override pub make: () Fx<Tuple1<Int32>, Int32> {
                return (x: Int32) Int32 {
                    return x
                }
            }
        }

        pub makeMaker: () Maker<Int32> {
            return IntMaker {}
        }

        // An override taken by const& where Kira takes a value: the body reads the value it
        // was passed, not the field the argument named, which it writes first.
        pub Alias: Feed<Int32> {
            pub mut n: Int32 = 1

            override pub mut take: (v: Int32) Int32 {
                n = 100
                return v
            }
        }

        pub makeAlias: () Alias {
            return Alias {}
        }

        // A trait default whose parameter is named as a field of the struct that copies it.
        pub Scaled: Any {
            pub unit: () Int32;
            pub scaled: (side: Int32) Int32 {
                return side
            }
        }

        pub Tile: Struct, Scaled {
            pub side: Int32 = 3

            override pub unit: () Int32 {
                return side
            }
        }

        // An override of a generic v: T at an opaque handle (`Handle* const&`), copying v as the
        // pointer its own declaration takes; the driver defines the bodyless functions. (Unsafe
        // and CStr are second-class, decision 4b: never a type argument or a field.)
        pub @_opaque Handle: Any

        pub useIt: (h: Handle) Int32;
        pub open: () Handle;

        pub Sink<T>: Any {
            pub put: (v: T) Int32;
        }

        pub HSink: Sink<Handle> {
            override pub put: (v: Handle) Int32 {
                return useIt(v)
            }
        }

        pub makeHSink: () Sink<Handle> {
            return HSink {}
        }

        // A construction's D33 temporaries of pointer type.
        pub PtrHolder: Any {
            pub g: Handle
            pub h: Handle
        }

        pub makePtrHolder: () PtrHolder {
            return PtrHolder { h = open(), g = open() }
        }

        // A method whose Str parameter names an object the body drops, and one that reads itself
        // after dropping its only other owner: neither guards, the caller copies and holds (I).
        pub Link: Any {
            pub name: Str
            pub mut next: Maybe<Link> = null

            pub mut cut: (s: Str) Str {
                next = null
                return s
            }

            pub detach: (owner: Link) Str {
                owner.drop()
                return name
            }

            pub mut drop: () Void {
                next = null
            }
        }

        pub makeLink: (name: Str) Link {
            return Link { name }
        }

        // A free function whose Str parameter names an object its other parameter drops.
        pub consume: (h: Link, s: Str) Str {
            h.drop()
            return s
        }

        // A template's const T& parameter, bound to the field the body writes first.
        pub Twin<T>: Any {
            pub mut n: T
            pub m: T

            pub mut take: (v: T) T {
                n = m
                return v
            }
        }

        pub makeTwin: (n: Int32, m: Int32) Twin<Int32> {
            return Twin<Int32> { n = n, m = m }
        }

        // A construction reads a field, a const& parameter, an implicit field and a mut global
        // before the impure sibling that renames or frees what it names, and a skipped middle
        // default after the given values (D33, as W2.3's call hoister orders a call).
        pub Tag: Any {
            pub mut name: Str

            pub mut rename: () Int32 {
                name = "new"
                return 7
            }
        }

        pub TagPair: Any {
            pub a: Str
            pub n: Int32
        }

        pub tagPair: (b: Tag, c: Tag) TagPair {
            return TagPair { b.name, c.rename() }
        }

        pub Shelf: Any {
            pub mut tag: Maybe<Tag> = null

            pub mut empty: () Int32 {
                tag = null
                return 7
            }
        }

        pub shelved: (s: Str, h: Shelf) TagPair {
            return TagPair { a = s, n = h.empty() }
        }

        pub Nest: Any {
            pub mut twig: Maybe<Twig> = null

            pub mut drop: () Int32 {
                twig = null
                return 7
            }
        }

        pub Twig: Any {
            pub name: Str
            pub nest: Nest

            pub leave: () TagPair {
                return TagPair { name, nest.drop() }
            }
        }

        pub mut seed: Int32 = 1

        pub bumpSeed: () Int32 {
            seed += 10
            return seed
        }

        pub Duo: Any {
            pub a: Int32
            pub b: Int32 = seed
            pub c: Int32
        }

        pub duo: () Duo {
            return Duo { a = seed, c = bumpSeed() }
        }

        // The same spill in a field's default, a constructor's default argument: its lambda captures nothing.
        pub DuoBox: Any {
            pub duo: Duo = Duo { a = seed, c = bumpSeed() }
        }

        // A type-parameter receiver the arguments replace in the caller's slot: the caller copies it (I).
        pub Greets: Any {
            pub greet: (n: Int32) Str;
        }

        pub Kid: Greets {
            pub name: Str

            override pub greet: (n: Int32) Str {
                return name
            }
        }

        pub Crib: Any {
            pub mut kid: Kid = Kid { "short" }

            pub mut swap: () Int32 {
                kid = Kid { "other" }
                return 7
            }
        }

        pub greetIt<T: Greets>: (x: T, h: Crib) Str {
            return x.greet(h.swap())
        }

        // R-D and OQ-2 (second-class round 3): a field default that is not PURE runs inside the constructor, in
        // declaration order, after the given values and after the superclass's defaults and initially (Kotlin's
        // order). As C++ default arguments, g++ and MSVC ran Three's right to left, and Late's k before Seen's
        // initially.
        pub mut tick: Int32 = 0

        pub next: () Int32 {
            tick += 1
            return tick
        }

        pub Three: Any {
            pub a: Int32 = next()
            pub b: Int32 = next()
            pub c: Int32 = next()
        }

        pub Seen: Any {
            pub mut seen: Int32 = 0

            initially {
                seen = next()
            }
        }

        pub Late: Seen {
            pub k: Int32 = next()
            pub mut after: Int32 = 0

            initially {
                after = next()
            }
        }

        pub Low: Any {
            pub a: Int32 = next()
        }

        pub High: Low {
            pub b: Int32 = next()
        }

        pub Mix: Any {
            pub x: Int32 = next()
            pub y: Int32
            pub z: Int32 = next()
            pub w: Int32 = next()
        }

        pub makeThree: () Three {
            return Three {}
        }

        pub makeLate: () Late {
            return Late {}
        }

        pub makeHigh: () High {
            return High {}
        }

        pub makeMix: () Mix {
            return Mix { y = next() }
        }

        pub makeMixGiven: () Mix {
            return Mix { w = 70, y = next(), x = 50 }
        }

        // A deferred default that calls a private function, in the constructor's definition; and a Maybe field whose
        // default is not PURE, given null: the null is the field's, not an omitted value.
        pick: () Maybe<Str> {
            tick += 100
            return "picked"
        }

        pub Slot: Any {
            pub mut item: Maybe<Str> = pick()
        }

        pub emptySlot: () Slot {
            return Slot { item = null }
        }

        pub fullSlot: () Slot {
            return Slot {}
        }
    """

    private val driver = """
        #include "src/oop/shapes.kira.hxx"

        #include <cstdio>
        #include <cstring>
        #include <memory>

        namespace shapes
        {
          class Handle
          {
          public:
              std::int32_t n = 9;
          };
        }

        namespace
        {
          shapes::Handle theHandle;
        }

        std::int32_t shapes::useIt(Handle* h)
        {
            return h->n;
        }

        shapes::Handle* shapes::open()
        {
            return &theHandle;
        }

        namespace
        {
          int checks = 0;
          int failures = 0;

          void check(bool ok, const char* what)
          {
              ++checks;
              if(!ok)
              {
                  ++failures;
              }
              std::printf("  %s  %s\n", ok ? "ok  " : "FAIL", what);
          }
        }

        int main()
        {
            const kira::Rc<shapes::Dog> d = shapes::makeDog("rex");
            check(d->id() == "rex" && d->sound() == "woof", "a subclass overrides, and inherits the trait method its base implements");
            const kira::Rc<shapes::Animal> a = d;
            check(a->sound() == "woof" && a->legCount() == 4, "virtual dispatch through the base; a defaulted field");
            check(d->greeting() == "hello", "a trait default body");
            check(d->me() == d, "this as a value is the object's own Rc");
            check(shapes::dogSize(d) == 0, "a generic bound dispatches statically");
            const kira::Rc<shapes::Named> n = shapes::asNamed(d);
            check(n->id() == "rex", "an upcast is implicit");
            const kira::Rc<shapes::Both> b = std::make_shared<shapes::Both>();
            const kira::Rc<shapes::Named> bn = b;
            check(b->greeting() == "hello" && b->tag() == "t" && b->label() == "l" && bn->id() == "both", "a diamond of traits has one Named");
            const kira::Rc<shapes::Pup> p = shapes::makePup("pip");
            const kira::Rc<shapes::Named> pn = p;
            check(p->id() == "pip" && pn->id() == "pip" && p->tag() == "pup" && p->sound() == "...", "a method inherited by dominance is forwarded");
            check(shapes::makeBox(7)->get() == 7, "a generic class");
            const kira::Rc<shapes::Cell<std::int32_t>> cell = shapes::makeCell(3);
            check(cell->me() == cell && cell->value == 3, "this as a value in a class template");
            check(d->later()() == d && d->laterMut()() == d && cell->later()() == cell, "an escaping lambda's self is the object, in a subclass and a template");
            check(shapes::makeFive()->again() == 5, "a trait template's default body calls the instantiation's override");
            check(shapes::makeFive()->take(4) == 4 && shapes::makeFlag()->take(true), "an override of a trait template's v: T at Int32 and at Bool overrides it");
            const kira::Rc<shapes::Base<std::int32_t>> ib = shapes::makeIntBase();
            check(ib->take(6) == 6 && std::make_shared<shapes::Base<std::int32_t>>()->take(6) == 0, "an override of a class template's v: T at Int32 overrides it");
            shapes::Holder h(d);
            check(h.getPet() == d && h.getSpare() == nullptr, "a stack-constructed class; Maybe<Class> is a nullable Rc");
            check(shapes::counter()->value == 7, "Ref<T> is an Rc of a Box");
            const kira::Rc<shapes::Band> band = shapes::makeBand();
            check(band->lo == 1 && band->mid == 5 && band->hi == 2, "a skipped middle default of a narrow type is filled in, typed");
            const kira::Rc<shapes::Counter> c = shapes::makeCounter();
            check(c->bump() == 1 && c->bump() == 2 && c->again() == 0 && c->take() == 7 && c->peek() == 7, "a plain fx that writes its receiver is callable through a const Rc");
            {
                shapes::Dog stacked("stacked");
                check(stacked.sound() == "woof", "C++ may construct a Kira class on the stack (D11)");
            }
            const kira::Rc<shapes::Frame> frame = shapes::makeFrame();
            const kira::Rc<shapes::Stamped> stamped = shapes::makeStamped();
            check(frame->buf[2] == 3 && frame->id == 7 && stamped->buf[3] == 4 && stamped->id == 8 && stamped->stamp == 9, "a skipped middle default of an Arr type is filled in, typed");
            const kira::Rc<shapes::Target> target = shapes::makeTarget();
            check(shapes::hitLeft(target) == 1 && shapes::hitRight(target) == 2 && target->hit() == 3, "one writing override of a method two traits declare");
            const kira::Rc<shapes::Counting> counting = shapes::makeCounting();
            check(shapes::stampVia(counting) == 1 && shapes::stampPlain(counting) == 2 && counting->count == 2, "one writing override of a superclass method that implements a trait's");
            const shapes::Square square{};
            shapes::Square growing{};
            growing.growOnce();
            check(shapes::squareSame(square) == 3 && shapes::squareDirect(square) == 3 && square.same() == 3 && growing.area() == 5, "a struct takes a trait's default body as its own member");
            const kira::Rc<shapes::Upper> upper = shapes::makeUpper();
            check(upper->later()() == 1 && upper->later()() == 2 && upper->laterReset()() == 100 && upper->peek()() == 100, "a subclass's escaping lambda writes through the captured self");
            const shapes::Sib sib{};
            const kira::Rc<shapes::SibClass> sibClass = shapes::makeSib();
            const kira::Rc<shapes::Abs> sibAbs = sibClass;
            check(sib.f() == 7 && shapes::sibBound(sib) == 7 && sibClass->f() == 7 && shapes::viaAbs(sibClass) == 7 && sibAbs->f() == 7, "a sibling trait's default satisfies another's requirement: a struct member, a class forwarder");
            const kira::Rc<shapes::Leaf> leaf = shapes::makeLeaf();
            const kira::Rc<shapes::Feed<std::int32_t>> feed = leaf;
            const kira::Rc<shapes::Mid> mid = leaf;
            check(feed->take(10) == 12 && mid->take(10) == 12 && std::make_shared<shapes::Mid>()->take(10) == 10, "an override of an override at Int32 overrides the generic root's const&");
            check(shapes::makeNums()->each([](std::int32_t x) { return x * 10; }) == 50, "an Fx parameter of a generic base keeps the base's kira::Fn shape at Int32");
            check(shapes::makeMaker()->make()(41) == 41, "an Fx return of a generic base keeps the base's kira::Fn shape at Int32");
            const kira::Rc<shapes::Alias> alias = shapes::makeAlias();
            check(alias->take(std::int32_t(alias->n)) == 1 && alias->n == 100, "an override taken by const& reads the value it was passed: a C++ caller copies what the call may write (contract 5.4.4)");
            const shapes::Tile tile{};
            check(tile.scaled(2) == 2 && tile.unit() == 3, "a trait default's parameter named as the copying struct's field");
            check(shapes::makeHSink()->put(shapes::open()) == 9, "an override at an opaque handle copies it as the pointer it takes");
            const kira::Rc<shapes::PtrHolder> held = shapes::makePtrHolder();
            check(held->g == shapes::open() && held->h == shapes::open(), "a construction spills pointers into const pointers");
            // Longer than any small-string buffer, so a read of a freed Str reads freed heap memory.
            const char* longName = "a name long enough that no standard library keeps it in the string object itself";
            const kira::Rc<shapes::Link> head = shapes::makeLink("head");
            head->next = shapes::makeLink(longName);
            check(head->cut(kira::Str(kira::unwrap(head->next)->name)) == longName && head->next == nullptr, "a C++ caller copies an argument naming what the body drops (contract 5.4.4)");
            head->next = shapes::makeLink(longName);
            check(kira::Rc<shapes::Link>(kira::unwrap(head->next))->detach(head) == longName && head->next == nullptr, "a C++ caller holds the receiver of a method that drops its only other owner (contract 5.4.4)");
            head->next = shapes::makeLink(longName);
            check(shapes::consume(head, kira::Str(kira::unwrap(head->next)->name)) == longName && head->next == nullptr, "a C++ caller copies a free function's argument naming what the body drops (contract 5.4.4)");
            {
                shapes::Link stacked("stacked");
                check(stacked.detach(head) == "stacked", "a method runs on a class C++ constructed on the stack, which no Rc owns");
            }
            const kira::Rc<shapes::Twin<std::int32_t>> twin = shapes::makeTwin(1, 7);
            check(twin->take(std::int32_t(twin->n)) == 1 && twin->n == 7, "a C++ caller copies a template's const T& argument the body writes (contract 5.4.4)");
            const kira::Rc<shapes::Tag> tag = std::make_shared<shapes::Tag>("old");
            const kira::Rc<shapes::TagPair> tagged = shapes::tagPair(tag, tag);
            check(tagged->a == "old" && tagged->n == 7 && tag->name == "new", "a construction reads a field before the sibling that renames it");
            const kira::Rc<shapes::Shelf> shelf = std::make_shared<shapes::Shelf>();
            shelf->tag = std::make_shared<shapes::Tag>(longName);
            check(shapes::shelved(kira::unwrap(shelf->tag)->name, shelf)->a == longName && shelf->tag == nullptr, "a construction copies a const& parameter before the sibling that frees what it names");
            const kira::Rc<shapes::Nest> nest = std::make_shared<shapes::Nest>();
            nest->twig = std::make_shared<shapes::Twig>(longName, nest);
            shapes::Twig* const twig = kira::unwrap(nest->twig).get();
            check(twig->leave()->a == longName && nest->twig == nullptr, "a construction copies its own object's field before the sibling that frees the object");
            const kira::Rc<shapes::Duo> duo = shapes::duo();
            const kira::Rc<shapes::DuoBox> duoBox = std::make_shared<shapes::DuoBox>();
            check(duoBox->duo->a == 11 && duoBox->duo->c == 21 && duoBox->duo->b == 21, "a field default's construction spills in a default argument, a skipped middle default read after the given values");
            check(duo->a == 1 && duo->c == 11 && duo->b == 11, "a skipped middle default is read after the given values");
            const kira::Rc<shapes::Crib> crib = std::make_shared<shapes::Crib>();
            crib->kid = std::make_shared<shapes::Kid>(longName);
            check(shapes::greetIt(kira::Rc<shapes::Kid>(crib->kid), crib) == longName && crib->kid->name == "other", "a C++ caller copies a type-parameter argument the arguments replace (contract 5.4.4)");
            shapes::tick = 0;
            const kira::Rc<shapes::Three> three = shapes::makeThree();
            check(three->a == 1 && three->b == 2 && three->c == 3, "defaults that are not pure run in declaration order (ctordefaults2)");
            shapes::tick = 0;
            const kira::Rc<shapes::Late> late = shapes::makeLate();
            check(late->seen == 1 && late->k == 2 && late->after == 3, "a subclass's defaults run after its superclass's initially (OQ-2, Kotlin's order)");
            shapes::tick = 0;
            const kira::Rc<shapes::High> high = shapes::makeHigh();
            check(high->a == 1 && high->b == 2, "a superclass's default runs before the subclass's (ctordefaults3)");
            shapes::tick = 0;
            const kira::Rc<shapes::Mix> mix = shapes::makeMix();
            check(mix->y == 1 && mix->x == 2 && mix->z == 3 && mix->w == 4, "the given values first, then the defaults in declaration order (Mid)");
            shapes::tick = 0;
            const kira::Rc<shapes::Mix> mixGiven = shapes::makeMixGiven();
            check(mixGiven->x == 50 && mixGiven->y == 1 && mixGiven->z == 2 && mixGiven->w == 70, "a given value of a field whose default is not pure fills its optional");
            shapes::tick = 10;
            const kira::Rc<shapes::Three> fromCpp = std::make_shared<shapes::Three>(7);
            check(fromCpp->a == 7 && fromCpp->b == 11 && fromCpp->c == 12 && shapes::tick == 12, "a C++ caller gives a value the optional takes, and leaves the rest to the constructor");
            shapes::tick = 0;
            const kira::Rc<shapes::Slot> emptied = shapes::emptySlot();
            const bool leftNull = emptied->item == std::nullopt && shapes::tick == 0;
            const kira::Rc<shapes::Slot> filled = shapes::fullSlot();
            check(leftNull && filled->item == kira::Str("picked") && shapes::tick == 100, "null given to a Maybe field whose default is not pure stays null; the default runs only when left out");
            std::printf("%d checks, %d failed\n", checks, failures);
            return failures == 0 ? 0 : 1;
        }
    """.trimIndent() + "\n"

    @TestFactory
    fun theShapesCompileAndRun(): List<DynamicTest> {
        val emitted = OopTestSupport.emit(OopTestSupport.module(uri, program))
        val header = emitted.header(uri)
        val source = emitted.source(uri)
        return listOf(CppToolchain.GCC, CppToolchain.CLANG, CppToolchain.MSVC, CppToolchain.ZIG_AARCH64).map { toolchain ->
            DynamicTest.dynamicTest("shapes on ${toolchain.id}") {
                val located = CppToolchains.requireOrSkip(toolchain)
                val dir = File("build/tmp/cpp-oop-compile/${toolchain.id}").absoluteFile
                val gen = File(dir, "gen/src/oop").apply { mkdirs() }
                File(gen, "shapes.kira.hxx").writeText(header)
                File(gen, "shapes.kira.cxx").writeText(source)
                val main = File(dir, "main.cxx").apply { writeText(driver) }
                val result = CppCompileSupport.compile(
                    sources = listOf(File(gen, "shapes.kira.cxx"), main),
                    includeDirs = listOf(File(dir, "gen"), File("kira/cpp").absoluteFile),
                    defines = emptyList(),
                    toolchain = located,
                    profile = CppProfile.forToolchain(toolchain),
                    outDir = File(dir, "out"),
                )
                assertTrue(result.success, "${toolchain.id} refused the classes:\n${result.describe()}\n--- header ---\n$header\n--- source ---\n$source")
                val exe = result.exe ?: return@dynamicTest
                val run = CppCompileSupport.run(exe, extraPathDirs = listOfNotNull(located.binDir))
                assertEquals(0, run.exitCode, "the driver failed on ${toolchain.id}:\n${run.stdout}\n${run.stderr}")
                assertTrue(run.stdout.contains("50 checks, 0 failed"), run.stdout)
            }
        }
    }
}
