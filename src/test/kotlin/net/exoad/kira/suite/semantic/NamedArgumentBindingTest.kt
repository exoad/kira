package net.exoad.kira.suite.semantic

import net.exoad.kira.suite.semantic.SemanticTestSupport.assertHealthy
import net.exoad.kira.suite.semantic.SemanticTestSupport.assertUnhealthy
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/** Semantic analysis: named arguments bind to parameter names. */
class NamedArgumentBindingTest {
    @Test
    fun namedArgumentsBindToParameterNames() {
        assertHealthy(
            """
            sub: (a: Int32, b: Int32) Int32 { a - b }

            Esc: Mutable<Any> {
                pub pulse: Mutable<Int32>
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
            sub: (a: Int32, b: Int32) Int32 { a - b }

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
            sub: (a: Int32, b: Int32) Int32 { a - b }

            main: () Void {
                x: Int32 = sub(10, a = 3)
            }
            """
        )
        assertTrue(duplicate.any { it.contains("given more than once") }, duplicate.toString())
        val missing = assertUnhealthy(
            """
            sub: (a: Int32, b: Int32) Int32 { a - b }

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
}
