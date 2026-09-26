package net.exoad.kira.cpp.decls

import net.exoad.kira.compiler.analysis.types.EnumSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.TypeSymbol
import net.exoad.kira.compiler.backend.codegen.cpp.CppClassesPart
import net.exoad.kira.compiler.backend.codegen.cpp.CppDeclEmitter
import net.exoad.kira.compiler.backend.codegen.cpp.CppDiagnostic
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitContextImpl
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitParts
import net.exoad.kira.compiler.backend.codegen.cpp.CppExprPart
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleEmitterFactory
import net.exoad.kira.compiler.backend.codegen.cpp.CppNames
import net.exoad.kira.compiler.backend.codegen.cpp.CppNamespaceCollisions
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.compiler.backend.codegen.cpp.CppStmtPart
import net.exoad.kira.compiler.backend.codegen.cpp.CppTypeSpeller
import net.exoad.kira.compiler.backend.codegen.cpp.CppUsage
import net.exoad.kira.compiler.backend.codegen.cpp.CppWriter
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What the first fix round of the declaration emitter pinned: names a struct's members hide
 * (self-initialization), a name a shared namespace holds twice, a `switch` over an enum with
 * two entries of one value, a private prototype nothing can define, a `@_magic` class the
 * runtime backs (or does not), and `std` or `kira` as a declared name.
 */
class CppDeclRound1Test {
    /** A statement part whose every body returns the module's declaration [name], spelled through `ctx.qualified`. */
    private class ReturnsNamed(val name: String) : CppStmtPart {
        override fun emit(ctx: CppEmitContextImpl, s: Statement, w: CppWriter) {
            w.line("/* stmt */")
        }

        override fun body(ctx: CppEmitContextImpl, fn: FnSymbol?, statements: List<Statement>, w: CppWriter) {
            w.line("return ${ctx.qualified(ctx.symbol.members[name] ?: fail("no declaration '$name'"))};")
        }
    }

    private object FakeExprs : CppExprPart {
        override fun emit(ctx: CppEmitContextImpl, e: Expr, parentPrec: Int): String = "<${e.javaClass.simpleName}>"
    }

    /** A classes part that records whether the context's scope was the class it was asked to define. */
    private class ScopeRecordingClasses : CppClassesPart {
        val defineInScope = mutableListOf<Boolean>()
        val membersInScope = mutableListOf<Boolean>()

        override fun define(ctx: CppEmitContextImpl, sym: TypeSymbol, w: CppWriter) {
            defineInScope += ctx.scope === sym
            w.line("class ${sym.name} {};")
        }

        override fun defineMembers(ctx: CppEmitContextImpl, sym: TypeSymbol, w: CppWriter, inline: Boolean) {
            membersInScope += ctx.scope === sym
        }
    }

    private fun assertContains(text: String, vararg wanted: String) {
        wanted.forEach { assertTrue(text.contains(it), "expected to find:\n$it\nin:\n$text") }
    }

    private fun assertLacks(text: String, vararg unwanted: String) {
        unwanted.forEach { assertTrue(!text.contains(it), "expected not to find:\n$it\nin:\n$text") }
    }

    private fun render(diagnostics: List<CppDiagnostic>): String = diagnostics.joinToString("\n") { it.render() }.ifEmpty { "(none)" }

    private fun emit(body: String, uri: String = "test:main", options: CppOptions = CppOptions(lineDirectives = false), parts: CppEmitParts = CppEmitParts(stmts = ReturnsNamed("LIMIT"), exprs = FakeExprs), usageOf: ((CppEmitContextImpl) -> CppUsage)? = null) =
        DeclTestSupport.emitWith(DeclTestSupport.module(uri, body), uri = uri, parts = parts, options = options, usageOf = usageOf)

