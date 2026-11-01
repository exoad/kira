package net.exoad.kira

import net.exoad.kira.compiler.analysis.types.GeneratedTuples
import net.exoad.kira.cpp.support.CppCompileSupport
import net.exoad.kira.cpp.support.CppProfile
import net.exoad.kira.cpp.support.CppToolchain
import net.exoad.kira.cpp.support.CppToolchains
import net.exoad.kira.cpp.support.LocatedToolchain
import net.exoad.kira.py.PyTestSupport
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A tuple of arity 7 and up is generated on use (GeneratedTuples): its magic class, its size and
 * its runtime class exist only when a program names it. Each target runs the CLI in a child JVM
 * on a project that reads the repository's kira/, then runs its output.
 */
class GeneratedTupleTest {

    private fun project(main: String): File {
        val dir = Files.createTempDirectory("kira-generated-tuples").toFile()
        File(dir, "project.kira").writeText(
            """
            PROJECT: Project = Project { } with {
                name = "generatedtuples"
                srcDir = "src"
                target = "cpp"
                types = "strict"
                dependencies = @mapOf("kira_stdlib", "${File("kira").absolutePath.replace('\\', '/')}")
            }
            """.trimIndent() + "\n",
        )
        File(dir, "src").mkdirs()
        File(dir, "src/main.kira").writeText(main)
        return dir
    }

    /** The CLI's exit code and its whole output, for `--target [target] --out [out]`. */
    private fun cli(dir: File, target: String, out: String): Pair<Int, String> {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val process = ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"), "net.exoad.kira.cli.MainKt",
            "--target", target, "--out", File(dir, out).absolutePath,
        ).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    private fun build(dir: File, target: String, out: String): File {
        val (code, output) = cli(dir, target, out)
        assertEquals(0, code, output)
        return File(dir, out)
    }

    private fun runCpp(out: File): String {
        val toolchain = CppToolchains.locate(CppToolchain.GCC)
        assumeTrue(toolchain is LocatedToolchain.Found, "no g++")
        val compiled = CppCompileSupport.compile(
            listOf(File(out, "main.kira.cxx")),
            listOf(out),
            emptyList(),
            toolchain as LocatedToolchain.Found,
            CppProfile.forToolchain(CppToolchain.GCC),
            File(out, "build"),
        )
        assertTrue(compiled.success, compiled.toString())
        val run = CppCompileSupport.run(compiled.exe!!)
        assertEquals(0, run.exitCode, run.stderr)
        return run.stdout.trim()
    }

    private fun runJs(out: File): String {
        val node = TestCompileSupport.findNode()
        assumeTrue(node != null, "no node")
        val run = TestCompileSupport.runJS(File(out, "out.kira.js").readText(), node!!)
        assertEquals(0, run.exitCode, run.stderr)
        return run.stdout.trim()
    }

    @Test
    fun aSevenTupleRunsOnCppJsAndPy() {
        val dir = project(SEVEN_MAIN)
        assertEquals(EXPECTED_SEVEN, runCpp(build(dir, "cpp", "out-cpp")))
        assertEquals(EXPECTED_SEVEN, runJs(build(dir, "js", "out-js")))
        val python = PyTestSupport.python
        assumeTrue(python != null, "no Python")
        val (code, output) = cli(dir, "py", "out-py")
        assertEquals(0, code, output)
        val run = PyTestSupport.run(listOf(python!!, "main_kira.py"), File(dir, "out-py/src"))
        assertEquals(0, run.exitCode, run.stderr)
        assertEquals(EXPECTED_SEVEN, run.stdout.trim())
    }

    /** The C++ struct and the JS class are written for the arity the program names, and nothing else. */
    @Test
    fun theSevenTupleIsEmittedOnlyForTheArityNamed() {
        val dir = project(SEVEN_MAIN)
        val out = build(dir, "cpp", "out-cpp")
        val header = File(out, "kira/tuples_gen.hxx").readText()
        assertTrue(header.contains("struct Tuple7"), header)
        assertFalse(header.contains("Tuple8"), header)
        assertTrue(File(out, "main.kira.hxx").readText().contains("#include \"kira/tuples_gen.hxx\""))
        val js = File(build(dir, "js", "out-js"), "out.kira.js").readText()
        assertTrue(js.contains("class KiraTuple7 {"), js)
        assertFalse(js.contains("KiraTuple8"), js)
        assertTrue(js.contains("size() { return 7; }"), js)
    }

    /** An arity above 10 warns at its first use, and still compiles and runs on C++ and JS. */
    @Test
    fun aTupleAboveTenWarnsAndRunsOnCppAndJs() {
        val dir = project(ELEVEN_MAIN)
        val (code, output) = cli(dir, "cpp", "out-cpp")
        assertEquals(0, code, output)
        assertTrue(output.contains("types.tuple.above-max"), output)
        assertTrue(output.contains("T11 is generated on use"), output)
        assertTrue(output.contains("main.kira:2:"), output)
        assertEquals(EXPECTED_ELEVEN, runCpp(File(dir, "out-cpp")))
        assertEquals(EXPECTED_ELEVEN, runJs(build(dir, "js", "out-js")))
        val header = File(dir, "out-cpp/kira/tuples_gen.hxx").readText()
        assertTrue(header.contains("P11 item11;"), header)
    }

