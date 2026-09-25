package net.exoad.kira

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.backend.codegen.c.KiraCCodeGenerator
import net.exoad.kira.compiler.backend.codegen.js.KiraJSCodeGenerator
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraSourceParsers
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A module's own declaration shadows an ambient `kira:*` magic name (the
 * typer's scope order, design 3.2: module members, then `use`d modules'
 * `pub` members, then the ambient stdlib), so a user `class Ref` or
 * `fx parseInt64` must be emitted and called.
 *
 * Both backends used to skip any declaration whose *name* was in the magic
 * set, whatever source it came from: the program then referenced a `Ref`
 * and a `parseInt64` that nothing defined, the CLI exited 0, and the C
 * compiler or node failed later. Every magic name the stdlib adds widens
 * that hole, so this pins the fix in both backends.
 *
 * The call side follows the same scope order, and only that order. A name
 * the caller's module declares, or a `pub` one of a module it `use`s, is
 * called as itself; a name only some other module declares is out of scope
 * and stays the stdlib's. The first fix of the call side used a unit-wide
 * set instead, so a private `min` anywhere in the program captured every
 * `min` call, including the one inside the stdlib's own `clamp` body, and
 * `clamp(5.0, 0.0, 1.0)` printed 5. The cases below use `min`, which the C
 * table renames to `fmin`, so a call that leaks through the table is
 * visible in the C text as well as in the JS one.
 *
 * The C program for the `ceil` case is emitted correctly but is not run
 * here: the C backend emits user functions under their Kira names, so a
 * user `ceil` collides with libc's, which gcc and clang treat as a builtin
 * (undefined behaviour: gcc 13.2 printed 2 and clang printed 0e+00 from a
 * `ceil` that returns 101.5). That is the C backend's unmangled symbols, a
 * separate defect a user `fx strlen` has too. `min` has no libc symbol, so
 * those programs run under gcc; the Windows SDK's `stdlib.h` makes it a
 * macro, which cProgramsRun explains.
 */
class UserDeclarationShadowsMagicNameTest {
    private val moduleUri = "test:shadow.magic"
    private val source = TestCompileSupport.wrapModule(
        moduleUri,
        """
        class Ref {
            require pub id: Int32
        }

        fx parseInt64: (x: Int32) Int32 {
            return x + 1
        }

        fx main: () Void {
            r: Ref = Ref { 4 }
            trace(r.id)
            trace(parseInt64(3))
            trace(parseInt64(9))
        }
        """
    )
    private val expectedLines = listOf("4", "4", "10")

    private val mathNameUri = "test:shadow.mathname"
    private val mathNameSource = TestCompileSupport.wrapModule(
        mathNameUri,
        """
        fx ceil: (x: Float64) Float64 {
            return x + 100.0
        }

        fx main: () Void {
            trace(ceil(1.5))
        }
        """
    )

    /** The user's `min` is Int32 and returns its first argument; the stdlib's is fmin / Math.min. */
    private val renamedUri = "test:shadow.renamed"
    private val renamedSource = TestCompileSupport.wrapModule(
        renamedUri,
        """
        fx min: (a: Int32, b: Int32) Int32 {
            return a
        }

        fx main: () Void {
            v: Int32 = min(7, 2)
            trace(v)
        }
        """
    )

    /**
     * The user's `min` is never called by user code. `clamp` is written in
     * Kira inside `kira:math`, whose own `min` is the magic one, so clamp
     * must keep calling fmin / Math.min and print 1, not the user's 5.
     */
    private val stdlibBodyUri = "test:shadow.stdlibbody"
    private val stdlibBodySource = TestCompileSupport.wrapModule(
        stdlibBodyUri,
        """
        fx min: (a: Int32, b: Int32) Int32 {
            return a
        }

        fx main: () Void {
            v: Float64 = clamp(5.0, 0.0, 1.0)
            trace(v)
        }
        """
    )

    private val utilUri = "test:shadow.util"
    private val twoModuleMainUri = "test:shadow.twomain"

