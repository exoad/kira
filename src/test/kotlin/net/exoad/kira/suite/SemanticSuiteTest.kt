package net.exoad.kira.suite

import net.exoad.kira.TestCompileSupport
import net.exoad.kira.compiler.analysis.semantic.SemanticAnalyzerResults
import net.exoad.kira.compiler.analysis.semantic.SemanticSymbolKind
import net.exoad.kira.compiler.analysis.semantic.SemanticScope
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Semantic analysis coverage: symbol declaration and resolution, scope
 * nesting, module URI validation, duplicate declarations, unknown types,
 * literal/type mismatch checks, visibility, and `use` import resolution.
 */
class SemanticSuiteTest {

    // --- helpers ---------------------------------------------------------

    private fun analyze(
        body: String,
        uri: String = "test:semantic.basic",
        runSemantic: Boolean = true,
    ): SemanticAnalyzerResults {
        val result = TestCompileSupport.compileSnippet(
            source = TestCompileSupport.wrapModule(uri, body),
            logicalPath = TestCompileSupport.logicalPathForModule(uri),
            runSemantic = runSemantic,
        )
        return assertNotNull(result.semanticResults)
    }

    /** Build a multi-source compilation unit so `use` can resolve a real module. */
    private fun analyzeMultiSource(
        sources: List<Pair<String, String>>,
        uri: String = "test:semantic.basic",
    ): SemanticAnalyzerResults {
        val cu = net.exoad.kira.compiler.CompilationUnit()
        for ((filePath, body) in sources) {
            val pre = net.exoad.kira.compiler.frontend.preprocessor.KiraPreprocessor(body)
            val processed = pre.process()
            val ctx = cu.addSource(filePath, processed.processedContent, emptyList())
            val tokens = net.exoad.kira.compiler.frontend.lexer.KiraLexer(ctx).tokenize()
            cu.addSource(filePath, ctx.content, tokens)
            net.exoad.kira.compiler.frontend.parser.LegacyKiraSourceParser(
                cu.getSource(filePath)!!
            ).parse()
        }
        return net.exoad.kira.compiler.analysis.semantic.KiraSemanticAnalyzer(cu).validateAST()
    }

    private fun messages(results: SemanticAnalyzerResults): List<String> =
        results.diagnostics.map { it.message }

    private fun assertHealthy(body: String, uri: String = "test:semantic.basic") {
        val results = analyze(body, uri)
        assertTrue(
            results.isHealthy,
            "expected healthy, got:\n${results.diagnostics.joinToString("\n") { it.message }}"
        )
    }

    private fun assertUnhealthy(body: String, uri: String = "test:semantic.basic"): List<String> {
        val results = analyze(body, uri)
        assertFalse(results.isHealthy, "expected diagnostics for: $body")
        return messages(results)
    }

    // --- healthy programs ----------------------------------------------------

    @Test
    fun healthyEmptyModule() {
        assertHealthy("")
    }

    @Test
    fun healthyVariablesWithBuiltinTypes() {
        assertHealthy(
            """
            a: Int32 = 1
            b: Int64 = 2
            c: Float32 = 1.5
            d: Float64 = 2.5
            e: Bool = true
            f: Str = "hello"
            g: Int32
            """
        )
    }

    @Test
    fun healthyFunctionWithParametersAndReturn() {
        assertHealthy(
            """
            add: (a: Int32, b: Int32) Int32 {
                return a + b
            }
            """
        )
    }

    @Test
    fun healthyClassWithFieldsAndMethods() {
        assertHealthy(
            """
            pub Counter: Any {
                pub value: Int32

                pub increment: () Int32 {
                    return value + 1
                }
            }
            """
        )
    }

    @Test
    fun healthyGenericsWithBounds() {
        assertHealthy(
            """
            pub Box<T>: Any {
                pub value: T
            }

            id<T>: (value: T) T {
                return value
            }
            """
        )
    }

