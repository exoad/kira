package net.exoad.kira.suite

import net.exoad.kira.TestCompileSupport
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
            add: (a: Int32, b: Int32) Int32 {
                a + b
            }

            greet: (name: Str) Str {
                name
            }

            main: () Void {
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
            main: () Void {
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
            main: () Void {
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
            main: () Void {
                i: Mutable<Int32> = 0
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
            main: () Void {
                for i: Int32 in 0..4 {
                    trace(i)
                }
            }
            """
        )
        assertTrue(output.contains("for(Int32 i = 0; i < 4; ++i)"), output)
        assertTrue(output.contains("print(\"%d\\n\", i);"), output)
    }

    @Test
    fun compoundAssignmentsEmitRealStores() {
        // `a += 2` must lower to a real store, not the old discarded
        // `(a + 2);` expression. Primitives keep the native compound op.
        val output = emit(
            """
            main: () Void {
                a: Mutable<Int32> = 1
                a += 2
            }
            """
        )
        assertTrue(output.contains("a += 2;"), output)
        assertFalse(output.contains("(a + 2);"), output)
    }

    // --- classes and methods ---------------------------------------------------

    @Test
    fun classLowersToStructConstructorAndFreeMethod() {
        val output = emit(
            """
            pub Pet: Any {
                pub name: Str
                pub sound: Str

                pub speak: () Str {
                    sound
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
            pub Pet: Any {
                pub name: Str

                pub speak: () Str {
                    name
                }
            }

            main: () Void {
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
            pub Pet: Any {
                pub name: Str
            }

            main: () Void {
                friend: Pet = Pet { "Mochi" }
                trace(friend.name)
            }
            """
        )
        assertTrue(output.contains("kira_rc_release(friend);"), output)
        assertTrue(output.contains("return 0;"), output)
    }

    // --- enums ---------------------------------------------------------------

    // --- generics -------------------------------------------------------------

    @Test
    fun genericsMonomorphize() {
        val output = emit(
            """
            pub Box<T>: Any {
                pub value: T
            }

            id<T>: (value: T) T {
                value
            }

            main: () Void {
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
            main: () Void {
                grid: Arr<Arr<Int32>> = @arrOf(@arrOf(1, 2), @arrOf(3, 4))
                trace(grid[1][0])
            }
            """
        )
        // Outer and inner Arr literals both appear, nested.
        assertTrue(output.contains("Arr_lit((KiraSlot[]){ Arr_lit((KiraSlot[]){ 1, 2 }, 2)"), output)
        // Chained element access composes the get helper. The outer read
        // unslots to the declared element type `Arr` (which is what `cc`
        // rejects); the inner one has no element type to go on.
        assertTrue(output.contains("Arr_get_i32(KIRA_UNSLOT(Arr, Arr_get(grid, 1)), 0)"), output)
    }

    @Test
    fun namedArgumentsAreOrderedByTheCalleeNotByTheCall() {
        val output = emit(
            """
            sub: (a: Int32, b: Int32) Int32 { a - b }

            main: () Void {
                trace(sub(b = 3, a = 10))
            }
            """
        )
        assertTrue(output.contains("sub(10, 3)"), output)
        // Without the analyzer in front, a name the callee lacks is refused
        // at emit time rather than reordered by guesswork.
        val e = assertThrows<IllegalStateException> {
            emit(
                """
                sub: (a: Int32, b: Int32) Int32 { a - b }

                main: () Void {
                    trace(sub(a = 10, c = 3))
                }
                """
            )
        }
        assertTrue(e.message!!.contains("no parameter named 'c'"), e.message)
    }

    @Test
    fun forInOverAnUnknownTargetIsADiagnosticNotAStubLoop() {
        // The body used to run exactly once inside a `for(;;) { ... break; }`
        // stub whatever the target was. Now a target the backend cannot
        // type is refused outright.
        val e = assertThrows<IllegalStateException> {
            emit(
                """
                main: () Void {
                    for x: Int32 in mystery {
                        trace(x)
                    }
                }
                """
            )
        }
        assertTrue(e.message!!.contains("for-in over 'mystery'"), e.message)
    }

    @Test
    fun genericCallReturnTypeNotThreadedIntoPrintFormat() {
        // Pinned gap: monomorphization itself is correct (id_Str returns Str),
        // but the print-format heuristic at a generic call site cannot see the
        // return type and falls back to %d. Int32 works by luck; Int64 should
        // widen to %lld and Str needs %s. A fix flips this test.
        val output = emit(
            """
            id<T>: (value: T) T {
                value
            }

            main: () Void {
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
            pub Speaker: Any {
                pub speak: () Str
            }

            pub Dog: Speaker {
                pub label: Str

                pub speak: () Str {
                    "woof"
                }
            }

            announce: (s: Speaker) Void {
                trace(s.speak())
            }

            main: () Void {
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
            main: () Void {
                numbers: Arr<Int32> = @arrOf(10, 20, 30)
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
            main: () Void {
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
        // A marker keeps its argument on the declaration it marks (design
        // 2.5); it used to spill into a stray `"fopen";` statement, and a
        // bare marker with no declaration after it is now a parse error
        // (ParserGrowthTest). The C symbol is the mark's string (design 7.3):
        // the prototype and every call spell `fopen`, never the Kira name,
        // on this semantic-free emit path as on the CLI's (the mark is read
        // from the parser's stored invocation, not from apply()).
        val output = emit(
            """
            @_opaque FileHandle: Any { }

            @_extern("fopen") openFile: (path: Str) FileHandle;

            main: () Int32 {
                h: FileHandle = openFile("x")
                0
            }
            """
        )
        assertTrue(output.contains("extern FileHandle* fopen(Str path);"), output)
        assertTrue(output.contains("fopen(\"x\")"), output)
        assertFalse(output.contains("openFile"), output)
        assertFalse(output.contains("\"fopen\";"), output)
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
            main: () Void {
                numbers: Arr<Int32> = @arrOf(10, 20)
                trace(numbers[0])
            }
            """
        )
        // The read unslots to the declared element type; it used to be
        // Arr_get_i32 for every element type.
        assertTrue(output.contains("KIRA_UNSLOT(Int32, Arr_get(numbers, 0))"), output)
    }

    @Test
    fun stringMethodsLowerToPreludeHelpers() {
        val output = emit(
            """
            main: () Void {
                s: Str = "  Kira  "
                trace(s.trim().length())
            }
            """
        )
        assertTrue(output.contains("Str_trim("), output)
        assertTrue(output.contains("Str_length("), output)
    }
}