    /** `test:shadow.util` declares a Float64 `min` that returns 0.0, `pub` or private; main may `use` it. */
    private fun twoModuleUnit(utilIsPub: Boolean, mainUsesUtil: Boolean): CompilationUnit {
        val visibility = if (utilIsPub) "pub " else ""
        val use = if (mainUsesUtil) "use \"$utilUri\"\n" else ""
        return unitOf(
            utilUri to """
                ${visibility}fx min: (a: Float64, b: Float64) Float64 {
                    return 0.0
                }
            """.trimIndent(),
            twoModuleMainUri to """
                $use
                fx main: () Void {
                    v: Float64 = min(1.0, 2.0)
                    trace(v)
                }
            """.trimIndent(),
        )
    }

    @Test
    fun cEmitsTheUsersDeclarations() {
        val generated = TestCompileSupport.transpileSnippetToC(source, TestCompileSupport.logicalPathForModule(moduleUri))
        assertTrue(Regex("struct Ref\\s*\\{").containsMatchIn(generated), generated)
        assertTrue(Regex("Int32 parseInt64\\(Int32 x\\)\\s*\\{").containsMatchIn(generated), generated)
    }

    @Test
    fun jsEmitsTheUsersDeclarations() {
        val generated = TestCompileSupport.transpileSnippetToJS(source, TestCompileSupport.logicalPathForModule(moduleUri))
        assertTrue(Regex("class Ref\\b").containsMatchIn(generated), generated)
        assertTrue(Regex("function parseInt64\\(x\\)").containsMatchIn(generated), generated)
    }

    @Test
    fun cProgramRunsWithTheUsersDeclarations() {
        val compiler = TestCompileSupport.findCCompiler()
        assumeTrue(compiler != null, "No C compiler found on PATH")
        val generated = TestCompileSupport.transpileSnippetToC(source, TestCompileSupport.logicalPathForModule(moduleUri))
        val result = TestCompileSupport.compileAndRunC(generated, compiler!!)
        assertEquals(0, result.compileResult.exitCode, result.compileResult.stderr)
        val run = assertNotNull(result.runResult)
        assertEquals(expectedLines, outputLines(run.stdout), run.stderr)
    }

    @Test
    fun jsProgramRunsWithTheUsersDeclarations() {
        val node = TestCompileSupport.findNode()
        assumeTrue(node != null, "No node found on PATH")
        val generated = TestCompileSupport.transpileSnippetToJS(source, TestCompileSupport.logicalPathForModule(moduleUri))
        val run = TestCompileSupport.runJS(generated, node!!)
        assertEquals(0, run.exitCode, run.stderr)
        assertEquals(expectedLines, outputLines(run.stdout), run.stderr)
    }

    @Test
    fun cCallsTheUsersFunctionOverTheMagicTable() {
        val generated = TestCompileSupport.transpileSnippetToC(mathNameSource, TestCompileSupport.logicalPathForModule(mathNameUri))
        assertTrue(Regex("Float64 ceil\\(Float64 x\\)\\s*\\{").containsMatchIn(generated), generated)
        assertTrue(generated.contains("ceil(1.5)"), generated)
    }

    @Test
    fun jsCallsTheUsersFunctionOverTheMagicTable() {
        val generated = TestCompileSupport.transpileSnippetToJS(mathNameSource, TestCompileSupport.logicalPathForModule(mathNameUri))
        assertTrue(Regex("function ceil\\(x\\)").containsMatchIn(generated), generated)
        assertTrue(generated.contains("ceil(1.5)") && !generated.contains("Math.ceil"), generated)

        val node = TestCompileSupport.findNode()
        assumeTrue(node != null, "No node found on PATH")
        val run = TestCompileSupport.runJS(generated, node!!)
        assertEquals(0, run.exitCode, run.stderr)
        assertEquals(listOf("101.5"), outputLines(run.stdout), run.stderr)
    }

    // The six scope cases below. Every C case asserts on the emitted text,
    // which is where the call-side decision shows (`min(` is the user's,
    // `fmin(` the table's); cProgramsRun then compiles and runs all six.
    // The unit always holds the stdlib's `clamp` body, whose own `fmin(` /
    // `Math.min(` must stay, so the assertions look at the user's call
    // line and never at the whole text.

