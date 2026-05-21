package net.exoad.kira

import net.exoad.kira.compiler.frontend.parser.ParserBackend
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class StdlibMagicTypeTest {
    @Test
    fun magicIntrinsicRegistersTypeDuringSemanticAnalysis() {
        val moduleUri = "test:magic.semantic"
        val source = TestCompileSupport.wrapModule(
            moduleUri,
            """
            @_magic class Int32
            value: Int32 = 7
            """
        )

        val result = TestCompileSupport.compileSnippet(
            source = source,
            logicalPath = TestCompileSupport.logicalPathForModule(moduleUri),
            parserBackend = ParserBackend.LEGACY,
            runSemantic = true
        )

        assertTrue(result.compilationUnit.isMagicType("Int32"))
    }

    @Test
    fun magicTypeLowersToFixedWidthCType() {
        val moduleUri = "test:magic.codegen"
        val source = TestCompileSupport.wrapModule(
            moduleUri,
            """
            @_magic class Int32
            value: Int32 = 42
            """
        )

        val generated = TestCompileSupport.transpileSnippetToC(
            source = source,
            logicalPath = TestCompileSupport.logicalPathForModule(moduleUri),
            parserBackend = ParserBackend.LEGACY,
            runSemantic = false
        )

        assertTrue(generated.contains("#include <stdint.h>"))
        assertTrue(
            generated.contains("int32_t value = 42;") || generated.contains("KInt value = 42;"),
            generated
        )
    }

    @Test
    fun stdlibDeclaresCoreMagicTypes() {
        val result = TestCompileSupport.compileFile(
            filePath = "kira/stl.kira",
            parserBackend = ParserBackend.LEGACY,
            runSemantic = false
        )

        val marked = result.compilationUnit.collectIntrinsicMarkedTypeNames("_magic")
        assertTrue(marked.contains("Int32"))
        assertTrue(marked.contains("Float64"))
        assertTrue(marked.contains("Bool"))
        assertTrue(marked.contains("Str"))
        assertTrue(marked.contains("Arr"))
        assertTrue(marked.contains("List"))
        assertTrue(marked.contains("Map"))
        assertTrue(marked.contains("Set"))
        assertTrue(marked.contains("Maybe"))
        assertTrue(marked.contains("Result"))
    }
}
