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
 * constructed class, a destructor from `finally`, a skipped middle default of a narrow
 * type, and plain methods that write their receiver (not `const`). The bodies are W2.4's
 * fakes ([OopTestSupport.FakeStmtEmitter]), so the Kira bodies here only return values and
 * assign; the shapes around them are the real classes part.
 */
class CppClassCompileTest {
    private val uri = "oop:shapes"

    private val program = """
        // Two traits over one parent: a diamond, so both derive Named virtually.
        pub trait Named {
            pub fx id: () Str;
            pub fx greeting: () Str {
                return "hello"
            }
        }

        pub trait Tagged: Named {
            pub fx tag: () Str;
        }

        pub trait Labelled: Named {
            pub fx label: () Str;
        }

        pub trait Sized {
            pub fx size: () Int32;
        }

        pub class Animal: Named {
            require pub name: Str
            mut legs: Int32 = 4

            finally {
            }

            override pub fx id: () Str {
                return name
            }

            pub fx sound: () Str {
                return "..."
            }

            pub fx legCount: () Int32 {
                return legs
            }
        }

        pub class Dog: Animal, Sized {
            mut tricks: Int32 = 0

            override pub fx sound: () Str {
                return "woof"
            }

            override pub fx size: () Int32 {
                return tricks
            }

            pub fx me: () Dog {
                return this
            }

            // Lambdas that escape and capture the receiver: [self = ...], cast to Dog.
            pub fx later: () Fx<Tuple0, Dog> {
                return fx() Dog {
                    return this
                }
            }

            pub mut fx laterMut: () Fx<Tuple0, Dog> {
                return fx() Dog {
                    return this
                }
            }
        }

        pub class Both: Tagged, Labelled {
            override pub fx id: () Str {
                return "both"
            }

            override pub fx tag: () Str {
                return "t"
            }

            override pub fx label: () Str {
                return "l"
            }
        }

        // Reaches Named through Animal (which implements id) and through Tagged (which does
        // not): id is inherited by dominance, so Pup forwards it (MSVC C4250 otherwise).
        pub class Pup: Animal, Tagged {
            override pub fx tag: () Str {
                return "pup"
            }
        }

        pub class Box<T> {
            require pub value: T

            pub fx get: () T {
                return value
            }
        }

        // A class template whose `this` escapes: its kira::Shared base is dependent.
        pub class Cell<T> {
            require pub value: T

            pub fx me: () Cell<T> {
                return this
            }

            pub mut fx later: () Fx<Tuple0, Cell<T>> {
                return fx() Cell<T> {
                    return this
                }
            }
        }

        // A trait template with a default body, implemented at one instantiation; take's
        // parameter is a bare T, const T& in C++.
        pub trait Source<T> {
            pub fx get: () T;
            pub fx take: (v: T) T;
            pub fx again: () T {
                return get()
            }
        }

        pub class Five: Source<Int32> {
            override pub fx get: () Int32 {
                return 5
            }

            override pub fx take: (v: Int32) Int32 {
                return v
            }
        }

        // The same requirement at Bool, and a generic class's method overridden at Int32:
        // each override says const T& as its base does, or it overrides nothing.
        pub class Flag: Source<Bool> {
            override pub fx get: () Bool {
                return true
            }

            override pub fx take: (v: Bool) Bool {
                return v
            }
        }

        pub class Base<T> {
            pub fx take: (v: T) Int32 {
                return 0
            }
        }

        pub class IntBase: Base<Int32> {
            override pub fx take: (v: Int32) Int32 {
                return v
            }
        }

        pub fx makeFlag: () Source<Bool> {
            return Flag {}
        }

        pub fx makeIntBase: () Base<Int32> {
            return IntBase {}
        }

        pub class Holder {
            require pet: Animal
            mut spare: Maybe<Animal> = null

            pub fx getPet: () Animal {
                return pet
            }

            pub fx getSpare: () Maybe<Animal> {
                return spare
            }
        }

        pub fx makeDog: (name: Str) Dog {
            return Dog { name }
        }

        pub fx makePup: (name: Str) Pup {
            return Pup { name }
        }

        pub fx makeCell: (v: Int32) Cell<Int32> {
            return Cell<Int32> { v }
        }

        pub fx makeFive: () Source<Int32> {
            return Five {}
        }

        pub fx makeBox: (v: Int32) Box<Int32> {
            return Box<Int32> { v }
        }

        pub fx asNamed: (d: Dog) Named {
            return d
        }

        pub fx sizeOf<T: Sized>: (v: T) Int32 {
            return v.size()
        }

        pub fx dogSize: (d: Dog) Int32 {
            return sizeOf<Dog>(d)
        }

        pub fx counter: () Ref<Int32> {
            return Ref<Int32> { value = 7 }
        }

        // A skipped middle default of a narrow type: typed, or MSVC /W4 /WX sees an int
        // narrowed inside make_shared (C4244).
        pub class Band {
            require pub lo: Int32
            pub mid: UInt8 = 5
            require pub hi: Int32
        }

        pub fx makeBand: () Band {
            return Band { lo = 1, hi = 2 }
        }

        // Plain fx methods that write the receiver (the typer allows it, D29): not const.
        pub fx grab: (mut v: Int32) Void {
            v = 7
        }

        pub class Counter {
            mut n: Int32 = 0

            pub fx bump: () Int32 {
                n += 1
                return n
            }

            pub mut fx reset: () Void {
                n = 0
            }

            pub fx again: () Int32 {
                reset()
                return n
            }

            pub fx take: () Int32 {
                grab(mut n)
                return n
            }

            pub fx peek: () Int32 {
                return n
            }
        }

        pub fx makeCounter: () Counter {
            return Counter {}
        }

        // A skipped middle default of an Arr type: make_shared deduces nothing from a bare
        // braced list, so the filled-in default carries its type, in a subclass's construction too.
        pub class Frame {
            pub mut buf: Arr<UInt8, 4> = [1, 2, 3, 4]
            require pub id: Int32
        }

        pub class Stamped: Frame {
            require pub stamp: Int32
        }

        pub fx makeFrame: () Frame {
            return Frame { id = 7 }
        }

        pub fx makeStamped: () Stamped {
            return Stamped { id = 8, stamp = 9 }
        }

        // One override of a method two traits declare, writing its receiver: both pure virtuals lose const.
        pub trait Left {
            pub fx hit: () Int32;
        }

        pub trait Right {
            pub fx hit: () Int32;
        }

        pub class Target: Left, Right {
            mut hits: Int32 = 0

            override pub fx hit: () Int32 {
                hits += 1
                return hits
            }
        }

        pub fx makeTarget: () Target {
            return Target {}
        }

        pub fx hitLeft: (l: Left) Int32 {
            return l.hit()
        }

        pub fx hitRight: (r: Right) Int32 {
            return r.hit()
        }

        // One override of a superclass method that also implements a trait's, writing: the trait's follows.
        pub trait Stamper {
            pub fx stamp: () Int32;
        }

        pub class Plain {
            pub mut count: Int32 = 0

            pub fx stamp: () Int32 {
                return 1
            }
        }

        pub class Counting: Plain, Stamper {
            override pub fx stamp: () Int32 {
                count += 1
                return count
            }
        }

        pub fx makeCounting: () Counting {
            return Counting {}
        }

        pub fx stampVia: (s: Stamper) Int32 {
            return s.stamp()
        }

        pub fx stampPlain: (p: Plain) Int32 {
            return p.stamp()
        }

        // A struct takes a trait's default body as its own member: static dispatch through a bound and directly.
        pub trait Shape {
            pub fx area: () Int32;
            pub fx same: () Int32 {
                return area()
            }
            pub mut fx grow: () Void;
            pub fx growOnce: () Void {
                grow()
            }
        }

        pub struct Square: Shape {
            pub side: Int32 = 3

            override pub fx area: () Int32 {
                return side
            }

            override pub mut fx grow: () Void {
                side = 5
            }
        }

        pub fx sameOf<T: Shape>: (s: T) Int32 {
            return s.same()
        }

        pub fx squareSame: (q: Square) Int32 {
            return sameOf<Square>(q)
        }

        pub fx squareDirect: (q: Square) Int32 {
            return q.same()
        }

        // A subclass's escaping lambda that writes a field and calls a mut fx: the captured
        // self is cast down without const, as the method itself is not const.
        pub class Lower {
            mut n: Int32 = 0

            pub fx get: () Int32 {
                return n
            }
        }

        pub class Upper: Lower {
            mut k: Int32 = 0

            pub mut fx reset: () Void {
                k = 100
            }

            pub fx later: () Fx<Tuple0, Int32> {
                return fx() Int32 {
                    k += 1
                    return k
                }
            }

            pub fx laterReset: () Fx<Tuple0, Int32> {
                return fx() Int32 {
                    reset()
                    return k
                }
            }

            pub fx peek: () Fx<Tuple0, Int32> {
                return fx() Int32 {
                    return k
                }
            }
        }

        pub fx makeUpper: () Upper {
            return Upper {}
        }

        // One name from two unrelated traits, a requirement beside a default: the struct takes
        // the default as its member, the class declares a forwarder to it (C++ would hold the
        // class abstract and the call ambiguous).
        pub trait Abs {
            pub fx f: () Int32;
        }

        pub trait Def {
            pub fx f: () Int32 {
                return 7
            }
        }

        pub struct Sib: Abs, Def {
            pub v: Int32 = 0
        }

        pub class SibClass: Abs, Def {
        }

        pub fx viaAbs: (a: Abs) Int32 {
            return a.f()
        }

        pub fx viaAbsBound<T: Abs>: (s: T) Int32 {
            return s.f()
        }

        pub fx sibBound: (s: Sib) Int32 {
            return viaAbsBound<Sib>(s)
        }

        pub fx makeSib: () SibClass {
            return SibClass {}
        }

        // An override of an override of a generic root, at Int32: both say the root's const&.
        pub trait Feed<T> {
            pub fx take: (v: T) T;
        }

        pub class Mid: Feed<Int32> {
            override pub fx take: (v: Int32) Int32 {
                return v
            }
        }

        pub class Leaf: Mid {
            override pub fx take: (v: Int32) Int32 {
                return 12
            }
        }

        pub fx makeLeaf: () Leaf {
            return Leaf {}
        }

        // An Fx parameter and an Fx return of a generic base, at Int32: kira::Fn<...(const T&)> stays so.
        pub trait Each<T> {
            pub fx each: (f: Fx<Tuple1<T>, Int32>) Int32;
        }

        pub class Nums: Each<Int32> {
            override pub fx each: (f: Fx<Tuple1<Int32>, Int32>) Int32 {
                return f(5)
            }
        }

        pub fx makeNums: () Each<Int32> {
            return Nums {}
        }

        pub trait Maker<T> {
            pub fx make: () Fx<Tuple1<T>, Int32>;
        }

        pub class IntMaker: Maker<Int32> {
            override pub fx make: () Fx<Tuple1<Int32>, Int32> {
                return fx(x: Int32) Int32 {
                    return x
                }
            }
        }

        pub fx makeMaker: () Maker<Int32> {
            return IntMaker {}
        }

        // An override taken by const& where Kira takes a value: the body reads the value it
        // was passed, not the field the argument named, which it writes first.
        pub class Alias: Feed<Int32> {
            pub mut n: Int32 = 1

            override pub fx take: (v: Int32) Int32 {
                n = 100
                return v
            }
        }

        pub fx makeAlias: () Alias {
            return Alias {}
        }

        // A trait default whose parameter is named as a field of the struct that copies it.
        pub trait Scaled {
            pub fx unit: () Int32;
            pub fx scaled: (side: Int32) Int32 {
                return side
            }
        }

        pub struct Tile: Scaled {
            pub side: Int32 = 3

            override pub fx unit: () Int32 {
                return side
            }
        }
    """

    private val driver = """
        #include "src/oop/shapes.kira.hxx"

        #include <cstdio>
        #include <memory>

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
            check(alias->take(alias->n) == 1 && alias->n == 100, "an override taken by const& reads the value it was passed, not the field it writes");
            const shapes::Tile tile{};
            check(tile.scaled(2) == 2 && tile.unit() == 3, "a trait default's parameter named as the copying struct's field");
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
                assertTrue(run.stdout.contains("30 checks, 0 failed"), run.stdout)
            }
        }
    }
}
