package net.exoad.kira.cpp.exprs

import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.cpp.CppGoldenCompileTest
import net.exoad.kira.cpp.CppGoldenEmitTest
import net.exoad.kira.cpp.support.CppGoldenCase
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The unilidar golden is `emit: pending` until W2.5's EscapePass fills `TypedModel.fxEscapes`:
 * its `eachPacket(buf, each)` expects `each` as a non-escaping `Fx` parameter (design 5.6, a
 * `template<typename F_each> requires kira::Callable<...>` with `F_each&& each`), and the
 * table's default, an absent entry, is "escapes" (`const kira::Fn<...>&`). This test stands in
 * for that pass: it marks every `Fx` parameter of the case's own modules non-escaping, the
 * answer EscapePass gives unilidar (no `Fx` parameter there is stored or returned), and the
 * emitter must then write expected/ byte for byte. When the pass lands, the case flips to
 * `emit: required` and this test keeps proving the template path end to end.
 */
class CppNonEscapingFxGoldenTest {
    private fun case(name: String): CppGoldenCase {
        val (roots, _) = CppGoldenCompileTest.configuredRoots()
        return roots.filter { it.isDirectory }.flatMap { CppGoldenCase.discover(it) }.firstOrNull { it.name == name }
            ?: fail("no golden case $name under $roots")
    }

    /** Every `Fx`-typed, by-value parameter of the workspace's functions and methods. */
    private fun fxParams(program: TypedProgram): List<ParamSymbol> = program.workspaceModules.flatMap { m ->
        m.members.values.flatMap { sym ->
            when (sym) {
                is FnSymbol -> listOf(sym)
                is ClassSymbol -> sym.methods
                else -> emptyList()
            }
        }
    }.flatMap { fn -> fn.params.filter { it.type is KType.Fn && !it.byRef } }

    @Test
    fun unilidarMatchesExpectedOnceItsFxParameterIsKnownNotToEscape() {
        val case = case("unilidar")
        assertEquals("pending", case.emit, "unilidar is emit: required now, so flip it in CppGoldenEmitTest and retire this stand-in")
        var marked = emptyList<String>()
        CppGoldenEmitTest().check(case) { program ->
            val params = fxParams(program)
            params.forEach { program.model.fxEscapes[it] = false }
            marked = params.map { it.name }
        }
        assertTrue(marked == listOf("each"), "the Fx parameters marked non-escaping: $marked")
    }
}
