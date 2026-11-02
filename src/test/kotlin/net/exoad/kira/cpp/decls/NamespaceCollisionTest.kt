package net.exoad.kira.cpp.decls

import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleLayout
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleRef
import net.exoad.kira.compiler.backend.codegen.cpp.CppNamespaceCollisions
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.cpp.decls.DeclEmitRun.assertContains
import net.exoad.kira.cpp.decls.DeclEmitRun.emit
import net.exoad.kira.cpp.decls.DeclEmitRun.header
import net.exoad.kira.cpp.decls.DeclEmitRun.render
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Two declarations of one name in one namespace collide, a private name only against what its module can reach, and `std` or `kira` is no namespace segment. */
class NamespaceCollisionTest {
    private fun named(d: net.exoad.kira.compiler.backend.codegen.cpp.CppDiagnostic): String = d.message.substringAfter("'").substringBefore("'")

    private val util = DeclTestSupport.module(
        "test:util",
        """
        pub LIMIT: Int32 = 1
        pub Pt: Struct { pub x: Int32 = 0 }
        """,
    )

    private fun other(uses: String) = DeclTestSupport.module(
        "test:other",
        """
        $uses
        LIMIT: Int32 = 2
        counter: Mutable<Int32> = LIMIT
        Pt: Struct { pub y: Int32 = 0 }
        p: Mutable<Pt> = Pt {}
        pub get: () Int32
        """,
    )

    private val shared = mapOf("test:util" to "shared", "test:other" to "shared", "test:mid" to "shared")

