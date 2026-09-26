package net.exoad.kira.cpp.decls

import net.exoad.kira.compiler.analysis.types.EnumSymbol
import net.exoad.kira.compiler.backend.codegen.cpp.CppDeclEmitter
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitContextImpl
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitParts
import net.exoad.kira.compiler.backend.codegen.cpp.CppExprPart
import net.exoad.kira.compiler.backend.codegen.cpp.CppNamespaceCollisions
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.compiler.backend.codegen.cpp.CppUsage
import net.exoad.kira.compiler.backend.codegen.cpp.CppWriter
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the third fix round pinned: a Str literal never reaches the header as a trigraph
 * (`??=` is written `?\?=`, which gcc and clang would otherwise refuse under `-Wall
 * -Werror`), wherever [CppDeclEmitter.cppString] writes it; and a `.cxx`-private name meets
 * another module's exported name in a shared namespace only when the private module's
 * translation unit can include that module's header, that is, when it reaches the module
 * through its `use` statements.
 */
class CppDeclRound3Test {
    private object FakeExprs : CppExprPart {
        override fun emit(ctx: CppEmitContextImpl, e: Expr, parentPrec: Int): String = "<${e.javaClass.simpleName}>"
    }

    private fun assertContains(text: String, vararg wanted: String) {
        wanted.forEach { assertTrue(text.contains(it), "expected:\n$it\nin:\n$text") }
    }

    private fun named(d: net.exoad.kira.compiler.backend.codegen.cpp.CppDiagnostic): String = d.message.substringAfter("'").substringBefore("'")

    // ---- issue 1: trigraphs in a Str literal ---------------------------------------------------------

    @Test
    fun cppStringEscapesTheSecondQuestionMarkOfEveryPair() {
        // each of the nine trigraph sequences
        listOf("=", "/", "'", "(", ")", "!", "<", ">", "-").forEach { third ->
            assertEquals("\"a ?\\?$third b\"", CppDeclEmitter.cppString("a ??$third b"), "??$third")
        }
        // a run of three: the second and the third are each preceded by a '?'
        assertEquals("\"?\\?\\?=\"", CppDeclEmitter.cppString("???="))
        // a lone '?' and a '?' after another character stay as they are
        assertEquals("\"what?\"", CppDeclEmitter.cppString("what?"))
        assertEquals("\"a?b?c\"", CppDeclEmitter.cppString("a?b?c"))
        // a '?' after an escaped character is not a pair in the source text
        assertEquals("\"\\\\?\"", CppDeclEmitter.cppString("\\?"))
        assertEquals("\"\\n?\"", CppDeclEmitter.cppString("\n?"))
        // the other escapes are as before
        assertEquals("\"say \\\"hi\\\"\\t\\001\"", CppDeclEmitter.cppString("say \"hi\"\t\u0001"))
        // no output ever holds two adjacent question marks
        val all = "??= ??/ ??' ??( ??) ??! ??< ??> ??- ???= ????"
        assertTrue(!CppDeclEmitter.cppString(all).contains("??"), CppDeclEmitter.cppString(all))
    }

    @Test
    fun noTrigraphReachesTheHeaderFromAnyStrSite() {
        val uri = "test:tri"
        val (emitted, _) = DeclTestSupport.emitWith(
            DeclTestSupport.module(
                uri,
                """
                pub QUERY: Str = "a ??= b"
                pub enum Op: Str {
                    OP_SET = "??=",
                    OP_LT = "??<"
                }
                pub struct Cfg {
                    pub text: Str = "??/"
                }
                pub fx show: (label: Str = "??'") Void;
                @_static_assert(1 == 1, "??(")
                """,
            ),
            uri = uri,
            parts = CppEmitParts(exprs = FakeExprs),
            usageOf = { ctx -> CppUsage.of(nameOf = setOf(ctx.symbol.members["Op"] as EnumSymbol)) },
        )
        assertTrue(!emitted.hasErrors, emitted.diagnostics.joinToString("\n") { it.render() })
        val h = CppWriter.normalize(emitted.header)
        assertContains(
            h,
            "inline constexpr const char* QUERY = \"a ?\\?= b\";",
            "case Op::OP_SET:\n              return \"?\\?=\";",
            "case Op::OP_LT:\n              return \"?\\?<\";",
            "kira::Str text = \"?\\?/\";",
            "void show(const kira::Str& label = \"?\\?'\");",
            "static_assert(<BinaryExpr>, \"?\\?(\");",
        )
        assertTrue(!h.contains("??"), "two adjacent question marks in:\n$h")
    }

    // ---- issue 2: a private name meets only what its own translation unit includes ------------------

    private val util = DeclTestSupport.module(
        "test:util",
        """
        pub LIMIT: Int32 = 1
        pub struct Pt { pub x: Int32 = 0 }
        """,
    )

    private fun other(uses: String) = DeclTestSupport.module(
        "test:other",
        """
        $uses
        LIMIT: Int32 = 2
        mut counter: Int32 = LIMIT
        struct Pt { pub y: Int32 = 0 }
        mut p: Pt = Pt {}
        pub fx get: () Int32;
        """,
    )

    private val shared = mapOf("test:util" to "shared", "test:other" to "shared", "test:mid" to "shared")

