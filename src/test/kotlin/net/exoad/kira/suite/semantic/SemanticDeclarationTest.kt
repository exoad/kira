package net.exoad.kira.suite.semantic

import net.exoad.kira.suite.semantic.SemanticTestSupport.analyze
import net.exoad.kira.suite.semantic.SemanticTestSupport.analyzeMultiSource
import net.exoad.kira.suite.semantic.SemanticTestSupport.assertHealthy
import net.exoad.kira.suite.semantic.SemanticTestSupport.assertUnhealthy
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Semantic analysis: healthy declarations, duplicate declarations and unknown symbols. */
class SemanticDeclarationTest {
    @Test
    fun healthyEmptyModule() {
        assertHealthy("")
    }

    @Test
    fun healthyVariablesWithBuiltinTypes() {
        assertHealthy(
            """
            a: Int32 { 1 }
            b: Int64 { 2 }
            c: Float32 { 1.5 }
            d: Float64 { 2.5 }
            e: Bool { true }
            f: Str { "hello" }
            g: Int32
            """
        )
    }

    @Test
    fun healthyFunctionWithParametersAndReturn() {
        assertHealthy(
            """
            add: (a: Int32, b: Int32) Int32 {
                a + b
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
                    value + 1
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
                value
            }
            """
        )
    }

    @Test
    fun healthyTraitAlias() {
        assertHealthy(
            """
            pub Speaker: Any {
                pub speak: () Str
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
                    pub OtherType: Any { pub n: Int32 { 0 } }
                    """,
                "test/semantic/basic.kira" to """""" + "\n" + """
                    test.semantic.other as other
                    x: OtherType { }
                    """,
            )
        )
        assertTrue(
            results.isHealthy,
            results.diagnostics.joinToString("\n") { it.message }
        )
    }

    @Test
    fun rejectsDuplicateVariableInSameScope() {
        val msgs = assertUnhealthy(
            """
            x: Int32 { 1 }
            x: Int32 { 2 }
            """
        )
        assertTrue(msgs.any { it.contains("already declared in this scope") }, msgs.toString())
    }

    @Test
    fun rejectsDuplicateTypeInModule() {
        // A trait's visitor checks the module scope unconditionally.
        val msgs = assertUnhealthy(
            """
            pub First: Any { pub a: () Int32 }
            pub First: Any { pub b: () Int32 }
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
            pub Dup: Any { pub n: Int32 { 0 } }
            pub Dup: Any { pub n: Int32 { 0 } }
            """
        )
    }

    @Test
    fun rejectsClassAndTraitSharingAName() {
        val msgs = assertUnhealthy(
            """
            pub Shared: Any { pub n: Int32 { 0 } }
            pub Shared: Any { pub a: () Int32 }
            pub Impl: Shared { }
            """
        )
        assertTrue(msgs.any { it.contains("already declared in the current module") }, msgs.toString())
    }

    @Test
    fun allowsSameVariableNameInNestedFunctionScope() {
        assertHealthy(
            """
            x: Int32 { 1 }

            f: (x: Int32) Int32 {
                x
            }
            """
        )
    }

    @Test
    fun rejectsUnknownType() {
        val msgs = assertUnhealthy(
            """
            x: MissingType { 1 }
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
            x: Int32 { notDefined(1) }
            """
        )
        assertNotNull(results)
        assertTrue(results.isHealthy)
    }
}