    @Test
    fun cCallsTheUsersFunctionUnderANameTheTableRenames() {
        val generated = cOf(renamedSource, renamedUri)
        assertTrue(Regex("Int32 min\\(Int32 a, Int32 b\\)\\s*\\{").containsMatchIn(generated), generated)
        assertTrue(generated.contains("Int32 v = min(7, 2);"), generated)
        assertFalse(generated.contains("fmin(7, 2)"), generated)
    }

    @Test
    fun jsCallsTheUsersFunctionUnderANameTheTableRenames() {
        val generated = jsOf(renamedSource, renamedUri)
        assertTrue(Regex("function min\\(a, b\\)").containsMatchIn(generated), generated)
        assertTrue(generated.contains("const v = min(7, 2);"), generated)
        assertFalse(generated.contains("Math.min(7, 2)"), generated)
        runJS(generated, listOf("7"))
    }

    @Test
    fun cStdlibBodiesKeepCallingTheMagicName() {
        val generated = cOf(stdlibBodySource, stdlibBodyUri)
        assertTrue(Regex("Int32 min\\(Int32 a, Int32 b\\)\\s*\\{").containsMatchIn(generated), generated)
        assertTrue(generated.contains("fmax(lo, fmin(value, hi))"), generated)
    }

    @Test
    fun jsStdlibBodiesKeepCallingTheMagicName() {
        val generated = jsOf(stdlibBodySource, stdlibBodyUri)
        assertTrue(Regex("function min\\(a, b\\)").containsMatchIn(generated), generated)
        assertTrue(generated.contains("Math.max(lo, Math.min(value, hi))"), generated)
        runJS(generated, listOf("1"))
    }

    @Test
    fun cAnotherModulesPrivateFunctionIsOutOfScope() {
        val generated = cOf(twoModuleUnit(utilIsPub = false, mainUsesUtil = false))
        assertTrue(generated.contains("Float64 v = fmin(1.0, 2.0);"), generated)
    }

    @Test
    fun jsAnotherModulesPrivateFunctionIsOutOfScope() {
        val generated = jsOf(twoModuleUnit(utilIsPub = false, mainUsesUtil = false))
        assertTrue(generated.contains("const v = Math.min(1.0, 2.0);"), generated)
        runJS(generated, listOf("1"))
    }

    @Test
    fun cAUsedModulesPrivateFunctionIsOutOfScope() {
        val generated = cOf(twoModuleUnit(utilIsPub = false, mainUsesUtil = true))
        assertTrue(generated.contains("Float64 v = fmin(1.0, 2.0);"), generated)
    }

    @Test
    fun jsAUsedModulesPrivateFunctionIsOutOfScope() {
        val generated = jsOf(twoModuleUnit(utilIsPub = false, mainUsesUtil = true))
        assertTrue(generated.contains("const v = Math.min(1.0, 2.0);"), generated)
        runJS(generated, listOf("1"))
    }

    @Test
    fun cAUsedModulesPubFunctionShadowsTheMagicName() {
        val generated = cOf(twoModuleUnit(utilIsPub = true, mainUsesUtil = true))
        assertTrue(generated.contains("Float64 v = min(1.0, 2.0);"), generated)
        assertFalse(generated.contains("fmin(1.0, 2.0)"), generated)
    }

    @Test
    fun jsAUsedModulesPubFunctionShadowsTheMagicName() {
        val generated = jsOf(twoModuleUnit(utilIsPub = true, mainUsesUtil = true))
        assertTrue(generated.contains("const v = min(1.0, 2.0);"), generated)
        assertFalse(generated.contains("Math.min(1.0, 2.0)"), generated)
        runJS(generated, listOf("0"))
    }

    @Test
    fun cAPubFunctionOfAModuleNotUsedIsOutOfScope() {
        val generated = cOf(twoModuleUnit(utilIsPub = true, mainUsesUtil = false))
        assertTrue(generated.contains("Float64 v = fmin(1.0, 2.0);"), generated)
    }

    @Test
    fun jsAPubFunctionOfAModuleNotUsedIsOutOfScope() {
        val generated = jsOf(twoModuleUnit(utilIsPub = true, mainUsesUtil = false))
        assertTrue(generated.contains("const v = Math.min(1.0, 2.0);"), generated)
        runJS(generated, listOf("1"))
    }

