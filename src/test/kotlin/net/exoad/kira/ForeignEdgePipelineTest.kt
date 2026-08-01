package net.exoad.kira

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ForeignEdgePipelineTest {
    @Test
    fun emitsExternPrototypesAndOpaquePointers() {
        val moduleUri = "test:foreign.edge"
        val source = TestCompileSupport.wrapModule(
            moduleUri,
            """
            @_opaque
            pub class Handle

            @_extern
            fx foreignOpen: (name: Str) Handle

            @_extern
            fx foreignClose: (h: Handle) Void

            fx main: () Void {
                h: Handle = foreignOpen("x")
                foreignClose(h)
            }
            """
        )

        val generated = TestCompileSupport.transpileSnippetToC(
            source = source,
            logicalPath = TestCompileSupport.logicalPathForModule(moduleUri),
            runSemantic = false
        )

        assertTrue(generated.contains("typedef struct Handle Handle;"), generated)
        assertTrue(
            generated.contains("extern Handle* foreignOpen(Str name);") ||
                generated.contains("Handle* foreignOpen(Str name);"),
            generated
        )
        assertTrue(
            generated.contains("foreignOpen(") && generated.contains("foreignClose("),
            generated
        )
        // Opaque values are pointers in signatures / usage
        assertTrue(generated.contains("Handle*"), generated)
    }

    @Test
    fun ffiMiniExampleCompilesAndRuns() {
        val compiler = TestCompileSupport.findCCompiler()
        assumeTrue(compiler != null, "No C compiler on PATH")

        val project = java.io.File("examples/ffi-mini")
        assumeTrue(project.isDirectory, "examples/ffi-mini missing")

        val kiraBin = java.io.File("build/install/kira/bin/kira")
        assumeTrue(kiraBin.canExecute() || java.io.File("build/install/kira/bin/kira").exists(),
            "run installDist first")

        // Prefer installed CLI; fall back to gradle run is too heavy -- require installDist
        assumeTrue(kiraBin.exists(), "build/install/kira/bin/kira not found")

        val emit = ProcessBuilder(kiraBin.absolutePath)
            .directory(project)
            .redirectErrorStream(true)
            .start()
        val emitOut = emit.inputStream.bufferedReader().readText()
        val emitCode = emit.waitFor()
        assertEquals(0, emitCode, "kira emit failed:\n$emitOut")

        val outC = java.io.File(project, "out.kira.c")
        assertTrue(outC.isFile, "out.kira.c missing")
        val text = outC.readText()
        assertTrue(text.contains("miniCreate"), text)
        assertTrue(text.contains("MiniSurface*"), text)

        val app = java.io.File(project, "app_test_bin")
        val cc = ProcessBuilder(
            compiler!!, "-std=c17", "-O0", "-o", app.absolutePath,
            outC.absolutePath,
            java.io.File(project, "native/mini_gfx.c").absolutePath
        ).directory(project).redirectErrorStream(true).start()
        val ccOut = cc.inputStream.bufferedReader().readText()
        assertEquals(0, cc.waitFor(), "cc failed:\n$ccOut")

        val run = ProcessBuilder(app.absolutePath).directory(project).redirectErrorStream(true).start()
        val runOut = run.inputStream.bufferedReader().readText()
        assertEquals(0, run.waitFor(), "run failed:\n$runOut")
        assertTrue(runOut.contains("mini:"), runOut)
        assertTrue(runOut.contains("ffi-mini ok"), runOut)

        outC.delete()
        app.delete()
    }
}
