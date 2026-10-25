package net.exoad.kira

import net.exoad.kira.compiler.FrontendService
import net.exoad.kira.compiler.backend.targets.GeneratedProvider
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrontendServiceTest {
    @Test
    fun compilesHelloExampleCleanly() {
        val root = Path.of("src/test/resources/test-projects/01-hello").toAbsolutePath().normalize()
        val result = FrontendService.compileProject(root)
        assertTrue(
            result.diagnostics.none { it.severity == FrontendService.Diagnostic.Severity.ERROR },
            "unexpected errors: ${result.diagnostics}"
        )
        assertTrue(result.compilationUnit != null)
    }

    @Test
    fun reportsParseErrorOnBrokenOverlay() {
        val dir = Files.createTempDirectory("kira-frontend-")
        try {
            dir.resolve("kira.yaml").writeText(
                """
                project:
                  name: tmp
                srcDir: src
                build:
                  target: c
                dependencies:
                  kira_stdlib:
                    path: ${Path.of("kira").toAbsolutePath().normalize()}
                """.trimIndent()
            )
            val srcDir = dir.resolve("src")
            Files.createDirectories(srcDir)
            val main = srcDir.resolve("main.kira")
            main.writeText(
                """
                main: () Void {
                    trace("ok")
                }
                """.trimIndent()
            )

            val broken = """
                                main: () Void {
                    this is not valid kira !!!
                }
            """.trimIndent()

            val result = FrontendService.compileProject(
                dir,
                overlays = mapOf(main.toAbsolutePath().normalize().toString() to broken)
            )
            assertFalse(result.isOk, "expected diagnostics for broken overlay")
            // The overlay's own parse error: its `is` is no operator any more (`x == T` is the type test).
            assertTrue(
                result.diagnostics.any {
                    it.tag == "parse.removed" && it.file.endsWith("main.kira") && it.start.lineNumber == 2 &&
                        it.message.contains("'is' is not part of Kira any more")
                },
                result.diagnostics.toString(),
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** The language server leaves the target at none: a py module's opaque method and raises = check clean there (D70). */
    @Test
    fun aPyModulesOpaqueMethodMarkerChecksCleanAsTheLanguageServerRunsIt() {
        val dir = Files.createTempDirectory("kira-frontend-")
        val previous = GeneratedProvider.outputMode
        try {
            GeneratedProvider.outputMode = GeneratedProvider.OutputTarget.NONE
            dir.resolve("kira.yaml").writeText(
                """
                project:
                  name: tmp
                srcDir: src
                build:
                  target: py
                dependencies:
                  kira_stdlib:
                    path: ${Path.of("kira").toAbsolutePath().normalize()}
                """.trimIndent()
            )
            Files.createDirectories(dir.resolve("src/app"))
            dir.resolve("src/app/main.kira").writeText(
                """
                pub @_opaque Sock: Any {
                    pub @_extern(raises = "TimeoutError") recv: (n: Int32) List<UInt8>
                }

                @_extern(raises = "OSError") open: () Sock
                """.trimIndent()
            )
            val result = FrontendService.compileProject(dir)
            assertTrue(result.diagnostics.none { it.severity == FrontendService.Diagnostic.Severity.ERROR }, "${result.diagnostics}")
        } finally {
            GeneratedProvider.outputMode = previous
            dir.toFile().deleteRecursively()
        }
    }
}
