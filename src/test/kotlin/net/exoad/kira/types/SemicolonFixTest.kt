package net.exoad.kira.types

import net.exoad.kira.compiler.fix.SemicolonFix
import net.exoad.kira.py.PyTestSupport
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `kira --fix-semicolons`: a statement-ending `;` goes, a `;` in a string, char or comment never does, and a second run changes nothing. */
class SemicolonFixTest {
    private fun fix(text: String) = SemicolonFix.rewrite(text)

    @Test
    fun aStatementEndingSemicolonGoesAndSpaceBeforeItWith() {
        val r = fix("main: () Void {\n    x: Int32 { 1 } ;\n    trace(x);\n}\n")
        assertEquals("main: () Void {\n    x: Int32 { 1 }\n    trace(x)\n}\n", r.text)
        assertEquals(2, r.dropped)
    }

    @Test
    fun aSemicolonInAStringACharOrACommentIsNeverTouched() {
        val src = "main: () Void {\n    s: Str { \"a;b\" }\n    c: Char { ';' }\n    t: Str { \"x ${'$'}{s};\" }\n    // a; b\n    trace(s)\n}\n"
        val r = fix(src)
        assertEquals(src, r.text)
        assertEquals(0, r.dropped)
    }

    @Test
    fun aTrailingCommentKeepsItsPlaceAndItsText() {
        val r = fix("    trace(1);  // one; two\n    trace(2); // three\n")
        assertEquals("    trace(1)  // one; two\n    trace(2) // three\n", r.text)
        assertEquals(2, r.dropped)
    }

    @Test
    fun crlfAndTabsAreKept() {
        val src = "main: () Void {\r\n\tx: Int32 { 1 };\r\n\ttrace(x);  \r\n}\r\n"
        val r = fix(src)
        assertEquals("main: () Void {\r\n\tx: Int32 { 1 }\r\n\ttrace(x)\r\n}\r\n", r.text)
        assertEquals(2, r.dropped)
    }

    @Test
    fun codeAfterASemicolonOnTheLineMovesToALineOfItsOwnAtTheSameIndent() {
        val r = fix("main: () Void {\n\ta: Int32 { 1 }; b: Int32 { 2 }\n}\n")
        assertEquals("main: () Void {\n\ta: Int32 { 1 }\n\tb: Int32 { 2 }\n}\n", r.text)
        val crlf = fix("main: () Void {\r\n  a: Int32 { 1 }; b: Int32 { 2 };\r\n}\r\n")
        assertEquals("main: () Void {\r\n  a: Int32 { 1 }\r\n  b: Int32 { 2 }\r\n}\r\n", crlf.text)
    }

    @Test
    fun aSemicolonInsideParenthesesIsLeftAndListed() {
        val src = "main: () Void {\n    f(1;\n      2)\n}\n"
        val r = fix(src)
        assertEquals(src, r.text)
        assertEquals(0, r.dropped)
        assertTrue(r.left.single().contains("inside parentheses or brackets"), r.left.toString())
    }

    @Test
    fun aFileWithoutAnyIsReturnedAsItWasAndASecondRunChangesNothing() {
        val src = "main: () Void {\n    trace(1);\n    trace(2)\n}\n"
        val once = fix(src)
        assertEquals("main: () Void {\n    trace(1)\n    trace(2)\n}\n", once.text)
        val twice = fix(once.text)
        assertEquals(once.text, twice.text)
        assertEquals(0, twice.dropped)
        assertEquals("", fix("").text)
    }

    @Test
    fun theCliRewritesTheFileAndSaysHowManyAndRefusesAFileTheLexerCannotRead(@TempDir dir: File) {
        val good = File(dir, "a.kira").also { it.writeText("main: () Void {\n    trace(1);\n}\n") }
        val bad = File(dir, "b.kira").also { it.writeText("main: () Void {\n    s: Str { \"unterminated;\n}\n") }
        val ran = PyTestSupport.cli(dir, "--fix-semicolons", good.absolutePath, bad.absolutePath)
        assertEquals(0, ran.exitCode, ran.all)
        assertTrue(ran.stdout.contains("a.kira: dropped 1, left 0"), ran.all)
        assertTrue(ran.stdout.contains("b.kira: refused") || ran.stdout.contains("b.kira: dropped"), ran.all)
        assertEquals("main: () Void {\n    trace(1)\n}\n", good.readText())
    }
}
