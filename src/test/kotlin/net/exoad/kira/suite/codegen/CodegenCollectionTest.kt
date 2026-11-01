package net.exoad.kira.suite.codegen

import net.exoad.kira.suite.codegen.CodegenEmit.emit
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/** C backend shape: containers, indexing and string helpers. */
class CodegenCollectionTest {
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
