package net.exoad.kira.types

import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.types.TyperTestSupport.codes
import net.exoad.kira.types.TyperTestSupport.expectNoErrors
import net.exoad.kira.types.TyperTestSupport.module
import net.exoad.kira.types.TyperTestSupport.render
import net.exoad.kira.types.TyperTestSupport.type
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** A declaration's role comes from what it declares: trait or class by state, and final whatever the role. */
class DeclarationRoleTest {
    @Test
    fun aUserTypeNamedLikeAStdlibTraitLeavesTheStdlibsAlone() {
        val p = TyperTestSupport.snippet(
            """
            Hashable: Any {
                pub seed: Int64 = 0
            }

            main: () Void {
                h: Hashable = Hashable { }
                s: Str = "abc"
                n: Int64 = h.seed + s.hashCode()
            }
            """,
        )
        expectNoErrors(p)
        assertIs<TraitSymbol>(p.module("kira:core")!!.members["Hashable"])
        assertIs<ClassSymbol>(p.module("test:main")!!.members["Hashable"])
    }

    @Test
    fun anUnimportedModulesStatefulTypeLeavesItsNamesakeATrait() {
        val p = type(
            module("probe:a.model", "pub Handler: Any {\n    pub count: Int32 = 0\n}"),
            module(
                "probe:b.shapes",
                "pub Handler: Any {\n    pub handle: () Str\n}\npub Impl: Handler {\n    pub tag: Str = \"impl\"\n\n" +
                    "    pub override handle: () Str {\n        tag\n    }\n}\npub run: (h: Handler) Str {\n    h.handle()\n}",
            ),
            module("probe:main", "probe.b.shapes as shapes\nprobe.b.shapes.Impl as Impl\n\nmain: () Void {\n    i: Impl = Impl { }\n    println(shapes.run(i))\n}"),
        )
        expectNoErrors(p)
        assertIs<TraitSymbol>(p.module("probe:b.shapes")!!.members["Handler"])
        assertIs<ClassSymbol>(p.module("probe:a.model")!!.members["Handler"])
    }

    @Test
    fun aStatelessFinalTypeIsNoParentEither() {
        val p = TyperTestSupport.snippet(
            """
            pub final Base: Any {
                pub greet: () Str
            }
            pub Kid: Base {
                pub x: Int32 = 1

                pub override greet: () Str {
                    "hi"
                }
            }
            pub Sub: Base {
                pub other: () Str
            }
            """,
        )
        assertIs<TraitSymbol>(p.module("test:main")!!.members["Base"])
        assertEquals(2, codes(p).count { it == "types.class.final" }, render(p))
    }
}
