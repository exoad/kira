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
 * constructed class and a destructor from `finally`. The bodies are W2.4's fakes
 * ([OopTestSupport.FakeStmtEmitter]), so the Kira bodies here only return values; the
 * shapes around them are the real classes part.
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

        // A trait template with a default body, implemented at one instantiation.
        pub trait Source<T> {
            pub fx get: () T;
            pub fx again: () T {
                return get()
            }
        }

        pub class Five: Source<Int32> {
            override pub fx get: () Int32 {
                return 5
            }
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
            shapes::Holder h(d);
            check(h.getPet() == d && h.getSpare() == nullptr, "a stack-constructed class; Maybe<Class> is a nullable Rc");
            check(shapes::counter()->value == 7, "Ref<T> is an Rc of a Box");
            {
                shapes::Dog stacked("stacked");
                check(stacked.sound() == "woof", "C++ may construct a Kira class on the stack (D11)");
            }
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
                assertTrue(run.stdout.contains("15 checks, 0 failed"), run.stdout)
            }
        }
    }
}
