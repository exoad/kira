package net.exoad.kira.py

import net.exoad.kira.compiler.backend.codegen.py.PyNames
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Each case under src/test/resources/py-golden is compiled by the CLI and run, through its
 * driver.py when it has one; its stdout must be expected.txt, the C++ backend's run of the same
 * module unless the case's header says otherwise. Skipped when no Python is found (KIRA_PYTHON).
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
        assertEquals(emptyList(), PyTestSupport.unreservedBuiltins(generated.map { it.toPath() }, PyNames.RESERVED), "${case.name} names a builtin a Kira name could shadow")
        val driver = File(case, "driver.py")
        val command = if (driver.isFile) {
            listOf(python!!, driver.absolutePath, out.absolutePath)
        } else {
            val mains = generated.filter { it.readText().contains("\nif __name__ == \"__main__\":\n") }
            assertEquals(1, mains.size, "${case.name}: no one module has a main among ${generated.map { it.name }}")
            listOf(python!!, mains.single().absolutePath)
        }
        val ran = PyTestSupport.run(command, case)
        assertEquals(0, ran.exitCode, "${case.name} exited ${ran.exitCode}:\n${ran.all}")
        val expected = File(case, "expected.txt").readText().replace("\r", "")
        assertEquals(expected, ran.stdout, "${case.name}: stdout differs from expected.txt\n${ran.stderr}")
    }
}
