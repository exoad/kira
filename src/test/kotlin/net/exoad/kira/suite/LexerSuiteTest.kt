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
 * Full lexer coverage: every literal form, operator, keyword, intrinsic
 * identifier, comment stripping, error path, and source-position bookkeeping
 * of the legacy [KiraLexer].
 */
class LexerSuiteTest {

    // --- helpers ---------------------------------------------------------

    private fun lexRaw(source: String): List<Token> {
        val pre = KiraPreprocessor(source)
        val res = pre.process()
        val cu = CompilationUnit()
        val src = cu.addSource("lexer.kira", res.processedContent, emptyList())
        return KiraLexer(src).tokenize()
    }

    /** Tokenize and drop the trailing EOF so assertions read the real stream. */
    private fun lex(source: String): List<Token> =
        lexRaw(source).filter { it.type != Token.Type.S_EOF }

    private fun types(source: String): List<Token.Type> =
        lex(source).map { it.type }

    private fun contents(source: String): List<String> =
        lex(source).map { it.content }

    private fun assertLexes(source: String, vararg expected: Pair<Token.Type, String>) {
        val tokens = lex(source)
        assertEquals(expected.size, tokens.size, "token count for: $source")
        expected.forEachIndexed { i, (type, content) ->
            assertEquals(type, tokens[i].type, "token[$i] type for: $source")
            assertEquals(content, tokens[i].content, "token[$i] content for: $source")
        }
    }

    // --- literals --------------------------------------------------------

    @Test
    fun integerLiterals() {
        assertLexes("7", Token.Type.L_INTEGER to "7")
        assertLexes("0", Token.Type.L_INTEGER to "0")
        assertLexes("12345", Token.Type.L_INTEGER to "12345")
    }

    @Test
    fun hexLiteralsNormalizeToDecimal() {
        assertLexes("0xFF", Token.Type.L_INTEGER to "255")
        assertLexes("0x10", Token.Type.L_INTEGER to "16")
        assertLexes("0x0", Token.Type.L_INTEGER to "0")
    }

    @Test
    fun floatLiterals() {
        assertLexes("3.14", Token.Type.L_FLOAT to "3.14")
        assertLexes("1.0", Token.Type.L_FLOAT to "1.0")
        assertLexes("0.5", Token.Type.L_FLOAT to "0.5")
    }

    @Test
    fun stringLiteralsKeepContentAndEscapes() {
        assertLexes("\"hello\"", Token.Type.L_STRING to "hello")
        // Escapes are passed through verbatim; the lexer does not interpret them.
        assertLexes("\"hi\\nthere\"", Token.Type.L_STRING to "hi\\nthere")
        assertLexes("\"\"", Token.Type.L_STRING to "")
    }

    @Test
    fun unterminatedStringThrows() {
        assertThrows<DiagnosticsException> { lex("\"oops") }
    }

    @Test
    fun newlineInsideStringThrows() {
        assertThrows<DiagnosticsException> { lex("\"line1\nline2\"") }
    }

    @Test
    fun nullIsNotAKeywordInTheLegacyLexer() {
        assertLexes("null", Token.Type.IDENTIFIER to "null")
    }

    @Test
    fun trueAndFalseAreIdentifiersNotKeywords() {
        assertLexes("true", Token.Type.IDENTIFIER to "true")
        assertLexes("false", Token.Type.IDENTIFIER to "false")
    }

    // --- identifiers and keywords ----------------------------------------

    @Test
    fun keywordsMapToKeywordTokens() {
        val source = listOf(
            "if" to Token.Type.K_IF,
            "else" to Token.Type.K_ELSE,
            "while" to Token.Type.K_WHILE,
            "do" to Token.Type.K_DO,
            "return" to Token.Type.K_RETURN,
            "mut" to Token.Type.K_MODIFIER_MUTABLE,
            "pub" to Token.Type.K_MODIFIER_PUBLIC,
            "require" to Token.Type.K_MODIFIER_REQUIRE,
            "class" to Token.Type.K_CLASS,
            "for" to Token.Type.K_FOR,
            "module" to Token.Type.K_MODULE,
            "use" to Token.Type.K_USE,
            "enum" to Token.Type.K_ENUM,
            "as" to Token.Type.K_AS,
            "is" to Token.Type.K_IS,
            "break" to Token.Type.K_BREAK,
            "continue" to Token.Type.K_CONTINUE,
            "with" to Token.Type.K_WITH,
            "fx" to Token.Type.K_FX,
            "trait" to Token.Type.K_TRAIT,
            "try" to Token.Type.K_TRY,
            "throw" to Token.Type.K_THROW,
            "on" to Token.Type.K_ON,
            "variant" to Token.Type.K_VARIANT,
            "alias" to Token.Type.K_ALIAS,
            "this" to Token.Type.K_THIS,
        )
        for ((word, type) in source) {
            assertLexes(word, type to word)
        }
    }

