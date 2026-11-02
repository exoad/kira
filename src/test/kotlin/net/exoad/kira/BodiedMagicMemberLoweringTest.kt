package net.exoad.kira

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
import kotlin.test.assertTrue

/**
 * A member with a body on a stdlib magic type is lowered on the py, JS and C++ targets when they have
 * no binding for it: the body is a helper `Str_startsTwice(self, prefix)`, and a bare call of another
 * member in that body is a call on `self`; on C++ a generic owner's helper is a template, one per
 * instantiation. C refuses the call (its lowering waits). The stdlib is a copy of kira/ with the
 * members added, so the program compiles against it; each target runs the CLI in a child JVM.
 */
class BodiedMagicMemberLoweringTest {

    private fun project(main: String = MAIN, members: String = MEMBERS, into: Into = STR): File {
        val dir = Files.createTempDirectory("kira-bodied-lowering").toFile()
        File(dir, "project.kira").writeText(
            """
            PROJECT: Project = Project { } with {
                name = "bodiedlowering"
                srcDir = "src"
                target = "c"
                types = "strict"
                dependencies = @mapOf("kira_stdlib", "${stdlib(members, into).absolutePath.replace('\\', '/')}")
            }
            """.trimIndent() + "\n",
        )
        File(dir, "src").mkdirs()
        File(dir, "src/main.kira").writeText(main)
        return dir
    }

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

    /**
     * A binding wins over a body: a tuple's size has a binding on the C++ and py targets, and its
     * runtime class has the member on JS, so none of the three writes a helper for it.
     */
    @Test
    fun aBindingOrARuntimeMemberWinsOverTheBody() {
        val dir = project(TUPLE_MAIN)
        val cpp = File(build(dir, "cpp", "out-cpp"), "main.kira.cxx").readText()
        assertTrue(cpp.contains("static_cast<std::int32_t>(2)"), cpp)
        assertFalse(cpp.contains("T2_size"), cpp)
        val py = File(build(dir, "py", "out-py"), "src/main_kira.py").readText()
        assertFalse(py.contains("T2_size"), py)
        val js = File(build(dir, "js", "out-js"), "out.kira.js").readText()
        assertTrue(js.contains(".size()"), js)
        assertFalse(js.contains("T2_size"), js)
    }

    /**
     * A helper the program calls from several places is written once per output unit: a Python or
     * JS redefinition is no error, so the count is what shows it. The C++ header has the prototype
     * and the definition, two lines.
     */
    @Test
    fun aHelperIsWrittenOncePerOutputUnit() {
        val dir = project()
        val js = File(build(dir, "js", "out-js"), "out.kira.js").readText()
        assertEquals(1, countOf(js, "function Str_startsTwice("), js)
        val py = File(build(dir, "py", "out-py"), "src/main_kira.py").readText()
        assertEquals(1, countOf(py, "def _k_kira_core_Str_startsTwice("), py)
        val hxx = File(build(dir, "cpp", "out-cpp"), "main.kira.hxx").readText()
        assertEquals(2, countOf(hxx, "inline bool Str_startsTwice("), hxx)
    }

    /** C lowers no body of a magic type yet: the call is refused at its line, never a call of a missing function. */
    @Test
    fun cRefusesTheCall() {
        val (code, output) = cli(project(), "c", "out-c")
        assertEquals(1, code, output)
        assertTrue(output.contains("a method written in Kira on the magic type 'Str' ('startsTwice') is not supported by the C backend"), output)
    }

    /**
     * A generic owner's helper is one C++ template over its type parameters, and each call names the
     * receiver's type arguments, so two instantiations get their own: one helper keyed by its name over
     * the first receiver's type made g++ refuse the second call (`T7_size` on a Str tuple, measured).
     */
    @Test
    fun aGenericOwnersHelperIsATemplateOnePerInstantiation() {
        val out = build(project(main = GENERIC_MAIN, members = GENERIC_MEMBERS, into = T2), "cpp", "out-cpp")
        val hxx = File(out, "main.kira.hxx").readText()
        assertEquals(2, countOf(hxx, "template<class A, class B> inline A T2_pick("), hxx)
        val cxx = File(out, "main.kira.cxx").readText()
        assertTrue(cxx.contains("T2_pick<std::int32_t, bool>(") && cxx.contains("T2_pick<kira::Str, kira::Str>("), cxx)
        assertEquals("1x", runCpp(out))
    }

    private fun countOf(text: String, needle: String): Int = text.split(needle).size - 1

    /** The C++ program's output: main.kira.cxx built with the output's headers, then run. */
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