    private fun header(body: String, uri: String = "test:main", options: CppOptions = CppOptions(lineDirectives = false), usageOf: ((CppEmitContextImpl) -> CppUsage)? = null): String {
        val (emitted, _) = emit(body, uri, options, usageOf = usageOf)
        val errors = emitted.diagnostics.filter { it.isError }
        if (errors.isNotEmpty()) {
            fail("errors:\n" + render(errors) + "\n--- header ---\n" + emitted.header)
        }
        return CppWriter.normalize(emitted.header)
    }

    // ---- issue 1: a member hides a same-module name -------------------------------------------

    @Test
    fun aMemberNamedLikeAModuleDeclarationSpellsTheDeclarationThroughTheNamespace() {
        val h = header(
            """
            pub width: Int32 = 5
            pub OTHER: Int32 = 3
            LIMIT: Int32 = 7
            enum Mode: Int32 { MODE_A = 0, MODE_B = 1 }
            pub fx scale: (x: Int32) Int32;
            pub struct Box {
                pub width: Int32 = width
                pub LIMIT: Int32 = LIMIT
                pub other: Int32 = OTHER
                pub Mode: Mode = Mode.MODE_B
                pub fx grow: (by: Int32 = LIMIT) Int32;
                pub fx scale: (x: Int32 = width) Int32;
            }
            pub struct Plain {
                pub w: Int32 = width
                pub m: Mode = Mode.MODE_A
            }
            """,
        )
        // a field, a private constant in impl_, an enum both as the field's type and its default, a default argument
        assertContains(
            h,
            "std::int32_t width = ::main_::width;",
            "std::int32_t LIMIT = ::main_::impl_::LIMIT;",
            "::main_::impl_::Mode Mode = ::main_::impl_::Mode::MODE_B;",
            "grow(std::int32_t by = ::main_::impl_::LIMIT) const;",
            "scale(std::int32_t x = ::main_::width) const;",
        )
        // a name no member hides stays bare, inside the struct and outside it
        assertContains(h, "std::int32_t other = OTHER;", "std::int32_t w = width;", "impl_::Mode m = impl_::Mode::MODE_A;")
        assertLacks(h, "std::int32_t width = width;", "::main_::OTHER", "::main_::Mode m")
    }

    @Test
    fun aHeaderOnlyModuleQualifiesItsImplNamesTheSameWay() {
        val h = header(
            """
            LIMIT: Int32 = 7
            pub struct Box {
                pub LIMIT: Int32 = LIMIT
            }
            """,
            uri = "lib:units",
            options = CppOptions(lineDirectives = false, headerOnly = listOf("lib:units"), namespaces = mapOf("lib:units" to "golden::units")),
        )
        assertContains(h, "std::int32_t LIMIT = ::golden::units::impl_::LIMIT;")
    }

    @Test
    fun aMethodBodyIsSpelledInClassScopeAndAFreeFunctionIsNot() {
        val (emitted, _) = emit(
            """
            pub LIMIT: Int32 = 7
            pub struct Box {
                pub LIMIT: Int32 = 1
                pub fx limit: () Int32 { return LIMIT }
            }
            pub fx limit: () Int32 { return LIMIT }
            """,
        )
        val errors = emitted.diagnostics.filter { it.isError }
        assertTrue(errors.isEmpty(), render(errors))
        val source = CppWriter.normalize(emitted.source ?: fail("no source"))
        // the out-of-line member definition is class scope: the module constant is spelled from the global namespace
        assertContains(source, "std::int32_t Box::limit() const\n  {\n      return ::main_::LIMIT;\n  }")
        assertContains(source, "std::int32_t limit()\n  {\n      return LIMIT;\n  }")
    }

