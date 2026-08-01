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
            fx add: (a: Int32, b: Int32) Int32 {
                return a + b
            }
            """
        )
    }

    @Test
    fun healthyClassWithFieldsAndMethods() {
        assertHealthy(
            """
            pub class Counter {
                require pub value: Int32

                pub fx increment: () Int32 {
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
            pub class Box<T> {
                require pub value: T
            }

            fx id<T>: (value: T) T {
                return value
            }
            """
        )
    }

    @Test
    fun healthyEnumTraitAliasVariant() {
        assertHealthy(
            """
            pub enum Status {
                READY,
                RUNNING
            }

            pub trait Speaker {
                pub fx speak: () Str;
            }

            pub alias MyInt as Int32

            variant Shape {
                Circle: MyInt
                Square: MyInt
            }
            """
        )
    }

    @Test
    fun healthyUseImportBringsPublicTypes() {
        val results = analyzeMultiSource(
            listOf(
                "test/semantic/other.kira" to """module "test:semantic.other"""" + "\n" + """
                    pub class OtherType { }
                    """,
                "test/semantic/basic.kira" to """module "test:semantic.basic"""" + "\n" + """
                    use "test:semantic.other"
                    x: OtherType = OtherType { }
                    """,
            )
        )
        assertTrue(
            results.isHealthy,
            results.diagnostics.joinToString("\n") { it.message }
        )
    }

    // --- module URI validation ------------------------------------------------

    @Test
    fun rejectsInvalidModuleUriFormat() {
        val results = TestCompileSupport.compileSnippet(
            source = "module \"bad uri\"\nx: Int32 = 1",
            logicalPath = "test/semantic/basic.kira",
            runSemantic = true,
        )
        val msgs = messages(assertNotNull(results.semanticResults))
        assertTrue(msgs.any { it.contains("must be in the format") }, msgs.toString())
    }

    @Test
    fun rejectsModuleUriNotMatchingFileLocation() {
        val result = TestCompileSupport.compileSnippet(
            source = """module "test:semantic.basic" """ + "\n",
            logicalPath = "somewhere/else.kira",
            runSemantic = true,
        )
        val results = assertNotNull(result.semanticResults)
        assertFalse(results.isHealthy)
        assertTrue(
            messages(results).any { it.contains("does not match the source file location") },
            messages(results).toString()
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
        // Enum-vs-enum and enum-vs-class duplicates are diagnosed because the
        // enum visitor checks the module scope unconditionally.
        val msgs = assertUnhealthy(
            """
            pub enum First { A }
            pub enum First { B }
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
            pub class Dup { }
            pub class Dup { }
            """
        )
    }

    @Test
    fun rejectsClassAndEnumSharingAName() {
        val msgs = assertUnhealthy(
            """
            pub class Shared { }
            pub enum Shared { A, B }
            """
        )
        assertTrue(msgs.any { it.contains("already declared in the current module") }, msgs.toString())
    }

    @Test
    fun allowsSameVariableNameInNestedFunctionScope() {
        assertHealthy(
            """
            x: Int32 = 1

            fx f: (x: Int32) Int32 {
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

    // --- symbol table --------------------------------------------------------------

    @Test
    fun resolvesDeclaredTypesAndVariables() {
        val results = analyze(
            """
            pub class Pet { }

            pub enum Mood {
                HAPPY
            }

            pub trait Speaker {
                pub fx speak: () Str;
            }

            pub alias Age as Int32

            fx main: () Void {
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

        val mood = table.resolve("Mood")
        assertNotNull(mood)
        assertEquals(SemanticSymbolKind.TYPE_SPECIFIER, mood.kind)

        val speaker = table.resolve("Speaker")
        assertNotNull(speaker)
        assertEquals(SemanticSymbolKind.TYPE_SPECIFIER, speaker.kind)
    }

    @Test
    fun resolvesUseImportedTypes() {
        val results = analyzeMultiSource(
            listOf(
                "test/semantic/other.kira" to """module "test:semantic.other"""" + "\n" + """
                    pub class OtherType { }
                    """,
                "test/semantic/basic.kira" to """module "test:semantic.basic"""" + "\n" + """
                    use "test:semantic.other"
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
            pub alias Age as Int32
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
            fx outer: () Int32 {
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
            fx main: () Void {
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