    @Test
    fun plainIdentifiers() {
        assertLexes("camelCase", Token.Type.IDENTIFIER to "camelCase")
        assertLexes("PascalCase", Token.Type.IDENTIFIER to "PascalCase")
        assertLexes("x1", Token.Type.IDENTIFIER to "x1")
        assertLexes("_", Token.Type.S_UNDERSCORE to "_")
    }

    @Test
    fun underscoresInsideIdentifiersThrow() {
        val e = assertThrows<DiagnosticsException> { lex("my_var") }
        assertTrue(e.message!!.contains("Underscores are not allowed"), e.message)
    }

    @Test
    fun intrinsicIdentifiersAllowUnderscores() {
        assertLexes(
            "@trace_one(1)",
            Token.Type.INTRINSIC_IDENTIFIER to "trace_one",
            Token.Type.S_OPEN_PARENTHESIS to "(",
            Token.Type.L_INTEGER to "1",
            Token.Type.S_CLOSE_PARENTHESIS to ")",
        )
        assertLexes(
            "@__dummy__()",
            Token.Type.INTRINSIC_IDENTIFIER to "__dummy__",
            Token.Type.S_OPEN_PARENTHESIS to "(",
            Token.Type.S_CLOSE_PARENTHESIS to ")",
        )
        assertLexes(
            "@_global(true)",
            Token.Type.INTRINSIC_IDENTIFIER to "_global",
            Token.Type.S_OPEN_PARENTHESIS to "(",
            Token.Type.IDENTIFIER to "true",
            Token.Type.S_CLOSE_PARENTHESIS to ")",
        )
    }

    @Test
    fun atSymbolWithoutIdentifierThrows() {
        assertThrows<DiagnosticsException> { lex("@ ") }
        assertThrows<DiagnosticsException> { lex("@1") }
    }

    // --- operators -------------------------------------------------------

    @Test
    fun arithmeticOperators() {
        val tokens = lex("1 + 2 - 3 * 4 / 5 % 6")
        assertEquals(
            listOf(
                Token.Type.L_INTEGER, Token.Type.OP_ADD,
                Token.Type.L_INTEGER, Token.Type.OP_SUB,
                Token.Type.L_INTEGER, Token.Type.OP_MUL,
                Token.Type.L_INTEGER, Token.Type.OP_DIV,
                Token.Type.L_INTEGER, Token.Type.OP_MOD,
                Token.Type.L_INTEGER,
            ),
            tokens.map { it.type }
        )
        // The modulo token currently carries a '.' as its diagnostic content;
        // only its type is load-bearing.
        assertEquals(Token.Type.OP_MOD, tokens[9].type)
    }

    @Test
    fun compoundAssignmentOperators() {
        for ((op, type) in listOf(
            "+=" to Token.Type.OP_ASSIGN_ADD,
            "-=" to Token.Type.OP_ASSIGN_SUB,
            "*=" to Token.Type.OP_ASSIGN_MUL,
            "/=" to Token.Type.OP_ASSIGN_DIV,
            "%=" to Token.Type.OP_ASSIGN_MOD,
            "&=" to Token.Type.OP_ASSIGN_BIT_AND,
            "|=" to Token.Type.OP_ASSIGN_BIT_OR,
            "^=" to Token.Type.OP_ASSIGN_BIT_XOR,
            "<<=" to Token.Type.OP_ASSIGN_BIT_SHL,
        )) {
            assertLexes(op, type to op)
        }
    }

