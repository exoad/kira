package net.exoad.kira.types

import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.py.PyTestSupport
import net.exoad.kira.types.TyperTestSupport.expectDiagnostic
import net.exoad.kira.types.TyperTestSupport.expectNoErrors
import net.exoad.kira.types.TyperTestSupport.render
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A function-typed slot is one rule, whichever spelling it takes. */
class FunctionSlotTest {
    private fun btn(slot: String): String =
        """
        Btn: Any {
            pub label: Str = "b"
            pub onClick: $slot

            pub twice: (n: Int32) Int32 {
                onClick(onClick(n))
            }
        }

        main: () Void {
            b: Btn = Btn { onClick = (n: Int32) Int32 { n + 1 } }
            c: Btn = Btn { "c", (n: Int32) Int32 { n } }
            n: Int32 = b.onClick(1) + b.twice(1) + c.onClick(5)
        }
        """

    @Test
    fun aSlotEitherSpellingIsAFieldTheConstructionGivesAndIsCalledAsAMethod() {
        for (slot in listOf("(n: Int32) Int32", "Fx<T1<Int32>, Int32>")) {
            val p = TyperTestSupport.snippet(btn(slot))
            expectNoErrors(p)
            val cls = assertIs<ClassSymbol>(p.module("test:main")!!.members["Btn"])
            val field = assertNotNull(cls.fields.singleOrNull { it.name == "onClick" }, slot)
            assertIs<KType.Fn>(field.type, slot)
            assertTrue(cls.methods.none { it.name == "onClick" }, slot)
        }
        assertEquals(PyTestSupport.emit(btn("(n: Int32) Int32")).python(), PyTestSupport.emit(btn("Fx<T1<Int32>, Int32>")).python())
        val missing = TyperTestSupport.snippet("Btn: Any {\n    pub label: Str = \"b\"\n    pub onClick: (n: Int32) Int32\n}\n\nmain: () Void {\n    b: Btn = Btn { }\n}")
        assertTrue(expectDiagnostic(missing, "types.init.missing-required").message.contains("'onClick'"))
    }

    @Test
    fun aSlotASubtypeOverridesIsTheSubtypesWhicheverTheSpelling() {
        val p = TyperTestSupport.snippet(
            """
            Shape: Any {
                pub area: Fx<T0, Int32>
            }
            Sq: Shape {
                pub s: Int32 = 3

                pub override area: () Int32 {
                    s * s
                }
            }
            Base: Any {
                pub x: Int32 = 1
                pub run: Fx<T1<Int32>, Int32>
            }
            Kid: Base {
                pub override run: (n: Int32) Int32 {
                    n + x
                }
            }

            main: () Void {
                k: Kid = Kid { }
                sq: Sq = Sq { }
                n: Int32 = sq.area() + k.run(4)
            }
            """,
        )
        expectNoErrors(p)
        val m = p.module("test:main")!!
        assertTrue(assertIs<TraitSymbol>(m.members["Shape"]).methods.any { it.name == "area" })
        val base = assertIs<ClassSymbol>(m.members["Base"])
        assertTrue(base.methods.any { it.name == "run" } && base.fields.none { it.name == "run" }, render(p))
    }
}
