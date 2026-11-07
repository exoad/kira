package net.exoad.kira.types

import net.exoad.kira.types.TyperTestSupport.expectDiagnostic
import net.exoad.kira.types.TyperTestSupport.expectNoErrors
import net.exoad.kira.types.TyperTestSupport.module
import net.exoad.kira.types.TyperTestSupport.type
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/** Import aliases: one alias one module, and a type named through its module's alias. */
class ModuleAliasTest {
    private val utilA = module("probe:a.util", "pub which: () Str {\n    \"a\"\n}\npub onlyA: () Str {\n    \"onlyA\"\n}")
    private val utilB = module("probe:b.util", "pub which: () Str {\n    \"b\"\n}")

    @Test
    fun twoModulesUnderOneAliasAreRefused() {
        val p = type(utilA, utilB, module("probe:main", "probe.a.util as util\nprobe.b.util as util\n\nmain: () Void {\n    println(util.which())\n}"))
        val d = expectDiagnostic(p, "types.use.alias-taken")
        assertTrue(d.message.contains("'util' already names probe.a.util"), d.message)
    }

    @Test
    fun oneModuleUnderTwoAliasesAnswersToBoth() {
        val p = type(utilA, module("probe:main", "probe.a.util as u1\nprobe.a.util as u2\n\nmain: () Void {\n    println(u1.which() + u2.onlyA())\n}"))
        expectNoErrors(p)
    }

    @Test
    fun aQualifiedTypeNamesTheImportedModulesType() {
        val shapes = module("probe:b.shapes", "pub Impl: Any {\n    pub tag: Str { \"impl\" }\n}\npub Box<T>: Any {\n    pub v: T\n}")
        val p = type(
            shapes,
            module(
                "probe:main",
                "probe.b.shapes as shapes\n\nHolder: Any {\n    pub item: shapes.Impl { shapes.Impl { tag = \"held\" } }\n}\n\n" +
                    "main: () Void {\n    x: shapes.Impl { }\n    xs: Mutable<List<shapes.Impl>> { }\n" +
                    "    xs.add(x)\n    b: shapes.Box<Int32> { shapes.Box<Int32> { 7 } }\n    h: Holder { }\n    println(x.tag + h.item.tag)\n}",
            ),
        )
        expectNoErrors(p)
        val unknown = type(shapes, module("probe:main", "main: () Void {\n    x: shapes.Impl { shapes.Impl { } }\n}"))
        expectDiagnostic(unknown, "types.type.unknown-module")
    }
}