    @Test
    fun healthyTraitAlias() {
        assertHealthy(
            """
            pub Speaker: Any {
                pub speak: () Str;
            }

            pub Int32 as MyInt
            """
        )
    }

    @Test
    fun healthyUseImportBringsPublicTypes() {
        val results = analyzeMultiSource(
            listOf(
                "test/semantic/other.kira" to """""" + "\n" + """
                    pub OtherType: Any { pub n: Int32 = 0 }
                    """,
                "test/semantic/basic.kira" to """""" + "\n" + """
                    test.semantic.other as other
                    x: OtherType = OtherType { }
                    """,
            )
        )
        assertTrue(
            results.isHealthy,
            results.diagnostics.joinToString("\n") { it.message }
        )
    }

    // --- duplicate declarations ------------------------------------------------

    @Test
    fun rejectsDuplicateVariableInSameScope() {
        val msgs = assertUnhealthy(
            """
            x: Int32 = 1
            x: Int32 = 2
            """
        )
        assertTrue(msgs.any { it.contains("already declared in this scope") }, msgs.toString())
    }

    @Test
    fun rejectsDuplicateTypeInModule() {
        // A trait's visitor checks the module scope unconditionally.
        val msgs = assertUnhealthy(
            """
            pub First: Any { pub a: () Int32; }
            pub First: Any { pub b: () Int32; }
            pub Impl: First { }
            """
        )
        assertTrue(msgs.any { it.contains("already declared in the current module") }, msgs.toString())
    }

    @Test
    fun classClassDuplicatesAreCurrentlySilentlyAccepted() {
        // Known analyzer gap, pinned so a fix is visible: visitClassDecl early-
        // returns when the type name already resolves, so a second identical
        // class passes without a diagnostic.
        assertHealthy(
            """
            pub Dup: Any { pub n: Int32 = 0 }
            pub Dup: Any { pub n: Int32 = 0 }
            """
        )
    }

    @Test
    fun rejectsClassAndTraitSharingAName() {
        val msgs = assertUnhealthy(
            """
            pub Shared: Any { pub n: Int32 = 0 }
            pub Shared: Any { pub a: () Int32; }
            pub Impl: Shared { }
            """
        )
        assertTrue(msgs.any { it.contains("already declared in the current module") }, msgs.toString())
    }

    @Test
    fun allowsSameVariableNameInNestedFunctionScope() {
        assertHealthy(
            """
            x: Int32 = 1

            f: (x: Int32) Int32 {
                return x
            }
            """
        )
    }

    // --- unknown symbols ---------------------------------------------------------

    @Test
    fun rejectsUnknownType() {
        val msgs = assertUnhealthy(
            """
            x: MissingType = 1
            """
        )
        assertTrue(msgs.any { it.contains("The type 'MissingType' was not found") }, msgs.toString())
    }

    @Test
    fun unknownFunctionReferenceIsCurrentlyNotDiagnosed() {
        // Known analyzer gap, pinned: function-call resolution is a TODO, so
        // a call to an undeclared function passes silently. The analyzer must
        // still return a healthy result rather than crashing.
        val results = analyze(
            """
            x: Int32 = notDefined(1)
            """
        )
        assertNotNull(results)
        assertTrue(results.isHealthy)
    }

    // --- named arguments -------------------------------------------------------------

    @Test
    fun namedArgumentsBindToParameterNames() {
        assertHealthy(
            """
            sub: (a: Int32, b: Int32) Int32 { return a - b }

            Esc: Any {
                pub mut pulse: Int32
                pub set: (v: Int32, scale: Int32) Void { pulse = v * scale }
            }

            main: () Void {
                x: Int32 = sub(b = 3, a = 10)
                y: Int32 = sub(10, b = 3)
                e: Esc = Esc { 0 }
                e.set(scale = 2, v = 1)
            }
            """
        )
    }

