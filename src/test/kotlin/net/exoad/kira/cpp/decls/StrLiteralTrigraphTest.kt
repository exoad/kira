package net.exoad.kira.cpp.decls

import net.exoad.kira.compiler.backend.codegen.cpp.CppDeclEmitter
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitParts
import net.exoad.kira.compiler.backend.codegen.cpp.CppWriter
import net.exoad.kira.cpp.decls.DeclEmitRun.FakeExprs
import net.exoad.kira.cpp.decls.DeclEmitRun.assertContains
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A Str literal never reaches the header as a trigraph, wherever the emitter writes it. */
class StrLiteralTrigraphTest {
    private fun assertContains(text: String, vararg wanted: String) {
        wanted.forEach { assertTrue(text.contains(it), "expected:\n$it\nin:\n$text") }
    }

    @Test
    fun cppStringEscapesTheSecondQuestionMarkOfEveryPair() {
        // each of the nine trigraph sequences
        listOf("=", "/", "'", "(", ")", "!", "<", ">", "-").forEach { third ->
            assertEquals("\"a ?\\?$third b\"", CppDeclEmitter.cppString("a ??$third b"), "??$third")
        }
        // a run of three: the second and the third are each preceded by a '?'
        assertEquals("\"?\\?\\?=\"", CppDeclEmitter.cppString("???="))
        // a lone '?' and a '?' after another character stay as they are
        assertEquals("\"what?\"", CppDeclEmitter.cppString("what?"))
        assertEquals("\"a?b?c\"", CppDeclEmitter.cppString("a?b?c"))
        // a '?' after an escaped character is not a pair in the source text
        assertEquals("\"\\\\?\"", CppDeclEmitter.cppString("\\?"))
        assertEquals("\"\\n?\"", CppDeclEmitter.cppString("\n?"))
        // the other escapes are as before
        assertEquals("\"say \\\"hi\\\"\\t\\001\"", CppDeclEmitter.cppString("say \"hi\"\t\u0001"))
        // no output ever holds two adjacent question marks
        val all = "??= ??/ ??' ??( ??) ??! ??< ??> ??- ???= ????"
        assertTrue(!CppDeclEmitter.cppString(all).contains("??"), CppDeclEmitter.cppString(all))
    }

    @Test
    fun noTrigraphReachesTheHeaderFromAnyStrSite() {
        val uri = "test:tri"
        val (emitted, _) = DeclTestSupport.emitWith(
            DeclTestSupport.module(
                uri,
                """
                pub QUERY: Str = "a ??= b"
                pub Cfg: Struct {
                    pub text: Str = "??/"
                }
                pub show: (label: Str = "??'") Void
                @_static_assert(1 == 1, "??(")
                """,
            ),
            uri = uri,
            parts = CppEmitParts(exprs = FakeExprs),
        )
        assertTrue(!emitted.hasErrors, emitted.diagnostics.joinToString("\n") { it.render() })
        val h = CppWriter.normalize(emitted.header)
        assertContains(
            h,
            "inline constexpr const char* QUERY = \"a ?\\?= b\";",
            "kira::Str text = \"?\\?/\";",
            "void show(const kira::Str& label = \"?\\?'\");",
            "static_assert(<BinaryExpr>, \"?\\?(\");",
        )
        assertTrue(!h.contains("??"), "two adjacent question marks in:\n$h")
    }
}
