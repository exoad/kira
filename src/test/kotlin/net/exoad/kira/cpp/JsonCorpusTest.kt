package net.exoad.kira.cpp

import net.exoad.kira.cpp.support.CppCompileSupport
import net.exoad.kira.cpp.support.CppProfile
import net.exoad.kira.cpp.support.CppToolchain
import net.exoad.kira.cpp.support.CppToolchains
import net.exoad.kira.py.PyTestSupport
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * kira/cpp/tests/json_corpus.cxx, built by each host toolchain, answers json_corpus.py's corpus with the
 * py target's bytes (D65, D66). Skipped without Python (set KIRA_PYTHON).
 */
class JsonCorpusTest {
    private val tests = File("kira/cpp/tests")

    @TestFactory
    fun corpus(): List<DynamicTest> = listOf(CppToolchain.GCC, CppToolchain.CLANG, CppToolchain.MSVC).map { toolchain ->
        DynamicTest.dynamicTest("json_corpus [${toolchain.id}]") { run(toolchain) }
    }

    private fun run(toolchain: CppToolchain) {
        assumeTrue(CppToolchains.isEnabled(toolchain), "toolchain '${toolchain.id}' is disabled by KIRA_TOOLCHAINS")
        val python = PyTestSupport.python
        assumeTrue(python != null, "no Python on PATH (set KIRA_PYTHON)")
        val located = CppToolchains.requireOrSkip(toolchain)
        val out = File("build/tmp/json-corpus/${toolchain.id}").absoluteFile
        out.deleteRecursively()
        out.mkdirs()
        val gen = PyTestSupport.run(listOf(python!!, File(tests, "json_corpus.py").absolutePath, "gen", out.path), out)
        assertEquals(0, gen.exitCode, "json_corpus.py gen failed:\n${gen.all}")
        val built = CppCompileSupport.compile(
            sources = listOf(File(tests, "json_corpus.cxx")),
            includeDirs = listOf(File("kira/cpp")),
            defines = emptyList(),
            toolchain = located,
            profile = CppProfile.HOSTED,
            outDir = File(out, "bin"),
            exeName = "json_corpus",
        )
        assertTrue(built.success, "json_corpus.cxx did not build under ${toolchain.id}\n${built.describe()}")
        val ran = CppCompileSupport.run(
            built.exe!!, workingDir = out, timeoutSeconds = 300, extraPathDirs = listOfNotNull(located.binDir),
            args = listOf(File(out, "corpus.in").path, File(out, "corpus.got").path),
        )
        assertEquals(0, ran.exitCode, "json_corpus exited ${ran.exitCode} under ${toolchain.id}:\n${ran.stdout}\n${ran.stderr}")
        val check = PyTestSupport.run(listOf(python, File(tests, "json_corpus.py").absolutePath, "check", out.path), out)
        println("${toolchain.id}: ${check.stdout.trim()}")
        assertEquals(0, check.exitCode, "kira::json and the py target answer differently under ${toolchain.id}:\n${check.all}")
    }
}
