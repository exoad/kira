package net.exoad.kira.suite

import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CLI coverage: runs the real `net.exoad.kira.cli.MainKt` as a subprocess on
 * throwaway project directories (each with its own `kira.yaml`), then verifies
 * exit codes, emitted output, and behavior of the produced C binary.
 *
 * This is the same entry point CI exercises via `kira`, without needing the
 * installed distribution.
 */
class CliSuiteTest {

    companion object {
        private val repoRoot: File = File(System.getProperty("user.dir"))
        private val kiraStdlib: String = File(repoRoot, "kira").absolutePath
    }

    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

    /** Create a temp project under build/tmp/cli-suite/<name> with kira.yaml + src/. */
    private fun tempProject(
        name: String,
        manifest: String,
        sources: Map<String, String>,
    ): File {
        val dir = File(repoRoot, "build/tmp/cli-suite/$name").apply { deleteRecursively(); mkdirs() }
        val resolvedManifest = manifest.replace("\${kira_stdlib}", kiraStdlib)
        File(dir, "kira.yaml").writeText(resolvedManifest)
        for ((relPath, content) in sources) {
            val file = File(dir, relPath)
            file.parentFile.mkdirs()
            file.writeText(content)
        }
        return dir
    }

    /** Run the real CLI main in [dir]. */
    private fun runCli(dir: File): CliResult {
        val java = System.getProperty("java.home") + "/bin/java"
        val classpath = System.getProperty("java.class.path")
        val proc = ProcessBuilder(
            java, "-cp", classpath, "net.exoad.kira.cli.MainKt"
        )
            .directory(dir)
            .redirectErrorStream(false)
            .start()
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        val code = proc.waitFor()
        return CliResult(code, stdout, stderr)
    }

    private fun basicManifest(): String = """
        project:
          name: cli-test

        srcDir: src

        build:
          target: c

        dependencies:
          kira_stdlib:
            path: ${'$'}{kira_stdlib}
    """.trimIndent()

    // --- happy path -----------------------------------------------------------

    @Test
    fun compilesProjectAndEmitsC() {
        val dir = tempProject(
            "happy",
            basicManifest(),
            mapOf(
                "src/app/main.kira" to """
                    module "app:main"

                    fx main: () Void {
                        trace("cli-ok")
                    }
                """.trimIndent(),
            )
        )
        val result = runCli(dir)
        assertEquals(0, result.exitCode, "stdout:\n${result.stdout}\nstderr:\n${result.stderr}")

        val out = File(dir, "out.kira.c")
        assertTrue(out.exists(), "expected out.kira.c in ${dir.absolutePath}")
        val text = out.readText()
        assertTrue(text.contains("Int32 main(Void)"), "emitted C should contain main")
        assertTrue(text.contains("cli-ok"), "emitted C should contain the literal")
    }

    @Test
    fun emittedBinaryRunsThroughRealCli() {
        val compiler = findCCompiler()
        if (compiler == null) {
            return
        }
        val dir = tempProject(
            "run",
            basicManifest(),
            mapOf(
                "src/app/main.kira" to """
                    module "app:main"

                    fx main: () Void {
                        trace("from-cli")
                    }
                """.trimIndent(),
            )
        )
        val result = runCli(dir)
        assertEquals(0, result.exitCode, "stdout:\n${result.stdout}\nstderr:\n${result.stderr}")

        val exe = File(dir, "app")
        val cc = ProcessBuilder(compiler, "-std=c17", "-O2", "-o", exe.absolutePath, File(dir, "out.kira.c").absolutePath)
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        val ccOut = cc.inputStream.bufferedReader().readText()
        assertEquals(0, cc.waitFor(), "cc failed:\n$ccOut")

        val run = ProcessBuilder(exe.absolutePath)
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        val runOut = run.inputStream.bufferedReader().readText()
        assertEquals(0, run.waitFor(), "binary failed:\n$runOut")
        assertTrue(runOut.contains("from-cli"), runOut)
    }

    // --- failure paths -----------------------------------------------------------

    @Test
    fun exitsNonZeroOnSemanticDiagnostics() {
        val dir = tempProject(
            "diagnostics",
            basicManifest(),
            mapOf(
                "src/app/main.kira" to """
                    module "app:main"

                    fx main: () Void {
                        x: MissingType = 1
                        trace(x)
                    }
                """.trimIndent(),
            )
        )
        val result = runCli(dir)
        assertEquals(1, result.exitCode, "stdout:\n${result.stdout}\nstderr:\n${result.stderr}")
        assertTrue(
            result.stderr.contains("MissingType") || result.stdout.contains("MissingType"),
            "diagnostic should name the missing type. stdout:\n${result.stdout}\nstderr:\n${result.stderr}"
        )
        // Backend emit must be skipped when diagnostics are present.
        assertFalse(File(dir, "out.kira.c").exists(), "no C should be emitted on diagnostics")
    }

    @Test
    fun panicsOnLegacyTomlManifest() {
        val dir = tempProject(
            "legacy-toml",
            basicManifest(),
            mapOf(
                "kira.toml" to """
                    [project]
                    name = "legacy"
                """.trimIndent(),
            )
        )
        // kira.toml must take precedence over kira.yaml for the panic path.
        File(dir, "kira.yaml").delete()
        val result = runCli(dir)
        assertEquals(1, result.exitCode)
        assertTrue(
            result.stderr.contains("kira.toml") || result.stdout.contains("kira.toml"),
            "migration message should mention kira.toml. stdout:\n${result.stdout}\nstderr:\n${result.stderr}"
        )
    }

    @Test
    fun panicsWhenNoSourcesFound() {
        val dir = tempProject(
            "no-sources",
            """
            project:
              name: empty

            srcDir: src

            build:
              target: c
            """.trimIndent(),
            mapOf()
        )
        // srcDir must exist for validation to pass; an empty src then triggers
        // the "no source files" panic.
        File(dir, "src").mkdirs()
        val result = runCli(dir)
        assertEquals(1, result.exitCode)
        assertTrue(
            result.stderr.contains("No source files") || result.stdout.contains("No source files"),
            "stdout:\n${result.stdout}\nstderr:\n${result.stderr}"
        )
    }

    @Test
    fun rejectsMissingSrcDirDuringManifestValidation() {
        val dir = tempProject(
            "missing-srcdir",
            """
            project:
              name: empty

            srcDir: src

            build:
              target: c
            """.trimIndent(),
            mapOf()
        )
        val result = runCli(dir)
        assertEquals(1, result.exitCode)
        assertTrue(
            result.stderr.contains("srcDir does not exist") || result.stdout.contains("srcDir does not exist"),
            "stdout:\n${result.stdout}\nstderr:\n${result.stderr}"
        )
    }

    // --- helpers ---------------------------------------------------------------

    private fun findCCompiler(): String? {
        for (candidate in listOf("clang", "cc", "gcc")) {
            val proc = ProcessBuilder("which", candidate).start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (proc.exitValue() == 0 && out.isNotBlank()) {
                return out
            }
        }
        return null
    }
}
