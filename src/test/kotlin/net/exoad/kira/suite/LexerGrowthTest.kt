package net.exoad.kira.suite

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.lexer.Token
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lexer forms design 2 adds (W1.1, step 2): the keywords `struct`, `in`,
 * `initially`, `finally` and `override`; char literals with their escapes;
 * and string interpolation, which lexes a string with `${` as text pieces
 * around nested expression token streams. A string with no `${` stays one
 * L_STRING, and `\$` is a literal dollar.
 */
class LexerGrowthTest {

    private fun lexRaw(source: String): List<Token> {
        val res = KiraPreprocessor(source).process()
        val cu = CompilationUnit()
        val src = cu.addSource("lexer-growth.kira", res.processedContent, emptyList())
        return KiraLexer(src).tokenize()
    }

    private fun lex(source: String): List<Token> =
        lexRaw(source).filter { it.type != Token.Type.S_EOF }

    private fun assertLexes(source: String, vararg expected: Pair<Token.Type, String>) {
        val tokens = lex(source)
        assertEquals(
            expected.map { "${it.first}:${it.second}" },
            tokens.map { "${it.type}:${it.content}" },
            "token stream for: $source"
        )
    }

    // --- keywords ----------------------------------------------------------

    @Test
    fun newKeywordsLexAsKeywords() {
        assertLexes(
            "struct in initially finally override",
            Token.Type.K_STRUCT to "struct",
            Token.Type.K_IN to "in",
            Token.Type.K_INITIALLY to "initially",
            Token.Type.K_FINALLY to "finally",
            Token.Type.K_MODIFIER_OVERRIDE to "override",
        )
        // `override` sits with the other modifiers, so parseModifiers sees it.
        assertTrue(Token.Type.K_MODIFIER_OVERRIDE in Token.Type.modifiers)
    }

    @Test
    fun keywordsDoNotSwallowLongerIdentifiers() {
        assertLexes("inside structure finallyDone", Token.Type.IDENTIFIER to "inside", Token.Type.IDENTIFIER to "structure", Token.Type.IDENTIFIER to "finallyDone")
    }

    // --- char literals -------------------------------------------------------

    @Test
    fun charLiteralsLexRaw() {
        // The token carries the raw text between the quotes; the parser decodes it.
        assertLexes("'a'", Token.Type.L_CHAR to "a")
        assertLexes("' '", Token.Type.L_CHAR to " ")
        assertLexes("'\\n'", Token.Type.L_CHAR to "\\n")
        assertLexes("'\\t'", Token.Type.L_CHAR to "\\t")
        assertLexes("'\\r'", Token.Type.L_CHAR to "\\r")
        assertLexes("'\\\\'", Token.Type.L_CHAR to "\\\\")
        assertLexes("'\\''", Token.Type.L_CHAR to "\\'")
        assertLexes("'\\0'", Token.Type.L_CHAR to "\\0")
        // A char in expression context lexes like any other literal.
        assertLexes(
            "c == ' ' || c == '\\t'",
            Token.Type.IDENTIFIER to "c", Token.Type.OP_CMP_EQL to "==", Token.Type.L_CHAR to " ",
            Token.Type.OP_CMP_OR to "||",
            Token.Type.IDENTIFIER to "c", Token.Type.OP_CMP_EQL to "==", Token.Type.L_CHAR to "\\t",
        )
    }

    @Test
    fun malformedCharLiteralsAreRejected() {
        assertThrows<DiagnosticsException> { lex("''") }
        assertThrows<DiagnosticsException> { lex("'ab'") }
        assertThrows<DiagnosticsException> { lex("'a") }
        assertThrows<DiagnosticsException> { lex("'") }
    }

    // --- string interpolation -------------------------------------------------

    @Test
    fun plainStringsStayOneToken() {
        assertLexes("\"plain\"", Token.Type.L_STRING to "plain")
        assertLexes("\"\"", Token.Type.L_STRING to "")
        // A lone `$` is text; only `${` opens a hole.
        assertLexes("\"cost \$5\"", Token.Type.L_STRING to "cost \$5")
        // The escaped dollar never opens a hole; the parser turns `\$` into `$`.
        assertLexes("\"\\\${x}\"", Token.Type.L_STRING to "\\\${x}")
    }

