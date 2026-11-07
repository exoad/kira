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
        private const val TRACE_MAIN = "main: () Void {\n    trace(\"x\")\n}\n"
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
    private fun runCli(dir: File, vararg args: String): CliResult {
        val java = System.getProperty("java.home") + "/bin/java"
        val classpath = System.getProperty("java.class.path")
        val proc = ProcessBuilder(
            java, "-cp", classpath, "net.exoad.kira.cli.MainKt", *args
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
                        x: MissingType { 1 }
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
                        s: Str { "a\0b" }
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
                        n: Size { s.size() }
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

    private val dumpSource = "main: () Void {\n    x: Int32 { 1 + 2 }\n    trace(\"hi\")\n}\n"

    @Test
    fun helpListsEveryDumpFlagWithItsText() {
        val result = runCli(tempProject("dump-help", null, mapOf()), "--help")
        assertEquals(0, result.exitCode, result.stderr)
        for (dump in net.exoad.kira.cli.Dumps.all) {
            assertTrue(result.stdout.contains(dump.flag), "--help omits ${dump.flag}:\n${result.stdout}")
            dump.help.forEach { assertTrue(result.stdout.contains(it), "--help omits the text of ${dump.flag}:\n${result.stdout}") }
        }
    }

    @Test
    fun dumpAstXmlPrintsEachSourceFileAndWritesNothing() {
        val dir = tempProject("dump-ast", basicProject(), mapOf("src/app/main.kira" to dumpSource))
        val result = runCli(dir, "--dump-ast-xml")
        assertEquals(0, result.exitCode, result.stderr)
        assertTrue(result.stdout.startsWith("<!-- src/app/main.kira -->\n<?xml"), result.stdout)
        assertTrue(result.stdout.contains("<BinaryExpr op=\"ADD\">"), result.stdout)
        assertFalse(result.stdout.contains("Generated at"), "the dump holds a timestamp, so two runs differ")
        assertEquals(1, Regex("<!-- .* -->\n<[?]xml").findAll(result.stdout).count(), "the stdlib's files are no part of the dump")
        assertEquals(result.stdout, runCli(dir, "--dump-ast-xml").stdout, "two runs differ")
        assertFalse(File(dir, "out.kira.c").exists(), "a dump run emitted code")
    }

    @Test
    fun dumpTypedPrintsSymbolsAndTheFactsOfEachExpression() {
        val dir = tempProject("dump-typed", basicProject(), mapOf("src/app/main.kira" to dumpSource))
        val result = runCli(dir, "--dump-typed")
        assertEquals(0, result.exitCode, result.stdout + result.stderr)
        assertTrue(result.stdout.contains("module cli-test:app.main"), result.stdout)
        assertTrue(result.stdout.contains("bodies cli-test/app/main.kira\n"), result.stdout)
        assertTrue(result.stdout.contains("main.kira:2:16  1 + 2  Int32  const=3:Int32"), result.stdout)
        assertFalse(File(dir, "out.kira.c").exists(), "a dump run emitted code")
    }

    @Test
    fun dumpTypedExitsOneOnATyperError() {
        val dir = tempProject("dump-typed-error", basicProject(), mapOf("src/app/main.kira" to "main: () Void {\n    x: Int32 { \"s\" }\n}\n"))
        val result = runCli(dir, "--dump-typed")
        assertEquals(1, result.exitCode, result.stdout + result.stderr)
        assertTrue(result.stdout.contains("diag error"), result.stdout)
    }

    @Test
    fun severalDumpFlagsEachPrintUnderTheirOwnHeader() {
        val dir = tempProject("dump-both", basicProject(), mapOf("src/app/main.kira" to dumpSource))
        val result = runCli(dir, "--dump-typed", "--dump-ast-xml", "--dump-typed")
        assertEquals(0, result.exitCode, result.stderr)
        val typed = result.stdout.indexOf("# --dump-typed\n")
        val xml = result.stdout.indexOf("# --dump-ast-xml\n")
        assertTrue(typed in 0 until xml, result.stdout)
        assertEquals(1, Regex("# --dump-typed\n").findAll(result.stdout).count(), "a repeated flag dumps twice")
    }

    @Test
    fun aMisspelledDependencyPathIsRefusedNamingKeyAndPath() {
        val project = ProjectText.of("badpath", target = "c", stdlib = "nowhere/kira")
        val dir = tempProject("bad-dependency-path", project, mapOf("src/app/main.kira" to TRACE_MAIN))
        val result = runCli(dir)
        val output = result.stdout + result.stderr
        assertEquals(1, result.exitCode, output)
        assertTrue(output.contains("dependencies['kira_stdlib']") && output.contains("nowhere/kira"), output)
        assertFalse(File(dir, "out.kira.c").exists(), "a refused project wrote a file")
    }

    @Test
    fun aProjectNamingNoKiraDependencyIsRefusedEvenWithAKiraFolderBeside() {
        val dir = tempProject("no-dependency", ProjectText.of("nodep", target = "c", stdlib = null), mapOf("src/app/main.kira" to TRACE_MAIN))
        File(kiraStdlib).copyRecursively(File(dir, "kira"), overwrite = true)
        val result = runCli(dir)
        val output = result.stdout + result.stderr
        assertEquals(1, result.exitCode, output)
        assertTrue(output.contains("project.kira names no kira dependency"), output)
        assertFalse(output.contains("at net.exoad."), output)
        assertFalse(File(dir, "out.kira.c").exists(), "a refused project wrote a file")
    }

    @Test
    fun refusesTheRemovedNekoTargetWithAMessage() {
        val dir = tempProject("neko", basicProject(), mapOf("src/app/main.kira" to TRACE_MAIN))
        val result = runCli(dir, "--target", "neko")
        val output = result.stdout + result.stderr
        assertEquals(1, result.exitCode, output)
        assertTrue(output.contains("Unknown target 'neko' (expected c, cpp, js, py, none)"), output)
        assertFalse(output.contains("at net.exoad."), output)
        assertFalse(File(dir, "out.kira.c").exists(), "a refused target wrote a file")
    }

    @Test
    fun refusesANekoTargetNamedInTheProjectFile() {
        val dir = tempProject("neko-project", basicProject(target = "neko"), mapOf("src/app/main.kira" to TRACE_MAIN))
        val result = runCli(dir)
        val output = result.stdout + result.stderr
        assertEquals(1, result.exitCode, output)
        assertTrue(output.contains("unsupported target 'neko'"), output)
    }

    // --- helpers ---------------------------------------------------------------

    private fun findCCompiler(): String? = TestCompileSupport.findCCompiler()
}
