package net.exoad.kira.suite

import net.exoad.kira.compiler.analysis.types.ModuleGraph
import net.exoad.kira.types.TyperTestSupport
import net.exoad.kira.types.TyperTestSupport.expectNoErrors
import net.exoad.kira.types.TyperTestSupport.module
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Stage 5: an import reaches its module only through its alias or a member alias. */
class AliasImportTest {
    private val units = module(
        "app:units",
        """
        pub SCALE: Int32 = 100
        pub Meter: Any {
            pub n: Int32 = 0
        }
        pub bump: (v: Int32) Int32 {
            v + 1
        }
        """,
    )

    private fun main(body: String) = module("app:main", body)

    @Test
    fun aBareNameOfAnImportIsRefusedNamingBothFixes() {
        val p = TyperTestSupport.type(units, main("app.units as units\n\nf: () Int32 {\n    m: Meter = Meter { }\n    bump(SCALE) + m.n\n}"))
        val bare = p.diagnostics.filter { it.code == "types.use.bare" }
        // The type (twice), the call and the value.
        assertEquals(4, bare.size, TyperTestSupport.render(p))
        assertTrue(bare.any { it.message == "'SCALE' is not in scope: add `app.units.SCALE as SCALE` or write `units.SCALE`." }, TyperTestSupport.render(p))
        assertTrue(bare.any { it.message.startsWith("'bump' is not in scope") } && bare.any { it.message.startsWith("'Meter' is not in scope") })
    }

    @Test
    fun aMemberAliasOrTheQualifiedNameReachesIt() {
        expectNoErrors(TyperTestSupport.type(units, main("app.units as units\napp.units.bump as bump\napp.units.Meter as Meter\n\nf: () Int32 {\n    m: Meter = Meter { }\n    bump(units.SCALE) + m.n\n}")))
        // A member alias may name the thing anew.
        expectNoErrors(TyperTestSupport.type(units, main("app.units.bump as next\n\nf: () Int32 {\n    next(1)\n}")))
    }

    @Test
    fun theStdlibIsInScopeBareImportedOrNot() {
        expectNoErrors(TyperTestSupport.snippet("kira.math as math\n\nf: () Float64 {\n    sqrt(4.0) + math.sqrt(9.0)\n}"))
        expectNoErrors(TyperTestSupport.snippet("f: () Float64 {\n    sqrt(4.0)\n}"))
    }

    // A project module with names the stdlib has too, as bibo's pylib.joinPath, floorMod and Ran; lerp private.
    private val shadows = module(
        "app:lib",
        """
        pub clamp: (value: Float64, lo: Float64, hi: Float64) Float64 {
            42.0
        }
        pub Ran: Any {
            pub code: Int32 = 7
        }
        lerp: (a: Float64, b: Float64, t: Float64) Float64 {
            0.0
        }
        """,
    )

    @Test
    fun aBareNameAnImportAndTheStdlibBothHaveIsRefused() {
        val text = "app.lib as lib\n\nf: () Float64 {\n    r: Ran = Ran { }\n    clamp(5.0, 0.0, 2.0) + r.code as Float64\n}\n"
        val p = TyperTestSupport.type(shadows, main(text))
        val bare = p.diagnostics.filter { it.code == "types.use.bare" }
        // The type (twice) and the call: none of them silently the stdlib's.
        assertEquals(3, bare.size, TyperTestSupport.render(p))
        assertTrue(
            bare.any { it.message == "'clamp' is not in scope, as app.lib and kira.math both have it: add `app.lib.clamp as clamp` or write `lib.clamp`, or for kira.math's add `kira.math.clamp as clamp`." },
            TyperTestSupport.render(p),
        )
        assertTrue(bare.count { it.message.startsWith("'Ran' is not in scope, as app.lib and kira.os both have it") } == 2, TyperTestSupport.render(p))
    }

