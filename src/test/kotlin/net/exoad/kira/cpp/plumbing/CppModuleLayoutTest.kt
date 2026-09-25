package net.exoad.kira.cpp.plumbing

import net.exoad.kira.compiler.backend.codegen.cpp.CppLayout
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleLayout
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleRef
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.compiler.backend.codegen.cpp.CppSeverity
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CppModuleLayoutTest {
    // An absolute root under build/: the layout only ever joins and relativizes it, nothing is written.
    private val root: Path = Path.of("build/tmp/cpp-plumbing/layout-root").toAbsolutePath().normalize()

    private val proto = CppModuleRef("firmware:pilot.src.proto", root.resolve("firmware/pilot/src/proto.kira"))
    private val scan = CppModuleRef("firmware:pilot.src.scan", root.resolve("firmware/pilot/src/scan.kira"))
    private val text = CppModuleRef("firmware:lib.text", root.resolve("firmware/lib/text.kira"))
    private val cal = CppModuleRef("firmware:lib.chassis.cal", root.resolve("firmware/lib/chassis/cal.kira"))
    private val wireA = CppModuleRef("firmware:pilot.src.bibowire.frame", root.resolve("firmware/pilot/src/bibowire/frame.kira"))
    private val math = CppModuleRef("kira:math", root.resolve("stdlib/math.kira"))

    private fun rel(path: Path): String = root.relativize(path).toString().replace('\\', '/')

    // --- paths ---------------------------------------------------------------

    @Test
    fun besideLayoutPutsHeaderAndSourceNextToTheKiraFile() {
        val layout = CppModuleLayout(CppOptions(layout = CppLayout.BESIDE), root, listOf(proto))
        val files = layout.filesFor(proto)
        assertEquals("firmware/pilot/src/proto.kira.hxx", rel(files.header))
        assertEquals("firmware/pilot/src/proto.kira.cxx", rel(files.source!!))
    }

    @Test
    fun treeLayoutMirrorsTheUriUnderOutDir() {
        val layout = CppModuleLayout(CppOptions(layout = CppLayout.TREE, outDir = "gen/kira"), root, listOf(proto, cal))
        assertEquals("gen/kira/firmware/pilot/src/proto.kira.hxx", rel(layout.filesFor(proto).header))
        assertEquals("gen/kira/firmware/pilot/src/proto.kira.cxx", rel(layout.filesFor(proto).source!!))
        assertEquals("gen/kira/firmware/lib/chassis/cal.kira.hxx", rel(layout.filesFor(cal).header))
    }

    @Test
    fun headerOnlyGlobDropsTheSource() {
        val options = CppOptions(headerOnly = listOf("firmware:lib.**", "firmware:pilot.src.{scan,imu}"))
        val layout = CppModuleLayout(options, root, listOf(proto, scan, text, cal))
        assertNull(layout.filesFor(text).source)
        assertNull(layout.filesFor(cal).source)
        assertNull(layout.filesFor(scan).source)
        assertEquals("firmware/pilot/src/proto.kira.cxx", rel(layout.filesFor(proto).source!!))
    }

    @Test
    fun customExtensionsAreHonoured() {
        val options = CppOptions(headerExt = ".gen.hpp", sourceExt = ".gen.cpp")
        val layout = CppModuleLayout(options, root, listOf(proto))
        assertEquals("firmware/pilot/src/proto.gen.hpp", rel(layout.filesFor(proto).header))
        assertEquals("firmware/pilot/src/proto.gen.cpp", rel(layout.filesFor(proto).source!!))
    }

    @Test
    fun stdlibModulesLandHeaderOnlyUnderRuntimeKiraStd() {
        val options = CppOptions(runtimeDir = "firmware/lib")
        val layout = CppModuleLayout(options, root, listOf(math))
        val files = layout.filesFor(math)
        assertEquals("firmware/lib/kira/std/math.kira.hxx", rel(files.header))
        assertNull(files.source)
        assertEquals("firmware/lib/kira", rel(layout.runtimeKiraDir))
    }

    @Test
    fun runtimeDirDefaultsToOutDir() {
        val layout = CppModuleLayout(CppOptions(outDir = "gen/kira"), root)
        assertEquals("gen/kira/kira", rel(layout.runtimeKiraDir))
    }

    // --- namespaces ----------------------------------------------------------

    @Test
    fun defaultNamespaceIsTheLastUriSegment() {
        val layout = CppModuleLayout(CppOptions(), root, listOf(proto, cal))
        assertEquals("proto", layout.namespaceFor("firmware:pilot.src.proto"))
        assertEquals("cal", layout.namespaceFor("firmware:lib.chassis.cal"))
    }

    @Test
    fun manifestOverrideWinsAndMayNest() {
        val options = CppOptions(
            namespaces = mapOf(
                "firmware:lib.text" to "bibo::text",
                "firmware:pilot.src.scan" to "bibo",
            )
        )
        val layout = CppModuleLayout(options, root, listOf(text, scan, proto))
        assertEquals("bibo::text", layout.namespaceFor("firmware:lib.text"))
        assertEquals("bibo", layout.namespaceFor("firmware:pilot.src.scan"))
        assertEquals("proto", layout.namespaceFor("firmware:pilot.src.proto"))
    }

    @Test
    fun globOverrideCoversSubmodulesAndExactBeatsGlob() {
        val options = CppOptions(
            namespaces = mapOf(
                "firmware:pilot.src.bibowire.*" to "bibowire",
                "firmware:lib.**" to "lib",
                "firmware:lib.chassis.cal" to "bibo::drive",
            )
        )
        val layout = CppModuleLayout(options, root, listOf(wireA, text, cal))
        assertEquals("bibowire", layout.namespaceFor("firmware:pilot.src.bibowire.frame"))
        assertEquals("lib", layout.namespaceFor("firmware:lib.text"))
        assertEquals("bibo::drive", layout.namespaceFor("firmware:lib.chassis.cal"))
    }

    @Test
    fun longerGlobBeatsShorterGlob() {
        val options = CppOptions(
            namespaces = mapOf(
                "firmware:**" to "fw",
                "firmware:lib.**" to "lib",
            )
        )
        val layout = CppModuleLayout(options, root, listOf(text, proto))
        assertEquals("lib", layout.namespaceFor("firmware:lib.text"))
        assertEquals("fw", layout.namespaceFor("firmware:pilot.src.proto"))
    }

    @Test
    fun kiraModulesMapToKiraNamespace() {
        val layout = CppModuleLayout(CppOptions(), root, listOf(math))
        assertEquals("kira::math", layout.namespaceFor("kira:math"))
    }

    @Test
    fun nestedStdlibModulesKeepTheirPathSoTwoListsNeverMerge() {
        val a = CppModuleRef("kira:collections.list", root.resolve("stdlib/collections/list.kira"))
        val b = CppModuleRef("kira:text.list", root.resolve("stdlib/text/list.kira"))
        val layout = CppModuleLayout(CppOptions(), root, listOf(a, b))
        assertEquals("kira::collections::list", layout.namespaceFor("kira:collections.list"))
        assertEquals("kira::text::list", layout.namespaceFor("kira:text.list"))
        assertEquals(emptyList(), layout.checkCollisions().filter { it.isError })
    }

    @Test
    fun aKeywordSegmentIsEscapedLikeAnyName() {
        val new = CppModuleRef("firmware:pilot.new", root.resolve("firmware/pilot/new.kira"))
        val layout = CppModuleLayout(CppOptions(), root, listOf(new))
        assertEquals("new_", layout.namespaceFor("firmware:pilot.new"))
        assertEquals(emptyList(), layout.checkCollisions().filter { it.isError })
    }

    @Test
    fun stdAndKiraAreReservedAndAManifestKeywordIsAnError() {
        val std = CppModuleRef("app:x.std", root.resolve("a/std.kira"))
        val kira = CppModuleRef("app:x.kira", root.resolve("a/kira.kira"))
        val layout = CppModuleLayout(CppOptions(), root, listOf(std, kira, proto))
        val errors = layout.checkCollisions().filter { it.isError }
        assertEquals(2, errors.size, errors.toString())
        assertTrue(errors.all { it.code == "cpp.namespace-invalid" })
        assertTrue(errors.any { it.message.contains("app:x.std") && it.message.contains("std") })
        assertTrue(errors.any { it.message.contains("app:x.kira") && it.message.contains("runtime") })

        val spelled = CppModuleLayout(
            CppOptions(namespaces = mapOf("firmware:pilot.src.proto" to "bibo::new", "firmware:pilot.src.scan" to "std::x")),
            root, listOf(proto, scan),
        )
        val spelledErrors = spelled.checkCollisions().filter { it.isError }
        assertEquals(2, spelledErrors.size, spelledErrors.toString())
        // a kira: module may sit under kira::
        val stdlib = CppModuleLayout(CppOptions(namespaces = mapOf("kira:math" to "kira::maths")), root, listOf(math))
        assertEquals(emptyList(), stdlib.checkCollisions().filter { it.isError })
    }

    // --- includes ------------------------------------------------------------

    @Test
    fun includePathInTheSameDirectoryIsTheBareName() {
        val layout = CppModuleLayout(CppOptions(), root, listOf(proto, scan))
        val from = layout.filesFor(proto).header
        val to = layout.filesFor(scan).header
        assertEquals("scan.kira.hxx", layout.includePath(from, to))
    }

    @Test
    fun includePathClimbsToALibraryDirectory() {
        val layout = CppModuleLayout(CppOptions(), root, listOf(proto, text))
        val from = layout.filesFor(proto).header
        val to = layout.filesFor(text).header
        assertEquals("../../lib/text.kira.hxx", layout.includePath(from, to))
    }

    @Test
    fun includePathFromASourceFileUsesItsDirectory() {
        val layout = CppModuleLayout(CppOptions(), root, listOf(proto, cal))
        val from = layout.filesFor(proto).source!!
        val to = layout.filesFor(cal).header
        assertEquals("../../lib/chassis/cal.kira.hxx", layout.includePath(from, to))
    }

    // --- collisions ----------------------------------------------------------

    @Test
    fun twoFilesDeclaringOneModuleCollide() {
        val twin = CppModuleRef("firmware:pilot.src.proto", root.resolve("firmware/other/proto.kira"))
        val layout = CppModuleLayout(CppOptions(), root, listOf(proto, twin))
        val errors = layout.checkCollisions().filter { it.isError }
        assertTrue(errors.any { it.code == "cpp.module-collision" }, "expected a module collision, got $errors")
    }

    @Test
    fun twoModulesWritingOneFileCollide() {
        // Tree layout: a module path differing only in a package-level detail the layout drops.
        val a = CppModuleRef("app:x.Main", root.resolve("a/Main.kira"))
        val b = CppModuleRef("app:x.main", root.resolve("b/main.kira"))
        val layout = CppModuleLayout(CppOptions(layout = CppLayout.TREE), root, listOf(a, b))
        val errors = layout.checkCollisions().filter { it.isError }
        assertTrue(errors.any { it.code == "cpp.output-collision" }, "expected an output collision, got $errors")
    }

    @Test
    fun cleanLayoutHasNoCollisions() {
        val layout = CppModuleLayout(CppOptions(), root, listOf(proto, scan, text, cal, wireA, math))
        assertEquals(emptyList(), layout.checkCollisions().filter { it.isError })
    }

    @Test
    fun unusedNamespaceOverrideIsAWarningAndABadOneAnError() {
        val options = CppOptions(namespaces = mapOf("firmware:nope" to "x", "firmware:lib.text" to "bad name"))
        val layout = CppModuleLayout(options, root, listOf(text))
        val diagnostics = layout.checkCollisions()
        assertTrue(diagnostics.any { it.code == "cpp.namespace-unused" && it.severity == CppSeverity.WARNING })
        assertTrue(diagnostics.any { it.code == "cpp.namespace-invalid" && it.isError })
    }

    @Test
    fun aMacroSegmentIsEscapedAndAManifestMacroIsAnError() {
        val modules = listOf("linux", "errno", "unix", "EOF", "INFINITE").map {
            CppModuleRef("app:$it", root.resolve("app/$it.kira"))
        }
        val layout = CppModuleLayout(CppOptions(), root, modules)
        assertEquals(listOf("linux_", "errno_", "unix_", "EOF_", "INFINITE_"), modules.map { layout.namespaceFor(it.uri) })
        assertEquals(emptyList(), layout.checkCollisions().filter { it.isError })
        assertEquals("kira::errno_", CppModuleLayout(CppOptions(), root, emptyList()).namespaceFor("kira:errno"))

        val spelled = CppModuleLayout(
            CppOptions(namespaces = mapOf("firmware:pilot.src.proto" to "errno", "firmware:pilot.src.scan" to "bibo::linux")),
            root, listOf(proto, scan),
        )
        val errors = spelled.checkCollisions().filter { it.isError }
        assertEquals(2, errors.size, errors.toString())
        assertTrue(errors.all { it.code == "cpp.namespace-invalid" && it.message.contains("macro") }, errors.toString())
        assertNull(spelled.namespaceProblem("firmware:pilot.src.proto", "proto"))
        assertTrue(spelled.namespaceProblem("firmware:pilot.src.proto", "EOF")!!.contains("EOF"))
    }
}