    /**
     * Compiles and runs the six C programs above and checks what each
     * prints. They all declare a user `min`, which the Windows SDK's
     * `stdlib.h` defines as a macro, so LLVM clang targeting MSVC cannot
     * even declare it (the C backend's unmangled symbols again); the
     * MSYS2 ucrt64 gcc can. The run therefore takes `$CC` when set, else
     * the first of clang, cc and gcc whose headers leave `min` alone, and
     * is skipped, saying so, when none does. The text assertions above
     * never skip.
     */
    @Test
    fun cProgramsRun() {
        val compiler = cCompilerThatDeclaresMin
        assumeTrue(compiler != null, "No C compiler on PATH whose headers leave a user `min` alone")
        val cases = listOf(
            Triple("renamed", cOf(renamedSource, renamedUri), "7"),
            Triple("stdlib body", cOf(stdlibBodySource, stdlibBodyUri), "1"),
            Triple("private, not used", cOf(twoModuleUnit(utilIsPub = false, mainUsesUtil = false)), "1"),
            Triple("private, used", cOf(twoModuleUnit(utilIsPub = false, mainUsesUtil = true)), "1"),
            Triple("pub, used", cOf(twoModuleUnit(utilIsPub = true, mainUsesUtil = true)), "0"),
            Triple("pub, not used", cOf(twoModuleUnit(utilIsPub = true, mainUsesUtil = false)), "1"),
        )
        cases.forEach { (name, generated, expected) ->
            val result = TestCompileSupport.compileAndRunC(generated, compiler!!)
            assertEquals(0, result.compileResult.exitCode, "$name: ${result.compileResult.stderr}")
            val run = assertNotNull(result.runResult, name)
            assertEquals(listOf(expected), outputLines(run.stdout), "$name: ${run.stderr}")
        }
    }

    private val cCompilerThatDeclaresMin: String? by lazy {
        val candidates = listOfNotNull(TestCompileSupport.findCCompiler()) +
            listOfNotNull(TestCompileSupport.findOnPath("cc"), TestCompileSupport.findOnPath("gcc"))
        val probe = "#include <stdlib.h>\n#include <math.h>\n" +
            "double min(double a, double b) { return a; }\n" +
            "int main(void) { return min(0.0, 1.0) == 0.0 ? 0 : 1; }\n"
        candidates.distinct().firstOrNull { compiler ->
            val result = TestCompileSupport.compileAndRunC(probe, compiler)
            result.compileResult.exitCode == 0 && result.runResult?.exitCode == 0
        }
    }

    private fun cOf(source: String, uri: String): String {
        return TestCompileSupport.transpileSnippetToC(source, TestCompileSupport.logicalPathForModule(uri))
    }

    private fun jsOf(source: String, uri: String): String {
        return TestCompileSupport.transpileSnippetToJS(source, TestCompileSupport.logicalPathForModule(uri))
    }

    private fun cOf(unit: CompilationUnit): String {
        return KiraCCodeGenerator(unit).emitToString()
    }

    private fun jsOf(unit: CompilationUnit): String {
        return KiraJSCodeGenerator(unit).emitToString()
    }

    /** Runs [generated] under node when it is on PATH, and checks its stdout. */
    private fun runJS(generated: String, expected: List<String>) {
        val node = TestCompileSupport.findNode()
        assumeTrue(node != null, "No node found on PATH")
        val run = TestCompileSupport.runJS(generated, node!!)
        assertEquals(0, run.exitCode, run.stderr)
        assertEquals(expected, outputLines(run.stdout), run.stderr)
    }

    /** One compilation unit holding several modules, each `(uri, body)` parsed as its own source. */
    private fun unitOf(vararg modules: Pair<String, String>): CompilationUnit {
        val unit = CompilationUnit()
        modules.forEach { (uri, body) ->
            val processed = KiraPreprocessor(TestCompileSupport.wrapModule(uri, body)).process()
            val path = TestCompileSupport.logicalPathForModule(uri)
            val bare = unit.addSource(path, processed.processedContent, emptyList())
            val tokens = KiraLexer(bare).tokenize()
            val withTokens = unit.addSource(path, bare.content, tokens)
            KiraSourceParsers.from(withTokens).parse()
        }
        return unit
    }

    private fun outputLines(stdout: String): List<String> {
        return stdout.lines().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
    }
}