    /** No arity between 7 and 10 warns: the warning is for T11 and up only. */
    @Test
    fun anArityOfTenDoesNotWarn() {
        val dir = project(TEN_MAIN)
        val (code, output) = cli(dir, "js", "out-js")
        assertEquals(0, code, output)
        assertFalse(output.contains("types.tuple.above-max"), output)
    }

    /** A program that names only T0..T6 gets nothing generated: no header, no class, no helper. */
    @Test
    fun aProgramWithOnlyHandWrittenTuplesGeneratesNothing() {
        val dir = project(HAND_WRITTEN_MAIN)
        val out = build(dir, "cpp", "out-cpp")
        assertFalse(File(out, "kira/tuples_gen.hxx").exists())
        assertFalse(File(out, "main.kira.hxx").readText().contains("tuples_gen"))
        assertFalse(File(out, "main.kira.cxx").readText().contains("T7_size"))
        val js = File(build(dir, "js", "out-js"), "out.kira.js").readText()
        assertFalse(js.contains("KiraTuple7"), js)
        val py = File(build(dir, "py", "out-py"), "src/main_kira.py").readText()
        assertFalse(py.contains("T7_size"), py)
    }

    /** The module is parsed only for the arities a unit names, from literals and from types alike. */
    @Test
    fun theGeneratedModuleHoldsOnlyTheArityTheProgramNames() {
        assertEquals(emptySet(), arityOf("main: () Void {\n    t: T2<Int32, Int32> { 1, 2 }\n}\n"))
        assertEquals(setOf(7), arityOf("main: () Void {\n    t: T7<Int32, Int32, Int32, Int32, Int32, Int32, Int32> { 1, 2, 3, 4, 5, 6, 7 }\n}\n"))
        assertEquals(setOf(9), arityOf("pick: (t: T9<Int32, Int32, Int32, Int32, Int32, Int32, Int32, Int32, Int32>) Int32 {\n    9\n}\n"))
        assertEquals(setOf(7, 11), arityOf(SEVEN_MAIN + "\n" + ELEVEN_MAIN.replace("main:", "other:")))
    }

    /** The field and parameter names above the hand-written set: `item<n>` and `P<n>`, the same on every backend. */
    @Test
    fun theNamesAboveTheHandWrittenSetFollowOneRule() {
        assertEquals("seventh", GeneratedTuples.field(7))
        assertEquals("tenth", GeneratedTuples.field(10))
        assertEquals("item11", GeneratedTuples.field(11))
        assertEquals("J", GeneratedTuples.param(10))
        assertEquals("P11", GeneratedTuples.param(11))
    }

    /** C has no tuple representation, so a seven-tuple is refused there, as every tuple is. */
    @Test
    fun cRefusesASevenTuple() {
        val dir = project(SEVEN_MAIN)
        val (code, output) = cli(dir, "c", "out-c")
        assertNotEquals(0, code, output)
        assertTrue(output.contains("no C representation"), output)
    }

    /** The arities the program's sources name above the hand-written set, after the generated module is parsed. */
    private fun arityOf(source: String): Set<Int> {
        val unit = TestCompileSupport.compileSnippet(source, "main.kira").compilationUnit
        val generated = GeneratedTuples.ensure(unit).arities
        val declared = unit.allSources().filter { it.moduleName == GeneratedTuples.MODULE_URI }
        assertEquals(generated.isEmpty(), declared.isEmpty(), "the module is parsed exactly when a tuple is generated")
        return generated
    }

    private companion object {
        const val EXPECTED_SEVEN = "76"
        const val EXPECTED_ELEVEN = "1111"

        val SEVEN_MAIN = """pick: (t: T7<Int32, Int32, Int32, Int32, Int32, Int32, Int32>) Int32 {
    t.sixth
}

main: () Void {
    t: T7<Int32, Int32, Int32, Int32, Int32, Int32, Int32> { 1, 2, 3, 4, 5, 6, 7 }
    print(t.size())
    print(pick(t))
}
"""

        val ELEVEN_MAIN = """main: () Void {
    t: T11<Int32, Int32, Int32, Int32, Int32, Int32, Int32, Int32, Int32, Int32, Int32> { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11 }
    print(t.size())
    print(t.item11)
}
"""

        val TEN_MAIN = """main: () Void {
    t: T10<Int32, Int32, Int32, Int32, Int32, Int32, Int32, Int32, Int32, Int32> { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 }
    print(t.size())
}
"""

        val HAND_WRITTEN_MAIN = """main: () Void {
    t: T6<Int32, Int32, Int32, Int32, Int32, Int32> { 1, 2, 3, 4, 5, 6 }
    print(t.size())
}
"""
    }
}
