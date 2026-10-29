package net.exoad.tests.kira

import net.exoad.kira.compiler.KiraImmediateCompiler
import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TestBroken {
    /** [source] refused with [tag], its message holding [words]. */
    private fun refusedAs(source: String, tag: String, words: String) {
        val e = assertThrows<DiagnosticsException>(source) { KiraImmediateCompiler.formAST(source.trimIndent()) }
        assertEquals(tag, e.tag, e.message)
        assertTrue(e.message.contains(words), e.message)
    }

    @Test
    fun testVariadicsAreNotAllowed() {
        // `<[T]>` is no type-parameter list, so the head is no declaration: its `[` is a removed literal.
        refusedAs("\nA<[T]>: Any", "parse.removed", "`[...]` literals are not part of Kira")
        refusedAs("\nA<[T: A]>: Any", "parse.removed", "`[...]` literals are not part of Kira")
    }


    @Test
    fun testUnderscoresInNormalIdentifiersNotAllowed() {
        // The lexer reads the whole file first, so `a_b` is refused before `A_T` is parsed.
        refusedAs("\nA_T: Any {}\n\na_b: Int32 = 123", "KiraLexer::lexIdentifier", "Underscores are not allowed in 'a_b'")
        refusedAs("\nA_T: Any {\n    pub x: Int32\n}", "parse.type.name", "'A_T' is a constant's name")
    }

    @Test
    fun testUnderscoresAllowedInIntrinsincs() {
        val ast = KiraImmediateCompiler.formAST(
            """
            @op_add(1, 2)
            """.trimIndent()
        )
        assertNotNull(ast)
    }
}