    @Test
    fun comparisonAndLogicalOperators() {
        assertLexes(
            "1 == 2 != 3 <= 4 < 5 > 6 && 7 || 8",
            Token.Type.L_INTEGER to "1",
            Token.Type.OP_CMP_EQL to "==",
            Token.Type.L_INTEGER to "2",
            Token.Type.OP_CMP_NEQ to "!=",
            Token.Type.L_INTEGER to "3",
            Token.Type.OP_CMP_LEQ to "<=",
            Token.Type.L_INTEGER to "4",
            Token.Type.S_OPEN_ANGLE to "<",
            Token.Type.L_INTEGER to "5",
            Token.Type.S_CLOSE_ANGLE to ">",
            Token.Type.L_INTEGER to "6",
            Token.Type.OP_CMP_AND to "&&",
            Token.Type.L_INTEGER to "7",
            Token.Type.OP_CMP_OR to "||",
            Token.Type.L_INTEGER to "8",
        )
    }

    @Test
    fun bitwiseAndShiftOperators() {
        assertLexes("a & b", Token.Type.IDENTIFIER to "a", Token.Type.S_AND to "&", Token.Type.IDENTIFIER to "b")
        assertLexes("a | b", Token.Type.IDENTIFIER to "a", Token.Type.S_PIPE to "|", Token.Type.IDENTIFIER to "b")
        assertLexes("a ^ b", Token.Type.IDENTIFIER to "a", Token.Type.OP_BIT_XOR to "^", Token.Type.IDENTIFIER to "b")
        assertLexes("a << b", Token.Type.IDENTIFIER to "a", Token.Type.OP_BIT_SHL to "<<", Token.Type.IDENTIFIER to "b")
        // '>' group is lexed conservatively as separate tokens (generics live in the same symbol space).
        assertLexes("a >> b", Token.Type.IDENTIFIER to "a", Token.Type.S_CLOSE_ANGLE to ">", Token.Type.S_CLOSE_ANGLE to ">", Token.Type.IDENTIFIER to "b")
        assertLexes("a >= b", Token.Type.IDENTIFIER to "a", Token.Type.S_CLOSE_ANGLE to ">", Token.Type.S_EQUAL to "=", Token.Type.IDENTIFIER to "b")
    }

    @Test
    fun nestedGenericClosersLexAsIndividualAngles() {
        // The C++ `>>` problem, solved lexically: every '>' that could close a
        // generic type is its own S_CLOSE_ANGLE token, so nesting is unambiguous.
        val tokens = lex("Arr<Arr<Arr<Int32>>>")
        assertEquals(
            listOf(
                Token.Type.IDENTIFIER, Token.Type.S_OPEN_ANGLE,
                Token.Type.IDENTIFIER, Token.Type.S_OPEN_ANGLE,
                Token.Type.IDENTIFIER, Token.Type.S_OPEN_ANGLE,
                Token.Type.IDENTIFIER,
                Token.Type.S_CLOSE_ANGLE, Token.Type.S_CLOSE_ANGLE, Token.Type.S_CLOSE_ANGLE,
            ),
            tokens.map { it.type }
        )
    }

    @Test
    fun rangeScopeAndMiscSymbols() {
        assertLexes("a .. b", Token.Type.IDENTIFIER to "a", Token.Type.OP_RANGE to "..", Token.Type.IDENTIFIER to "b")
        assertLexes("A :: B", Token.Type.IDENTIFIER to "A", Token.Type.OP_SCOPE to "::", Token.Type.IDENTIFIER to "B")
        assertLexes(
            "# ? ~ ! , ( ) [ ] { }",
            Token.Type.OP_HASH_MARK to "#",
            Token.Type.S_QUESTION_MARK to "?",
            Token.Type.S_TILDE to "~",
            Token.Type.S_BANG to "!",
            Token.Type.S_COMMA to ",",
            Token.Type.S_OPEN_PARENTHESIS to "(",
            Token.Type.S_CLOSE_PARENTHESIS to ")",
            Token.Type.S_OPEN_BRACKET to "[",
            Token.Type.S_CLOSE_BRACKET to "]",
            Token.Type.S_OPEN_BRACE to "{",
            Token.Type.S_CLOSE_BRACE to "}",
        )
        assertLexes("a.b", Token.Type.IDENTIFIER to "a", Token.Type.S_DOT to ".", Token.Type.IDENTIFIER to "b")
        assertLexes("a = b", Token.Type.IDENTIFIER to "a", Token.Type.S_EQUAL to "=", Token.Type.IDENTIFIER to "b")
        assertLexes("a: b", Token.Type.IDENTIFIER to "a", Token.Type.S_COLON to ":", Token.Type.IDENTIFIER to "b")
    }

