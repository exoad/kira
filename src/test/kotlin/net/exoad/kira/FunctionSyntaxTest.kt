package net.exoad.kira

import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the function declaration form: `fx name: (params) ReturnType`.
 *
 * The colon binds the name to its signature the same way `value: Int32` binds
 * a variable to its type. The pre-migration form `fx name(params): ReturnType`
 * must now be a parse error, not a silent reinterpretation.
 */
class FunctionSyntaxTest {
    private fun compile(body: String, uri: String) =
        TestCompileSupport.compileSnippet(
            source = TestCompileSupport.wrapModule(uri, body),
            logicalPath = TestCompileSupport.logicalPathForModule(uri),
            runSemantic = true
        )

    @Test
    fun acceptsColonBoundSignature() {
        val result = compile(
            """
            add: (a: Int32, b: Int32) Int32 {
                a + b
            }

            answer: Int32 = add(1, 2)
            """,
            "test:fxsyntax.basic"
        )

        assertNotNull(result.sourceContext.ast)
        val semantics = assertNotNull(result.semanticResults)
        assertTrue(semantics.isHealthy, semantics.diagnostics.joinToString("\n") { it.message })
    }

    @Test
    fun acceptsZeroParameterAndGenericAndAbstractForms() {
        val result = compile(
            """
            now: () Int64 {
                0
            }

            id<T>: (value: T) T {
                value
            }

            pub Speaker: Any {
                pub speak: () Str;
            }
            """,
            "test:fxsyntax.forms"
        )

        assertNotNull(result.sourceContext.ast)
        val semantics = assertNotNull(result.semanticResults)
        assertTrue(semantics.isHealthy, semantics.diagnostics.joinToString("\n") { it.message })
    }

    @Test
    fun rejectsOldParenthesesThenColonForm() {
        val failure = assertThrows<DiagnosticsException> {
            compile(
                """
                fx add(a: Int32, b: Int32): Int32 {
                    return a + b
                }
                """,
                "test:fxsyntax.legacy"
            )
        }

        // The diagnostic should teach the new shape, not just report a bad token.
        assertTrue(
            failure.message.contains("a function is 'name: (a: Int32) Int32 { }'"),
            "expected a migration hint, got: ${failure.message}"
        )
    }
}