    @Test
    fun aPrivateNameBesideAnExportedOneTheModuleNeverReachesIsNoCollision() {
        val emitted = DeclTestSupport.emit(util, other(""), options = CppOptions(lineDirectives = false, namespaces = shared))
        val all = emitted.diagnostics("test:util") + emitted.diagnostics("test:other")
        assertTrue(all.none { it.code == CppNamespaceCollisions.CODE }, emitted.render(all))
        // and both modules still emit: other's LIMIT and Pt are private to its .cxx
        assertContains(emitted.source("test:other") ?: "", "namespace\n", "std::int32_t LIMIT = 2;")
        assertContains(emitted.header("test:util"), "inline constexpr std::int32_t LIMIT = 1;")
    }

    @Test
    fun aPrivateNameBesideAnExportedOneTheModuleUsesIsACollisionOnBothSides() {
        val emitted = DeclTestSupport.emit(util, other("use \"test:util\""), options = CppOptions(lineDirectives = false, namespaces = shared))
        val inOther = emitted.diagnostics("test:other").filter { it.code == CppNamespaceCollisions.CODE }
        val inUtil = emitted.diagnostics("test:util").filter { it.code == CppNamespaceCollisions.CODE }
        assertEquals(listOf("LIMIT", "Pt"), inOther.map(::named).sorted(), emitted.render(inOther))
        assertEquals(listOf("LIMIT", "Pt"), inUtil.map(::named).sorted(), emitted.render(inUtil))
    }

    @Test
    fun aPrivateNameMeetsAnExportedOneReachedThroughAnotherModulesUse() {
        // other uses mid, mid uses util: other's .cxx includes mid's header, which includes util's
        val mid = DeclTestSupport.module(
            "test:mid",
            """
            use "test:util"
            pub struct Wrap { pub at: Pt = Pt {} }
            """,
        )
        val emitted = DeclTestSupport.emit(util, mid, other("use \"test:mid\""), options = CppOptions(lineDirectives = false, namespaces = shared))
        val inOther = emitted.diagnostics("test:other").filter { it.code == CppNamespaceCollisions.CODE }
        val inUtil = emitted.diagnostics("test:util").filter { it.code == CppNamespaceCollisions.CODE }
        assertEquals(listOf("LIMIT", "Pt"), inOther.map(::named).sorted(), emitted.render(inOther))
        assertEquals(listOf("LIMIT", "Pt"), inUtil.map(::named).sorted(), emitted.render(inUtil))
        assertTrue(emitted.diagnostics("test:mid").none { it.code == CppNamespaceCollisions.CODE }, emitted.render(emitted.diagnostics("test:mid")))
        // the reverse direction reaches nothing: util uses nobody, so its exported names meet no private one of mid
        val reversed = DeclTestSupport.emit(
            DeclTestSupport.module("test:util", "pub LIMIT: Int32 = 1\nhelper: Int32 = 3\npub struct Pt { pub x: Int32 = 0 }"),
            DeclTestSupport.module("test:mid", "use \"test:util\"\npub struct Wrap { pub at: Pt = Pt {} }\nmut helper: Int32 = 4\npub fx go: () Void;"),
            options = CppOptions(lineDirectives = false, namespaces = shared),
        )
        // util's private helper and mid's private helper are two anonymous namespaces
        val revAll = reversed.diagnostics("test:util") + reversed.diagnostics("test:mid")
        assertTrue(revAll.none { it.code == CppNamespaceCollisions.CODE }, reversed.render(revAll))
    }

    @Test
    fun aPrivateNameBesideANestedNamespaceSegmentMeetsItOnlyWhenReached() {
        val inner = DeclTestSupport.module("test:inner", "pub DEPTH: Int32 = 1")
        val outer = { uses: String ->
            DeclTestSupport.module("test:outer", "$uses\nutil: Int32 = 2\nmut n: Int32 = util\npub fx go: () Void;")
        }
        val ns = mapOf("test:inner" to "shared::util", "test:outer" to "shared")
        val apart = DeclTestSupport.emit(inner, outer(""), options = CppOptions(lineDirectives = false, namespaces = ns))
        val apartAll = apart.diagnostics("test:inner") + apart.diagnostics("test:outer")
        assertTrue(apartAll.none { it.code == CppNamespaceCollisions.CODE }, apart.render(apartAll))
        val reached = DeclTestSupport.emit(inner, outer("use \"test:inner\""), options = CppOptions(lineDirectives = false, namespaces = ns))
        val inOuter = reached.diagnostics("test:outer").filter { it.code == CppNamespaceCollisions.CODE }
        assertEquals(listOf("util"), inOuter.map(::named), reached.render(inOuter))
    }

    // ---- the collision test of round 2 still holds: an exported name beside an exported one, whoever uses whom ----

    @Test
    fun twoExportedNamesInOneNamespaceCollideWithoutAnyUse() {
        val a = DeclTestSupport.module("test:a", "pub LIMIT: Int32 = 1")
        val b = DeclTestSupport.module("test:b", "pub LIMIT: Int32 = 2")
        val emitted = DeclTestSupport.emit(a, b, options = CppOptions(lineDirectives = false, namespaces = mapOf("test:a" to "shared", "test:b" to "shared")))
        assertEquals(listOf("LIMIT"), emitted.diagnostics("test:a").filter { it.code == CppNamespaceCollisions.CODE }.map(::named))
        assertEquals(listOf("LIMIT"), emitted.diagnostics("test:b").filter { it.code == CppNamespaceCollisions.CODE }.map(::named))
    }
}
