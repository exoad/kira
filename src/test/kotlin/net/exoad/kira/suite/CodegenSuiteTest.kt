package net.exoad.kira.suite

import net.exoad.kira.TestCompileSupport
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * C backend shape coverage: what each Kira construct lowers to in the emitted
 * translation unit. These pin the emitted text (not behavior -- that is
 * RuntimeSuiteTest's job), so a drift in the lowering breaks a test with the
 * actual output.
 */
class CodegenSuiteTest {

    // --- helpers ---------------------------------------------------------

    private fun emit(body: String, uri: String = "test:codegen.basic"): String =
        TestCompileSupport.transpileSnippetToC(
            source = TestCompileSupport.wrapModule(uri, body),
            logicalPath = TestCompileSupport.logicalPathForModule(uri),
            runSemantic = false,
        )

    // --- prelude -----------------------------------------------------------

    @Test
    fun emitsPreludeSubstrateAndFacade() {
        val output = emit("x: Int32 = 1")
        assertTrue(output.contains("KIRA_COMPILER_BUNDLE_H"), output)
        assertTrue(output.contains("KIRA_RUNTIME_H"), output)
        assertTrue(output.contains("#include <stdio.h>"), output)
        assertTrue(output.contains("#include <stdlib.h>"), output)
        assertTrue(output.contains("typedef kira_i32   Int32;"), output)
        assertTrue(output.contains("typedef kira_f64   Float64;"), output)
        assertTrue(output.contains("typedef CharSeq Str;"), output)
        assertTrue(output.contains("#define print(...)"), output)
    }

    @Test
    fun emitsArcRuntimeHooks() {
        val output = emit("x: Int32 = 1")
        assertTrue(output.contains("kira_rc_alloc_with"), output)
        assertTrue(output.contains("kira_rc_retain"), output)
        assertTrue(output.contains("kira_rc_release"), output)
        assertTrue(output.contains("kira_rc_store"), output)
    }

    // --- functions and globals ----------------------------------------------

    @Test
    fun functionsLowerToCSignatures() {
        val output = emit(
            """
            fx add: (a: Int32, b: Int32) Int32 {
                return a + b
            }

            fx greet: (name: Str) Str {
                return name
            }

            fx main: () Void {
                trace("hi")
            }
            """
        )
        assertTrue(output.contains("Int32 add(Int32 a, Int32 b)"), output)
        assertTrue(output.contains("Str greet(Str name)"), output)
        assertTrue(output.contains("Int32 main(Void)"), output)
        // return value
        assertTrue(output.contains("return (a + b);"), output)
    }

    @Test
    fun globalsLowerToFileScopeVariables() {
        val output = emit(
            """
            x: Int32 = 10
            y: Str = "hello"
            """
        )
        assertTrue(output.contains("Int32 x = 10;"), output)
        assertTrue(output.contains("Str y = \"hello\";"), output)
    }

    @Test
    fun traceLowersToPrintWithCorrectFormat() {
        val output = emit(
            """
            fx main: () Void {
                trace("text")
                trace(7)
            }
            """
        )
        assertTrue(output.contains("print(\"%s\\n\", \"text\");"), output)
        assertTrue(output.contains("print(\"%d\\n\", 7);"), output)
    }

    // --- control flow ---------------------------------------------------------

    @Test
    fun ifElseLowersToCIfElse() {
        val output = emit(
            """
            fx main: () Void {
                x: Int32 = 2
                if x == 1 {
                    trace("one")
                } else {
                    trace("other")
                }
            }
            """
        )
        assertTrue(output.contains("if((x == 1))"), output)
        assertTrue(output.contains("} else"), output)
        assertTrue(output.contains("print(\"%s\\n\", \"one\");"), output)
        assertTrue(output.contains("print(\"%s\\n\", \"other\");"), output)
    }

    @Test
    fun whileLowersToCWhile() {
        val output = emit(
            """
            fx main: () Void {
                mut i: Int32 = 0
                while i < 3 {
                    i = i + 1
                }
            }
            """
        )
        assertTrue(output.contains("while((i < 3))"), output)
        assertTrue(output.contains("i = (i + 1);"), output)
    }

    @Test
    fun forRangeLowersToCFor() {
        val output = emit(
            """
            fx main: () Void {
                for mut i: 0..3 {
                    trace(i)
                }
            }
            """
        )
        assertTrue(output.contains("for(Int32 i = 0; i <= 3; ++i)"), output)
        assertTrue(output.contains("print(\"%d\\n\", i);"), output)
    }

    @Test
    fun compoundAssignmentsAreCurrentlyEmittedAsDiscardedExpressions() {
        // Known lowering gap, pinned: `a += 2` currently emits `(a + 2);`
        // which discards the result. A fix should make this test fail.
        val output = emit(
            """
            fx main: () Void {
                mut a: Int32 = 1
                a += 2
            }
            """
        )
        assertTrue(output.contains("(a + 2);"), output)
        assertFalse(output.contains("a = (a + 2);"), output)
    }

    // --- classes and methods ---------------------------------------------------

    @Test
    fun classLowersToStructConstructorAndFreeMethod() {
        val output = emit(
            """
            pub class Pet {
                require pub name: Str
                require pub sound: Str

                pub fx speak: () Str {
                    return sound
                }
            }
            """
        )
        assertTrue(output.contains("typedef struct Pet Pet;"), output)
        assertTrue(output.contains("struct Pet"), output)
        assertTrue(output.contains("Str name;"), output)
        assertTrue(output.contains("Str sound;"), output)
        // constructor
        assertTrue(output.contains("Pet* Pet_new(Str name, Str sound)"), output)
        assertTrue(output.contains("kira_rc_alloc_with(sizeof(Pet), null)"), output)
        // method as free function with receiver
        assertTrue(output.contains("Str Pet_speak(Pet* this)"), output)
        assertTrue(output.contains("return this->sound;"), output)
    }

    @Test
    fun methodCallsLowerToFreeFunctionWithReceiver() {
        val output = emit(
            """
            pub class Pet {
                require pub name: Str

                pub fx speak: () Str {
                    return name
                }
            }

            fx main: () Void {
                friend: Pet = Pet { "Mochi" }
                trace(friend.name)
                trace(friend.speak())
            }
            """
        )
        assertTrue(output.contains("Pet* friend = Pet_new(\"Mochi\");"), output)
        assertTrue(output.contains("print(\"%s\\n\", friend->name);"), output)
        assertTrue(output.contains("Pet_speak(friend)"), output)
    }

    @Test
    fun classLocalsAreArcReleasedAtScopeEnd() {
        val output = emit(
            """
            pub class Pet {
                require pub name: Str
            }

            fx main: () Void {
                friend: Pet = Pet { "Mochi" }
                trace(friend.name)
            }
            """
        )
        assertTrue(output.contains("kira_rc_release(friend);"), output)
        assertTrue(output.contains("return 0;"), output)
    }

    // --- enums ---------------------------------------------------------------

    @Test
    fun enumLowersToTypedefEnumWithPrefixedMembers() {
        val output = emit(
            """
            pub enum Mood {
                HAPPY,
                SAD
            }
            """
        )
        assertTrue(output.contains("typedef enum Mood"), output)
        assertTrue(output.contains("MOOD_HAPPY,"), output)
        assertTrue(output.contains("MOOD_SAD"), output)
    }

    @Test
    fun enumMemberAccessLowersToUppercaseConstant() {
        val output = emit(
            """
            pub enum Mood {
                HAPPY,
                SAD
            }

            fx main: () Void {
                state: Mood = Mood.HAPPY
            }
            """
        )
        assertTrue(output.contains("Mood state = MOOD_HAPPY;"), output)
    }

    // --- generics -------------------------------------------------------------

    @Test
    fun genericsMonomorphize() {
        val output = emit(
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
            """
        )
        assertTrue(output.contains("typedef struct Box_Int32 Box_Int32;"), output)
        assertTrue(output.contains("struct Box_Int32"), output)
        assertTrue(output.contains("Int32 id_Int32(Int32 value)"), output)
        assertTrue(output.contains("Box_Int32_new(7)"), output)
        assertFalse(output.contains("T id(T"), output)
    }

    @Test
    fun nestedGenericsLowerToNestedLiteralsAndAccessors() {
        val output = emit(
            """
            fx main: () Void {
                grid: Arr<Arr<Int32>> = [[1, 2], [3, 4]]
                trace(grid[1][0])
            }
            """
        )
        // Outer and inner Arr literals both appear, nested.
        assertTrue(output.contains("Arr_lit((KiraSlot[]){ Arr_lit((KiraSlot[]){ 1, 2 }, 2)"), output)
        // Chained element access composes the get helper.
        assertTrue(output.contains("Arr_get_i32(Arr_get_i32(grid, 1), 0)"), output)
    }

    @Test
    fun genericCallReturnTypeNotThreadedIntoPrintFormat() {
        // Pinned gap: monomorphization itself is correct (id_Str returns Str),
        // but the print-format heuristic at a generic call site cannot see the
        // return type and falls back to %d. Int32 works by luck; Int64 should
        // widen to %lld and Str needs %s. A fix flips this test.
        val output = emit(
            """
            fx id<T>: (value: T) T {
                return value
            }

            fx main: () Void {
                trace(id<Int32>(7))
                trace(id<Int64>(7))
                trace(id<Str>("ada"))
            }
            """
        )
        assertTrue(output.contains("Str id_Str(Str value)"), output)
        assertTrue(output.contains("print(\"%d\\n\", id_Int64(7));"), output)
        assertTrue(output.contains("print(\"%d\\n\", id_Str(\"ada\"));"), output)
        assertFalse(output.contains("id_Str(\"ada\")") && output.contains("print(\"%s\\n\", id_Str"), output)
    }

    // --- traits ----------------------------------------------------------------

    @Test
    fun traitDispatchEmitsVtablesAndTrampolines() {
        val output = emit(
            """
            pub trait Speaker {
                pub fx speak: () Str
            }

            pub class Dog: Speaker {
                require pub label: Str

                pub fx speak: () Str {
                    return "woof"
                }
            }

            fx announce: (s: Speaker) Void {
                trace(s.speak())
            }

            fx main: () Void {
                d: Dog = Dog { "Rex" }
                s: Speaker = d
                announce(s)
            }
            """
        )
        assertTrue(output.contains("struct Speaker"), output)
        assertTrue(output.contains("SpeakerVTable"), output)
        assertTrue(output.contains("Speaker_speak_tramp_Dog"), output)
        assertTrue(output.contains("Speaker_vtable_Dog"), output)
        assertTrue(output.contains(".vtable = &Speaker_vtable_Dog"), output)
        assertTrue(output.contains(".vtable->speak("), output)
    }

    // --- collections --------------------------------------------------------------

    @Test
    fun arrAndMapLowerToPreludeContainers() {
        val output = emit(
            """
            fx main: () Void {
                numbers: Arr<Int32> = [10, 20, 30]
                entries: Map<Str, Int32> = Map<Str, Int32> { }
                trace(numbers[0])
                entries.put("a", 1)
            }
            """
        )
        assertTrue(output.contains("typedef struct Arr"), output)
        assertTrue(output.contains("typedef struct Map"), output)
        assertTrue(output.contains("Arr_lit((KiraSlot[])"), output)
        assertTrue(output.contains("Map_new_s()"), output)
        assertTrue(output.contains("Map_put"), output)
    }

    @Test
    fun listSetStackQueueLowerToPreludeContainers() {
        val output = emit(
            """
            fx main: () Void {
                names: List<Str> = List<Str> { }
                seen: Set<Int32> = Set<Int32> { }
                stack: Stack<Int32> = Stack<Int32> { }
                queue: Queue<Int32> = Queue<Int32> { }
                names.add("ada")
                seen.add(1)
                stack.push(2)
                queue.enqueue(3)
            }
            """
        )
        assertTrue(output.contains("List_add"), output)
        assertTrue(output.contains("Set_add"), output)
        assertTrue(output.contains("Stack_push"), output)
        assertTrue(output.contains("Queue_enqueue"), output)
    }

    // --- intrinsics / foreign ----------------------------------------------------

    @Test
    fun externLowersToLiteralCNamePlaceholder() {
        // Known gap: @_extern currently emits the C name as a bare string
        // statement. Pinned so the intended fopen() call is not silently lost.
        val output = emit(
            """
            @_opaque class FileHandle { }

            fx openFile: (path: Str) FileHandle {
                @_extern("fopen")
                return null
            }
            """
        )
        assertTrue(output.contains("FileHandle* openFile(Str path)"), output)
        assertTrue(output.contains("\"fopen\";"), output)
    }

    @Test
    fun magicClassesAreNotEmittedAsUserStructs() {
        val output = emit("x: Int32 = 1")
        // The stdlib declares @_magic classes; they must not appear as structs.
        assertFalse(output.contains("struct Int32"), output)
        assertFalse(output.contains("struct Str"), output)
        assertFalse(output.contains("struct Bool"), output)
    }

    // --- misc shapes ---------------------------------------------------------------

    @Test
    fun arrayIndexLowersToGetHelper() {
        val output = emit(
            """
            fx main: () Void {
                numbers: Arr<Int32> = [10, 20]
                trace(numbers[0])
            }
            """
        )
        assertTrue(output.contains("Arr_get_i32"), output)
    }

    @Test
    fun stringMethodsLowerToPreludeHelpers() {
        val output = emit(
            """
            fx main: () Void {
                s: Str = "  Kira  "
                trace(s.trim().length())
            }
            """
        )
        assertTrue(output.contains("Str_trim("), output)
        assertTrue(output.contains("Str_length("), output)
    }
}
