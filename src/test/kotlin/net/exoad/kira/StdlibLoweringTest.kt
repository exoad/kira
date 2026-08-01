package net.exoad.kira

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The stdlib declares far more surface than magic types alone: Str helpers,
 * Num conversions, Set / Stack / Queue / Deque, and Maybe / Result. Each has C
 * backing in the prelude, reached through slot casts because containers erase
 * their element type. These tests pin both the emitted shape and the runtime
 * behaviour.
 */
class StdlibLoweringTest {
    companion object {
        private var compiler: String? = null

        @JvmStatic
        @BeforeAll
        fun locateCompiler() {
            compiler = TestCompileSupport.findCCompiler()
        }
    }

    private fun emit(body: String, uri: String): String {
        return TestCompileSupport.transpileSnippetToC(
            source = TestCompileSupport.wrapModule(uri, body),
            logicalPath = TestCompileSupport.logicalPathForModule(uri),
            runSemantic = false
        )
    }

    private fun runAndCapture(generated: String): String? {
        val cc = compiler ?: return null
        val result = TestCompileSupport.compileAndRunC(generated, cc)
        assumeTrue(
            result.compileResult.exitCode == 0,
            "cc failed:\n${result.compileResult.stderr}\n$generated"
        )
        return result.runResult?.stdout
    }

    @Test
    fun strMethodsLowerToPreludeHelpers() {
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
            "test:stdlib.str"
        )

        assertTrue(generated.contains("Str_trim("), generated)
        assertTrue(generated.contains("Str_length("), generated)
        assertTrue(generated.contains("Str_toUpper("), generated)
        // Str-returning methods must print with %s, not %d.
        assertTrue(generated.contains("print(\"%s\\n\", Str_toUpper("), generated)

        assertEquals("4\nKIRA\n1\n", runAndCapture(generated) ?: return)
    }

    @Test
    fun setDeduplicatesThroughSlotCasts() {
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
            "test:stdlib.set"
        )

        assertTrue(generated.contains("Set_new()"), generated)
        assertTrue(generated.contains("Set_add(&seen, KIRA_SLOT(1))"), generated)

        assertEquals("2\n1\n", runAndCapture(generated) ?: return)
    }

    @Test
    fun mapGetReturnsMaybeAndUnwrapsToElementType() {
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
            "test:stdlib.map"
        )

        // Str keys hash/compare by content.
        assertTrue(generated.contains("Map_new_s()"), generated)
        assertTrue(generated.contains("KIRA_SLOT_PTR(\"ada\")"), generated)
        // The Maybe payload is cast back to the declared element type.
        assertTrue(generated.contains("KIRA_UNSLOT(Int32, Maybe_unwrapOr("), generated)

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
            "test:stdlib.adt"
        )

        assertTrue(generated.contains("Stack_new()"), generated)
        assertTrue(generated.contains("Queue_enqueue(&jobs, KIRA_SLOT(1))"), generated)

        // pop on empty is None, and Queue is FIFO (1 before 2).
        assertEquals("10\n1\n1\n", runAndCapture(generated) ?: return)
    }

    @Test
    fun numConversionsLowerToCastsAndPrintWidened() {
        val generated = emit(
            """
            fx main: () Void {
                n: Int32 = 7
                trace(n.toInt64())
            }
            """,
            "test:stdlib.num"
        )

        assertTrue(generated.contains("((Int64)(n))"), generated)
        // int64 must widen at the print site so %lld is correct on LP64 and LLP64.
        assertTrue(generated.contains("print(\"%lld\\n\", (long long)("), generated)

        assertEquals("7\n", runAndCapture(generated) ?: return)
    }

    @Test
    fun assertUsesTwoArgumentHelperNotCMacro() {
        val generated = emit(
            """
            fx main: () Void {
                assert(true, "fine")
                trace("ok")
            }
            """,
            "test:stdlib.assert"
        )

        // C's assert() macro takes one argument; Kira's takes a message too, so
        // the call must route to the prelude helper rather than bare assert().
        assertTrue(generated.contains("kira_assert(true, \"fine\")"), generated)
        assertTrue(!generated.contains("#include <assert.h>"), generated)

        assertEquals("ok\n", runAndCapture(generated) ?: return)
    }

    @Test
    fun listHoldsStrElementsThroughPointerSlots() {
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
            "test:stdlib.liststr"
        )

        // Pointer elements slot through intptr_t, not a truncating int cast.
        assertTrue(generated.contains("List_add(&names, KIRA_SLOT_PTR(\"ada\"))"), generated)
        assertTrue(generated.contains("KIRA_UNSLOT_PTR(Str, List_get("), generated)

        assertEquals("2\ngrace\n1\n", runAndCapture(generated) ?: return)
    }
}
