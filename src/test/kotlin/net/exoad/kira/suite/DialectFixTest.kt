package net.exoad.kira.suite

import net.exoad.kira.compiler.analysis.diagnostics.DiagnosticsException
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleLayout
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleRef
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.compiler.frontend.parser.ast.declarations.ClassDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.py.PyTestSupport
import net.exoad.kira.types.TyperTestSupport
import net.exoad.kira.types.TyperTestSupport.Src
import net.exoad.kira.types.TyperTestSupport.codes
import net.exoad.kira.types.TyperTestSupport.expectDiagnostic
import net.exoad.kira.types.TyperTestSupport.expectNoErrors
import net.exoad.kira.types.TyperTestSupport.module
import net.exoad.kira.types.TyperTestSupport.render
import net.exoad.kira.types.TyperTestSupport.type
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The fixes of the clean dialect's first review: each named by the fix it pins. */
class DialectFixTest {
    private fun refused(vararg sources: Src): DiagnosticsException = assertThrows { TyperTestSupport.unitOf(*sources) }

    private fun refused(body: String): DiagnosticsException = refused(module("test:main", body))

    // ---- 1. roles by declaration -----------------------------------------------------------------

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

    // ---- 2. import aliases -------------------------------------------------------------------------

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

    // ---- 3. final, whatever the role ---------------------------------------------------------------

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

    // ---- 4. a project's module globs -----------------------------------------------------------------

    @Test
    fun aGlobThatNamesNoModuleIsWarnedWithTheDottedSpellingItMeant() {
        val root = Path.of("build/tmp/dialect-globs").toAbsolutePath().normalize()
        val text = CppModuleRef("bibo:firmware.lib.text", root.resolve("firmware/lib/text.kira"))
        val options = CppOptions(headerOnly = listOf("firmware:lib.**"), freestanding = listOf("firmware:lib.**", "bibo.firmware.lib.**"))
        val warnings = CppModuleLayout(options, root, listOf(text)).checkCollisions().filter { it.code == "cpp.glob-unused" }
        assertEquals(2, warnings.size, warnings.joinToString("\n") { it.message })
        assertTrue(warnings.all { !it.isError && it.message.contains("'bibo.firmware.lib.**'") }, warnings.joinToString("\n") { it.message })
        assertTrue(warnings.any { it.message.startsWith("cpp.headerOnly['firmware:lib.**'] matches no module") })
    }

