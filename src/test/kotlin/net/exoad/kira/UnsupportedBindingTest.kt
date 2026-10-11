package net.exoad.kira

import net.exoad.kira.py.PyTestSupport
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** D83: a manifest's `unsupported` entry refuses a call at compile time on its own target, naming why, and the other target binds it. */
class UnsupportedBindingTest {
    @Test
    fun eachTargetRefusesItsUnsupportedCallByNameAndTheOtherBindsIt() {
        val dir = File(PyTestSupport.repoRoot, "build/tmp/unsupported-binding").apply { deleteRecursively(); mkdirs() }
        val stdlib = File(dir, "stdlib/kira")
        File(PyTestSupport.repoRoot, "kira").copyRecursively(stdlib)
        File(stdlib, "extra.kira").writeText("module \"kira:extra\"\n\npub @_magic fx onlyPy: () Int32;\npub @_magic fx onlyCpp: () Int32;\n")
        File(stdlib, "extra.bind.yaml").writeText(
            "onlyPy: { cpp: { unsupported: \"a rule of Python's\" }, py: { expr: \"7\" } }\n" +
                "onlyCpp: { cpp: { expr: \"7\" }, py: { unsupported: \"a rule of C++'s\" } }\n"
        )
        File(dir, "kira.yaml").writeText(
            "project:\n  name: unsupported\nsrcDir: src\nbuild:\n  target: py\ndependencies:\n  kira_stdlib:\n    path: ${stdlib.absolutePath.replace('\\', '/')}\n"
        )
        val main = File(dir, "src/app/main.kira").apply { parentFile.mkdirs() }
        main.writeText("module \"app:main\"\n\nuse \"kira:extra\"\n\nfx main: () Int32 {\n    return onlyPy() + onlyCpp()\n}\n")

        val py = PyTestSupport.cli(dir, "--target", "py", "--out", File(dir, "out-py").absolutePath)
        assertEquals(1, py.exitCode, py.all)
        assertTrue(py.all.contains("error: py.unsupported: 'onlyCpp' is not supported on the py target: a rule of C++'s"), py.all)
        assertFalse(py.all.contains("'onlyPy'"), py.all)
        val cpp = PyTestSupport.cli(dir, "--target", "cpp", "--out", File(dir, "out-cpp").absolutePath)
        assertEquals(1, cpp.exitCode, cpp.all)
        assertTrue(cpp.all.contains("error: cpp.unsupported: 'onlyPy' is not supported on the C++ target: a rule of Python's"), cpp.all)
        assertFalse(cpp.all.contains("'onlyCpp'"), cpp.all)

        main.writeText("module \"app:main\"\n\nuse \"kira:extra\"\n\nfx main: () Int32 {\n    return onlyPy()\n}\n")
        assertEquals(0, PyTestSupport.cli(dir, "--target", "py", "--out", File(dir, "out-py").absolutePath).exitCode)
        assertTrue(File(dir, "out-py/src/app/main_kira.py").readText().contains("def _main():\n    return 7\n"))
    }

    /** K4: kira:os's py-only calls bind on py and are refused on C++ by name, with the stdlib's own reason. */
    @Test
    fun kiraOsPyOnlyCallsAreRefusedOnCppByName() {
        val dir = File(PyTestSupport.repoRoot, "build/tmp/unsupported-os").apply { deleteRecursively(); mkdirs() }
        val stdlib = File(PyTestSupport.repoRoot, "kira").absolutePath.replace('\\', '/')
        File(dir, "kira.yaml").writeText("project:\n  name: unsupportedos\nsrcDir: src\nbuild:\n  target: cpp\ndependencies:\n  kira_stdlib:\n    path: $stdlib\n")
        File(dir, "src/app/main.kira").apply { parentFile.mkdirs() }.writeText(
            "module \"app:main\"\n\nuse \"kira:os\"\n\nfx main: () Int32 {\n    d: Maybe<Str> = tempDir(\"x\")\n    p: Str = pythonExecutable()\n    return runInherit([p])\n}\n"
        )
        val cpp = PyTestSupport.cli(dir, "--target", "cpp", "--out", File(dir, "out-cpp").absolutePath)
        assertEquals(1, cpp.exitCode, cpp.all)
        listOf(
            "'tempDir' is not supported on the C++ target: py only for now: tempfile.mkdtemp has no C++ half yet",
            "'runInherit' is not supported on the C++ target: py only for now: subprocess.run has no C++ half yet",
            "'pythonExecutable' is not supported on the C++ target: the interpreter that runs a py program; a C++ program is no script",
        ).forEach { assertTrue(cpp.all.contains("error: cpp.unsupported: $it"), cpp.all) }
        assertEquals(0, PyTestSupport.cli(dir, "--target", "py", "--out", File(dir, "out-py").absolutePath).exitCode)
    }
}