    @Test
    fun aMemberAliasChoosesTheProjectsOrTheStdlibs() {
        for ((line, from) in listOf("app.lib.clamp as clamp" to "app:lib", "kira.math.clamp as clamp" to "kira:math")) {
            val p = TyperTestSupport.type(shadows, main("app.lib as lib\n$line\n\nf: () Float64 {\n    clamp(5.0, 0.0, 2.0)\n}\n"))
            expectNoErrors(p)
            assertEquals(from, (p.graph.lookup(p.module("app:main")!!, "clamp") as ModuleGraph.Lookup.Found).from.uri, line)
        }
        // Not imported, imported only for another name, or private there: the stdlib's, as the old rule had it.
        for (head in listOf("", "app.lib.Ran as Ran\n", "app.lib as lib\n")) {
            val call = if (head == "app.lib as lib\n") "lerp(0.0, 1.0, 0.5)" else "clamp(5.0, 0.0, 2.0)"
            val p = TyperTestSupport.type(shadows, main("$head\nf: () Float64 {\n    $call\n}\n"))
            expectNoErrors(p)
            assertEquals("kira:math", (p.graph.lookup(p.module("app:main")!!, call.substringBefore('(')) as ModuleGraph.Lookup.Found).from.uri, head)
        }
    }

    // The print family is no fallback for a name an imported project module has: the old rule meant that one.
    @Test
    fun aPrintNameAnImportHasIsRefusedBareToo() {
        val lib = module("app:lib", "pub println: (value: Str) Void {\n}\n\npub trace: (n: Int32) Void {\n}\n")
        val p = TyperTestSupport.type(lib, main("app.lib as lib\n\nf: () Void {\n    println(\"a\")\n    trace(1)\n}\n"))
        val bare = p.diagnostics.filter { it.code == "types.use.bare" }.map { it.message }
        assertEquals(2, bare.size, TyperTestSupport.render(p))
        assertTrue(bare.any { it.startsWith("'println' is not in scope, as app.lib and kira.io both have it") }, TyperTestSupport.render(p))
        assertTrue(bare.any { it == "'trace' is not in scope: add `app.lib.trace as trace` or write `lib.trace`." }, TyperTestSupport.render(p))
        val chosen = TyperTestSupport.type(lib, main("app.lib.println as println\napp.lib.trace as trace\n\nf: () Void {\n    println(\"a\")\n    trace(1)\n}\n"))
        expectNoErrors(chosen)
        assertEquals(listOf("app:lib", "app:lib"), chosen.model.calls.values.mapNotNull { it.fn?.module?.uri }.filter { !it.startsWith("kira:") }, TyperTestSupport.render(chosen))
    }

    // A member alias's module counts as imported for its free operators, as a module alias's does.
    @Test
    fun theOperatorsOfAModuleNamedByAMemberAliasApply() {
        val geo = module("app:geo", "pub V: Struct {\n    pub x: Int32 = 0\n}\n")
        val ops = module("app:ops", "app.geo.V as V\n\npub @op_add: (a: V, b: V) V {\n    V { a.x + b.x }\n}\n\npub zero: () V {\n    V { }\n}\n")
        val p = TyperTestSupport.type(geo, ops, main("app.geo.V as V\napp.ops.zero as zero\n\nf: (a: V) V {\n    a + zero()\n}\n"))
        expectNoErrors(p)
        assertEquals("app:ops", p.model.opCalls.values.single().fn!!.module.uri)
    }

    @Test
    fun anOwnNameIsNeverAnImports() {
        val p = TyperTestSupport.type(units, main("app.units as units\napp.units.SCALE as SCALE\n\nbump: (v: Int32) Int32 {\n    v + SCALE\n}\n\nf: () Int32 {\n    bump(1)\n}"))
        expectNoErrors(p)
        val m = p.module("app:main")!!
        assertEquals("app:main", (p.graph.lookup(m, "bump") as ModuleGraph.Lookup.Found).from.uri)
        assertEquals("app:units", (p.graph.lookup(m, "SCALE") as ModuleGraph.Lookup.Found).from.uri)
    }
}
