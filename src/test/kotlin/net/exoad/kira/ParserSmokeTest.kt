package net.exoad.kira

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraParser
import net.exoad.kira.compiler.frontend.parser.ast.XMLASTVisitorKira
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertNotNull

class ParserSmokeTest {
    @Test
    fun parsesSample() {
        val file = File("test_kira/main.kira")
        val pre = KiraPreprocessor(file.readText())
        val res = pre.process()
        val cu = CompilationUnit()
        val src = cu.addSource(file.canonicalPath, res.processedContent, emptyList())
        val lexer = KiraLexer(src)
        val tokens = lexer.tokenize()
        val out = File("build/tmp/tokens_dump.txt")
        out.parentFile.mkdirs()
        out.writeText(tokens.joinToString("\n") { t -> "${t.type} -> '${t.content}'" })
        assertNotNull(tokens)
        val srcWithTokens = cu.addSource(file.canonicalPath, src.content, tokens)
        val parser = KiraParser(srcWithTokens)
        parser.parse()
        assertNotNull(srcWithTokens.ast)
        // dump AST as XML to repository root for inspection
        val astOut = File("ast.kira.xml")
        astOut.writeText(XMLASTVisitorKira.build(srcWithTokens.ast))
//        val semanticAnalyzer = KiraSemanticAnalyzer(cu)
//        val semOut = File("build/tmp/semantic_dump.txt")
//        semOut.parentFile.mkdirs()
//        try {
//            val semanticResults = semanticAnalyzer.validateAST()
//            semOut.writeText("isHealthy: ${semanticResults.isHealthy}\n")
//            semOut.appendText("\nDiagnostics:\n")
//            if (semanticResults.diagnostics.isEmpty()) {
//                semOut.appendText("  (none)\n")
//            } else {
//                semanticResults.diagnostics.forEachIndexed { i, d ->
//                    semOut.appendText("-- Diagnostic #${i + 1} --\n")
//                    semOut.appendText("tag: ${d.tag}\n")
//                    semOut.appendText("message: ${d.message}\n")
//                    semOut.appendText("location: ${d.location ?: "(none)"}\n")
//                    semOut.appendText("selectorLength: ${d.selectorLength}\n")
//                    semOut.appendText("context.file: ${d.context.file}\n")
//                    if (d.cause != null) {
//                        val sw = java.io.StringWriter()
//                        d.cause.printStackTrace(java.io.PrintWriter(sw))
//                        semOut.appendText("cause:\n")
//                        semOut.appendText(sw.toString())
//                    }
//                    semOut.appendText("\n")
//                }
//            }
//            semOut.appendText("\nSymbol Table:\n")
//            semOut.appendText("Total Symbols: ${cu.symbolTable.totalSymbols()}\n")
//            cu.symbolTable.forEach { frame ->
//                semOut.appendText("Scope: ${frame.kind}\n")
//                frame.symbols.forEach { (k, v) ->
//                    semOut.appendText("  $k -> $v\n")
//                }
//            }
//        } catch (e: Exception) {
//            semOut.writeText("isHealthy: false\n\nException during semantic analysis:\n")
//            val sw = java.io.StringWriter()
//            e.printStackTrace(java.io.PrintWriter(sw))
//            semOut.appendText(sw.toString())
//        }
    }
}
