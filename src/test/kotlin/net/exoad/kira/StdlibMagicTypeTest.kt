package net.exoad.kira

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
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
            runSemantic = false
        )

        assertTrue(generated.contains("#include <stdint.h>"), generated)
        assertTrue(
            generated.contains("Int32 value = 42;"),
            generated
        )
    }

    /**
     * The stdlib is split by concern across several files under `kira/`, so
     * assert per file rather than against one monolith. `sourceFilter` is what
     * keeps this honest: CompilationUnit bootstraps every stdlib source into
     * itself, so an unfiltered scan would pass no matter what one file holds.
     */
    @Test
    fun stdlibDeclaresCoreMagicTypesInTheExpectedModules() {
        val expected = mapOf(
            "core.kira" to listOf("Bool", "Str", "Num", "Int32", "Int64", "Float32", "Float64"),
            "collections.kira" to listOf("Arr", "List", "Map", "Set", "Stack", "Queue", "Deque"),
            "result.kira" to listOf("Maybe", "Result", "Exception"),
            "tuples.kira" to listOf("Tuple0", "Tuple2", "Tuple9")
        )

        expected.forEach { (fileName, typeNames) ->
            val path = File("kira", fileName)
            assertTrue(path.isFile, "missing stdlib source $path")

            val marked = magicTypesDeclaredIn(path)
            typeNames.forEach { typeName ->
                assertTrue(
                    marked.contains(typeName),
                    "'$typeName' should be @_magic in $fileName, but that file declares $marked"
                )
            }
        }
    }

    /** No magic type may be declared by two stdlib files at once. */
    @Test
    fun stdlibDeclaresEachMagicTypeExactlyOnce() {
        val stdlibFiles = File("kira")
            .listFiles { f -> f.isFile && f.extension == "kira" }
            .orEmpty()
        assertTrue(stdlibFiles.isNotEmpty(), "no stdlib sources found under kira/")

        val duplicates = stdlibFiles
            .flatMap { file -> magicTypesDeclaredIn(file).map { it to file.name } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }

        assertEquals(emptyMap(), duplicates, "magic types declared in more than one stdlib file")
    }

    /** Magic type names declared by [file] alone. */
    private fun magicTypesDeclaredIn(file: File): Set<String> {
        val result = TestCompileSupport.compileFile(file.path, runSemantic = false)
        val canonicalPath = file.canonicalPath
        return result.compilationUnit.collectIntrinsicMarkedTypeNames("_magic") { source ->
            source.file == canonicalPath
        }
    }
}
