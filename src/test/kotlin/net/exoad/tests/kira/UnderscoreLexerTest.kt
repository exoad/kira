package net.exoad.tests.kira

import net.exoad.kira.Public
import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.lexer.Token
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UnderscoreLexerTest {
    @Test
    fun intrinsicWithUnderscoreIsAllowed() {
        val content = "module \"t\"\nfx main(): Void { @trace_one(123) }"
        val pre = KiraPreprocessor(content)
        val res = pre.process()
        val cu = CompilationUnit()
        val src = cu.addSource("test_intrinsic.kira", res.processedContent, emptyList())
        val lexer = KiraLexer(src)
        val tokens = lexer.tokenize()
        val found = tokens.any { it.type == Token.Type.INTRINSIC_IDENTIFIER && it.content == "trace_one" }
        Assertions.assertTrue(found, "Expected INTRINSIC_IDENTIFIER token with content 'trace_one' to be present")
    }

    @Test
    fun normalIdentifierWithUnderscorePanics() {
        val content = "module \"t\"\nfx main(): Void { my_var: Int32 = 0 }"
        val pre = KiraPreprocessor(content)
        val res = pre.process()
        val cu = CompilationUnit()
        val src = cu.addSource("test_identifier.kira", res.processedContent, emptyList())
        val lexer = KiraLexer(src)
        val ex = assertThrows<DiagnosticsException> {
            lexer.tokenize()
        }
        Assertions.assertTrue(ex.message.contains("Underscores are not allowed in identifiers") || ex.message.contains("Underscores are not allowed"))
    }
}