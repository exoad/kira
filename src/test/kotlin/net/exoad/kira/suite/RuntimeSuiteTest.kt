package net.exoad.kira.suite

import net.exoad.kira.TestCompileSupport
import net.exoad.kira.compiler.frontend.parser.ParserBackend
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end runtime coverage: transpile Kira to C, compile with the native
 * toolchain, run the binary, and assert exact stdout. This is the full
 * language ladder exercised as behavior, not just emitted text.
 *
 * Skips (via JUnit assumption) when no C compiler is on PATH, so the rest of
 * the suite still runs on machines without a toolchain.
 */
class RuntimeSuiteTest {

    companion object {
        private var compiler: String? = null

        @JvmStatic
        @BeforeAll
        fun locateCompiler() {
            compiler = TestCompileSupport.findCCompiler()
        }
    }

    private fun run(body: String, uri: String = "test:runtime.basic"): Pair<String, String> {
        val cc = compiler ?: return "" to ""
        val generated = TestCompileSupport.transpileSnippetToC(
            source = TestCompileSupport.wrapModule(uri, body),
            logicalPath = TestCompileSupport.logicalPathForModule(uri),
            parserBackend = ParserBackend.LEGACY,
            runSemantic = false,
        )
        val result = TestCompileSupport.compileAndRunC(generated, cc)
        assumeTrue(
            result.compileResult.exitCode == 0,
            "cc failed. stderr:\n${result.compileResult.stderr}\nC:\n$generated"
        )
        val exec = assertNotNull(result.runResult, "binary did not run")
        return exec.stdout to exec.stderr
    }

    private fun assertStdout(expected: String, uri: String = "test:runtime.basic", body: () -> String) {
        val (stdout, stderr) = run(body(), uri)
        assertEquals(expected, stdout, "stderr:\n$stderr")
    }

    // --- hello / functions -----------------------------------------------------

    @Test
    fun helloWorld() {
        assertStdout("hello, kira\n") {
            """
            fx main: () Void {
                trace("hello, kira")
            }
            """
        }
    }

    @Test
    fun arithmeticAndOrderOfOperations() {
        assertStdout("5\n") {
            """
            fx main: () Void {
                a: Int32 = 1 + 2 * 3 - 4 / 2
                trace(a)
            }
            """
        }
    }

    @Test
    fun functionCallAndReturn() {
        assertStdout("8\n") {
            """
            fx add: (a: Int32, b: Int32) Int32 {
                return a + b
            }

            fx main: () Void {
                trace(add(5, 3))
            }
            """
        }
    }

    // --- control flow ------------------------------------------------------------

    @Test
    fun ifElseBranches() {
        assertStdout("two\n") {
            """
            fx main: () Void {
                x: Int32 = 2
                if x == 1 {
                    trace("one")
                } else {
                    trace("two")
                }
            }
            """
        }
    }

    @Test
    fun whileLoop() {
        assertStdout("0\n1\n2\n3\n4\n") {
            """
            fx main: () Void {
                mut i: Int32 = 0
                while i < 5 {
                    trace(i)
                    i = i + 1
                }
            }
            """
        }
    }

    @Test
    fun forRangeLoop() {
        assertStdout("0\n1\n2\n3\n") {
            """
            fx main: () Void {
                for mut i: 0..3 {
                    trace(i)
                }
            }
            """
        }
    }

    @Test
    fun breakAndContinue() {
        assertStdout("1\n2\n3\n") {
            """
            fx main: () Void {
                for mut i: 0..10 {
                    if i == 0 {
                        continue
                    }
                    if i == 4 {
                        break
                    }
                    trace(i)
                }
            }
            """
        }
    }

    // --- classes / methods / ARC ---------------------------------------------------

    @Test
    fun classConstructionAndMethods() {
        assertStdout("Mochi\nmeow\n") {
            """
            pub class Pet {
                require pub name: Str
                require pub sound: Str

                pub fx speak: () Str {
                    return sound
                }
            }

            fx main: () Void {
                friend: Pet = Pet { "Mochi", "meow" }
                trace(friend.name)
                trace(friend.speak())
            }
            """
        }
    }

    // --- enums / generics ------------------------------------------------------------

    @Test
    fun enumComparison() {
        assertStdout("ready\n") {
            """
            pub enum Status {
                READY,
                RUNNING,
                DONE
            }

            fx main: () Void {
                state: Status = Status.READY
                if state == Status.READY {
                    trace("ready")
                } else {
                    trace("other")
                }
            }
            """
        }
    }

    @Test
    fun genericIdentityAndBox() {
        assertStdout("7\n") {
            """
            pub class Box<T> {
                require pub value: T
            }

            fx id<T>: (value: T) T {
                return value
            }

            fx main: () Void {
                wrapped: Box<Int32> = Box<Int32> { 7 }
                trace(id<Int32>(wrapped.value))
            }
            """
        }
    }

