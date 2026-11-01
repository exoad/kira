package net.exoad.kira.suite.semantic

import net.exoad.kira.TestCompileSupport
import net.exoad.kira.compiler.analysis.semantic.SemanticAnalyzerResults
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Analyzes a snippet or a few sources and asserts on the semantic results. */
object SemanticTestSupport {
    fun analyze(
        body: String,
        uri: String = "test:semantic.basic",
        runSemantic: Boolean = true,
    ): SemanticAnalyzerResults {
        val result = TestCompileSupport.compileSnippet(
            source = TestCompileSupport.wrapModule(uri, body),
            logicalPath = TestCompileSupport.logicalPathForModule(uri),
            runSemantic = runSemantic,
        )
        return assertNotNull(result.semanticResults)
    }

    /** Build a multi-source compilation unit so `use` can resolve a real module. */
    fun analyzeMultiSource(
        sources: List<Pair<String, String>>,
        uri: String = "test:semantic.basic",
    ): SemanticAnalyzerResults {
        val cu = net.exoad.kira.compiler.CompilationUnit()
        for ((filePath, body) in sources) {
            val pre = net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor(body)
            val processed = pre.process()
            val ctx = cu.addSource(filePath, processed.processedContent, emptyList())
            val tokens = net.exoad.kira.compiler.frontend.lexer.KiraLexer(ctx).tokenize()
            cu.addSource(filePath, ctx.content, tokens)
            net.exoad.kira.compiler.frontend.parser.LegacyKiraSourceParser(
                cu.getSource(filePath)!!
            ).parse()
        }
        return net.exoad.kira.compiler.analysis.semantic.KiraSemanticAnalyzer(cu).validateAST()
    }

    fun messages(results: SemanticAnalyzerResults): List<String> =
        results.diagnostics.map { it.message }

    fun assertHealthy(body: String, uri: String = "test:semantic.basic") {
        val results = analyze(body, uri)
        assertTrue(
            results.isHealthy,
            "expected healthy, got:\n${results.diagnostics.joinToString("\n") { it.message }}"
        )
    }

    fun assertUnhealthy(body: String, uri: String = "test:semantic.basic"): List<String> {
        val results = analyze(body, uri)
        assertFalse(results.isHealthy, "expected diagnostics for: $body")
        return messages(results)
    }
}
