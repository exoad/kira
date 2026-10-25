package net.exoad.kira.suite

import net.exoad.kira.ProjectText
import net.exoad.kira.TestCompileSupport
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CLI coverage: runs the real `net.exoad.kira.cli.MainKt` as a subprocess on
 * throwaway project directories (each with its own `project.kira`), then verifies
 * exit codes, emitted output, and behavior of the produced C binary.
 *
 * This is the same entry point CI exercises via `kira`, without needing the
 * installed distribution.
 */
class CliSuiteTest {

    companion object {
        private val repoRoot: File = File(System.getProperty("user.dir"))
        private val kiraStdlib: String = File(repoRoot, "kira").absolutePath.replace('\\', '/')
    }

    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

    /** Create a temp project under build/tmp/cli-suite/<name> with [project] as its project.kira (none for null) + src/. */
    private fun tempProject(
        name: String,
        project: String?,
        sources: Map<String, String>,
    ): File {
        val dir = File(repoRoot, "build/tmp/cli-suite/$name").apply { deleteRecursively(); mkdirs() }
        project?.let { File(dir, "project.kira").writeText(it) }
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

    private fun basicProject(target: String = "c"): String = ProjectText.of("cli-test", target = target, stdlib = kiraStdlib)

    // --- happy path -----------------------------------------------------------

    @Test
    fun compilesProjectAndEmitsC() {
        val dir = tempProject(
            "happy",
            basicProject(),
            mapOf(
                "src/app/main.kira" to """
                    main: () Void {
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
            basicProject(),
            mapOf(
                "src/app/main.kira" to """
                    main: () Void {
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
            basicProject(),
            mapOf(
                "src/app/main.kira" to """
                    main: () Void {
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
    fun anUnknownStringEscapeIsADiagnosticNotACrash() {
        val dir = tempProject(
            "unknown-escape",
            basicProject(),
            mapOf(
                "src/app/main.kira" to """
                    main: () Void {
                        s: Str = "a\0b"
                        trace(s)
                    }
                """.trimIndent(),
            )
        )
        val result = runCli(dir)
        val output = result.stdout + result.stderr
        assertEquals(1, result.exitCode, output)
        assertTrue(output.contains("Unknown escape sequence '\\0'"), output)
        assertTrue(output.contains("Kira knows \\n, \\t, \\r, \\\\, \\\", \\${'$'} and \\uXXXX."), output)
        assertTrue(output.contains("main.kira] : line 2"), output)
        assertFalse(output.contains("Exception in thread"), output)
        assertFalse(output.contains("at net.exoad."), output)
        assertFalse(File(dir, "out.kira.c").exists(), "no C should be emitted on a parse error")
    }

    @Test
    fun aMaybeParametersMisuseIsTheTypersCodedDiagnostic() {
        val dir = tempProject(
            "maybe-param",
            basicProject(target = "py"),
            mapOf(
                "src/app/main.kira" to """
                    f: (s: Maybe<Str>) Size {
                        n: Size = s.size()
                        n
                    }
                """.trimIndent(),
            )
        )
        val result = runCli(dir)
        val output = result.stdout + result.stderr
        assertEquals(1, result.exitCode, output)
        assertEquals(1, Regex("types\\.maybe\\.member").findAll(output).count(), output)
        assertFalse(output.contains("Diagnostic Report"), output)
    }

    @Test
    fun anEmptySourceFileIsAnEmptyModuleNotACrash() {
        // The file is the module, so an empty file is an empty module.
        val dir = tempProject("empty-source", basicProject(), mapOf("src/app/main.kira" to ""))
        val result = runCli(dir)
        val output = result.stdout + result.stderr
        assertEquals(0, result.exitCode, output)
        assertFalse(output.contains("Exception in thread"), output)
        assertFalse(output.contains("at net.exoad."), output)
    }

    @Test
    fun panicsWhenNoSourcesFound() {
        // No project file and no stdlib: an empty src then triggers the "no source files" panic. (A project.kira
        // project always has a stdlib, the one it is typed against.)
        val dir = tempProject("no-sources", null, mapOf())
        File(dir, "src").mkdirs()
        val result = runCli(dir)
        assertEquals(1, result.exitCode)
        assertTrue(
            result.stderr.contains("No source files") || result.stdout.contains("No source files"),
            "stdout:\n${result.stdout}\nstderr:\n${result.stderr}"
        )
    }

    @Test
    fun rejectsAMissingSrcDirAsAProjectIssue() {
        val dir = tempProject("missing-srcdir", ProjectText.of("empty", target = "c", stdlib = null), mapOf())
        val result = runCli(dir)
        assertEquals(1, result.exitCode)
        assertTrue(
            result.stderr.contains("srcDir does not exist") || result.stdout.contains("srcDir does not exist"),
            "stdout:\n${result.stdout}\nstderr:\n${result.stderr}"
        )
    }

    // --- helpers ---------------------------------------------------------------

    private fun findCCompiler(): String? = TestCompileSupport.findCCompiler()
}
