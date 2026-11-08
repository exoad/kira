package net.exoad.kira

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The JS backend's slice of kira:core beyond the original smoke set: Char
 * literals, string interpolation, and the Str/Num/Char/List methods the
 * stdlib declares. Every case runs on node and asserts the text the C++ and
 * py runtimes produce for the same program.
 */
class JSStdlibSurfaceTest {
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

    private fun run(body: String, uri: String): String? {
        val generated = emit(body, uri)
        val n = node ?: return null
        val result = TestCompileSupport.runJS(generated, n)
        assertEquals(0, result.exitCode, "node failed:\n${result.stderr}\n$generated")
        return result.stdout
    }

    @Test
    fun charLiteralsAreOneCharacterStrings() {
        assertEquals(
            "A\n1\n",
            run(
                """
                main: () Void {
                    c: Char = 'A'
                    trace(c)
                    trace('0'.isDigit())
                }
                """,
                "test:js.char"
            ) ?: return
        )
    }

    @Test
    fun interpolationFormatsBooleansLikeTrace() {
        assertEquals(
            "hi kira 7 1\n",
            run(
                """
                main: () Void {
                    name: Str = "kira"
                    n: Int32 = 7
                    flag: Bool = true
                    trace("hi ${'$'}{name} ${'$'}{n} ${'$'}{flag}")
                }
                """,
                "test:js.interp"
            ) ?: return
        )
    }

    @Test
    fun strSurfaceCoversSearchTrimPadReplaceAndParsing() {
        assertEquals(
            """
            8
            k
            4
            1
            6
            6
            3
            007
            7..
            X-b-X
            -a-b-c-
            42
            1
            255
            12.5
            1
            2
            65
            """.trimIndent() + "\n",
            run(
                """
                main: () Void {
                    s: Str = "  kira  "
                    trace(s.size())
                    trace(s.at(2))
                    trace(s.find("ra").unwrapOr(-1))
                    trace(s.find("zz").isNone())
                    trace(s.trimStart().size())
                    trace(s.trimEnd().size())
                    trace(" a  b \t c ".splitWhitespace().size())
                    trace("7".padStart(3, '0'))
                    trace("7".padEnd(3, '.'))
                    trace("a-b-a".replace("a", "X"))
                    trace("abc".replace("", "-"))
                    trace("+42".toInt64().unwrapOr(0))
                    trace("4x".toInt64().isNone())
                    trace("ff".toInt64Radix(16).unwrapOr(0))
                    trace("12.5".toFloat64().unwrapOr(0.0))
                    trace(" 1".toFloat64().isNone())
                    trace("AZ".bytes().size())
                    trace("AZ".bytes().get(0))
                }
                """,
                "test:js.strsurface"
            ) ?: return
        )
    }

    @Test
    fun charPredicatesAreAsciiOnly() {
        assertEquals(
            "1\n1\n1\n0\n",
            run(
                """
                main: () Void {
                    trace('7'.isDigit())
                    trace(' '.isWhitespace())
                    trace('A'.isLetter())
                    trace('!'.isLetter())
                }
                """,
                "test:js.charpred"
            ) ?: return
        )
    }

    @Test
    fun numToHexAndFixedMatchTheOtherBackends() {
        assertEquals(
            "ff\n-ff\n0.12\n2.67\n2\n",
            run(
                """
                main: () Void {
                    a: Int32 = 255
                    b: Int32 = -255
                    c: Float64 = 0.125
                    d: Float64 = 2.675
                    e: Float64 = 1.5
                    trace(a.toHex())
                    trace(b.toHex())
                    trace(c.fixed(2))
                    trace(d.fixed(2))
                    trace(e.fixed(0))
                }
                """,
                "test:js.num"
            ) ?: return
        )
    }

    @Test
    fun maybeIsNullIsTheAliasForNone() {
        assertEquals(
            "1\n1\n0\n",
            run(
                """
                main: () Void {
                    absent: Maybe<Size> = "ab".find("z")
                    present: Maybe<Size> = "ab".find("a")
                    trace(absent.isNull())
                    trace(absent.isNone())
                    trace(present.isNull())
                }
                """,
                "test:js.maybe"
            ) ?: return
        )
    }

    @Test
    fun listSurfaceJoinsSortsAndAggregates() {
        assertEquals(
            "3,1,2\n6\n1\n3\n123\n",
            run(
                """
                main: () Void {
                    xs: List<Int32> = List<Int32> { }
                    xs.add(3)
                    xs.add(1)
                    xs.add(2)
                    trace(xs.joinToString(","))
                    trace(xs.sum())
                    trace(xs.minOrNull().unwrapOr(-1))
                    trace(xs.maxOrNull().unwrapOr(-1))
                    xs.sort()
                    trace(xs.joinToString(""))
                }
                """,
                "test:js.listsurface"
            ) ?: return
        )
    }
}
