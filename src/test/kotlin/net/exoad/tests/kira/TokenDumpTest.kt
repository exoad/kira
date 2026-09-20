package net.exoad.tests.kira

import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.source.SourceContext
import org.junit.jupiter.api.Test
import java.io.File

class TokenDumpTest {
    @Test
    fun dumpTokens() {
        val path = "test_kira/main.kira"
        val content = File(path).readText()
        val ctx = SourceContext(content, path, emptyList())
        val lexer = KiraLexer(ctx)
        val tokens = lexer.tokenize()
        val out = java.io.File("build/token_dump.txt")
        out.parentFile.mkdirs()
        out.writeText(tokens.joinToString("\n") { it.type.name + " " + it.content })
    }
}