    // ---- 5. a slot is one rule ---------------------------------------------------------------------

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
        for (slot in listOf("(n: Int32) Int32", "Fx<Tuple1<Int32>, Int32>")) {
            val p = TyperTestSupport.snippet(btn(slot))
            expectNoErrors(p)
            val cls = assertIs<ClassSymbol>(p.module("test:main")!!.members["Btn"])
            val field = assertNotNull(cls.fields.singleOrNull { it.name == "onClick" }, slot)
            assertIs<KType.Fn>(field.type, slot)
            assertTrue(cls.methods.none { it.name == "onClick" }, slot)
        }
        assertEquals(PyTestSupport.emit(btn("(n: Int32) Int32")).python(), PyTestSupport.emit(btn("Fx<Tuple1<Int32>, Int32>")).python())
        val missing = TyperTestSupport.snippet("Btn: Any {\n    pub label: Str = \"b\"\n    pub onClick: (n: Int32) Int32\n}\n\nmain: () Void {\n    b: Btn = Btn { }\n}")
        assertTrue(expectDiagnostic(missing, "types.init.missing-required").message.contains("'onClick'"))
    }

    @Test
    fun aSlotASubtypeOverridesIsTheSubtypesWhicheverTheSpelling() {
        val p = TyperTestSupport.snippet(
            """
            Shape: Any {
                pub area: Fx<Tuple0, Int32>
            }
            Sq: Shape {
                pub s: Int32 = 3

                pub override area: () Int32 {
                    s * s
                }
            }
            Base: Any {
                pub x: Int32 = 1
                pub run: Fx<Tuple1<Int32>, Int32>
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

    // ---- 6. a type adds a member -------------------------------------------------------------------

    @Test
    fun aTypeThatAddsNoMemberIsRefusedUnlessTheHostSuppliesIt() {
        for (empty in listOf("Marker: Any { }", "Marker: Any", "Unit: Struct { }")) {
            val e = refused(empty)
            assertEquals("parse.type.empty", e.tag, empty)
            assertTrue(e.message.contains("adds no member"), e.message)
        }
        TyperTestSupport.unitOf(module("test:main", "pub @_opaque Handle: Any { }\npub Base: Any {\n    pub x: Int32 = 0\n}\npub Kid: Base { }"))
    }

    // ---- 7. a type through a module's alias --------------------------------------------------------

    @Test
    fun aQualifiedTypeNamesTheImportedModulesType() {
        val shapes = module("probe:b.shapes", "pub Impl: Any {\n    pub tag: Str = \"impl\"\n}\npub Box<T>: Any {\n    pub v: T\n}")
        val p = type(
            shapes,
            module(
                "probe:main",
                "probe.b.shapes as shapes\n\nHolder: Any {\n    pub item: shapes.Impl = shapes.Impl { tag = \"held\" }\n}\n\n" +
                    "main: () Void {\n    x: shapes.Impl = shapes.Impl { }\n    xs: Mutable<List<shapes.Impl>> = List<shapes.Impl> { }\n" +
                    "    xs.add(x)\n    b: shapes.Box<Int32> = shapes.Box<Int32> { 7 }\n    h: Holder = Holder { }\n    println(x.tag + h.item.tag)\n}",
            ),
        )
        expectNoErrors(p)
        val unknown = type(shapes, module("probe:main", "main: () Void {\n    x: shapes.Impl = shapes.Impl { }\n}"))
        expectDiagnostic(unknown, "types.type.unknown-module")
    }

    // ---- 8. the messages that pointed elsewhere ----------------------------------------------------

    @Test
    fun aValueWrittenAsATypeOrAnImportInABodySaysWhatItIs() {
        val local = refused("Cfg: Any {\n    pub x: Int32 = 1\n}\nmain: () Void {\n    c: Cfg { }\n}")
        assertEquals("parse.type.name", local.tag)
        assertTrue(local.message.contains("'c: Cfg { }' declares nothing") && local.message.contains("'c: Cfg = ...'"), local.message)
        val function = refused("f: Int32 {\n    return 1\n}")
        assertEquals("parse.type.name", function.tag)
        assertTrue(function.message.contains("'f: () Int32 { }'"), function.message)
        val import = refused("main: () Void {\n    kira.math as math\n}")
        assertEquals("parse.import.nested", import.tag)
        assertTrue(import.message.contains("An import is top-level only"), import.message)
        assertEquals("parse.import.nested", refused("main: () Void {\n    Tuple2<A, B> as Pair<A, B>\n}").tag)
    }

    // ---- 9. what a capitals name declares ----------------------------------------------------------

    @Test
    fun aCapitalsNameIsAConstantsAndAPascalCaseOrLoneCapitalATypes() {
        val url = refused("URL: Any {\n    pub host: Str = \"h\"\n}")
        assertEquals("parse.type.name", url.tag)
        assertTrue(url.message.contains("'Url'"), url.message)
        assertEquals("parse.type.name", refused("URL: Any, Base").tag)
        val ctx = TyperTestSupport.unitOf(module("test:main", "K: Any {\n    pub x: Int32 = 1\n}\nV2: Struct {\n    pub y: Int32 = 0\n}\npub @_extern(c = \"LIMIT\", header = \"l.h\") LIMIT: Int32\nN: Int32 = 4"))
            .allSources().single { it.moduleName == "test:main" }
        val decls = ctx.ast.statements.mapNotNull { (it as? Statement)?.expr }
        assertEquals(listOf("K", "V2"), decls.mapNotNull { (it as? ClassDecl)?.name?.identifier?.toString()?.trim('\'') } +
            decls.filterIsInstance<net.exoad.kira.compiler.frontend.parser.ast.declarations.StructDecl>().map { it.name.identifier.toString().trim('\'') })
        assertEquals(listOf("LIMIT", "N"), decls.filterIsInstance<VariableDecl>().map { it.name.value })
    }

    // ---- 10. snake_case only in a top-level import's path ------------------------------------------

    @Test
    fun snakeCaseIsAnImportPathsOnly() {
        val e = refused("Pt: Struct {\n    pub x: Int32 = 1\n}\nmain: () Void {\n    p: Pt = Pt { }\n    p.my_x as Int32\n}")
        assertEquals("KiraLexer::lexIdentifier", e.tag)
        assertTrue(e.message.contains("'my_x'"), e.message)
        assertEquals("KiraLexer::lexIdentifier", refused("kira.math as my_math").tag)
        TyperTestSupport.unitOf(module("test:main", "net.exoad.bibo.dash.webdrive_fake as webdriveFake"))
    }
}
