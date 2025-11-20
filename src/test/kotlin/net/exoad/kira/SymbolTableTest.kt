package net.exoad.kira

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.analysis.semantic.KiraSemanticAnalyzer
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraParser
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertNotNull

class SymbolTableTest {
    @Test
    fun testSymbolTableDump() {
        // Use the existing sample in test_kira/sub/test.kira
        val file = File("test_kira/sub/test.kira")
        val pre = KiraPreprocessor(file.readText())
        val res = pre.process()
        val cu = CompilationUnit()
        val src = cu.addSource(file.canonicalPath, res.processedContent, emptyList())
        val lexer = KiraLexer(src)
        val tokens = lexer.tokenize()
        assertNotNull(tokens)
        val srcWithTokens = cu.addSource(file.canonicalPath, src.content, tokens)
        val parser = KiraParser(srcWithTokens)
        parser.parse()
        assertNotNull(srcWithTokens.ast)
        val intrOut = File("build/tmp/intrinsics_debug.txt")
        intrOut.parentFile.mkdirs()
        intrOut.writeText("All sources in compilation unit:\n")
        cu.allSources().forEach { src ->
            intrOut.appendText("  ${src.file}\n")
        }
        intrOut.appendText("\n")

        cu.allSources().forEach { srcCtx ->
            intrOut.appendText("Source: ${srcCtx.file}\n")
            try {
                intrOut.appendText("  Intrinsic markers count: ${srcCtx.astIntrinsicMarked.size}\n")
                srcCtx.astIntrinsicMarked.forEach { (node, intrinsics) ->
                    intrOut.appendText("    Node: ${node::class.simpleName} -> ${intrinsics.joinToString { intrinsic -> intrinsic.name }}\n")
                }
            } catch (_: UninitializedPropertyAccessException) {
                intrOut.appendText("  astIntrinsicMarked not initialized\n")
            } catch (e: Exception) {
                intrOut.appendText("  Error: ${e.message}\n")
            }
            intrOut.appendText("\n")
        }

        val semanticAnalyzer = KiraSemanticAnalyzer(cu)
        val semOut = File("build/tmp/diagnostics_test.txt")
        val symOut = File("build/tmp/symbols_after_semantic.txt")
        semOut.parentFile.mkdirs()
        symOut.parentFile.mkdirs()

        try {
            val semanticResults = semanticAnalyzer.validateAST()
            semOut.writeText("isHealthy: ${semanticResults.isHealthy}\n\n")
            if (semanticResults.diagnostics.isEmpty()) {
                semOut.appendText("(no diagnostics)\n")
            } else {
                semanticResults.diagnostics.forEachIndexed { i, d ->
                    semOut.appendText("-- Diagnostic #${i + 1} --\n")
                    semOut.appendText("tag: ${d.tag}\n")
                    semOut.appendText("message: ${d.message}\n")
                    semOut.appendText("location: ${d.location ?: "(none)"}\n")
                    semOut.appendText("selectorLength: ${d.selectorLength}\n")
                    semOut.appendText("context.file: ${d.context.file}\n\n")
                }
            }

            symOut.writeText("Total Symbols: ${cu.symbolTable.totalSymbols()}\n\n")
            cu.symbolTable.forEach { frame ->
                symOut.appendText("Scope: ${frame.kind}\n")
                frame.symbols.forEach { (k, v) ->
                    symOut.appendText("  $k -> ${v}\n")
                }
                symOut.appendText("\n")
            }
            val intrOut = File("build/tmp/intrinsics_debug.txt")
            intrOut.parentFile.mkdirs()
            intrOut.writeText("All sources in compilation unit:\n")
            cu.allSources().forEach { src ->
                intrOut.appendText("  ${src.file}\n")
            }
            intrOut.appendText("\n")
            cu.allSources().forEach { srcCtx ->
                intrOut.appendText("Source: ${srcCtx.file}\n")
                intrOut.appendText("  Intrinsic markers count: ${srcCtx.astIntrinsicMarked.size}\n")
                srcCtx.astIntrinsicMarked.forEach { (node, intrinsics) ->
                    intrOut.appendText("    Node: ${node::class.simpleName} -> ${intrinsics.joinToString { intrinsic -> intrinsic.name }}\n")
                }
                intrOut.appendText("\n")
            }
        } catch (e: Exception) {
            semOut.writeText("Exception during semantic analysis:\n")
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            semOut.appendText(sw.toString())
        }
    }
}
