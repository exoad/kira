package net.exoad.kira

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FrontendCompilationPipelineTest {
    @Test
    fun compilesInlineSampleThroughLegacyFrontend() {
        val moduleUri = "test:frontend.legacy"
        val source = TestCompileSupport.wrapModule(
            moduleUri,
            """
            x: Int32 = 1
            y: Int32 = 2

            fx add: (a: Int32, b: Int32) Int32 {
                return a + b
            }

            answer: Int32 = add(x, y)
            """
        )

        val result = TestCompileSupport.compileSnippet(
            source = source,
            logicalPath = TestCompileSupport.logicalPathForModule(moduleUri),
            runSemantic = true
        )

        assertNotNull(result.sourceContext.ast)
        val semantics = assertNotNull(result.semanticResults)
        assertTrue(
            semantics.isHealthy,
            semantics.diagnostics.joinToString("\n") { it.message }
        )
    }

    @Test
    fun compilesExistingSampleFileThroughFrontendPipeline() {
        val result = TestCompileSupport.compileFile(
            filePath = "test_kira/sub/test.kira",
            runSemantic = true
        )

        assertNotNull(result.sourceContext.ast)
        val semantics = assertNotNull(result.semanticResults)
        assertTrue(
            semantics.isHealthy,
            semantics.diagnostics.joinToString("\n") { it.message }
        )
        assertNotNull(result.compilationUnit.resolveSymbol("TestEnum"))
    }
}
