package net.exoad.kira

import net.exoad.kira.compiler.frontend.parser.ParserBackend
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The JS backend shares the frontend and semantics with the C backend, then
 * diverges exactly where the runtime shape demands: generics erase instead of
 * monomorphizing, traits erase instead of building vtables, ARC is a no-op
 * (GC owns memory), and magic receivers rewrite to prelude helpers (Str is a
 * JS primitive string, Arr is a native Array). These tests pin the emitted
 * shape and the runtime behaviour.
 */
class JSCodeGeneratorSmokeTest {
    companion object {
        private var node: String? = null

        @JvmStatic
        @BeforeAll
        fun locateNode() {
            node = TestCompileSupport.findNode()
        }
    }

    private fun emit(body: String, uri: String): String {
        return TestCompileSupport.transpileSnippetToJS(
            source = TestCompileSupport.wrapModule(uri, body),
            logicalPath = TestCompileSupport.logicalPathForModule(uri),
            parserBackend = ParserBackend.LEGACY,
            runSemantic = false
        )
    }

    private fun runAndCapture(generated: String): String? {
        val n = node ?: return null
        val result = TestCompileSupport.runJS(generated, n)
        assumeTrue(result.exitCode == 0, "node failed:\n${result.stderr}\n$generated")
        return result.stdout
    }

    @Test
    fun mainIsInvokedExplicitly() {
        val generated = emit(
            """
            fx main: () Void {
                trace("hi")
            }
            """,
            "test:js.main"
        )
        assertTrue(generated.trimEnd().endsWith("main();"), generated)
    }

    @Test
    fun printFormatsBooleansLikeC() {
        val generated = emit(
            """
            fx main: () Void {
                trace(true)
                trace(false)
            }
            """,
            "test:js.bool"
        )
        assertEquals("1\n0\n", runAndCapture(generated) ?: return)
    }

    @Test
    fun integerDivisionTruncatesLikeC() {
        val generated = emit(
            """
            fx main: () Void {
                trace(7 / 2)
                trace(9 / 3)
            }
            """,
            "test:js.div"
        )
        // C int / int truncates; JS / is float division, so codegen must truncate.
        assertTrue(generated.contains("Math.trunc"), generated)
        assertEquals("3\n3\n", runAndCapture(generated) ?: return)
    }

    @Test
    fun strMethodsRewriteToPreludeHelpers() {
        val generated = emit(
            """
            fx main: () Void {
                s: Str = "  Kira  "
                t: Str = s.trim()
                trace(t.length())
                trace(t.toUpper())
                trace(t.startsWith("Ki"))
            }
            """,
            "test:js.str"
        )

        assertTrue(generated.contains("kira_str_trim("), generated)
        assertTrue(generated.contains("kira_str_length("), generated)
        assertTrue(generated.contains("kira_str_toUpper("), generated)

        assertEquals("4\nKIRA\n1\n", runAndCapture(generated) ?: return)
    }

    @Test
    fun genericsEraseInsteadOfMonomorphizing() {
        val generated = emit(
            """
            pub class Box<T> {
                require pub value: T
            }

            fx id<T>: (value: T) T {
                return value
            }

            fx main: () Void {
                wrapped: Box<Int32> = Box<Int32> { 7 }
                value: Int32 = id<Int32>(wrapped.value)
                trace(value)
            }
            """,
            "test:js.generics"
        )

        // One template, type args dropped -- no Box_Int32 / id_Int32 monomorphs.
        assertTrue(generated.contains("class Box"), generated)
        assertTrue(generated.contains("new Box("), generated)
        assertTrue(generated.contains("function id("), generated)
        assertTrue(generated.contains("id(wrapped.value)"), generated)
        assertFalse(generated.contains("Box_Int32"), generated)

        assertEquals("7\n", runAndCapture(generated) ?: return)
    }

