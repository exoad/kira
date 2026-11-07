package net.exoad.kira.suite.runtime

import net.exoad.kira.TestCompileSupport
import net.exoad.kira.suite.runtime.RuntimeRun.assertStdout
import net.exoad.kira.suite.runtime.RuntimeRun.compiler
import org.junit.jupiter.api.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** End to end on C: Arr, Map, List, Set, Stack and Queue. */
class CollectionRuntimeTest {
    @Test
    fun arrIndexAndSize() {
        assertStdout("10\n") {
            """
            main: () Void {
                numbers: Arr<Int32> { @arrOf(10, 20, 30) }
                trace(numbers[0])
            }
            """
        }
    }

    @Test
    fun nestedGenericArrayIsRejectedByCToolchain() {
        // Pinned gap: codegen emits nested Arr literals (an Arr value is wider
        // than the 64-bit KiraSlot a container stores), so `cc` rejects the
        // emitted C. This was previously a silently *skipped* test: a known
        // limit dressed as green. Fixing nested containers (slot the struct or
        // heap-box it) flips this to expect success.
        val cc = compiler ?: return
        val generated = TestCompileSupport.transpileSnippetToC(
            source = TestCompileSupport.wrapModule(
                "test:runtime.nested",
                """
                main: () Void {
                    grid: Arr<Arr<Int32>> { @arrOf(@arrOf(1, 2), @arrOf(3, 4)) }
                    trace(grid[1][0])
                }
                """
            ),
            logicalPath = TestCompileSupport.logicalPathForModule("test:runtime.nested"),
            runSemantic = false,
        )
        val result = TestCompileSupport.compileAndRunC(generated, cc)
        assertNotEquals(
            0,
            result.compileResult.exitCode,
            "nested Arr now compiles; the pinned gap is fixed, update this test"
        )
        // The emitted shape is still worth pinning even though cc rejects it.
        assertTrue(generated.contains("Arr_lit((KiraSlot[]){ Arr_lit("), generated)
    }

    @Test
    fun mapPutGetWithMaybe() {
        assertStdout("1\n36\n-1\n") {
            """
            main: () Void {
                ages: Map<Str, Int32> { }
                ages.put("ada", 36)
                found: Maybe<Int32> { ages.get("ada") }
                trace(found.isSome())
                trace(found.unwrapOr(0))
                missing: Maybe<Int32> { ages.get("nobody") }
                trace(missing.unwrapOr(-1))
            }
            """
        }
    }

    @Test
    fun listOfStrings() {
        assertStdout("2\ngrace\n1\n") {
            """
            main: () Void {
                names: List<Str> { }
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
            main: () Void {
                seen: Set<Int32> { }
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
            main: () Void {
                undo: Stack<Int32> { }
                undo.push(10)
                top: Maybe<Int32> { undo.pop() }
                trace(top.unwrapOr(0))
                empty: Maybe<Int32> { undo.pop() }
                trace(empty.isNone())

                jobs: Queue<Int32> { }
                jobs.enqueue(1)
                jobs.enqueue(2)
                first: Maybe<Int32> { jobs.dequeue() }
                trace(first.unwrapOr(0))
            }
            """
        }
    }
}