    @Test
    fun interpolationLexesAsHeadHolesAndTail() {
        assertLexes(
            "\"a \${x} b\"",
            Token.Type.L_STRING_HEAD to "a ",
            Token.Type.IDENTIFIER to "x",
            Token.Type.L_STRING_TAIL to " b",
        )
        assertLexes(
            "\"\${a}-\${b}\"",
            Token.Type.L_STRING_HEAD to "",
            Token.Type.IDENTIFIER to "a",
            Token.Type.L_STRING_PART to "-",
            Token.Type.IDENTIFIER to "b",
            Token.Type.L_STRING_TAIL to "",
        )
        assertLexes(
            "\"STEER \${fixed3(f)}\"",
            Token.Type.L_STRING_HEAD to "STEER ",
            Token.Type.IDENTIFIER to "fixed3",
            Token.Type.S_OPEN_PARENTHESIS to "(",
            Token.Type.IDENTIFIER to "f",
            Token.Type.S_CLOSE_PARENTHESIS to ")",
            Token.Type.L_STRING_TAIL to "",
        )
    }

    @Test
    fun holesTrackNestedBraces() {
        // `{` inside a hole deepens it, so the if-expression's braces do not end the hole.
        assertLexes(
            "\"\${ if c { 1 } else { 2 } }!\"",
            Token.Type.L_STRING_HEAD to "",
            Token.Type.K_IF to "if",
            Token.Type.IDENTIFIER to "c",
            Token.Type.S_OPEN_BRACE to "{",
            Token.Type.L_INTEGER to "1",
            Token.Type.S_CLOSE_BRACE to "}",
            Token.Type.K_ELSE to "else",
            Token.Type.S_OPEN_BRACE to "{",
            Token.Type.L_INTEGER to "2",
            Token.Type.S_CLOSE_BRACE to "}",
            Token.Type.L_STRING_TAIL to "!",
        )
    }

    @Test
    fun holesMayHoldStringsAndCharsAndOtherHoles() {
        assertLexes(
            "\"\${\"in\"}\"",
            Token.Type.L_STRING_HEAD to "",
            Token.Type.L_STRING to "in",
            Token.Type.L_STRING_TAIL to "",
        )
        assertLexes(
            "\"\${pad(3, '0')}\"",
            Token.Type.L_STRING_HEAD to "",
            Token.Type.IDENTIFIER to "pad",
            Token.Type.S_OPEN_PARENTHESIS to "(",
            Token.Type.L_INTEGER to "3",
            Token.Type.S_COMMA to ",",
            Token.Type.L_CHAR to "0",
            Token.Type.S_CLOSE_PARENTHESIS to ")",
            Token.Type.L_STRING_TAIL to "",
        )
        // A hole inside a hole's string: the stack of holes keeps each one's depth.
        assertLexes(
            "\"\${\"\${x}\"}\"",
            Token.Type.L_STRING_HEAD to "",
            Token.Type.L_STRING_HEAD to "",
            Token.Type.IDENTIFIER to "x",
            Token.Type.L_STRING_TAIL to "",
            Token.Type.L_STRING_TAIL to "",
        )
    }

    @Test
    fun textAroundHolesKeepsEscapesRaw() {
        // Escapes are decoded by the parser, as for plain strings.
        assertLexes(
            "\"tab\\t\${x}\\n\"",
            Token.Type.L_STRING_HEAD to "tab\\t",
            Token.Type.IDENTIFIER to "x",
            Token.Type.L_STRING_TAIL to "\\n",
        )
    }

    @Test
    fun unterminatedHolesAreRejected() {
        assertThrows<DiagnosticsException> { lex("\"\${x\"") }
        assertThrows<DiagnosticsException> { lex("\"\${x") }
        assertThrows<DiagnosticsException> { lex("\"\${x}") }
    }

    @Test
    fun sourcePositionsSurviveInterpolation() {
        val tokens = lex("x: Str = \"a \${y} b\"\nz: Int32 = 1")
        val z = tokens.first { it.content == "z" }
        assertEquals(2, z.canonicalLocation.lineNumber)
        assertEquals(1, z.canonicalLocation.column)
    }
}
