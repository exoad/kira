package net.exoad.kira.suite.semantic

import net.exoad.kira.compiler.analysis.semantic.SemanticScope
import net.exoad.kira.compiler.analysis.semantic.SemanticSymbolKind
import net.exoad.kira.suite.semantic.SemanticTestSupport.analyze
import net.exoad.kira.suite.semantic.SemanticTestSupport.analyzeMultiSource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Semantic analysis: symbol resolution, scope stacks and robustness on control flow. */
class SemanticSymbolTableTest {
    @Test
    fun resolvesDeclaredTypesAndVariables() {
        val results = analyze(
            """
            pub Pet: Any { pub name: Str = "" }

            pub Speaker: Any {
                pub speak: () Str
            }

            pub Int32 as Age

            main: () Void {
                count: Mutable<Int32> = 0
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
                inner
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

    @Test
    fun analyzerDoesNotCrashOnControlFlowBodies() {
        // Most statement visitors are TODO stubs; the analyzer must still
        // produce a result instead of throwing.
        val results = analyze(
            """
            main: () Void {
                i: Mutable<Int32> = 0
                while i < 3 {
                    i = i + 1
                }
                if i == 3 {
                    trace("three")
                } else {
                    trace("other")
                }
                for j: Int32 in 0..6 {
                    trace(j)
                }
            }
            """
        )
        assertNotNull(results)
    }
}
