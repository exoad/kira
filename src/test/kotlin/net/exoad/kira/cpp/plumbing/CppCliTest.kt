package net.exoad.kira.cpp.plumbing

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real CLI (`net.exoad.kira.cli.MainKt`) as a subprocess, the way
 * CliSuiteTest runs it, on a throwaway project with the repository's stdlib.
 */
class CppCliTest {
    private val repoRoot = File(System.getProperty("user.dir"))
    private val kiraStdlib = File(repoRoot, "kira").absolutePath.replace('\\', '/')

    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String) {
        val all: String get() = stdout + "\n" + stderr
    }

    private fun tempProject(name: String, target: String = "c"): File {
        val dir = File(repoRoot, "build/tmp/cpp-plumbing-cli/$name").apply { deleteRecursively(); mkdirs() }
        File(dir, "kira.yaml").writeText(
            """
            project:
              name: cli-cpp

            srcDir: src

            build:
              target: $target

            dependencies:
              kira_stdlib:
                path: $kiraStdlib
            """.trimIndent()
        )
        File(dir, "src/app").mkdirs()
        File(dir, "src/app/main.kira").writeText(
            """
            module "app:main"

            fx main: () Void {
                trace("cpp-cli")
            }
            """.trimIndent()
        )
        return dir
    }

    private fun runCli(dir: File, vararg args: String): CliResult {
        val java = System.getProperty("java.home") + "/bin/java"
        val classpath = System.getProperty("java.class.path")
        val proc = ProcessBuilder(listOf(java, "-cp", classpath, "net.exoad.kira.cli.MainKt") + args)
            .directory(dir)
            .start()
        val stderrHolder = StringBuilder()
        val reader = Thread { stderrHolder.append(proc.errorStream.bufferedReader().readText()) }
        reader.start()
        val stdout = proc.inputStream.bufferedReader().readText()
        reader.join()
        return CliResult(proc.waitFor(), stdout, stderrHolder.toString())
    }

    private fun snapshot(dir: File): List<String> = PlumbingTestSupport.listFiles(dir.toPath())

    // Until the expression and statement parts (W2.3) land, every function body is
    // `cpp.unsupported: ... is not lowered yet`, so a program with a body, and the
    // Kira-written stdlib (kira:math's clamp) in every unit, still exits 1 and writes
    // nothing. W2.3 flips these to assert success.
    private val bodyUnsupported = "cpp.unsupported: the body of 'main' is not lowered yet"

    @Test
    fun targetCppExitsOneWithUnsupportedAndWritesNothing() {
        val dir = tempProject("unsupported")
        val before = snapshot(dir)
        val result = runCli(dir, "--target", "cpp")
        assertEquals(1, result.exitCode, result.all)
        assertTrue(result.all.contains(bodyUnsupported), result.all)
        assertTrue(!result.all.contains("Exception"), "a diagnostic, not a stack trace:\n${result.all}")
        assertEquals(before, snapshot(dir), "no file may be written")
    }

    @Test
    fun targetCPlusPlusIsAnAlias() {
        val dir = tempProject("alias")
        val result = runCli(dir, "--target", "c++")
        assertEquals(1, result.exitCode, result.all)
        assertTrue(result.all.contains(bodyUnsupported), result.all)
    }

    @Test
    fun manifestTargetCppIsAccepted() {
        val dir = tempProject("manifest-target", target = "cpp")
        val before = snapshot(dir)
        val result = runCli(dir)
        assertEquals(1, result.exitCode, result.all)
        assertTrue(result.all.contains(bodyUnsupported), result.all)
        assertTrue(!result.all.contains("Manifest validation failed"), result.all)
        assertEquals(before, snapshot(dir))
    }

    @Test
    fun manifestTargetJsIsAcceptedByTheValidator() {
        val dir = tempProject("manifest-js", target = "js")
        val result = runCli(dir)
        assertEquals(0, result.exitCode, result.all)
        assertTrue(File(dir, "out.kira.js").isFile)
    }

    @Test
    fun checkWithoutCppTargetIsRefused() {
        val dir = tempProject("check-c")
        val before = snapshot(dir)
        val result = runCli(dir, "--check")
        assertEquals(1, result.exitCode, result.all)
        assertTrue(result.all.contains("--check is only supported with --target cpp"), result.all)
        assertTrue(!result.all.contains("Exception") && !result.all.contains("\tat "), "a usage error, not a stack trace:\n${result.all}")
        assertEquals(before, snapshot(dir))
    }

    @Test
    fun checkWithCppTargetStillReportsUnsupported() {
        val dir = tempProject("check-cpp")
        val before = snapshot(dir)
        val result = runCli(dir, "--target", "cpp", "--check")
        assertEquals(1, result.exitCode, result.all)
        assertTrue(result.all.contains(bodyUnsupported), result.all)
        assertEquals(before, snapshot(dir))
    }

    @Test
    fun outDirectoryMovesTheCOutput() {
        val dir = tempProject("out-c")
        val result = runCli(dir, "--target", "c", "--out", "generated")
        assertEquals(0, result.exitCode, result.all)
        assertTrue(File(dir, "generated/out.kira.c").isFile, snapshot(dir).toString())
        assertTrue(!File(dir, "out.kira.c").exists())
    }
}
