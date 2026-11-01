package net.exoad.kira.suite.codegen

import net.exoad.kira.TestCompileSupport

/** Transpiles a snippet to C text without the semantic pass. */
object CodegenEmit {
    fun emit(body: String, uri: String = "test:codegen.basic"): String =
        TestCompileSupport.transpileSnippetToC(
            source = TestCompileSupport.wrapModule(uri, body),
            logicalPath = TestCompileSupport.logicalPathForModule(uri),
            runSemantic = false,
        )
}