    @Test
    fun namedArgumentThatIsNotAParameterIsDiagnosed() {
        val msgs = assertUnhealthy(
            """
            sub: (a: Int32, b: Int32) Int32 { return a - b }

            main: () Void {
                x: Int32 = sub(a = 10, c = 3)
            }
            """
        )
        assertTrue(msgs.any { it.contains("no parameter named 'c'") }, msgs.toString())
    }

    @Test
    fun duplicateAndMissingNamedArgumentsAreDiagnosed() {
        val duplicate = assertUnhealthy(
            """
            sub: (a: Int32, b: Int32) Int32 { return a - b }

            main: () Void {
                x: Int32 = sub(10, a = 3)
            }
            """
        )
        assertTrue(duplicate.any { it.contains("given more than once") }, duplicate.toString())
        val missing = assertUnhealthy(
            """
            sub: (a: Int32, b: Int32) Int32 { return a - b }

            main: () Void {
                x: Int32 = sub(b = 3)
            }
            """
        )
        assertTrue(missing.any { it.contains("without an argument: a") }, missing.toString())
    }

    @Test
    fun namedArgumentsBindToTheCallablesTheTyperMakes() {
        // Result.success and Str.of (D39, D55) have no declaration to read names from.
        assertHealthy(
            """
            main: () Void {
                v: List<UInt8> = @listOf(104, 105)
                trace(Str.of(bytes = v))
                r: Result<Int32, Str> = Result.success(value = 5)
                e: Result<Int32, Str> = Result.error(value = "no")
            }
            """
        )
        val msgs = assertUnhealthy(
            """
            main: () Void {
                v: List<UInt8> = @listOf(104, 105)
                trace(Str.of(text = v))
            }
            """
        )
        assertTrue(msgs.any { it.contains("'Str.of' has no parameter named 'text'. Its parameters are: bytes.") }, msgs.toString())
    }

    @Test
    fun namedArgumentsOnAnUnresolvableCalleeAreDiagnosed() {
        // Positional calls to unknown functions still pass (pinned gap above);
        // a call that uses names has nothing to bind them to, so it is refused
        // instead of being reordered by guesswork.
        val msgs = assertUnhealthy(
            """
            main: () Void {
                x: Int32 = mystery(a = 1)
            }
            """
        )
        assertTrue(msgs.any { it.contains("'mystery' is not a known function or method") }, msgs.toString())
    }

    // --- literal/type matching -----------------------------------------------------

    @Test
    fun rejectsIntegerLiteralForStringType() {
        val msgs = assertUnhealthy(
            """
            s: Str = 42
            """
        )
        assertTrue(msgs.any { it.contains("Type mismatch") }, msgs.toString())
    }

    @Test
    fun rejectsStringLiteralForIntType() {
        val msgs = assertUnhealthy(
            """
            n: Int32 = "hello"
            """
        )
        assertTrue(msgs.any { it.contains("Type mismatch") }, msgs.toString())
    }

    @Test
    fun rejectsFloatLiteralForIntType() {
        val msgs = assertUnhealthy(
            """
            n: Int32 = 1.5
            """
        )
        assertTrue(msgs.any { it.contains("Type mismatch") }, msgs.toString())
    }

    @Test
    fun acceptsIntegerLiteralForEveryStdlibIntegerType() {
        // The stdlib declares the unsigned integers and Size (kira/core.kira); the golden
        // corpus initialises them from literals (`MAX_PAYLOAD: Size = 1024`).
        assertHealthy(
            """
            a: Int8 = 1
            b: Int16 = 2
            c: Int32 = 3
            d: Int64 = 4
            e: UInt8 = 5
            f: UInt16 = 6
            g: UInt32 = 7
            h: UInt64 = 8
            i: Size = 9
            """
        )
    }

