package net.exoad.kira.cpp.decls

import net.exoad.kira.compiler.analysis.types.TypeSymbol
import net.exoad.kira.compiler.backend.codegen.cpp.CppClassesPart
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitContextImpl
import net.exoad.kira.compiler.backend.codegen.cpp.CppEmitParts
import net.exoad.kira.compiler.backend.codegen.cpp.CppNames
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.compiler.backend.codegen.cpp.CppWriter
import net.exoad.kira.cpp.decls.DeclEmitRun.FakeExprs
import net.exoad.kira.cpp.decls.DeclEmitRun.ReturnsNamed
import net.exoad.kira.cpp.decls.DeclEmitRun.assertContains
import net.exoad.kira.cpp.decls.DeclEmitRun.assertLacks
import net.exoad.kira.cpp.decls.DeclEmitRun.emit
import net.exoad.kira.cpp.decls.DeclEmitRun.header
import net.exoad.kira.cpp.decls.DeclEmitRun.render
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** A declaration's name is spelled through its namespace in every scope, and `std` or `kira` as a declared name is escaped. */
class DeclScopeAndNamesTest {
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

    @Test
    fun aMemberNamedLikeAModuleDeclarationSpellsTheDeclarationThroughTheNamespace() {
        val h = header(
            """
            pub width: Int32 { 5 }
            pub OTHER: Int32 { 3 }
            LIMIT: Int32 { 7 }
            pub scale: (x: Int32) Int32
            pub Box: Struct {
                pub width: Int32 { width }
                pub LIMIT: Int32 { LIMIT }
                pub other: Int32 { OTHER }
                pub grow: (by: Int32 = OTHER) Int32
                pub scale: (x: Int32 = width) Int32
            }
            pub Plain: Struct {
                pub w: Int32 { width }
            }
            """,
        )
        // a field, a private constant in impl_ (a field default may name one; a default argument may not, D48),
        // a default argument a field hides
        assertContains(
            h,
            "std::int32_t width = ::main_::width;",
            "std::int32_t LIMIT = ::main_::impl_::LIMIT;",
            "scale(std::int32_t x = ::main_::width) const;",
        )
        // a name no member hides stays bare, inside the struct and outside it
        assertContains(h, "std::int32_t other = OTHER;", "grow(std::int32_t by = OTHER) const;", "std::int32_t w = width;")
        assertLacks(h, "std::int32_t width = width;", "::main_::OTHER")
    }

    @Test
    fun aHeaderOnlyModuleQualifiesItsImplNamesTheSameWay() {
        val h = header(
            """
            LIMIT: Int32 { 7 }
            pub Box: Struct {
                pub LIMIT: Int32 { LIMIT }
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
            pub LIMIT: Int32 { 7 }
            pub Box: Struct {
                pub LIMIT: Int32 { 1 }
                pub limit: () Int32 { LIMIT }
            }
            pub limit: () Int32 { LIMIT }
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
            pub LIMIT: Int32 { 7 }
            pub Holder: Any {
                pub n: Int32 { 1 }
                pub limit: () Int32 { LIMIT }
            }
            pub Sized: Any {
                size: () Int32
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
            pub std: Int32 { 1 }
            pub S: Struct {
                pub kira: Int32 { std }
                pub v: Int32 { 2 }
            }
            """,
        )
        assertContains(h, "inline constexpr std::int32_t std_ = 1;", "std::int32_t kira_ = std_;", "std::int32_t v = 2;")
    }
}