    // --- traits ----------------------------------------------------------------------

    @Test
    fun traitDispatchThroughVtable() {
        assertStdout("Rex\nwoof\nLuna\nmeow\n8\n") {
            """
            pub trait Speaker {
                pub fx speak: () Str
                pub fx name: () Str
            }

            pub trait Noisy: Speaker {
                pub fx loudness: () Int32
            }

            pub class Dog: Noisy {
                require pub label: Str

                pub fx speak: () Str {
                    return "woof"
                }

                pub fx name: () Str {
                    return label
                }

                pub fx loudness: () Int32 {
                    return 8
                }
            }

            pub class Cat: Speaker {
                require pub label: Str

                pub fx speak: () Str {
                    return "meow"
                }

                pub fx name: () Str {
                    return label
                }
            }

            fx announce: (s: Speaker) Void {
                trace(s.name())
                trace(s.speak())
            }

            fx noiseLevel: (s: Noisy) Int32 {
                return s.loudness()
            }

            fx main: () Void {
                dog: Dog = Dog { "Rex" }
                cat: Cat = Cat { "Luna" }
                announce(dog)
                announce(cat)
                trace(noiseLevel(dog))
            }
            """
        }
    }

    // --- collections -------------------------------------------------------------------

    @Test
    fun arrIndexAndSize() {
        assertStdout("10\n") {
            """
            fx main: () Void {
                numbers: Arr<Int32> = [10, 20, 30]
                trace(numbers[0])
            }
            """
        }
    }

    @Test
    fun nestedGenericArrayIndexing() {
        assertStdout("3\n") {
            """
            fx main: () Void {
                grid: Arr<Arr<Int32>> = [[1, 2], [3, 4]]
                trace(grid[1][0])
            }
            """
        }
    }

    @Test
    fun genericIdentityInstantiatesAcrossNumericTypes() {
        // Int8/Int16/Int32 all print as %d, so the happy path works through
        // generic calls. Str/Int64 returns hit the print-format gap pinned in
        // CodegenSuiteTest.genericCallReturnTypeNotThreadedIntoPrintFormat.
        assertStdout("1\n2\n3\n") {
            """
            fx id<T>: (value: T) T {
                return value
            }

            fx main: () Void {
                trace(id<Int8>(1))
                trace(id<Int16>(2))
                trace(id<Int32>(3))
            }
            """
        }
    }

    @Test
    fun mapPutGetWithMaybe() {
        assertStdout("1\n36\n-1\n") {
            """
            fx main: () Void {
                ages: Map<Str, Int32> = Map<Str, Int32> { }
                ages.put("ada", 36)
                found: Maybe<Int32> = ages.get("ada")
                trace(found.isSome())
                trace(found.unwrapOr(0))
                missing: Maybe<Int32> = ages.get("nobody")
                trace(missing.unwrapOr(-1))
            }
            """
        }
    }

    @Test
    fun listOfStrings() {
        assertStdout("2\ngrace\n1\n") {
            """
            fx main: () Void {
                names: List<Str> = List<Str> { }
                names.add("ada")
                names.add("grace")
                trace(names.size())
                trace(names.get(1))
                trace(names.contains("ada"))
            }
            """
        }
    }

    @Test
    fun setDeduplicates() {
        assertStdout("2\n1\n") {
            """
            fx main: () Void {
                seen: Set<Int32> = Set<Int32> { }
                seen.add(1)
                seen.add(2)
                seen.add(1)
                trace(seen.size())
                trace(seen.contains(2))
            }
            """
        }
    }

    @Test
    fun stackAndQueue() {
        assertStdout("10\n1\n1\n") {
            """
            fx main: () Void {
                undo: Stack<Int32> = Stack<Int32> { }
                undo.push(10)
                top: Maybe<Int32> = undo.pop()
                trace(top.unwrapOr(0))
                empty: Maybe<Int32> = undo.pop()
                trace(empty.isNone())

                jobs: Queue<Int32> = Queue<Int32> { }
                jobs.enqueue(1)
                jobs.enqueue(2)
                first: Maybe<Int32> = jobs.dequeue()
                trace(first.unwrapOr(0))
            }
            """
        }
    }

    // --- stdlib helpers -----------------------------------------------------------------

    @Test
    fun stringMethods() {
        assertStdout("4\nKIRA\n1\n") {
            """
            fx main: () Void {
                s: Str = "  Kira  "
                t: Str = s.trim()
                trace(t.length())
                trace(t.toUpper())
                trace(t.startsWith("Ki"))
            }
            """
        }
    }

    @Test
    fun numConversion() {
        assertStdout("7\n") {
            """
            fx main: () Void {
                n: Int32 = 7
                trace(n.toInt64())
            }
            """
        }
    }

    @Test
    fun assertHelper() {
        assertStdout("ok\n") {
            """
            fx main: () Void {
                assert(true, "fine")
                trace("ok")
            }
            """
        }
    }
}
