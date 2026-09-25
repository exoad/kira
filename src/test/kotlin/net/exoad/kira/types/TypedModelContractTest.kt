package net.exoad.kira.types

import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.KiraTyper
import net.exoad.kira.compiler.analysis.types.Prim
import net.exoad.kira.compiler.analysis.types.TypedModel
import net.exoad.kira.compiler.analysis.types.TyperMode
import net.exoad.kira.compiler.analysis.types.UntypedExpression
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import org.junit.jupiter.api.Test
import java.util.IdentityHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The typed model keys every fact by node identity. `Identifier.equals` compares by value, so
 * a HashMap would merge two different `x` identifiers into one entry.
 */
class TypedModelContractTest {
    @Test
    fun twoEqualIdentifiersGetSeparateEntries() {
        val a = Identifier("x")
        val b = Identifier("x")
        assertEquals(a, b, "the premise: Identifier compares by value")
        assertEquals(1, hashMapOf<Expr, KType>(a to KType.INT32, b to KType.Str).size, "a HashMap merges them")

        val model = TypedModel()
        model.types[a] = KType.INT32
        model.types[b] = KType.Str
        assertEquals(2, model.types.size)
        assertEquals(KType.INT32, model.require(a))
        assertEquals(KType.Str, model.require(b))
    }

    @Test
    fun twoParsedXIdentifiersAreTwoKeys() {
        val unit = TyperTestSupport.unitOf(
            TyperTestSupport.module(
                "test:ids",
                """
                fx first: (x: Int32) Int32 {
                    return x
                }

                fx second: (x: Str) Str {
                    return x
                }
                """
            )
        )
        val source = unit.allSources().single { runCatching { it.getModuleUri() }.getOrNull() == "test:ids" }
        val xs = mutableListOf<Identifier>()
        AstTree.walk(source.ast) { if (it is Identifier && it.value == "x") xs.add(it) }
        assertEquals(4, xs.size, "two parameters and two uses")
        val model = TypedModel()
        xs.forEachIndexed { i, x -> model.types[x] = if (i < 2) KType.INT32 else KType.Str }
        assertEquals(4, model.types.size)
        xs.forEachIndexed { i, x -> assertEquals(if (i < 2) KType.INT32 else KType.Str, model.require(x)) }
    }

    @Test
    fun requireThrowsForAMissingFact() {
        val model = TypedModel()
        val e = Identifier("nowhere")
        assertNull(model.typeOrNull(e))
        val thrown = assertFailsWith<UntypedExpression> { model.require(e) }
        assertSame(e, thrown.expr)
        assertTrue(thrown.message!!.contains("internal compiler error"), thrown.message)
    }

    @Test
    fun everyTableIsAnIdentityHashMap() {
        val model = TypedModel()
        val maps = TypedModel::class.java.declaredFields.filter { Map::class.java.isAssignableFrom(it.type) }
        assertTrue(maps.size >= 20, "found ${maps.map { it.name }}")
        for (field in maps) {
            field.isAccessible = true
            assertTrue(field.get(model) is IdentityHashMap<*, *>, "${field.name} must be an IdentityHashMap")
        }
        val required = listOf(
            "types", "refs", "typeRefs", "calls", "opCalls", "inits", "members", "coercions", "places", "consts",
            "conversions", "captures", "loops", "ifShape", "effects", "declSyms", "fxEscapes", "viewEscapes",
        )
        val names = maps.map { it.name }.toSet()
        assertTrue(names.containsAll(required), "missing ${required - names}")
    }

    @Test
    fun absentEffectAndEscapeEntriesMeanTheConservativeAnswer() {
        val model = TypedModel()
        assertEquals(net.exoad.kira.compiler.analysis.types.Effect.IMPURE, model.effect(Identifier("f")))
    }

    @Test
    fun offModeReturnsAnEmptyProgram() {
        val program = KiraTyper.run(TyperTestSupport.unitOf(TyperTestSupport.module("test:off", "x: Int32 = 1")), TyperMode.OFF)
        assertTrue(program.modules.isEmpty())
        assertTrue(program.diagnostics.isEmpty())
        assertEquals(TyperMode.OFF, program.mode)
    }

    @Test
    fun primTableMatchesTheDesign() {
        assertTrue(Prim.UINT8.promotesInCpp && Prim.INT16.promotesInCpp)
        assertTrue(!Prim.INT32.promotesInCpp && !Prim.SIZE.promotesInCpp && !Prim.CHAR.promotesInCpp)
        assertTrue(Prim.SIZE.isInteger && !Prim.BOOL.isInteger && !Prim.CHAR.isInteger && !Prim.FLOAT32.isInteger)
        assertEquals(32, Prim.SIZE.portableBits, "Size is range-checked at the Pico's width")
        assertNotSame(KType.Scalar(Prim.INT32), KType.Scalar(Prim.INT32))
        assertEquals(KType.Scalar(Prim.INT32), KType.INT32)
    }
}
