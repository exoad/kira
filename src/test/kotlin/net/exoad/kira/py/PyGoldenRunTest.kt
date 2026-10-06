package net.exoad.kira.py

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The py goldens (src/test/resources/py-golden/<case>): each case is a project the real CLI
 * compiles with `--target py --out build/tmp/py-golden/<case>`. Every generated file must parse
 * as Python 3.10 (the board's), and the run's stdout must equal the case's expected.txt: the
 * module itself when it has a `main`, or `python driver.py <out>` when the case has a driver,
 * hand-written Python that imports the generated module. numbers, order, bitmath, text, aliases and
 * globalorder hold the C++ backend's output for the same module, and shiftcount and shortview each call's
 * that the C++ backend's run of it alone gives (a value, or the panic's message); ladder runs the
 * target program against the hand-written Ladder it replaces. wsframe, jpegwalk and lebytes do
 * both: their driver runs the module's main, whose output is the C++ backend's, then holds the
 * walkers against the hand-written bibo code they port (and struct, binascii and zlib) over
 * seeded inputs, as maskcmd, linkwatch, dashtext, tagcheck and drivewords do bibo's text parsers,
 * hexpad its zero-padded hex and wstext its WebSocket text and close frames; bebytes holds the
 * big-endian set at the end of its views as shortview does; strs runs its main, then its panics
 * and the py target's one rule for text, a length counting code points; utf8of holds Str.of
 * against Python's decoder over every 1- and 2-byte sequence and more; stdapi runs the
 * Kotlin-named batch (D56 on); strings makes cpp-golden/strings' checks one for one, its
 * expected output that case's. Skipped when no Python is found (set KIRA_PYTHON).
 */
class PyGoldenRunTest {
    private val root = File(PyTestSupport.repoRoot, "src/test/resources/py-golden")

    @TestFactory
    fun goldens(): List<DynamicTest> {
        val cases = root.listFiles { f -> f.isDirectory && File(f, "kira.yaml").isFile }.orEmpty().sortedBy { it.name }
        val tests = cases.map { case -> DynamicTest.dynamicTest(case.name) { check(case) } }
        return tests + DynamicTest.dynamicTest("the corpus holds the target program") {
            assertTrue(cases.any { it.name == "ladder" }, "no ladder case under $root")
            assertTrue(cases.size >= 4, "py-golden holds ${cases.size} cases")
        }
    }

    private fun check(case: File) {
        val python = PyTestSupport.python
        assumeTrue(python != null, "no Python on PATH (set KIRA_PYTHON)")
        println("py golden ${case.name}: Python ${PyTestSupport.pythonVersion} ($python)")
        val out = File(PyTestSupport.repoRoot, "build/tmp/py-golden/${case.name}")
        out.deleteRecursively()
        val compiled = PyTestSupport.cli(case, "--target", "py", "--out", out.absolutePath)
        assertEquals(0, compiled.exitCode, "kira --target py failed for ${case.name}:\n${compiled.all}")
        val generated = out.walkTopDown().filter { it.isFile && it.name.endsWith(".kira.py") }.toList()
        assertTrue(generated.isNotEmpty(), "${case.name}: nothing was generated under $out")
        generated.forEach { assertNull(PyTestSupport.parsesAs310(it.toPath()), "${it.name} is no Python 3.10 source") }
        val driver = File(case, "driver.py")
        val command = if (driver.isFile) {
            listOf(python!!, driver.absolutePath, out.absolutePath)
        } else {
            listOf(python!!, generated.single().absolutePath)
        }
        val ran = PyTestSupport.run(command, case)
        assertEquals(0, ran.exitCode, "${case.name} exited ${ran.exitCode}:\n${ran.all}")
        val expected = File(case, "expected.txt").readText().replace("\r", "")
        assertEquals(expected, ran.stdout, "${case.name}: stdout differs from expected.txt\n${ran.stderr}")
    }
}
