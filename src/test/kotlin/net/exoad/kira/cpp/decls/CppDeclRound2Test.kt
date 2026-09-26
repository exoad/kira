package net.exoad.kira.cpp.decls

import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleEmitterFactory
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleLayout
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleRef
import net.exoad.kira.compiler.backend.codegen.cpp.CppNamespaceCollisions
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.compiler.backend.codegen.cpp.KiraCppBackend
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the second fix round pinned: a system module's `@_magic` class built empty is
 * `make_shared`, never a null `kira::Rc`; a system module is its runtime header alone and
 * is never emitted as `kira/std/<name>.kira.hxx`; a `.cxx`-private name collides with
 * another module's exported name in a shared namespace; and `std` or `kira` is refused as
 * any segment of a namespace, not the first alone.
 */
class CppDeclRound2Test {
    private fun assertContains(text: String, vararg wanted: String) {
        wanted.forEach { assertTrue(text.contains(it), "expected:\n$it\nin:\n$text") }
    }

    private fun assertLacks(text: String, vararg unwanted: String) {
        unwanted.forEach { assertTrue(!text.contains(it), "did not expect:\n$it\nin:\n$text") }
    }

    private fun named(d: net.exoad.kira.compiler.backend.codegen.cpp.CppDiagnostic): String = d.message.substringAfter("'").substringBefore("'")

    // ---- issue 1: a system class built empty --------------------------------------------------------

    @Test
    fun aSystemClassBuiltEmptyIsMakeSharedEverywhereItIsDeclared() {
        val emitted = DeclTestSupport.emit(
            DeclTestSupport.module(
                "test:hold",
                """
                use "kira:sync"
                use "kira:os"

                pub struct Holder {
                    pub q: BlockingQueue<Str> = BlockingQueue<Str> {}
                    pub xs: List<Str> = List<Str> {}
                    pub poll: Poller = Poller {}
                    pub sock: UdpSocket = UdpSocket {}
                }
                pub mut inbox: BlockingQueue<Str> = BlockingQueue<Str> {}
                pub OUTBOX: BlockingQueue<Int32> = BlockingQueue<Int32> {}
                mut backlog: BlockingQueue<Int32> = BlockingQueue<Int32> {}
                pub fx go: () Void;
                """,
            ),
        )
        val h = emitted.header("test:hold")
        assertContains(
            h,
            "kira::Rc<kira::sync::BlockingQueue<kira::Str>> q = std::make_shared<kira::sync::BlockingQueue<kira::Str>>();",
            "kira::List<kira::Str> xs{};",
            "kira::Rc<kira::os::Poller> poll = std::make_shared<kira::os::Poller>();",
            "kira::Rc<kira::os::UdpSocket> sock = std::make_shared<kira::os::UdpSocket>();",
            "inline kira::Rc<kira::sync::BlockingQueue<kira::Str>> inbox = std::make_shared<kira::sync::BlockingQueue<kira::Str>>();",
            "inline const kira::Rc<kira::sync::BlockingQueue<std::int32_t>> OUTBOX = std::make_shared<kira::sync::BlockingQueue<std::int32_t>>();",
        )
        // never the null-pointer forms this round's first version wrote
        assertLacks(h, "> q{};", "> poll{};", "> sock{};", "BlockingQueue<kira::Str>>{}")
        val s = emitted.source("test:hold")
        assertNotNull(s, "a private mut gives the module a .cxx")
        assertContains(s, "kira::Rc<kira::sync::BlockingQueue<std::int32_t>> backlog = std::make_shared<kira::sync::BlockingQueue<std::int32_t>>();")
    }

    // ---- issue 2: a system module is its runtime header alone -------------------------------------------

    @Test
    fun aSystemModuleIsIncludedAsItsRuntimeHeaderAloneAndNeverEmitted() {
        val net = DeclTestSupport.module(
            "test:net",
            """
            use "kira:os"

            pub struct Link {
                pub last: Datagram
                pub sock: Maybe<UdpSocket> = null
                pub events: Int32 = POLL_READ
            }
            pub fx send: (s: UdpSocket, d: Datagram) Bool;
            pub fx ready: (r: Ready) Bool;
            """,
        )
        val emitted = DeclTestSupport.emit(net)
        val h = emitted.header("test:net")
        assertContains(
            h,
            "#include \"kira/os.hxx\"",
            "::kira::os::Datagram last{};",
            "kira::Maybe<kira::Rc<kira::os::UdpSocket>> sock = kira::none;",
            "std::int32_t events = ::kira::os::POLL_READ;",
            "[[nodiscard]] bool send(const kira::Rc<kira::os::UdpSocket>& s, const ::kira::os::Datagram& d);",
        )
        // kira/os.hxx defines Datagram, Ready and POLL_* by hand: the emitted twin would redefine them
        assertLacks(h, "kira/std/os.kira.hxx")
        assertEquals(1, h.lines().count { it.contains("kira/os.hxx") }, "included once:\n$h")

        // The CLI's path: the workspace module, then the stdlib modules it reaches; a system module is not one of them.
        val (unit, _) = DeclTestSupport.unitOf(listOf(net))
        val options = CppOptions(lineDirectives = false)
        val emitter = CppModuleEmitterFactory.create(unit, options)
        val sources = unit.allSources().mapNotNull { src ->
            val uri = runCatching { src.getModuleUri() }.getOrNull() ?: return@mapNotNull null
            if (uri.startsWith("(unknown)")) null else CppModuleRef(uri, Path.of(src.file)) to src
        }
        emitter.prepare(CppModuleLayout(options, DeclTestSupport.root, sources.map { it.first }), "dev")
        val all = KiraCppBackend.emitModules(emitter, sources)
        val uris = all.map { it.ref.uri }
        assertTrue("test:net" in uris, uris.toString())
        assertTrue(all.first { it.ref.uri == "test:net" }.emitted.uses.contains("kira:os"), "the module reaches kira:os")
        assertTrue(uris.none { it == "kira:os" || it == "kira:sync" || it == "kira:test" || it == "kira:time" }, "no system module is emitted: $uris")
    }

    // ---- issue 3: a private name against an exported one in a shared namespace ------------------------

    @Test
    fun aPrivateNameOfTheCxxCollidesWithAnotherModulesExportedName() {
        val util = DeclTestSupport.module(
            "test:util",
            """
            pub LIMIT: Int32 = 1
            pub struct Pt { pub x: Int32 = 0 }
            hidden: Int32 = 4
            """,
        )
        val other = DeclTestSupport.module(
            "test:other",
            """
            use "test:util"

            LIMIT: Int32 = 2
            mut counter: Int32 = LIMIT
            hidden: Int32 = 5
            pub fx get: (p: Pt) Int32;
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