    @Test
    fun theClassesPartRunsInTheScopeOfTheClassItDefines() {
        val classes = ScopeRecordingClasses()
        val (emitted, ctx) = emit(
            """
            pub LIMIT: Int32 = 7
            pub class Holder {
                pub n: Int32 = 1
                pub fx limit: () Int32 { return LIMIT }
            }
            pub trait Sized {
                fx size: () Int32
            }
            """,
            parts = CppEmitParts(stmts = ReturnsNamed("LIMIT"), exprs = FakeExprs, classes = classes),
        )
        val errors = emitted.diagnostics.filter { it.isError }
        assertTrue(errors.isEmpty(), render(errors))
        assertEquals(listOf(true, true), classes.defineInScope, "define() ran in the scope of the class and the trait")
        assertTrue(classes.membersInScope.isNotEmpty() && classes.membersInScope.all { it }, "defineMembers() ran in the scope of its class: ${classes.membersInScope}")
        assertNull(ctx.scope, "the scope is restored to namespace scope afterwards")
    }

    @Test
    fun stdAndKiraAsDeclaredNamesAreEscaped() {
        assertEquals("std_", CppNames().escape("std"))
        assertEquals("kira_", CppNames().escape("kira"))
        assertEquals("width", CppNames().escape("width"))
        val h = header(
            """
            pub std: Int32 = 1
            pub struct S {
                pub kira: Int32 = std
                pub v: Int32 = 2
            }
            """,
        )
        assertContains(h, "inline constexpr std::int32_t std_ = 1;", "std::int32_t kira_ = std_;", "std::int32_t v = 2;")
    }

    // ---- issue 3: one name in one namespace -------------------------------------------------------

    @Test
    fun twoModulesSharingANamespaceMayNotDeclareOneName() {
        val util = DeclTestSupport.module(
            "test:util",
            """
            pub LIMIT: Int32 = 1
            pub struct Pt { pub x: Int32 = 0 }
            pub ONLY_HERE: Int32 = 3
            hidden: Int32 = 4
            """,
        )
        val other = DeclTestSupport.module(
            "test:other",
            """
            pub LIMIT: Int32 = 2
            pub fx Pt: () Int32;
            hidden: Int32 = 5
            """,
        )
        val options = CppOptions(lineDirectives = false, namespaces = mapOf("test:util" to "shared", "test:other" to "shared"))
        val emitted = DeclTestSupport.emit(util, other, options = options)
        fun duplicates(uri: String) = emitted.diagnostics(uri).filter { it.code == CppNamespaceCollisions.CODE }
        val inUtil = duplicates("test:util")
        val inOther = duplicates("test:other")
        assertEquals(listOf("LIMIT", "Pt"), inUtil.map { it.message.substringAfter("'").substringBefore("'") }, render(inUtil))
        assertEquals(listOf("LIMIT", "Pt"), inOther.map { it.message.substringAfter("'").substringBefore("'") }, render(inOther))
        assertTrue(inUtil.all { it.isError && it.position != null && it.message.contains("module 'test:other'") }, render(inUtil))
        assertTrue(inOther.all { it.isError && it.position != null && it.message.contains("module 'test:util'") }, render(inOther))
        assertTrue(inUtil.none { it.message.contains("ONLY_HERE") || it.message.contains("hidden") }, "a name one module holds, or a private one of the .cxx, never collides")
        // the same modules in namespaces of their own: nothing
        val apart = DeclTestSupport.emit(util, other, options = CppOptions(lineDirectives = false))
        assertTrue(apart.diagnostics("test:util").none { it.isError } && apart.diagnostics("test:other").none { it.isError }, render(apart.diagnostics("test:util") + apart.diagnostics("test:other")))
    }

    @Test
    fun aNestedNamespaceCollidesWithADeclarationOfItsName() {
        val a = DeclTestSupport.module("test:a", "pub struct text { pub x: Int32 = 0 }")
        val b = DeclTestSupport.module("test:b", "pub LIMIT: Int32 = 1")
        val options = CppOptions(lineDirectives = false, namespaces = mapOf("test:a" to "bibo", "test:b" to "bibo::text"))
        val emitted = DeclTestSupport.emit(a, b, options = options)
        val inA = emitted.diagnostics("test:a").filter { it.code == CppNamespaceCollisions.CODE }
        assertEquals(1, inA.size, render(emitted.diagnostics("test:a")))
        assertTrue(inA[0].message.contains("'text'") && inA[0].message.contains("module 'test:b'") && inA[0].message.contains("bibo::text"), inA[0].render())
        assertTrue(emitted.diagnostics("test:b").none { it.isError }, render(emitted.diagnostics("test:b")))
    }

