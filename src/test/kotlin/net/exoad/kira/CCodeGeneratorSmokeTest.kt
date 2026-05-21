package net.exoad.kira

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.backend.codegen.c.KiraCCodeGenerator
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraSourceParsers
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import kotlin.test.Test
import kotlin.test.assertTrue

class CCodeGeneratorSmokeTest {
    @Test
    fun emitsBasicCForSimpleProgram() {
        val source = """
            module "tests:main"

            x: Int32 = 10
            y: Int32 = 20

            fx add(a: Int32, b: Int32): Int32 {
                return a + b
            }

            fx main(): Void {
                mut z: Int32 = add(x, y)
                trace(z)
            }
        """.trimIndent()

        val previous = System.getProperty("kira.parser")
        System.setProperty("kira.parser", "legacy")

        try {
            val pre = KiraPreprocessor(source)
            val processed = pre.process()
            val cu = CompilationUnit()
            val src = cu.addSource("inline-test.kira", processed.processedContent, emptyList())
            val tokens = KiraLexer(src).tokenize()
            val srcWithTokens = cu.addSource("inline-test.kira", src.content, tokens)

            KiraSourceParsers.from(srcWithTokens).parse()

            val generator = KiraCCodeGenerator(cu)
            generator.visitRootASTNode(srcWithTokens.ast)
            val output = generator.toString()

            assertTrue(output.contains("typedef int32_t KInt;"))
            assertTrue(output.contains("KInt x = 10;"))
            assertTrue(output.contains("KInt add(KInt a, KInt b)"))
            assertTrue(output.contains("#include <stdio.h>"))
            assertTrue(output.contains("printf(z);"))
        } finally {
            if (previous == null) {
                System.clearProperty("kira.parser")
            } else {
                System.setProperty("kira.parser", previous)
            }
        }
    }
}
