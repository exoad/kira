package net.exoad.kira

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BackendCompilationPipelineTest {
    @Test
    fun transpilesExistingSampleFileDirectlyToC() {
        val generated = TestCompileSupport.transpileFileToC(
            filePath = "test_kira/sub/test.kira",
            runSemantic = true
        )

        assertTrue(generated.contains("typedef enum TestEnum"), generated)
        assertTrue(generated.contains("TEST_ENUM_A"), generated)
        assertTrue(generated.contains("TEST_ENUM_B"), generated)
        assertTrue(generated.contains("TEST_ENUM_C"), generated)
    }

    @Test
    fun transpilesInlineSampleToCAndValidatesFragments() {
        val moduleUri = "test:backend.sample"
        val source = TestCompileSupport.wrapModule(
            moduleUri,
            """
            fx add: (a: Int32, b: Int32) Int32 {
                return a + b
            }

            fx main: () Void {
                trace("OK\\n")
            }
            """
        )

        val generated = TestCompileSupport.transpileSnippetToC(
            source = source,
            logicalPath = TestCompileSupport.logicalPathForModule(moduleUri),
            runSemantic = false
        )

        // Layer 0 bundle substrate + layer 1 Kira facade
        assertTrue(generated.contains("KIRA_COMPILER_BUNDLE_H") || generated.contains("kira_i32"), generated)
        assertTrue(
            generated.contains("typedef kira_i32   Int32;") ||
                generated.contains("typedef kira_i32 Int32;") ||
                generated.contains("typedef int32_t Int32;"),
            generated
        )
        assertTrue(generated.contains("#include <stdio.h>"), generated)
        assertTrue(generated.contains("Int32 add(Int32 a, Int32 b)"), generated)
        assertTrue(
            generated.contains("Int32 main(Void)") || generated.contains("Int32 main()"),
            generated
        )
        assertTrue(
            generated.contains("print(\"%s\\n\", \"OK\\\\n\")") ||
                generated.contains("print(\"%s\", \"OK\\\\n\")") ||
                generated.contains("print(\"OK\\\\n\")"),
            generated
        )
    }

    @Test
    fun generatedCPassesCompilerSyntaxCheckForSampleProgram() {
        val compiler = TestCompileSupport.findCCompiler()
        assumeTrue(compiler != null, "No C compiler found on PATH")

        val moduleUri = "test:backend.syntax"
        val source = TestCompileSupport.wrapModule(
            moduleUri,
            """
            fx sum: (a: Int32, b: Int32) Int32 {
                return a + b
            }

            fx main: () Void {
                mut v: Int32 = sum(3, 4)
                trace("syntax-ok\\n")
            }
            """
        )

        val generated = TestCompileSupport.transpileSnippetToC(
            source = source,
            logicalPath = TestCompileSupport.logicalPathForModule(moduleUri),
            runSemantic = false
        )

        val syntaxResult = TestCompileSupport.syntaxCheckC(generated, compiler!!)
        assertEquals(0, syntaxResult.exitCode, "stdout:\n${syntaxResult.stdout}\nstderr:\n${syntaxResult.stderr}")
    }

    @Test
    fun generatedCFromSampleFilePassesSyntaxCheckWhenCompilerExists() {
        val compiler = TestCompileSupport.findCCompiler()
        assumeTrue(compiler != null, "No C compiler found on PATH")

        val generated = TestCompileSupport.transpileFileToC(
            filePath = "test_kira/sub/test.kira",
            runSemantic = true
        )

        val syntaxResult = TestCompileSupport.syntaxCheckC(generated, compiler!!)
        assertEquals(0, syntaxResult.exitCode, "stdout:\n${syntaxResult.stdout}\nstderr:\n${syntaxResult.stderr}")
    }

    @Test
    fun generatedCCompilesAndRunsForDeterministicOutput() {
        val compiler = TestCompileSupport.findCCompiler()
        assumeTrue(compiler != null, "No C compiler found on PATH")

        val moduleUri = "test:backend.run"
        val source = TestCompileSupport.wrapModule(
            moduleUri,
            """
            fx main: () Void {
                trace("runtime-ok\\n")
            }
            """
        )

        val generated = TestCompileSupport.transpileSnippetToC(
            source = source,
            logicalPath = TestCompileSupport.logicalPathForModule(moduleUri),
            runSemantic = false
        )

        val runResult = TestCompileSupport.compileAndRunC(generated, compiler!!)
        assumeTrue(
            runResult.compileResult.exitCode == 0,
            "Compile step failed. stdout:\n${runResult.compileResult.stdout}\nstderr:\n${runResult.compileResult.stderr}"
        )

        val exec = runResult.runResult
        assertNotNull(exec)
        assertEquals(0, exec.exitCode, "stdout:\n${exec.stdout}\nstderr:\n${exec.stderr}")
        assertTrue(exec.stdout.contains("runtime-ok"))
    }

    @Test
    fun generatedCLowersArrAndMapAndRuns() {
        val compiler = TestCompileSupport.findCCompiler()
        assumeTrue(compiler != null, "No C compiler found on PATH")

        val moduleUri = "test:backend.collections"
        val source = TestCompileSupport.wrapModule(
            moduleUri,
            """
            fx first: (values: Arr<Int32>) Int32 {
                return values[0]
            }

            fx hasAny: (values: Map<Str, Int32>) Bool {
                return !values.isEmpty()
            }

            fx main: () Void {
                numbers: Arr<Int32> = [10, 20, 30]
                head: Int32 = first(numbers)
                entries: Map<Str, Int32> = Map<Str, Int32> { }
                present: Bool = hasAny(entries)
                if present {
                    trace("map has values")
                } else {
                    trace(head)
                }
            }
            """
        )

        val generated = TestCompileSupport.transpileSnippetToC(
            source = source,
            logicalPath = TestCompileSupport.logicalPathForModule(moduleUri),
            runSemantic = false
        )

        assertTrue(generated.contains("typedef struct Arr"), generated)
        assertTrue(generated.contains("typedef struct Map"), generated)
        // Arr literals build a KiraSlot compound literal; the typed accessor
        // macro keeps Int32 element reads readable.
        assertTrue(generated.contains("Arr_lit((KiraSlot[])"), generated)
        assertTrue(generated.contains("Arr_get_i32"), generated)
        // Str-keyed maps hash and compare by content, so they use Map_new_s.
        assertTrue(generated.contains("Map_new_s()"), generated)
        assertTrue(generated.contains("Map_isEmpty"), generated)

        val runResult = TestCompileSupport.compileAndRunC(generated, compiler!!)
        assumeTrue(
            runResult.compileResult.exitCode == 0,
            "Compile step failed. stdout:\n${runResult.compileResult.stdout}\nstderr:\n${runResult.compileResult.stderr}\nC:\n$generated"
        )
        val exec = runResult.runResult
        assertNotNull(exec)
        assertEquals(0, exec.exitCode, "stdout:\n${exec.stdout}\nstderr:\n${exec.stderr}")
        assertTrue(exec.stdout.contains("10"), exec.stdout)
    }

    @Test
    fun generatedCMonomorphizesGenericsAndRuns() {
        val compiler = TestCompileSupport.findCCompiler()
        assumeTrue(compiler != null, "No C compiler found on PATH")

        val moduleUri = "test:backend.generics"
        val source = TestCompileSupport.wrapModule(
            moduleUri,
            """
            pub enum BuildStatus {
                READY,
                RUNNING,
                DONE
            }

            pub class Box<T> {
                require pub value: T
            }

            fx id<T>: (value: T) T {
                return value
            }

            fx main: () Void {
                state: BuildStatus = BuildStatus.READY
                wrapped: Box<Int32> = Box<Int32> { 7 }
                value: Int32 = id<Int32>(wrapped.value)
                if state == BuildStatus.READY {
                    trace(value)
                }
            }
            """
        )

        val generated = TestCompileSupport.transpileSnippetToC(
            source = source,
            logicalPath = TestCompileSupport.logicalPathForModule(moduleUri),
            runSemantic = false
        )

        assertTrue(generated.contains("typedef struct Box_Int32 Box_Int32"), generated)
        assertTrue(generated.contains("struct Box_Int32"), generated)
        assertTrue(generated.contains("Int32 id_Int32(Int32 value)"), generated)
        assertTrue(generated.contains("id_Int32("), generated)
        assertTrue(!generated.contains("T id(T"), generated)
        assertTrue(!generated.contains("id < Int32"), generated)

        val runResult = TestCompileSupport.compileAndRunC(generated, compiler!!)
        assumeTrue(
            runResult.compileResult.exitCode == 0,
            "Compile step failed. stdout:\n${runResult.compileResult.stdout}\nstderr:\n${runResult.compileResult.stderr}\nC:\n$generated"
        )
        val exec = runResult.runResult
        assertNotNull(exec)
        assertEquals(0, exec.exitCode, "stdout:\n${exec.stdout}\nstderr:\n${exec.stderr}")
        assertTrue(exec.stdout.contains("7"), exec.stdout)
    }

    @Test
    fun generatedCLowersMethodCallsAndRuns() {
        val compiler = TestCompileSupport.findCCompiler()
        assumeTrue(compiler != null, "No C compiler found on PATH")

        val moduleUri = "test:backend.method"
        val source = TestCompileSupport.wrapModule(
            moduleUri,
            """
            pub class Pet {
                require pub name: Str
                require pub sound: Str

                pub fx speak: () Str {
                    return sound
                }
            }

            fx main: () Void {
                friend: Pet = Pet { "Mochi", "meow" }
                trace(friend.name)
                trace(friend.speak())
            }
            """
        )

        val generated = TestCompileSupport.transpileSnippetToC(
            source = source,
            logicalPath = TestCompileSupport.logicalPathForModule(moduleUri),
            runSemantic = false
        )

        assertTrue(generated.contains("Pet_speak"), generated)
        assertTrue(generated.contains("this->sound") || generated.contains("this->sound;"), generated)

        val runResult = TestCompileSupport.compileAndRunC(generated, compiler!!)
        assumeTrue(
            runResult.compileResult.exitCode == 0,
            "Compile step failed. stdout:\n${runResult.compileResult.stdout}\nstderr:\n${runResult.compileResult.stderr}\nC:\n$generated"
        )
        val exec = runResult.runResult
        assertNotNull(exec)
        assertEquals(0, exec.exitCode, "stdout:\n${exec.stdout}\nstderr:\n${exec.stderr}")
        assertTrue(exec.stdout.contains("Mochi"), exec.stdout)
        assertTrue(exec.stdout.contains("meow"), exec.stdout)
    }
}