    // ---- issue 5: two entries, one value ---------------------------------------------------------------

    @Test
    fun duplicateEnumValuesLabelOneCase() {
        val h = header(
            "pub enum Dup: Int32 { D_A = 1, D_B = 1, D_C = 2 }",
            usageOf = { ctx -> CppUsage.of(nameOf = setOf(ctx.symbol.members["Dup"] as EnumSymbol), enumValues = setOf(ctx.symbol.members["Dup"] as EnumSymbol)) },
        )
        assertContains(h, "D_A = 1,", "D_B = 1,", "case Dup::D_A:\n              return \"D_A\";", "case Dup::D_C:")
        assertLacks(h, "case Dup::D_B:")
        assertContains(h, "return {Dup::D_A, Dup::D_B, Dup::D_C};")
    }

    // ---- issue 7: a prototype nothing can define ------------------------------------------------------

    @Test
    fun aPrivateFunctionWithoutABodyIsAnError() {
        val (emitted, _) = emit(
            """
            fx helper: (x: Int32, y: Int32) Int32;
            pub fx defined: (x: Int32) Int32;
            struct Inner {
                pub v: Int32 = 1
                pub fx twice: () Int32;
            }
            pub struct Outer {
                pub v: Int32 = 1
                pub fx twice: () Int32;
            }
            """,
        )
        val refused = emitted.diagnostics.filter { it.code == CppDeclEmitter.NO_BODY_CODE }
        assertEquals(2, refused.size, render(emitted.diagnostics))
        assertTrue(refused[0].message.contains("function 'helper'") && refused[0].position?.lineNumber == 3, refused[0].render())
        assertTrue(refused[1].message.contains("method 'twice'") && refused[1].position != null, refused[1].render())
        assertTrue(emitted.diagnostics.none { it.isError && it.code != CppDeclEmitter.NO_BODY_CODE }, render(emitted.diagnostics))

        val headerOnly = emit("fx helper: (x: Int32) Int32;\npub X: Int32 = 1", uri = "lib:h", options = CppOptions(lineDirectives = false, headerOnly = listOf("lib:h"))).first
        assertEquals(1, headerOnly.diagnostics.count { it.code == CppDeclEmitter.NO_BODY_CODE }, render(headerOnly.diagnostics))
    }

    // ---- issue 2: magic classes the runtime backs, and one it does not -------------------------------------

    @Test
    fun theSystemModulesMapToTheirRuntimeHeaders() {
        assertEquals("kira/sync.hxx", CppTypeSpeller.systemHeaderFor("kira:sync"))
        assertEquals("kira/test.hxx", CppTypeSpeller.systemHeaderFor("kira:test"))
        assertEquals("kira/os.hxx", CppTypeSpeller.systemHeaderFor("kira:os"))
        assertEquals("kira/time.hxx", CppTypeSpeller.systemHeaderFor("kira:time"))
        assertNull(CppTypeSpeller.systemHeaderFor("kira:result"))
        assertNull(CppTypeSpeller.systemHeaderFor("kira:core"))
    }

    @Test
    fun aMagicClassNoRuntimeTypeBacksIsRefusedAtItsLine() {
        val (emitted, _) = emit(
            """
            use "kira:result"
            pub struct Holder {
                pub failure: Maybe<Exception> = null
            }
            pub fx describe: (e: Exception) Str;
            """,
        )
        val refused = emitted.diagnostics.filter { it.code == CppModuleEmitterFactory.UNSUPPORTED_CODE }
        assertEquals(2, refused.size, render(emitted.diagnostics))
        assertEquals(listOf(5, 7), refused.map { it.position?.lineNumber }, render(refused))
        assertTrue(refused.all { it.message.contains("Exception") }, render(refused))
    }
}
