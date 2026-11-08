package net.exoad.kira

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A closed loop for the JS backend: one integration program that uses the
 * surface together, then deliberate mutations of the emitted runtime prelude.
 * A mutant that still produces the expected output means the test suite is
 * not actually pinning that behaviour.
 */
class JSBackendClosedLoopTest {
    companion object {
        private var node: String? = null

        @JvmStatic
        @BeforeAll
        fun locateNode() {
            node = TestCompileSupport.findNode()
        }
    }

    private fun emit(body: String, uri: String): String =
        TestCompileSupport.transpileSnippetToJS(
            source = TestCompileSupport.wrapModule(uri, body),
            logicalPath = TestCompileSupport.logicalPathForModule(uri),
            runSemantic = false
        )

    private fun run(source: String): TestCompileSupport.ProcessResult? {
        val n = node ?: return null
        return TestCompileSupport.runJS(source, n)
    }

    private fun assertMutantDies(body: String, label: String, mutation: Pair<String, String>) {
        val good = emit(body, "test:js.mutant")
        val expected = run(good) ?: return
        assertEquals(0, expected.exitCode, "$label: the good program failed:\n${expected.stderr}\n$good")
        val mutated = good.replace(mutation.first, mutation.second)
        assertNotEquals(good, mutated, "$label: the mutation did not apply")
        val got = run(mutated) ?: return
        assertTrue(
            got.exitCode != 0 || got.stdout != expected.stdout,
            "$label: the mutant still produced the expected output\n${got.stdout}"
        )
    }

    @Test
    fun aWordCountProgramUsesStrListMapMaybeAndInterpolationTogether() {
        val program = """
            main: () Void {
                text: Str = "the quick the lazy the end"
                words: List<Str> = text.splitWhitespace()
                counts: Mutable<Map<Str, Int32>> = Map<Str, Int32> { }
                i: Mutable<Size> = 0
                while i < words.size() {
                    w: Str = words.get(i)
                    found: Maybe<Int32> = counts.get(w)
                    counts.put(w, found.unwrapOr(0) + 1)
                    i = i + 1
                }
                keys: List<Str> = @listOf("the", "quick", "lazy", "end")
                j: Mutable<Size> = 0
                while j < keys.size() {
                    trace("${'$'}{keys.get(j)}: ${'$'}{counts.get(keys.get(j)).unwrapOr(0)}")
                    j = j + 1
                }
            }
        """
        val result = run(emit(program, "test:js.wordcount")) ?: return
        assertEquals(0, result.exitCode, "node failed:\n${result.stderr}")
        assertEquals("the: 3\nquick: 1\nlazy: 1\nend: 1\n", result.stdout)
    }

    @Test
    fun whitespaceSplitMutationIsCaught() {
        assertMutantDies(
            """
            main: () Void {
                trace("a\tb".splitWhitespace().size())
            }
            """,
            "splitWhitespace whitespace set",
            "const KIRA_STR_WS = \" \\t\\n\\r\";" to "const KIRA_STR_WS = \" \";"
        )
    }

    @Test
    fun padStartMutationIsCaught() {
        assertMutantDies(
            """
            main: () Void {
                trace("7".padStart(3, '0'))
            }
            """,
            "padStart fill side",
            "return fill.repeat(width - s.length) + s;" to
                "return s + fill.repeat(width - s.length);"
        )
    }

    @Test
    fun toInt64RangeMutationIsCaught() {
        assertMutantDies(
            """
            main: () Void {
                trace("9223372036854775808".toInt64().isNone())
            }
            """,
            "toInt64 Int64 range",
            "if (value < -9223372036854775808n || value > 9223372036854775807n) return kira_none();" to
                "if (false) return kira_none();"
        )
    }

    @Test
    fun fixedRoundingMutationIsCaught() {
        assertMutantDies(
            """
            main: () Void {
                x: Float64 = 0.375
                trace(x.fixed(2))
            }
            """,
            "fixed half-to-even rounding",
            "if (rem * 2n > den || (rem * 2n === den && q % 2n === 1n)) q += 1n;" to
                "if (rem * 2n > den) q += 1n;"
        )
    }

    @Test
    fun listSumMutationIsCaught() {
        assertMutantDies(
            """
            main: () Void {
                xs: List<Int32> = List<Int32> { }
                xs.add(3)
                xs.add(1)
                xs.add(2)
                trace(xs.sum())
            }
            """,
            "List.sum accumulation",
            "for (const v of this.values) total += v;" to "for (const v of this.values) total = v;"
        )
    }
}