    @Test
    fun twoModulesSharingANamespaceMayNotDeclareOneName() {
        val util = DeclTestSupport.module(
            "test:util",
            """
            pub LIMIT: Int32 = 1
            pub Pt: Struct { pub x: Int32 = 0 }
            pub ONLY_HERE: Int32 = 3
            hidden: Int32 = 4
            """,
        )
        val other = DeclTestSupport.module(
            "test:other",
            """
            pub LIMIT: Int32 = 2
            pub Pt: () Int32
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
        val a = DeclTestSupport.module("test:a", "pub text: () Int32 {\n    1\n}")
        val b = DeclTestSupport.module("test:b", "pub LIMIT: Int32 = 1")
        val options = CppOptions(lineDirectives = false, namespaces = mapOf("test:a" to "bibo", "test:b" to "bibo::text"))
        val emitted = DeclTestSupport.emit(a, b, options = options)
        val inA = emitted.diagnostics("test:a").filter { it.code == CppNamespaceCollisions.CODE }
        assertEquals(1, inA.size, render(emitted.diagnostics("test:a")))
        assertTrue(inA[0].message.contains("'text'") && inA[0].message.contains("module 'test:b'") && inA[0].message.contains("bibo::text"), inA[0].render())
        assertTrue(emitted.diagnostics("test:b").none { it.isError }, render(emitted.diagnostics("test:b")))
    }

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
            pub get: (p: Pt) Int32
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
        val emitted = DeclTestSupport.emit(util, other("test.util as util"), options = CppOptions(lineDirectives = false, namespaces = shared))
        val inOther = emitted.diagnostics("test:other").filter { it.code == CppNamespaceCollisions.CODE }
        val inUtil = emitted.diagnostics("test:util").filter { it.code == CppNamespaceCollisions.CODE }
        assertEquals(listOf("LIMIT", "Pt"), inOther.map(::named).sorted(), emitted.render(inOther))
        assertEquals(listOf("LIMIT", "Pt"), inUtil.map(::named).sorted(), emitted.render(inUtil))
    }

    // A member alias's line includes its module's header as a module alias's does.
    @Test
    fun aPrivateNameMeetsAnExportedOneReachedOnlyThroughAMemberAlias() {
        val viaMember = DeclTestSupport.module("test:other", "test.util.Pt as Point\nLIMIT: Int32 = 2\ncounter: Mutable<Int32> = LIMIT\nat: Mutable<Point> = Point {}\npub get: () Int32")
        val emitted = DeclTestSupport.emit(util, viaMember, options = CppOptions(lineDirectives = false, namespaces = shared))
        val inOther = emitted.diagnostics("test:other").filter { it.code == CppNamespaceCollisions.CODE }
        val inUtil = emitted.diagnostics("test:util").filter { it.code == CppNamespaceCollisions.CODE }
        assertEquals(listOf("LIMIT"), inOther.map(::named), emitted.render(inOther))
        assertEquals(listOf("LIMIT"), inUtil.map(::named), emitted.render(inUtil))
    }

    @Test
    fun aPrivateNameMeetsAnExportedOneReachedThroughAnotherModulesUse() {
        // other uses mid, mid uses util: other's .cxx includes mid's header, which includes util's
        val mid = DeclTestSupport.module(
            "test:mid",
            """
            test.util as util
            test.util.Pt as Pt
            pub Wrap: Struct { pub at: Pt = Pt {} }
            """,
        )
        val emitted = DeclTestSupport.emit(util, mid, other("test.mid as mid"), options = CppOptions(lineDirectives = false, namespaces = shared))
        val inOther = emitted.diagnostics("test:other").filter { it.code == CppNamespaceCollisions.CODE }
        val inUtil = emitted.diagnostics("test:util").filter { it.code == CppNamespaceCollisions.CODE }
        assertEquals(listOf("LIMIT", "Pt"), inOther.map(::named).sorted(), emitted.render(inOther))
        assertEquals(listOf("LIMIT", "Pt"), inUtil.map(::named).sorted(), emitted.render(inUtil))
        assertTrue(emitted.diagnostics("test:mid").none { it.code == CppNamespaceCollisions.CODE }, emitted.render(emitted.diagnostics("test:mid")))
        // the reverse direction reaches nothing: util uses nobody, so its exported names meet no private one of mid
        val reversed = DeclTestSupport.emit(
            DeclTestSupport.module("test:util", "pub LIMIT: Int32 = 1\nhelper: Int32 = 3\npub Pt: Struct { pub x: Int32 = 0 }"),
            DeclTestSupport.module("test:mid", "test.util as util\ntest.util.Pt as Pt\npub Wrap: Struct { pub at: Pt = Pt {} }\nhelper: Mutable<Int32> = 4\npub go: () Void"),
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
            DeclTestSupport.module("test:outer", "$uses\nutil: Int32 = 2\nn: Mutable<Int32> = util\npub go: () Void")
        }
        val ns = mapOf("test:inner" to "shared::util", "test:outer" to "shared")
        val apart = DeclTestSupport.emit(inner, outer(""), options = CppOptions(lineDirectives = false, namespaces = ns))
        val apartAll = apart.diagnostics("test:inner") + apart.diagnostics("test:outer")
        assertTrue(apartAll.none { it.code == CppNamespaceCollisions.CODE }, apart.render(apartAll))
        val reached = DeclTestSupport.emit(inner, outer("test.inner as inner"), options = CppOptions(lineDirectives = false, namespaces = ns))
        val inOuter = reached.diagnostics("test:outer").filter { it.code == CppNamespaceCollisions.CODE }
        assertEquals(listOf("util"), inOuter.map(::named), reached.render(inOuter))
    }

    @Test
    fun twoExportedNamesInOneNamespaceCollideWithoutAnyUse() {
        val a = DeclTestSupport.module("test:a", "pub LIMIT: Int32 = 1")
        val b = DeclTestSupport.module("test:b", "pub LIMIT: Int32 = 2")
        val emitted = DeclTestSupport.emit(a, b, options = CppOptions(lineDirectives = false, namespaces = mapOf("test:a" to "shared", "test:b" to "shared")))
        assertEquals(listOf("LIMIT"), emitted.diagnostics("test:a").filter { it.code == CppNamespaceCollisions.CODE }.map(::named))
        assertEquals(listOf("LIMIT"), emitted.diagnostics("test:b").filter { it.code == CppNamespaceCollisions.CODE }.map(::named))
    }
}
