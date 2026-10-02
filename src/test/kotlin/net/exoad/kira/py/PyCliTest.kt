package net.exoad.kira.py

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `kira --target py` through the real CLI: the file beside its source, and `--check`. */
class PyCliTest {
    private val kiraStdlib = File(PyTestSupport.repoRoot, "kira").absolutePath.replace('\\', '/')

    private fun project(name: String, target: String): File {
        val dir = File(PyTestSupport.repoRoot, "build/tmp/py-cli/$name").apply { deleteRecursively(); mkdirs() }
        File(dir, "kira.yaml").writeText(
            """
            project:
              name: cli-py

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
                trace("py-cli")
            }
            """.trimIndent()
        )
        return dir
    }

    @Test
    fun theManifestTargetWritesTheModuleBesideItsSourceAndCheckThenPasses() {
        val dir = project("beside", "py")
        val written = PyTestSupport.cli(dir)
        assertEquals(0, written.exitCode, written.all)
        val generated = File(dir, "src/app/main.kira.py")
        assertTrue(generated.isFile, written.all)
        val text = generated.readText()
        assertTrue(text.contains("from src/app/main.kira, module app:main."), text)
        assertTrue(text.contains("def _main():\n    print(\"py-cli\")"), text)
        assertTrue(text.trimEnd().endsWith("if __name__ == \"__main__\":\n    _main()"), text)

        val current = PyTestSupport.cli(dir, "--check")
        assertEquals(0, current.exitCode, current.all)
        assertTrue(current.all.contains("py check: 1 generated files are current"), current.all)
    }

    @Test
    fun checkNamesAFileThatDiffersOrIsMissingAndWritesNothing() {
        val dir = project("check", "c")
        val missing = PyTestSupport.cli(dir, "--target", "py", "--check")
        assertEquals(1, missing.exitCode, missing.all)
        assertTrue(missing.stdout.lines().any { it == "drift: src/app/main.kira.py (missing)" }, missing.all)
        assertFalse(File(dir, "src/app/main.kira.py").exists(), "--check wrote a file")

        assertEquals(0, PyTestSupport.cli(dir, "--target", "py").exitCode)
        val generated = File(dir, "src/app/main.kira.py")
        generated.writeText(generated.readText().replace("py-cli", "edited"))
        val differs = PyTestSupport.cli(dir, "--target", "py", "--check")
        assertEquals(1, differs.exitCode, differs.all)
        assertTrue(differs.stdout.lines().any { it == "drift: src/app/main.kira.py (differs)" }, differs.all)
        assertTrue(generated.readText().contains("edited"), "--check rewrote the file")
    }

    @Test
    fun aRefusedConstructWritesNothingAndExitsOne() {
        val dir = project("refused", "py")
        File(dir, "src/app/main.kira").writeText(
            """
            module "app:main"

            fx main: () Void {
                x: Float32 = 1.5
                trace(x)
            }
            """.trimIndent()
        )
        val result = PyTestSupport.cli(dir)
        assertEquals(1, result.exitCode, result.all)
        assertTrue(result.all.contains("error: py.unsupported: the local 'x': Float32 (Float64 is Python's float) is not supported on the py target yet"), result.all)
        assertTrue(result.all.contains("py backend: "), result.all)
        assertFalse(File(dir, "src/app/main.kira.py").exists())
        assertFalse(result.all.contains("\tat "), "a diagnostic, not a stack trace:\n${result.all}")
    }
}