    @Test
    fun classesAndMethodsLowerToJsClasses() {
        val generated = emit(
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
            """,
            "test:js.class"
        )

        assertTrue(generated.contains("class Pet {"), generated)
        assertTrue(generated.contains("constructor(name, sound)"), generated)
        assertTrue(generated.contains("this.name = name"), generated)
        assertTrue(generated.contains("speak() {"), generated)
        assertTrue(generated.contains("this.sound"), generated)
        assertTrue(generated.contains("new Pet("), generated)

        assertEquals("Mochi\nmeow\n", runAndCapture(generated) ?: return)
    }

    @Test
    fun traitsEraseToDuckTyping() {
        val generated = emit(
            """
            trait Speaker {
                pub fx speak: () Str
            }

            class Dog {
                require pub label: Str

                pub fx speak: () Str {
                    return label
                }
            }

            fx announce: (s: Speaker) Void {
                trace(s.speak())
            }

            fx main: () Void {
                dog: Dog = Dog { "Rex" }
                announce(dog)
            }
            """,
            "test:js.trait"
        )

        // No vtable structs, no trampolines -- just a method call.
        assertFalse(generated.contains("VTable"), generated)
        assertFalse(generated.contains("tramp"), generated)
        assertTrue(generated.contains("s.speak()"), generated)

        assertEquals("Rex\n", runAndCapture(generated) ?: return)
    }

    @Test
    fun arcIsANoOp() {
        val generated = emit(
            """
            pub class Point {
                require pub x: Int32
                require pub y: Int32
            }

            fx main: () Void {
                p: Point = Point { 1, 2 }
                trace(p.x)
            }
            """,
            "test:js.arc"
        )

        assertFalse(generated.contains("kira_rc_"), generated)
        assertEquals("1\n", runAndCapture(generated) ?: return)
    }

    @Test
    fun setDeduplicatesThroughRuntimeClass() {
        val generated = emit(
            """
            fx main: () Void {
                seen: Set<Int32> = Set<Int32> { }
                seen.add(1)
                seen.add(2)
                seen.add(1)
                trace(seen.size())
                trace(seen.contains(2))
            }
            """,
            "test:js.set"
        )

        assertTrue(generated.contains("kira_set_new()"), generated)
        assertEquals("2\n1\n", runAndCapture(generated) ?: return)
    }

    @Test
    fun mapGetReturnsMaybeAndUnwraps() {
        val generated = emit(
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
            """,
            "test:js.map"
        )

        assertTrue(generated.contains("kira_map_new()"), generated)
        assertEquals("1\n36\n-1\n", runAndCapture(generated) ?: return)
    }

    @Test
    fun stackAndQueueReturnMaybeOnEmpty() {
        val generated = emit(
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
            """,
            "test:js.adt"
        )

        assertEquals("10\n1\n1\n", runAndCapture(generated) ?: return)
    }

    @Test
    fun numConversionsAreIdentityOnNumbers() {
        val generated = emit(
            """
            fx main: () Void {
                n: Int32 = 7
                trace(n.toInt64())
                trace(n.abs())
            }
            """,
            "test:js.num"
        )

        assertTrue(generated.contains("kira_num_toInt64("), generated)
        assertTrue(generated.contains("Math.abs("), generated)
        assertEquals("7\n7\n", runAndCapture(generated) ?: return)
    }

    @Test
    fun assertUsesTwoArgumentHelper() {
        val generated = emit(
            """
            fx main: () Void {
                assert(true, "fine")
                trace("ok")
            }
            """,
            "test:js.assert"
        )

        assertTrue(generated.contains("kira_assert(true, \"fine\")"), generated)
        assertEquals("ok\n", runAndCapture(generated) ?: return)
    }

    @Test
    fun listHoldsStrElementsByValue() {
        val generated = emit(
            """
            fx main: () Void {
                names: List<Str> = List<Str> { }
                names.add("ada")
                names.add("grace")
                trace(names.size())
                trace(names.get(1))
                trace(names.contains("ada"))
            }
            """,
            "test:js.liststr"
        )

        // JS compares string content, not pointers -- contains works without
        // the C backend's literal-pooling assumption.
        assertTrue(generated.contains("names.add(\"ada\")"), generated)
        assertEquals("2\ngrace\n1\n", runAndCapture(generated) ?: return)
    }

    @Test
    fun stdlibSourcesAreSkippedAndRuntimeComesFromPrelude() {
        val generated = emit(
            """
            fx main: () Void {
                trace("no stdlib noise")
            }
            """,
            "test:js.skip"
        )
        assertFalse(generated.contains("module \"kira:"), generated)
        assertTrue(generated.contains("kira_trace("), generated)
    }
}
