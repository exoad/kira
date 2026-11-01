package net.exoad.kira.suite

import net.exoad.kira.TestCompileSupport
import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.types.TyperTestSupport
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/** A type names its parent; only the root names itself, and says so with @_root; T0 is Void. */
class RootAndVoidTest {

    private fun parse(body: String) {
        TestCompileSupport.compileSnippet(body.trimIndent(), "tests/root.kira")
    }

    @Test
    fun aTypeNamingItselfAsParentIsRefused() {
        val e = assertThrows<DiagnosticsException> { parse("Foo: Foo {\n    pub v: Int32\n}\n") }
        assertEquals("parse.type.self-parent", e.tag)
    }

    @Test
    fun theRootMarkIsRefusedOnATypeThatNamesNoItself() {
        val e = assertThrows<DiagnosticsException> { parse("pub @_root Foo: Any {\n    pub v: Int32\n}\n") }
        assertEquals("parse.type.root", e.tag)
    }

    @Test
    fun theZeroTupleIsVoid() {
        val p = TyperTestSupport.snippet("pub run: (g: Fx<T0, Int32>) Int32 {\n    g()\n}\n")
        TyperTestSupport.expectNoErrors(p)
    }

    @Test
    fun theZeroTupleTakesNoArguments() {
        val p = TyperTestSupport.snippet("pub bad: () T0<Int32> {\n    1\n}\n")
        assertEquals(listOf("types.type.arity"), p.diagnostics.map { it.code }, TyperTestSupport.render(p))
    }
}