    @Test
    fun rejectsIntegerLiteralForFloatAndCharTypes() {
        for (type in listOf("Float32", "Float64", "Char", "Bool")) {
            val msgs = assertUnhealthy(
                """
                x: $type = 1
                """
            )
            assertTrue(msgs.any { it.contains("Type mismatch") }, "$type: $msgs")
        }
    }

    // --- symbol table --------------------------------------------------------------

    @Test
    fun resolvesDeclaredTypesAndVariables() {
        val results = analyze(
            """
            pub Pet: Any { pub name: Str = "" }

            pub Speaker: Any {
                pub speak: () Str;
            }

            pub Int32 as Age

            main: () Void {
                mut count: Int32 = 0
                friend: Pet = Pet { }
            }
            """
        )
        assertTrue(results.isHealthy, results.diagnostics.joinToString("\n") { it.message })

        val table = results.kiraSymbolTable
        val pet = table.resolve("Pet")
        assertNotNull(pet)
        assertEquals(SemanticSymbolKind.TYPE_SPECIFIER, pet.kind)
        assertTrue(pet.relativelyVisible, "public class should be relatively visible")

        val speaker = table.resolve("Speaker")
        assertNotNull(speaker)
        assertEquals(SemanticSymbolKind.TYPE_SPECIFIER, speaker.kind)
    }

    @Test
    fun resolvesUseImportedTypes() {
        val results = analyzeMultiSource(
            listOf(
                "test/semantic/other.kira" to """""" + "\n" + """
                    pub OtherType: Any { pub n: Int32 = 0 }
                    """,
                "test/semantic/basic.kira" to """""" + "\n" + """
                    test.semantic.other as other
                    x: OtherType = OtherType { }
                    """,
            )
        )
        assertTrue(results.isHealthy, results.diagnostics.joinToString("\n") { it.message })
        val imported = results.kiraSymbolTable.resolve("OtherType")
        assertNotNull(imported, "imported type should resolve after use")
    }

    @Test
    fun resolvesTypeAliasToTarget() {
        val results = analyze(
            """
            pub Int32 as Age
            """
        )
        assertTrue(results.isHealthy)
        val alias = results.kiraSymbolTable.resolve("Age")
        assertNotNull(alias)
        assertEquals(SemanticSymbolKind.TYPE_ALIAS, alias.kind)
        assertNotNull(alias.aliasedType, "alias should store its target type")
    }

    @Test
    fun variableSymbolsAreNotRelativelyVisible() {
        val results = analyze(
            """
            x: Int32 = 1
            """
        )
        assertTrue(results.isHealthy)
        val x = results.kiraSymbolTable.resolve("x")
        assertNotNull(x)
        assertEquals(SemanticSymbolKind.VARIABLE, x.kind)
        assertFalse(x.relativelyVisible)
    }

    @Test
    fun scopeStackRetainsModuleScopeAndDeclaredFunctionNames() {
        val results = analyze(
            """
            outer: () Int32 {
                inner: Int32 = 1
                return inner
            }
            """
        )
        assertTrue(results.isHealthy)
        // validateAST exits every entered scope, leaving the global + module
        // scopes on the stack. The module frame must still be present.
        assertTrue(
            results.kiraSymbolTable.any { it.kind is SemanticScope.Module },
            "module scope should remain on the stack after analysis"
        )
        assertTrue(
            results.kiraSymbolTable.any { it.kind is SemanticScope.Global },
            "global scope should remain on the stack after analysis"
        )
    }

    // --- analyzer robustness ---------------------------------------------------------

    @Test
    fun analyzerDoesNotCrashOnControlFlowBodies() {
        // Most statement visitors are TODO stubs; the analyzer must still
        // produce a result instead of throwing.
        val results = analyze(
            """
            main: () Void {
                mut i: Int32 = 0
                while i < 3 {
                    i = i + 1
                }
                if i == 3 {
                    trace("three")
                } else {
                    trace("other")
                }
                for mut j: 0..5 {
                    trace(j)
                }
            }
            """
        )
        assertNotNull(results)
    }
}
