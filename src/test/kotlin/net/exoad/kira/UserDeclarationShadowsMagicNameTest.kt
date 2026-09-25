package net.exoad.kira

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.backend.targets.GeneratedProvider
import net.exoad.kira.compiler.backend.codegen.c.KiraCCodeGenerator
import net.exoad.kira.compiler.backend.codegen.js.KiraJSCodeGenerator
import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraSourceParsers
import net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
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
 * In C the scope rule alone is not enough, because C has one global
 * namespace: a user `fx floor` defined as `floor` is the `floor` every
 * other module's `floor(2.7)` reaches (a private one in `app:util`
 * captured `app:main`'s call, so C printed 0 where JS printed 2), it
 * redefines libc's (undefined behaviour: gcc 13.2 printed 2 and clang
 * 0e+00 from a `ceil` that returns 101.5), and the minifier renames every
 * `floor` token with it. So a user function whose Kira name is a C symbol
 * the magic table lowers to (`floor`, `sqrt`, `ceil`, `fmin`, ...) is
 * emitted and called as `floor_user`. So is one whose Kira name is a magic
 * name the table lowers (`abs`, `min`, `max`, `assert`): the table's symbol
 * differs (`fabs`, `fmin`), but the prelude's `<stdlib.h>` declares `int
 * abs(int)` itself, so a Float64 `fx abs` defined as `abs` did not compile
 * ("conflicting types for abs") and an Int32 one replaced libc's (gcc and
 * clang printed 5 from an `abs` that returns x + 100; node printed 95);
 * the Windows SDK makes `min` and `max` macros, so clang targeting MSVC
 * could not declare a user `min` at all; and `assert` is C's one-argument
 * macro. Renamed, every case compiles and runs on every compiler.
 *
 * `assert` is also the one name the JS backend used to special-case before
 * asking the scope table, so a user `fx assert` was called in C and
 * replaced by the runtime's kira_assert in JS. Both backends now ask the
 * table first.
 *
 * The last cases pin where the scope is taken from: a class method body
 * (emitted with the struct bodies, before the final walk), a generic
 * specialization (emitted from the template's module, not the caller's)
 * and a top-level constant (a statement of the source, not a function),
 * including one in a source walked after another module's, whose scope
 * would otherwise linger.
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

    /**
     * `test:shadow.libc` declares a Float64 `floor` that returns 0.0, `pub`
     * or private, and never calls it; main may `use` it and traces
     * `floor(2.7)`. libc's floor gives 2, the user's 0.
     */
    private val libcUri = "test:shadow.libc"
    private val libcMainUri = "test:shadow.libcmain"

    private fun libcNameUnit(utilIsPub: Boolean, mainUsesUtil: Boolean): CompilationUnit {
        val visibility = if (utilIsPub) "pub " else ""
        val use = if (mainUsesUtil) "use \"$libcUri\"\n" else ""
        return unitOf(
            libcUri to """
                ${visibility}fx floor: (x: Float64) Float64 {
                    return 0.0
                }
            """.trimIndent(),
            libcMainUri to """
                $use
                fx main: () Void {
                    v: Float64 = floor(2.7)
                    trace(v)
                }
            """.trimIndent(),
        )
    }

    /** The user's own `sqrt`, called from its own module: 16 becomes 116, not 4. */
    private val ownLibcNameUri = "test:shadow.ownlibc"
    private val ownLibcNameSource = TestCompileSupport.wrapModule(
        ownLibcNameUri,
        """
        fx sqrt: (x: Float64) Float64 {
            return x + 100.0
        }

        fx main: () Void {
            trace(sqrt(16.0))
        }
        """
    )

    /**
     * `test:shadow.scopeutil` declares a private Float64 `max` returning 0.0
     * and a `pub` generic `pick` whose body calls it; `test:shadow.scopemain`
     * `use`s it, and holds a class whose method calls `min`, which the util
     * module exports as a `pub` Float64 function returning 0.0. In main
     * itself `max` is out of scope (fmax, 2) and `min` is in scope (0).
     */
    private val scopeUtilUri = "test:shadow.scopeutil"
    private val scopeMainUri = "test:shadow.scopemain"

    private fun scopeUnit(withClass: Boolean = true): CompilationUnit {
        val classDecl = if (!withClass) "" else """
            class Alpha {
                require pub id: Int32

                fx run: () Float64 {
                    return min(1.0, 2.0)
                }
            }
        """.trimIndent()
        val classUse = if (!withClass) "" else "a: Alpha = Alpha { 1 }\n    trace(a.run())"
        return unitOf(
            scopeUtilUri to """
                fx max: (a: Float64, b: Float64) Float64 {
                    return 0.0
                }

                pub fx min: (a: Float64, b: Float64) Float64 {
                    return 0.0
                }

                pub fx pick<T>: (a: Float64, b: Float64) Float64 {
                    return max(a, b)
                }
            """.trimIndent(),
            scopeMainUri to """
                use "$scopeUtilUri"

                $classDecl

                fx main: () Void {
                    picked: Float64 = pick<Int32>(1.0, 2.0)
                    trace(picked)
                    larger: Float64 = max(1.0, 2.0)
                    trace(larger)
                    $classUse
                }
            """.trimIndent(),
        )
    }

    /** The same two modules without the class: the C program then compiles (see cProgramsRun). */
    private fun scopeUnitWithoutTheClass(): CompilationUnit {
        return scopeUnit(withClass = false)
    }

    /**
     * A top-level constant of main initialised by `min`, which
     * `test:shadow.constutil` exports; main `use`s it. The constant is a
     * statement of the source, not a declaration a function body sits in,
     * so its scope comes from the source being walked.
     */
    private val constUtilUri = "test:shadow.constutil"
    private val constMainUri = "test:shadow.constmain"

    private fun topLevelConstantUnit(): CompilationUnit {
        return unitOf(
            constUtilUri to """
                pub fx min: (a: Float64, b: Float64) Float64 {
                    return 0.0
                }
            """.trimIndent(),
            constMainUri to """
                use "$constUtilUri"

                LOWEST: Float64 = min(1.0, 2.0)

                fx main: () Void {
                    trace(LOWEST)
                }
            """.trimIndent(),
        )
    }

    /**
     * The same constant in a main that does not `use` the util module, whose
     * `min` is private. The util source is walked first, so its scope is the
     * one in force when main's walk begins: the walk must reset the scope
     * per source, or main's constant sees util's `min` and prints 0 instead
     * of fmin's 1.
     */
    private fun laterSourceConstantUnit(): CompilationUnit {
        return unitOf(
            constUtilUri to """
                fx min: (a: Float64, b: Float64) Float64 {
                    return 0.0
                }
            """.trimIndent(),
            constMainUri to """
                LOWEST: Float64 = min(1.0, 2.0)

                fx main: () Void {
                    trace(LOWEST)
                }
            """.trimIndent(),
        )
    }

    /**
     * A user Int32 `abs` returning x + 100 (libc's returns 5 from -5), a
     * user Float64 `abs` (which could not even be declared beside libc's
     * `int abs(int)`), and a user `assert` that traces its message instead
     * of failing.
     */
    private val intAbsUri = "test:shadow.intabs"
    private val intAbsSource = TestCompileSupport.wrapModule(
        intAbsUri,
        """
        fx abs: (x: Int32) Int32 {
            return x + 100
        }

        fx main: () Void {
            v: Int32 = abs(-5)
            trace(v)
        }
        """
    )

    private val floatAbsUri = "test:shadow.floatabs"
    private val floatAbsSource = TestCompileSupport.wrapModule(
        floatAbsUri,
        """
        fx abs: (x: Float64) Float64 {
            return x + 100.0
        }

        fx main: () Void {
            v: Float64 = abs(-2.5)
            trace(v)
        }
        """
    )

    private val assertUri = "test:shadow.assert"
    private val assertSource = TestCompileSupport.wrapModule(
        assertUri,
        """
        fx assert: (c: Bool, m: Str) Void {
            trace(m)
        }

        fx main: () Void {
            assert(false, "mine")
            trace(1)
        }
        """
    )

    /** `test:shadow.assertutil` declares a private `assert`; main never `use`s it, so its `assert(true, ...)` is the runtime's. */
    private val assertUtilUri = "test:shadow.assertutil"
    private val assertMainUri = "test:shadow.assertmain"

    private fun assertOutOfScopeUnit(): CompilationUnit {
        return unitOf(
            assertUtilUri to """
                fx assert: (c: Bool, m: Str) Void {
                    trace(m)
                }
            """.trimIndent(),
            assertMainUri to """
                fx main: () Void {
                    assert(true, "fine")
                    trace(1)
                }
            """.trimIndent(),
        )
    }

    @Test
    fun cCallsTheUsersFunctionOverTheMagicTable() {
        val generated = TestCompileSupport.transpileSnippetToC(mathNameSource, TestCompileSupport.logicalPathForModule(mathNameUri))
        assertTrue(Regex("Float64 ceil_user\\(Float64 x\\)\\s*\\{").containsMatchIn(generated), generated)
        assertTrue(generated.contains("ceil_user(1.5)"), generated)
        assertFalse(Regex("\\bceil\\(").containsMatchIn(generated), generated)

        val compiler = TestCompileSupport.findCCompiler()
        assumeTrue(compiler != null, "No C compiler found on PATH")
        val result = TestCompileSupport.compileAndRunC(generated, compiler!!)
        assertEquals(0, result.compileResult.exitCode, result.compileResult.stderr)
        val run = assertNotNull(result.runResult)
        assertEquals(listOf("101.5"), outputLines(run.stdout), run.stderr)
    }

    @Test
    fun cDefinesAUserFunctionNamedLikeALibcSymbolUnderItsOwnName() {
        val generated = cOf(libcNameUnit(utilIsPub = false, mainUsesUtil = false))
        assertTrue(Regex("Float64 floor_user\\(Float64 x\\)\\s*\\{").containsMatchIn(generated), generated)
        assertFalse(Regex("Float64 floor\\(").containsMatchIn(generated), generated)
        assertTrue(generated.contains("Float64 v = floor(2.7);"), generated)
    }

    @Test
    fun cAUsedModulesPubLibcNamedFunctionIsCalledByItsCName() {
        val generated = cOf(libcNameUnit(utilIsPub = true, mainUsesUtil = true))
        assertTrue(generated.contains("Float64 v = floor_user(2.7);"), generated)
        assertFalse(Regex("\\bfloor\\(").containsMatchIn(generated), generated)
    }

    @Test
    fun cCallsTheUsersOwnLibcNamedFunction() {
        val generated = cOf(ownLibcNameSource, ownLibcNameUri)
        assertTrue(Regex("Float64 sqrt_user\\(Float64 x\\)\\s*\\{").containsMatchIn(generated), generated)
        assertTrue(generated.contains("sqrt_user(16.0)"), generated)
        assertFalse(Regex("\\bsqrt\\(").containsMatchIn(generated), generated)
    }

    /**
     * The CLI's default output is minified, and the minifier renames every
     * token of every user symbol: when the user's `floor` was emitted as
     * `floor`, main's libc `floor(2.7)` was renamed with it and printed 0.
     * The user symbol is `floor_user` now, and the libc call survives.
     */
    @Test
    fun cMinifiedOutputKeepsTheLibcCall() {
        val previous = GeneratedProvider.minifyOutput
        GeneratedProvider.minifyOutput = true
        val file = File.createTempFile("kira-shadow-min", ".c")
        try {
            KiraCCodeGenerator(libcNameUnit(utilIsPub = false, mainUsesUtil = false)).generate(file.path)
            val user = file.readText().substringAfterLast("#endif /* KIRA_RUNTIME_H */")
            assertTrue(Regex("[=(,;]floor\\(2\\.7\\)").containsMatchIn(user), user)
            assertFalse(Regex("Float64 floor\\(").containsMatchIn(user), user)
            assertFalse(user.contains("floor_user"), user)
        } finally {
            GeneratedProvider.minifyOutput = previous
            file.delete()
        }
    }

    @Test
    fun jsAnotherModulesPrivateLibcNamedFunctionIsOutOfScope() {
        val generated = jsOf(libcNameUnit(utilIsPub = false, mainUsesUtil = false))
        assertTrue(generated.contains("const v = Math.floor(2.7);"), generated)
        runJS(generated, listOf("2"))
    }

    @Test
    fun jsAUsedModulesPubLibcNamedFunctionShadowsTheMagicName() {
        val generated = jsOf(libcNameUnit(utilIsPub = true, mainUsesUtil = true))
        assertTrue(generated.contains("const v = floor(2.7);"), generated)
        runJS(generated, listOf("0"))
    }

    @Test
    fun cAClassMethodResolvesInItsModulesScope() {
        val generated = cOf(scopeUnit())
        assertTrue(Regex("Alpha_run\\([^)]*\\)\\s*\\{[^}]*return min_user\\(1\\.0, 2\\.0\\);").containsMatchIn(generated), generated)
        assertFalse(generated.contains("fmin(1.0, 2.0)"), generated)
    }

    @Test
    fun cAGenericSpecializationResolvesInItsTemplatesModule() {
        val generated = cOf(scopeUnit())
        assertTrue(Regex("pick_Int32\\([^)]*\\)\\s*\\{[^}]*return max_user\\(a, b\\);").containsMatchIn(generated), generated)
        assertTrue(generated.contains("Float64 larger = fmax(1.0, 2.0);"), generated)
    }

    @Test
    fun jsClassMethodsAndSpecializationsResolveInTheirModulesScope() {
        val generated = jsOf(scopeUnit())
        assertTrue(generated.contains("return min(1.0, 2.0);"), generated)
        assertTrue(generated.contains("return max(a, b);"), generated)
        assertTrue(generated.contains("const larger = Math.max(1.0, 2.0);"), generated)
        runJS(generated, listOf("0", "2", "0"))
    }

    @Test
    fun cATopLevelConstantResolvesInItsSourcesScope() {
        val generated = cOf(topLevelConstantUnit())
        assertTrue(generated.contains("LOWEST = min_user(1.0, 2.0);"), generated)
        assertFalse(generated.contains("fmin(1.0, 2.0)"), generated)
    }

    @Test
    fun jsATopLevelConstantResolvesInItsSourcesScope() {
        val generated = jsOf(topLevelConstantUnit())
        assertTrue(generated.contains("LOWEST = min(1.0, 2.0);"), generated)
        assertFalse(generated.contains("Math.min(1.0, 2.0)"), generated)
        runJS(generated, listOf("0"))
    }

    @Test
    fun cATopLevelConstantOfALaterSourceDoesNotKeepTheEarlierSourcesScope() {
        val generated = cOf(laterSourceConstantUnit())
        assertTrue(generated.contains("LOWEST = fmin(1.0, 2.0);"), generated)
        assertFalse(generated.contains("min_user(1.0, 2.0)"), generated)
    }

    @Test
    fun jsATopLevelConstantOfALaterSourceDoesNotKeepTheEarlierSourcesScope() {
        val generated = jsOf(laterSourceConstantUnit())
        assertTrue(generated.contains("LOWEST = Math.min(1.0, 2.0);"), generated)
        runJS(generated, listOf("1"))
    }

    @Test
    fun cDefinesAUserAbsUnderItsOwnNameAndCallsIt() {
        val generated = cOf(intAbsSource, intAbsUri)
        assertTrue(Regex("Int32 abs_user\\(Int32 x\\)\\s*\\{").containsMatchIn(generated), generated)
        assertTrue(generated.contains("Int32 v = abs_user(-5);"), generated)
        assertFalse(Regex("\\babs\\(").containsMatchIn(generated), generated)
        val floatGenerated = cOf(floatAbsSource, floatAbsUri)
        assertTrue(Regex("Float64 abs_user\\(Float64 x\\)\\s*\\{").containsMatchIn(floatGenerated), floatGenerated)
        assertTrue(floatGenerated.contains("Float64 v = abs_user(-2.5);"), floatGenerated)
    }

    @Test
    fun jsCallsTheUsersAbs() {
        val generated = jsOf(intAbsSource, intAbsUri)
        assertTrue(generated.contains("const v = abs(-5);"), generated)
        assertFalse(generated.contains("Math.abs"), generated)
        runJS(generated, listOf("95"))
    }

    @Test
    fun cCallsTheUsersAssertOverTheRuntimes() {
        val generated = cOf(assertSource, assertUri)
        assertTrue(Regex("Void assert_user\\(Bool c, Str m\\)\\s*\\{").containsMatchIn(generated), generated)
        assertTrue(generated.contains("assert_user(false, \"mine\")"), generated)
        assertFalse(generated.contains("kira_assert(false"), generated)
    }

    @Test
    fun jsCallsTheUsersAssertOverTheRuntimes() {
        val generated = jsOf(assertSource, assertUri)
        assertTrue(Regex("function assert\\(c, m\\)").containsMatchIn(generated), generated)
        assertTrue(generated.contains("assert(false, \"mine\")"), generated)
        assertFalse(generated.contains("kira_assert(false"), generated)
        runJS(generated, listOf("mine", "1"))
    }

    @Test
    fun cAnotherModulesPrivateAssertIsOutOfScope() {
        val generated = cOf(assertOutOfScopeUnit())
        assertTrue(generated.contains("kira_assert(true, \"fine\")"), generated)
        assertFalse(generated.contains("assert_user(true"), generated)
    }

    @Test
    fun jsAnotherModulesPrivateAssertIsOutOfScope() {
        val generated = jsOf(assertOutOfScopeUnit())
        assertTrue(generated.contains("kira_assert(true, \"fine\")"), generated)
        assertFalse(Regex("(?<!kira_)assert\\(true, \"fine\"\\)").containsMatchIn(generated), generated)
        runJS(generated, listOf("1"))
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
        assertTrue(Regex("Int32 min_user\\(Int32 a, Int32 b\\)\\s*\\{").containsMatchIn(generated), generated)
        assertTrue(generated.contains("Int32 v = min_user(7, 2);"), generated)
        assertFalse(generated.contains("fmin(7, 2)"), generated)
        assertFalse(Regex("\\bmin\\(").containsMatchIn(generated), generated)
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
        assertTrue(Regex("Int32 min_user\\(Int32 a, Int32 b\\)\\s*\\{").containsMatchIn(generated), generated)
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
        assertTrue(generated.contains("Float64 v = min_user(1.0, 2.0);"), generated)
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
     * Compiles and runs the C programs above with the first C compiler on
     * PATH and checks what each prints. The user's `min`, `max`, `abs` and
     * `assert` are `min_user`, `max_user`, ... in C, so the programs
     * compile whatever the compiler's headers make of those names (the
     * Windows SDK's `stdlib.h` makes `min` and `max` macros, and LLVM clang
     * targeting MSVC could not declare a user `min` before the rename). Two
     * programs are text-only in C: the class method, because the C backend
     * emits method bodies before the free-function prototypes, so a method
     * calling a free function of its own module does not compile whatever
     * the function is named; and the top-level constants, because C does
     * not take a call as a file-scope initializer. All run under node.
     */
    @Test
    fun cProgramsRun() {
        val compiler = TestCompileSupport.findCCompiler()
        assumeTrue(compiler != null, "No C compiler found on PATH")
        val cases = listOf(
            Triple("renamed", cOf(renamedSource, renamedUri), listOf("7")),
            Triple("stdlib body", cOf(stdlibBodySource, stdlibBodyUri), listOf("1")),
            Triple("private, not used", cOf(twoModuleUnit(utilIsPub = false, mainUsesUtil = false)), listOf("1")),
            Triple("private, used", cOf(twoModuleUnit(utilIsPub = false, mainUsesUtil = true)), listOf("1")),
            Triple("pub, used", cOf(twoModuleUnit(utilIsPub = true, mainUsesUtil = true)), listOf("0")),
            Triple("pub, not used", cOf(twoModuleUnit(utilIsPub = true, mainUsesUtil = false)), listOf("1")),
            Triple("private floor, not used", cOf(libcNameUnit(utilIsPub = false, mainUsesUtil = false)), listOf("2")),
            Triple("private floor, used", cOf(libcNameUnit(utilIsPub = false, mainUsesUtil = true)), listOf("2")),
            Triple("pub floor, used", cOf(libcNameUnit(utilIsPub = true, mainUsesUtil = true)), listOf("0")),
            Triple("pub floor, not used", cOf(libcNameUnit(utilIsPub = true, mainUsesUtil = false)), listOf("2")),
            Triple("own sqrt", cOf(ownLibcNameSource, ownLibcNameUri), listOf("116")),
            Triple("generic specialization", cOf(scopeUnitWithoutTheClass()), listOf("0", "2")),
            Triple("own Int32 abs", cOf(intAbsSource, intAbsUri), listOf("95")),
            Triple("own Float64 abs", cOf(floatAbsSource, floatAbsUri), listOf("97.5")),
            Triple("own assert", cOf(assertSource, assertUri), listOf("mine", "1")),
            Triple("private assert, not used", cOf(assertOutOfScopeUnit()), listOf("1")),
        )
        cases.forEach { (name, generated, expected) ->
            val result = TestCompileSupport.compileAndRunC(generated, compiler!!)
            assertEquals(0, result.compileResult.exitCode, "$name: ${result.compileResult.stderr}")
            val run = assertNotNull(result.runResult, name)
            assertEquals(expected, outputLines(run.stdout), "$name: ${run.stderr}")
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
