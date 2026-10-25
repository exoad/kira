package net.exoad.kira.cpp.decls

import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleLayout
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleRef
import net.exoad.kira.compiler.backend.codegen.cpp.CppNamespaceCollisions
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the second fix round pinned: a `.cxx`-private name collides with another module's
 * exported name in a shared namespace; and `std` or `kira` is refused as any segment of a
 * namespace, not the first alone.
 */
class CppDeclRound2Test {
    private fun named(d: net.exoad.kira.compiler.backend.codegen.cpp.CppDiagnostic): String = d.message.substringAfter("'").substringBefore("'")

    // ---- issue 3: a private name against an exported one in a shared namespace ------------------------

    @Test
    fun aPrivateNameOfTheCxxCollidesWithAnotherModulesExportedName() {
        val util = DeclTestSupport.module(
            "test:util",
            """
            pub LIMIT: Int32 = 1
            pub Pt: Struct { pub x: Int32 = 0 }
            hidden: Int32 = 4
            """,
        )
        val other = DeclTestSupport.module(
            "test:other",
            """
            test.util as util
            test.util.Pt as Pt

            LIMIT: Int32 = 2
            counter: Mutable<Int32> = LIMIT
            hidden: Int32 = 5
            pub get: (p: Pt) Int32;
            """,
        )
        val options = CppOptions(lineDirectives = false, namespaces = mapOf("test:util" to "shared", "test:other" to "shared"))
        val emitted = DeclTestSupport.emit(util, other, options = options)
        val inOther = emitted.diagnostics("test:other").filter { it.code == CppNamespaceCollisions.CODE }
        val inUtil = emitted.diagnostics("test:util").filter { it.code == CppNamespaceCollisions.CODE }
        // other's private LIMIT sits in shared::{anonymous}; get's body in namespace shared would find util's shared::LIMIT too
        assertEquals(listOf("LIMIT"), inOther.map(::named), emitted.render(inOther))
        val private = inOther.single()
        assertTrue(private.isError && private.position != null, private.render())
        assertTrue(private.message.contains("private to this module's .cxx") && private.message.contains("module 'test:util'") && private.message.contains("ambiguous"), private.render())
        assertEquals(listOf("LIMIT"), inUtil.map(::named), emitted.render(inUtil))
        assertTrue(inUtil.single().message.contains("module 'test:other'") && inUtil.single().message.contains("private to its .cxx"), inUtil.single().render())
        // hidden is private in both: two anonymous namespaces never meet
        assertTrue((inOther + inUtil).none { it.message.contains("hidden") }, emitted.render(inOther + inUtil))
        // in namespaces of their own, nothing
        val apart = DeclTestSupport.emit(util, other, options = CppOptions(lineDirectives = false))
        assertTrue(apart.diagnostics("test:util").none { it.isError } && apart.diagnostics("test:other").none { it.isError }, apart.render(apart.diagnostics("test:util") + apart.diagnostics("test:other")))
    }

    // ---- issue 4: std or kira as any namespace segment ----------------------------------------------------

    @Test
    fun aNamespaceNestingStdOrKiraIsRefusedNotOnlyAsTheFirstSegment() {
        val root = DeclTestSupport.root
        val refs = listOf("a", "b", "c").map { CppModuleRef("app:$it", root.resolve("src/app/$it.kira")) }
        val layout = CppModuleLayout(
            CppOptions(namespaces = mapOf("app:a" to "bibo", "app:b" to "bibo::std", "app:c" to "bibo::kira")),
            root, refs,
        )
        val errors = layout.checkCollisions().filter { it.isError }
        assertEquals(2, errors.size, errors.joinToString("\n") { it.render() })
        assertTrue(errors.any { it.message.contains("'bibo::std'") && it.message.contains("std::") && it.message.contains("bibo") }, errors.joinToString("\n") { it.render() })
        assertTrue(errors.any { it.message.contains("'bibo::kira'") && it.message.contains("kira::") }, errors.joinToString("\n") { it.render() })
        assertNull(layout.namespaceProblem("app:a", "bibo::stdx::kirax"))
        assertNotNull(layout.namespaceProblem("app:a", "bibo::text::std"))
        assertNotNull(layout.namespaceProblem("app:a", "bibo::text::kira"))
        // the stdlib lives in kira::, and nothing may nest a std under it
        val stdlib = CppModuleLayout(CppOptions(), root, emptyList())
        assertNull(stdlib.namespaceProblem("kira:sync", "kira::sync"))
        assertNotNull(stdlib.namespaceProblem("kira:sync", "kira::std::sync"))
    }
}
