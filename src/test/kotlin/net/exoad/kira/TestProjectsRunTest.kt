package net.exoad.kira

import net.exoad.kira.cpp.support.CppCompileSupport
import net.exoad.kira.cpp.support.CppProfile
import net.exoad.kira.cpp.support.CppToolchain
import net.exoad.kira.cpp.support.CppToolchains
import net.exoad.kira.py.PyTestSupport
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The test projects 01..09 (src/test/resources/test-projects) through the CLI: each compiles to C and JS and prints
 * its expected.txt as a C binary and on node, [PY] on Python too, [CPP] through g++ too; the C and JS runtime
 * preludes are the same in every project. A leg's compile always runs; a missing toolchain skips only its run.
 */
class TestProjectsRunTest {
    companion object {
        val root = File("src/test/resources/test-projects")

        // The py and C++ legs as they were: 02, 03 and 05..08 do not type under those targets' strict rules (TyperDeclTest's dumps).
        val PY = setOf("01-hello", "04-classes")
        val CPP = setOf("01-hello", "09-stdlib")

        const val C_PRELUDE_END = "#endif /* KIRA_RUNTIME_H */"
        const val JS_PRELUDE_END = "// __KIRA_JS_PRELUDE_END__"
    }

    private val cPreludes = LinkedHashMap<String, String>()
    private val jsPreludes = LinkedHashMap<String, String>()

    @TestFactory
    fun projects(): List<DynamicNode> {
        val dirs = root.listFiles { f -> f.isDirectory && f.name.matches(Regex("0[1-9]-.*")) }!!.sortedBy { it.name }
        assertEquals(9, dirs.size, "test-projects/01..09: ${dirs.map { it.name }}")
        val nodes: List<DynamicNode> = dirs.map { dir ->
            val legs = mutableListOf(DynamicTest.dynamicTest("c") { c(dir) }, DynamicTest.dynamicTest("js") { js(dir) })
            if (dir.name in PY) legs += DynamicTest.dynamicTest("py") { py(dir) }
            if (dir.name in CPP) legs += DynamicTest.dynamicTest("cpp") { cpp(dir) }
            DynamicContainer.dynamicContainer(dir.name, legs)
        }
        return nodes + DynamicTest.dynamicTest("one C and one JS prelude in every project") {
            assertEquals(dirs.map { it.name }, cPreludes.keys.toList(), "the C legs that emitted")
            assertEquals(dirs.map { it.name }, jsPreludes.keys.toList(), "the JS legs that emitted")
            assertEquals(1, cPreludes.values.toSet().size, "C preludes differ between projects")
            assertEquals(1, jsPreludes.values.toSet().size, "JS preludes differ between projects")
        }
    }

    private fun c(dir: File) {
        val text = File(emit(dir, "c"), "out.kira.c").readText()
        assertTrue(text.contains(C_PRELUDE_END), "${dir.name}: no prelude end in out.kira.c")
        cPreludes[dir.name] = text.substringBefore(C_PRELUDE_END)
        val cc = TestCompileSupport.findCCompiler()
        assumeTrue(cc != null, "No C compiler on PATH (set CC)")
        val built = TestCompileSupport.compileAndRunC(text, cc!!)
        assertEquals(0, built.compileResult.exitCode, "${dir.name}: cc failed:\n${built.compileResult.stderr}")
        val ran = built.runResult!!
        assertEquals(0, ran.exitCode, "${dir.name}: the C binary exited ${ran.exitCode}:\n${ran.stderr}")
        assertEquals(expected(dir), ran.stdout, "${dir.name}: C stdout")
    }

    private fun js(dir: File) {
        val text = File(emit(dir, "js"), "out.kira.js").readText()
        assertTrue(text.contains(JS_PRELUDE_END), "${dir.name}: no prelude end in out.kira.js")
        jsPreludes[dir.name] = text.substringBefore(JS_PRELUDE_END)
        val node = TestCompileSupport.findNode()
        assumeTrue(node != null, "No node on PATH (set NODE)")
        val ran = TestCompileSupport.runJS(text, node!!)
        assertEquals(0, ran.exitCode, "${dir.name}: node exited ${ran.exitCode}:\n${ran.stderr}")
        assertEquals(expected(dir), ran.stdout, "${dir.name}: JS stdout")
    }

    private fun py(dir: File) {
        val out = emit(dir, "py")
        val mains = out.walkTopDown().filter { it.isFile && it.name.endsWith("_kira.py") && it.readText().contains("\nif __name__ == \"__main__\":\n") }.toList()
        assertEquals(1, mains.size, "${dir.name}: modules with a main: ${mains.map { it.name }}")
        val python = PyTestSupport.python
        assumeTrue(python != null, "no Python on PATH (set KIRA_PYTHON)")
        val ran = PyTestSupport.run(listOf(python!!, mains.single().absolutePath), out)
        assertEquals(0, ran.exitCode, "${dir.name}: Python exited ${ran.exitCode}:\n${ran.stderr}")
        assertEquals(expected(dir), ran.stdout, "${dir.name}: py stdout")
    }

    private fun cpp(dir: File) {
        val out = emit(dir, "cpp")
        val sources = out.walkTopDown().filter { it.isFile && it.extension == "cxx" }.sortedBy { it.path }.toList()
        assertTrue(sources.isNotEmpty(), "${dir.name}: no .cxx under $out")
        val gcc = CppToolchains.requireOrSkip(CppToolchain.GCC)
        val built = CppCompileSupport.compile(
            sources, listOf(File("kira/cpp"), out), emptyList(), gcc, CppProfile.forToolchain(CppToolchain.GCC),
            File(out.parentFile, "cpp-build"),
        )
        assertTrue(built.success, "${dir.name}: g++ failed:\n${built.describe()}")
        val ran = CppCompileSupport.run(built.exe!!, extraPathDirs = listOfNotNull(gcc.binDir))
        assertEquals(0, ran.exitCode, "${dir.name}: the C++ binary exited ${ran.exitCode}:\n${ran.stderr}")
        assertEquals(expected(dir), ran.stdout, "${dir.name}: C++ stdout")
    }

    /** `kira --target [target]` in [dir], its output under build/tmp, never beside the project. */
    private fun emit(dir: File, target: String): File {
        val out = File("build/tmp/test-projects/${dir.name}/$target").absoluteFile
        out.deleteRecursively()
        val r = PyTestSupport.cli(dir, "--target", target, "--out", out.path)
        assertEquals(0, r.exitCode, "kira --target $target failed for ${dir.name}:\n${r.all}")
        return out
    }

    private fun expected(dir: File): String = File(dir, "expected.txt").readText().replace("\r", "")
}