    @Test
    fun jsLowersTheBodiesAndTheProgramRuns() {
        val node = TestCompileSupport.findNode()
        assumeTrue(node != null, "no node")
        val out = build(project(), "js", "out-js")
        val run = TestCompileSupport.runJS(File(out, "out.kira.js").readText(), node!!)
        assertEquals(0, run.exitCode, run.stderr)
        assertEquals(EXPECTED, run.stdout.trim())
    }

    @Test
    fun pyLowersTheBodiesAndTheProgramRuns() {
        val python = PyTestSupport.python
        assumeTrue(python != null, "no Python")
        val dir = project()
        val (code, output) = cli(dir, "py", "out-py")
        assertEquals(0, code, output)
        val run = PyTestSupport.run(listOf(python!!, "main_kira.py"), File(dir, "out-py/src"))
        assertEquals(0, run.exitCode, run.stderr)
        assertEquals(EXPECTED, run.stdout.trim())
    }

    @Test
    fun cppLowersTheBodiesAndTheProgramRuns() {
        val out = build(project(), "cpp", "out-cpp")
        assertEquals(EXPECTED, runCpp(out))
        // The bare calls in a body name `self`; a braced `{self}` left unexpanded would still compile.
        val hxx = File(out, "main.kira.hxx").readText()
        assertFalse(hxx.contains("{self}"), hxx)
    }

    /**
     * `this` in a body is the receiver: `self` on C++. JS and py refuse `this` in a body, as they
     * refuse it everywhere, and C refuses the call, so this runs the target that lowers it.
     */
    @Test
    fun thisInABodyIsTheReceiverOnCpp() {
        val dir = project(main = THIS_MAIN, members = THIS_MEMBERS)
        assertEquals("1", runCpp(build(dir, "cpp", "out-cpp")))
    }

    /** Where members are added in the stdlib copy: a file and the declaration line they follow. */
    class Into(val file: String, val anchor: String)

    companion object {
        private val STR = Into("core.kira", "pub @_magic Str: Equatable<Str>, Hashable {\n")
        private val T2 = Into("tuples.kira", "pub @_magic T2<A, B>: Tuple<T2<A, B>> {\n")

        /** `startsTwice` and `startsThrice` are bodies that call the members of Str, bare, and each other. */
        private const val MEMBERS = """    pub startsTwice: (prefix: Str) Bool {
        startsWith(prefix) && startsWith(prefix)
    }

    pub startsThrice: (prefix: Str) Bool {
        startsTwice(prefix) && startsWith(prefix)
    }

"""

        /** Prints `yes`, then the three Bool results as 1 or 0: `yes011`. */
        const val EXPECTED = "yes011"

        private const val THIS_MEMBERS = """    pub viaThis: (prefix: Str) Bool {
        this.startsWith(prefix)
    }

"""

        private const val THIS_MAIN = """main: () Void {
    print("hello".viaThis("he"))
}
"""

        /** A member of the generic T2 whose body reads a field of the type parameter A. */
        private const val GENERIC_MEMBERS = """    pub pick: (other: A) A {
        first
    }

"""

        /** Two instantiations of T2 call `pick`: prints `1x`. */
        private const val GENERIC_MAIN = """main: () Void {
    a: T2<Int32, Bool> { 1, true }
    b: T2<Str, Str> { "x", "y" }
    print(a.pick(5))
    print(b.pick("z"))
}
"""

        private const val TUPLE_MAIN = """main: () Void {
    t: T2<Int32, Int32> { 1, 2 }
    print(t.size())
}
"""

        private const val MAIN = """main: () Void {
    s: Str = "hello"
    if s.startsTwice("he") {
        print("yes")
    }
    print(s.startsTwice("lo"))
    print(s.startsThrice("he"))
    print("hello".startsThrice("hel"))
}
"""

        private val stdlibCopies = mutableMapOf<String, File>()

        /**
         * A copy of kira/ with [members] added after [into]'s anchor, made once per distinct pair. A
         * checkout may have CRLF line ends (core.autocrlf on Windows): the anchor and the members take the file's.
         */
        private fun stdlib(members: String, into: Into): File = stdlibCopies.getOrPut(into.file + "\u0000" + members) {
            val root = Files.createTempDirectory("kira-bodied-stdlib").toFile()
            val kira = File(root, "kira")
            File("kira").copyRecursively(kira)
            val file = File(kira, into.file)
            val text = file.readText()
            val nl = if (text.contains("\r\n")) "\r\n" else "\n"
            val anchor = into.anchor.replace("\n", nl)
            check(text.split(anchor).size == 2) { "the declaration '${into.anchor.trim()}' moved in ${into.file}: update its Into" }
            file.writeText(text.replace(anchor, anchor + members.replace("\n", nl)))
            kira
        }
    }
}
