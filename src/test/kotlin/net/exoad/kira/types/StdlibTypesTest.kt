package net.exoad.kira.types

import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.Prim
import net.exoad.kira.compiler.analysis.types.TypedModelDumper
import net.exoad.kira.compiler.analysis.types.display
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The stdlib types cleanly under STRICT: every `kira:*` declaration resolves, with no
 * diagnostic of any severity. (Its dump is not a golden: the stdlib grows in parallel.)
 */
class StdlibTypesTest {
    @Test
    fun stdlibTypesWithoutDiagnostics() {
        val program = TyperTestSupport.snippet("fx main: () Void { }")
        val stdlib = program.modules.filter { it.isStdlib }
        assertTrue(stdlib.map { it.uri }.containsAll(listOf("kira:core", "kira:collections", "kira:result", "kira:tuples")), stdlib.map { it.uri }.toString())
        val dump = TypedModelDumper.dumpSymbols(program) { it.isStdlib }
        assertTrue(program.diagnostics.isEmpty(), "stdlib diagnostics:\n${TyperTestSupport.render(program)}\n$dump")
        assertTrue("<error>" !in dump, "an unresolved type in the stdlib:\n$dump")
    }

    @Test
    fun magicScalarsMapToPrims() {
        val program = TyperTestSupport.snippet(
            """
            a: Int8 = 1
            b: UInt16 = 2
            c: Size = 3
            d: Char = x
            e: Float32 = 1.0
            f: Bool = true
            g: Int = 4
            """
        )
        val types = program.workspaceModules.single().declarations.associate { it.name to (it as net.exoad.kira.compiler.analysis.types.GlobalSymbol).type }
        assertEquals(KType.Scalar(Prim.INT8), types["a"])
        assertEquals(KType.Scalar(Prim.UINT16), types["b"])
        assertEquals(KType.SIZE, types["c"])
        assertEquals(KType.CHAR, types["d"])
        assertEquals(KType.FLOAT32, types["e"])
        assertEquals(KType.BOOL, types["f"])
        assertEquals("Int32", types["g"]!!.display())
    }
}
