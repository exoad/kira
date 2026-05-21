package net.exoad.kira

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraSourceParsers
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertNotNull

class AntlrParserSmokeTest {
    @Test
    fun parsesSampleWithAntlrBackendEnabled() {
        val previous = System.getProperty("kira.parser")
        System.setProperty("kira.parser", "antlr")
        try {
            val file = File.createTempFile("antlr-smoke", ".kira")
            file.writeText(
                """
                module "test:antlr"

                x: Int32 = 10
                """.trimIndent()
            )
            val pre = KiraPreprocessor(file.readText())
            val res = pre.process()
            val cu = CompilationUnit()
            val src = cu.addSource(file.canonicalPath, res.processedContent, emptyList())
            val lexer = KiraLexer(src)
            val tokens = lexer.tokenize()
            val srcWithTokens = cu.addSource(file.canonicalPath, src.content, tokens)

            KiraSourceParsers.from(srcWithTokens).parse()

            assertNotNull(srcWithTokens.ast)
        } finally {
            if (previous == null) {
                System.clearProperty("kira.parser")
            } else {
                System.setProperty("kira.parser", previous)
            }
        }
    }
}