    // --- separators ------------------------------------------------------

    @Test
    fun semicolonsProduceSeparatorTokens() {
        assertLexes(
            "a: Int32 = 1; b: Int32 = 2",
            Token.Type.IDENTIFIER to "a",
            Token.Type.S_COLON to ":",
            Token.Type.IDENTIFIER to "Int32",
            Token.Type.S_EQUAL to "=",
            Token.Type.L_INTEGER to "1",
            Token.Type.S_SEMICOLON to ";",
            Token.Type.IDENTIFIER to "b",
            Token.Type.S_COLON to ":",
            Token.Type.IDENTIFIER to "Int32",
            Token.Type.S_EQUAL to "=",
            Token.Type.L_INTEGER to "2",
        )
    }

    @Test
    fun newlinesAreWhitespaceNotSeparators() {
        val tokens = lex("a: Int32 = 1\nb: Int32 = 2")
        assertEquals(0, tokens.count { it.type == Token.Type.S_SEMICOLON }, "newline must not tokenize")
        // Int32 is an identifier too; a and b are the declared variables.
        assertEquals(listOf("a", "b"), tokens.filter { it.content == "a" || it.content == "b" }.map { it.content })
    }

    // --- source positions -------------------------------------------------

    @Test
    fun tracksLineAndColumn() {
        val tokens = lex("module \"t\"\n\nvalue: Int32 = 5")
        assertEquals(1 to 1, tokens[0].canonicalLocation.lineNumber to tokens[0].canonicalLocation.column)
        assertEquals(1 to 8, tokens[1].canonicalLocation.lineNumber to tokens[1].canonicalLocation.column)
        val value = tokens.first { it.content == "value" }
        assertEquals(3 to 1, value.canonicalLocation.lineNumber to value.canonicalLocation.column)
        val five = tokens.first { it.type == Token.Type.L_INTEGER }
        assertEquals(3 to 16, five.canonicalLocation.lineNumber to five.canonicalLocation.column)
    }

    @Test
    fun intrinsicPositionPointsPastTheAt() {
        val tokens = lex("@trace(1)")
        val intrinsic = tokens.first { it.type == Token.Type.INTRINSIC_IDENTIFIER }
        assertEquals(1 to 2, intrinsic.canonicalLocation.lineNumber to intrinsic.canonicalLocation.column)
    }

    // --- comments and whitespace ------------------------------------------

    @Test
    fun lineCommentsAreStripped() {
        assertLexes(
            "// hello\nx: Int32 = 1 // trailing",
            Token.Type.IDENTIFIER to "x",
            Token.Type.S_COLON to ":",
            Token.Type.IDENTIFIER to "Int32",
            Token.Type.S_EQUAL to "=",
            Token.Type.L_INTEGER to "1",
        )
    }

    @Test
    fun commentMarkersInsideStringsAreKept() {
        assertLexes("\"http://x\"", Token.Type.L_STRING to "http://x")
    }

    @Test
    fun blankLinesAreIgnored() {
        val tokens = lex("a: Int32 = 1\n\n\n\nb: Int32 = 2")
        assertEquals(
            listOf("a", "Int32", "b", "Int32"),
            tokens.filter { it.type == Token.Type.IDENTIFIER }.map { it.content }
        )
    }

    // --- error paths ------------------------------------------------------

    @Test
    fun unknownSymbolThrows() {
        val e = assertThrows<DiagnosticsException> { lex("a $ b") }
        assertTrue(e.message!!.contains("not known"), e.message)
    }

    @Test
    fun trailingDotAfterIntegerThrows() {
        // '1.' at EOF reads past the buffer looking for a float fraction.
        assertThrows<DiagnosticsException> { lex("1.") }
    }

    @Test
    fun tokenizeEndsWithEofToken() {
        val raw = lexRaw("x: Int32 = 1")
        assertEquals(Token.Type.S_EOF, raw.last().type)
    }
}
