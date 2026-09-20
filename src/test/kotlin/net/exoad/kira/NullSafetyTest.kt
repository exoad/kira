package net.exoad.kira

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `null` is not a keyword -- it is a stdlib global of type `Null`, the same way
 * `true` / `false` are globals of type `Bool`. Every type is non-nullable, and
 * `Maybe<T>` is the only shape that admits absence.
 */
class NullSafetyTest {
    private val pet = """
        pub class Pet {
            require pub name: Str
        }
    """.trimIndent()

    private fun diagnosticsFor(body: String, uri: String): List<String> {
        val result = TestCompileSupport.compileSnippet(
            source = TestCompileSupport.wrapModule(uri, body),
            logicalPath = TestCompileSupport.logicalPathForModule(uri),
            runSemantic = true
        )
        return result.semanticResults?.diagnostics?.map { it.message } ?: emptyList()
    }

    @Test
    fun nullIsDeclaredByTheStdlibRatherThanLexedAsAKeyword() {
        val core = java.io.File("kira/core.kira").readText()
        assertTrue(core.contains("pub @_magic class Null"), "Null type missing from kira:core")
        assertTrue(core.contains("@_global null: Null"), "null global missing from kira:core")

        // If `null` were a keyword the lexer would own it; it must stay an identifier.
        val keywords = java.io.File("src/main/kotlin/net/exoad/kira/core/Keywords.kt").readText()
        assertFalse(keywords.contains("\"null\""), "null must not be a lexer keyword")
    }

    @Test
    fun rejectsNullAssignedToANonNullableType() {
        val messages = diagnosticsFor(
            """
            $pet

            fx main: () Void {
                p: Pet = null
            }
            """,
            "test:nullsafety.reject"
        )

        assertTrue(
            messages.any { it.contains("cannot be assigned to the non-nullable type 'Pet'") },
            "expected a non-nullable diagnostic, got: $messages"
        )
        // The diagnostic should point at the fix.
        assertTrue(
            messages.any { it.contains("Maybe<Pet>") },
            "diagnostic should suggest Maybe<Pet>, got: $messages"
        )
    }

    @Test
    fun allowsNullOnlyThroughMaybe() {
        val messages = diagnosticsFor(
            """
            $pet

            fx main: () Void {
                p: Maybe<Pet> = null
                q: Maybe<Int32> = null
            }
            """,
            "test:nullsafety.allow"
        )

        assertTrue(
            messages.none { it.contains("non-nullable") },
            "Maybe<T> must accept null, got: $messages"
        )
    }

    @Test
    fun rejectsReachingThroughAMaybeWithoutUnwrapping() {
        val messages = diagnosticsFor(
            """
            $pet

            fx main: () Void {
                p: Maybe<Pet> = null
                q: Str = p.name
            }
            """,
            "test:nullsafety.deref"
        )

        assertTrue(
            messages.any { it.contains("'name' is not available on a 'Maybe'") },
            "expected an unwrap-required diagnostic, got: $messages"
        )
    }

    @Test
    fun guardAppliesInsideCallArgumentsToo() {
        val messages = diagnosticsFor(
            """
            $pet

            fx main: () Void {
                p: Maybe<Pet> = null
                trace(p.name)
            }
            """,
            "test:nullsafety.callarg"
        )

        assertTrue(
            messages.any { it.contains("not available on a 'Maybe'") },
            "the guard must reach into call arguments, got: $messages"
        )
    }

    @Test
    fun maybeApiItselfStaysReachable() {
        val messages = diagnosticsFor(
            """
            $pet

            fx main: () Void {
                p: Maybe<Pet> = null
                a: Bool = p.isSome()
                b: Bool = p.isNone()
            }
            """,
            "test:nullsafety.api"
        )

        assertTrue(
            messages.none { it.contains("not available on a 'Maybe'") },
            "isSome/isNone must remain callable, got: $messages"
        )
    }
}
