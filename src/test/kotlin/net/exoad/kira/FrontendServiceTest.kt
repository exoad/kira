package net.exoad.kira

import net.exoad.kira.compiler.FrontendService
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrontendServiceTest {
    @Test
    fun compilesHelloExampleCleanly() {
        val root = Path.of("examples/01-hello").toAbsolutePath().normalize()
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
                module "tmp:main"
                fx main: () Void {
                    trace("ok")
                }
                """.trimIndent()
            )

            val broken = """
                module "tmp:main"
                fx main: () Void {
                    this is not valid kira !!!
                }
            """.trimIndent()

            val result = FrontendService.compileProject(
                dir,
                overlays = mapOf(main.toAbsolutePath().normalize().toString() to broken)
            )
            assertFalse(result.isOk, "expected diagnostics for broken overlay")
            assertTrue(result.diagnostics.isNotEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
