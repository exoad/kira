package net.exoad.kira.cpp.plumbing

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.backend.codegen.cpp.CppBackendResult
import net.exoad.kira.compiler.backend.codegen.cpp.CppGenManifest
import net.exoad.kira.compiler.backend.codegen.cpp.CppLayout
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleEmitter
import net.exoad.kira.compiler.backend.codegen.cpp.CppModuleEmitterFactory
import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions
import net.exoad.kira.compiler.backend.codegen.cpp.KiraCppBackend
import net.exoad.kira.kim.BuildOptions
import net.exoad.kira.kim.ProjectManifest
import net.exoad.kira.kim.ProjectSpec
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KiraCppBackendTest {
    private class Project(val root: Path, val unit: CompilationUnit, val manifest: ProjectManifest, val stdlibCpp: Path) {
        val outLines = mutableListOf<String>()
        val reportLines = mutableListOf<String>()

        fun run(
            check: Boolean,
            factory: (CompilationUnit, CppOptions) -> CppModuleEmitter = { _, o -> FakeCppModuleEmitter(o) },
            version: String = "abc123",
        ) = KiraCppBackend.run(
            unit, manifest, check, root,
            emitterFactory = factory,
            version = version,
            stdlibCppDir = stdlibCpp,
            log = { },
            report = { reportLines += it },
            out = { outLines += it },
        )

        fun files(): List<String> = PlumbingTestSupport.listFiles(root).filterNot { it.startsWith("stdlib/") }
        fun rel(path: Path): String = root.relativize(path).toString().replace('\\', '/')

        /** The same project seen with only some of its sources, as after a module is deleted. */
        fun with(vararg relativeSources: String): Project {
            val unit = PlumbingTestSupport.compilationUnit(
                relativeSources.associate { root.resolve(it) to Files.readString(root.resolve(it)) }
            )
            return Project(root, unit, manifest, stdlibCpp)
        }
    }

    private fun toCrlf(path: Path) {
        Files.writeString(path, Files.readString(path).replace("\r\n", "\n").replace("\n", "\r\n"))
    }

    private fun manifestLine(root: Path, key: String, at: String = "kira.gen.manifest"): String {
        return Files.readString(root.resolve(at)).lines().first { it.startsWith("$key ") }
    }

    private fun project(name: String, options: CppOptions = CppOptions(runtimeDir = "lib", headerOnly = listOf("demo:lib.**"))): Project {
        val root = PlumbingTestSupport.tempProject(name)
        val stdlibCpp = PlumbingTestSupport.fakeStdlib(root)
        val proto = PlumbingTestSupport.write(root, "src/pilot/proto.kira", PlumbingTestSupport.module("demo:pilot.proto", """
            pub fx command: (verb: Str) Str {
                return verb
            }
        """))
        val text = PlumbingTestSupport.write(root, "lib/text.kira", PlumbingTestSupport.module("demo:lib.text", """
            pub fx isSpace: (c: Int32) Bool {
                return c == 32
            }
        """))
        val unit = PlumbingTestSupport.compilationUnit(
            mapOf(proto to Files.readString(proto), text to Files.readString(text))
        )
        val manifest = ProjectManifest(ProjectSpec("demo"), srcDir = ".", build = BuildOptions(target = "cpp", cpp = options))
        return Project(root, unit, manifest, stdlibCpp)
    }

    @Test
    fun writesModulesRuntimeVersionAndManifest() {
        val p = project("backend-write")
        val result = p.run(check = false)
        assertEquals(0, result.exitCode, p.reportLines.joinToString("\n"))

        val files = p.files()
        listOf(
            "src/pilot/proto.kira.hxx", "src/pilot/proto.kira.cxx",
            "lib/text.kira.hxx",
            "lib/kira/core.hxx", "lib/kira/rt.hxx", "lib/kira/VERSION",
            "kira.gen.manifest",
        ).forEach { assertTrue(it in files, "expected $it in $files") }
        assertFalse("lib/text.kira.cxx" in files, "a header-only module has no source")

        assertEquals("abc123\n", Files.readString(p.root.resolve("lib/kira/VERSION")))
        assertEquals("// fake header for demo:pilot.proto\n#pragma once\n", Files.readString(p.root.resolve("src/pilot/proto.kira.hxx")))

        val manifest = CppGenManifest.parse(Files.readString(p.root.resolve("kira.gen.manifest")))!!
        assertEquals("abc123", manifest.compiler)
        val listed = manifest.entries.map { it.path }
        assertEquals(listed.sorted(), listed)
        assertTrue("src/pilot/proto.kira.hxx" in listed)
        assertTrue("lib/kira/rt.hxx" in listed)
        assertFalse("kira.gen.manifest" in listed)
        val rtHash = CppGenManifest.sha256(Files.readAllBytes(p.root.resolve("lib/kira/rt.hxx")))
        assertEquals(rtHash, manifest.entries.first { it.path == "lib/kira/rt.hxx" }.sha256)
    }

    @Test
    fun checkAfterWriteIsCleanAndASecondWriteTouchesNothing() {
        val p = project("backend-clean")
        assertEquals(0, p.run(check = false).exitCode)
        val second = p.run(check = false)
        assertEquals(0, second.exitCode)
        assertEquals(emptyList(), second.changed, "unchanged content must not be rewritten")
        val check = p.run(check = true)
        assertEquals(0, check.exitCode, p.reportLines.joinToString("\n"))
        assertEquals(emptyList(), p.outLines)
    }

    @Test
    fun checkNamesADriftedFileAndIsCleanAgainAfterRestore() {
        val p = project("backend-drift")
        assertEquals(0, p.run(check = false).exitCode)
        val header = p.root.resolve("src/pilot/proto.kira.hxx")
        val original = Files.readAllBytes(header)

        val modified = original.copyOf()
        modified[0] = '#'.code.toByte()
        Files.write(header, modified)

        val drift = p.run(check = true)
        assertEquals(1, drift.exitCode)
        assertEquals(listOf(header.toAbsolutePath().normalize()), drift.changed)
        assertEquals(listOf("drift: src/pilot/proto.kira.hxx (differs)"), p.outLines)
        assertTrue(p.reportLines.any { it.contains("1 of") && it.contains("drifted") }, p.reportLines.toString())
        assertEquals(modified.toList(), Files.readAllBytes(header).toList(), "check must not write")

        Files.write(header, original)
        p.outLines.clear()
        assertEquals(0, p.run(check = true).exitCode)
        assertEquals(emptyList(), p.outLines)
    }

    @Test
    fun checkNamesAMissingFile() {
        val p = project("backend-missing")
        assertEquals(0, p.run(check = false).exitCode)
        Files.delete(p.root.resolve("lib/kira/VERSION"))
        val drift = p.run(check = true)
        assertEquals(1, drift.exitCode)
        assertEquals(listOf("drift: lib/kira/VERSION (missing)"), p.outLines)
    }

    @Test
    fun checkOnAFreshTreeNamesEveryFile() {
        val p = project("backend-fresh")
        val drift = p.run(check = true)
        assertEquals(1, drift.exitCode)
        assertTrue(p.outLines.all { it.startsWith("drift: ") && it.endsWith(" (missing)") }, p.outLines.toString())
        assertTrue(p.outLines.any { it == "drift: kira.gen.manifest (missing)" })
        assertEquals(emptyList(), p.files().filter { it.endsWith(".hxx") }, "check must never write")
    }

    @Test
    fun defaultFactoryReportsUnsupportedAndWritesNothing() {
        val p = project("backend-unsupported")
        val before = p.files()
        val result = p.run(check = false, factory = CppModuleEmitterFactory::create)
        assertEquals(1, result.exitCode)
        assertTrue(result.diagnostics.any { it.code == CppModuleEmitterFactory.UNSUPPORTED_CODE && it.isError })
        assertTrue(p.reportLines.any { it.contains("cpp.unsupported: the C++ emitter is not built yet") }, p.reportLines.toString())
        assertTrue(p.reportLines.any { it.contains("no file was written") })
        assertEquals(before, p.files(), "nothing may be written when an emitter reports an error")
    }

    @Test
    fun oneFailingModuleBlocksEveryWriteIncludingTheRuntime() {
        val p = project("backend-partial")
        val before = p.files()
        val result = p.run(check = false, factory = { _, o -> FakeCppModuleEmitter(o, failUris = setOf("demo:lib.text")) })
        assertEquals(1, result.exitCode)
        assertEquals(before, p.files())
        assertFalse(Files.exists(p.root.resolve("lib/kira/rt.hxx")))
    }

    @Test
    fun missingRuntimeIsAnErrorEvenWhenEmitSucceeds() {
        val p = project("backend-noruntime")
        val before = p.files()
        val result = KiraCppBackend.run(
            p.unit, p.manifest, false, p.root,
            emitterFactory = { _, o -> FakeCppModuleEmitter(o) },
            stdlibCppDir = p.root.resolve("stdlib/absent"),
            log = { }, report = { p.reportLines += it }, out = { },
        )
        assertEquals(1, result.exitCode)
        assertTrue(result.diagnostics.any { it.code == "cpp.runtime-missing" })
        assertEquals(before, p.files())
    }

    // --- line endings ---------------------------------------------------------

    @Test
    fun aCrlfCheckoutOfTheGeneratedTreeIsNotDrift() {
        val p = project("backend-crlf-tree")
        assertEquals(0, p.run(check = false).exitCode)
        listOf("src/pilot/proto.kira.hxx", "src/pilot/proto.kira.cxx", "lib/kira/rt.hxx", "kira.gen.manifest")
            .forEach { toCrlf(p.root.resolve(it)) }
        assertTrue(Files.readString(p.root.resolve("src/pilot/proto.kira.hxx")).contains("\r\n"))
        val check = p.run(check = true)
        assertEquals(0, check.exitCode, p.outLines.joinToString("\n") + p.reportLines.joinToString("\n"))
        assertEquals(emptyList(), p.outLines)
        // and a write run leaves the CRLF files alone: they are the same file
        val second = p.run(check = false)
        assertEquals(emptyList(), second.changed)
        assertTrue(Files.readString(p.root.resolve("src/pilot/proto.kira.hxx")).contains("\r\n"))
    }

    @Test
    fun aCrlfRuntimeCheckoutInstallsAsLfWithTheSameHashes() {
        val lf = project("backend-crlf-rt-lf")
        val crlf = project("backend-crlf-rt-crlf")
        listOf("kira/core.hxx", "kira/rt.hxx").forEach { toCrlf(crlf.stdlibCpp.resolve(it)) }
        assertTrue(Files.readString(crlf.stdlibCpp.resolve("kira/rt.hxx")).contains("\r\n"))

        assertEquals(0, lf.run(check = false).exitCode)
        assertEquals(0, crlf.run(check = false).exitCode)
        val installed = Files.readAllBytes(crlf.root.resolve("lib/kira/rt.hxx"))
        assertFalse(installed.any { it == '\r'.code.toByte() }, "the installed runtime must be LF")
        assertEquals(Files.readString(lf.root.resolve("lib/kira/rt.hxx")), String(installed))
        assertEquals(manifestLine(lf.root, "stdlib"), manifestLine(crlf.root, "stdlib"))
        assertEquals(Files.readString(lf.root.resolve("kira.gen.manifest")), Files.readString(crlf.root.resolve("kira.gen.manifest")))
    }

    // --- stale files -----------------------------------------------------------

    @Test
    fun aDeletedModulesFilesAreRemovedOnWriteAndCheckStaysHonest() {
        val p = project("backend-stale")
        assertEquals(0, p.run(check = false).exitCode)
        assertTrue(Files.exists(p.root.resolve("lib/text.kira.hxx")))

        // The module is gone: --check must say so, not stay green (the manifest differs too, rightly).
        val only = p.with("src/pilot/proto.kira")
        val check = only.run(check = true)
        assertEquals(1, check.exitCode)
        assertEquals(listOf("drift: kira.gen.manifest (differs)", "drift: lib/text.kira.hxx (stale)"), only.outLines)

        // A write removes what it wrote (the hash still matches the manifest) and the tree is clean.
        val write = only.run(check = false)
        assertEquals(0, write.exitCode, only.reportLines.joinToString("\n"))
        assertEquals(listOf(p.root.resolve("lib/text.kira.hxx")), write.removed)
        assertFalse(Files.exists(p.root.resolve("lib/text.kira.hxx")))
        val again = p.with("src/pilot/proto.kira")
        assertEquals(0, again.run(check = true).exitCode, again.outLines.joinToString("\n"))
        assertEquals(emptyList(), again.outLines)
        assertFalse(Files.readString(p.root.resolve("kira.gen.manifest")).contains("text.kira.hxx"))
    }

    @Test
    fun anEditedStaleFileIsNeverRemovedAndBlocksTheWrite() {
        val p = project("backend-stale-edited")
        assertEquals(0, p.run(check = false).exitCode)
        val stale = p.root.resolve("lib/text.kira.hxx")
        Files.writeString(stale, "// somebody typed here\n")
        val before = p.files()

        val only = p.with("src/pilot/proto.kira")
        val write = only.run(check = false)
        assertEquals(1, write.exitCode)
        assertTrue(write.diagnostics.any { it.code == KiraCppBackend.STALE_CODE && it.isError })
        assertTrue(only.reportLines.any { it.contains("lib/text.kira.hxx") && it.contains("edited") }, only.reportLines.toString())
        assertEquals(before, p.files(), "nothing is written while a stale file is unexplained")
        assertEquals("// somebody typed here\n", Files.readString(stale))

        val check = p.with("src/pilot/proto.kira")
        assertEquals(1, check.run(check = true).exitCode)
        assertTrue("drift: lib/text.kira.hxx (stale)" in check.outLines, check.outLines.toString())
    }

    @Test
    fun staleFilesAreFoundWithoutAManifestWhenTheirShapeOrPlaceSaysGenerated() {
        val p = project("backend-stale-nomanifest")
        assertEquals(0, p.run(check = false).exitCode)
        Files.delete(p.root.resolve("kira.gen.manifest"))
        PlumbingTestSupport.write(p.root, "lib/kira/old.hxx", "// left by an older runtime\n")
        PlumbingTestSupport.write(p.root, "lib/hand.hxx", "// hand-written, not generated-shaped\n")

        val only = p.with("src/pilot/proto.kira")
        val check = only.run(check = true)
        assertEquals(1, check.exitCode)
        assertTrue("drift: lib/text.kira.hxx (stale)" in only.outLines, only.outLines.toString())
        assertTrue("drift: lib/kira/old.hxx (stale)" in only.outLines, only.outLines.toString())
        assertFalse(only.outLines.any { it.contains("hand.hxx") }, only.outLines.toString())

        // Unrecorded, so a write cannot prove it wrote them: an error naming each, and nothing written.
        val write = p.with("src/pilot/proto.kira")
        assertEquals(1, write.run(check = false).exitCode)
        assertTrue(write.reportLines.any { it.contains("lib/text.kira.hxx") && it.contains("not recorded") }, write.reportLines.toString())
        assertTrue(Files.exists(p.root.resolve("lib/text.kira.hxx")))
        assertFalse(Files.exists(p.root.resolve("kira.gen.manifest")))
    }

    // --- collisions -------------------------------------------------------------

    @Test
    fun aRuntimeFileAndAGeneratedStdlibHeaderAtOnePathIsAnError() {
        val p = project("backend-dup")
        PlumbingTestSupport.write(p.stdlibCpp, "kira/std/geometry.kira.hxx", "// shipped by hand\n")
        val geometry = PlumbingTestSupport.write(p.root, "stdlib/geometry.kira", PlumbingTestSupport.module("kira:geometry", """
            pub fx clamp: (v: Int32, lo: Int32, hi: Int32) Int32 {
                return v
            }
        """))
        val unit = PlumbingTestSupport.compilationUnit(mapOf(geometry to Files.readString(geometry)))
        val before = p.files()
        val result = KiraCppBackend.run(
            unit, p.manifest, false, p.root,
            emitterFactory = { _, o -> FakeCppModuleEmitter(o) },
            stdlibCppDir = p.stdlibCpp,
            log = { }, report = { p.reportLines += it }, out = { },
        )
        assertEquals(1, result.exitCode)
        val collision = result.diagnostics.single { it.code == KiraCppBackend.OUTPUT_COLLISION_CODE }
        assertTrue(collision.message.contains("lib/kira/std/geometry.kira.hxx"), collision.message)
        assertTrue(collision.message.contains("kira:geometry") && collision.message.contains("runtime file"), collision.message)
        assertEquals(before, p.files())
    }

    // --- the stdlib hash ---------------------------------------------------------

    @Test
    fun stdlibHashNamesTheRuntimeAloneNotItsPlaceOrTheCompiler() {
        val a = project("backend-stdlibhash-a", CppOptions(runtimeDir = "lib", headerOnly = listOf("demo:lib.**")))
        val b = project("backend-stdlibhash-b", CppOptions(runtimeDir = "third/rt", headerOnly = listOf("demo:lib.**")))
        assertEquals(0, a.run(check = false, version = "1111111").exitCode)
        assertEquals(0, b.run(check = false, version = "2222222").exitCode)
        assertEquals(manifestLine(a.root, "stdlib"), manifestLine(b.root, "stdlib"))
        assertNotEquals(manifestLine(a.root, "compiler"), manifestLine(b.root, "compiler"))
        val c = project("backend-stdlibhash-c")
        Files.writeString(c.stdlibCpp.resolve("kira/rt.hxx"), "#pragma once\n// a different rt\n")
        assertEquals(0, c.run(check = false).exitCode)
        assertNotEquals(manifestLine(a.root, "stdlib"), manifestLine(c.root, "stdlib"))
    }

    // --- --out -------------------------------------------------------------------

    @Test
    fun outOverridePutsEveryGeneratedFileUnderTheDirectoryWhateverTheManifestSays() {
        // bibo's shape: beside layout with runtimeDir set, where --out used to change nothing.
        val p = project("backend-out-beside", CppOptions(layout = net.exoad.kira.compiler.backend.codegen.cpp.CppLayout.BESIDE, runtimeDir = "lib"))
        val before = p.files()
        val result = KiraCppBackend.run(
            p.unit, p.manifest, false, p.root,
            outDirOverride = "gen",
            emitterFactory = { _, o -> FakeCppModuleEmitter(o) },
            stdlibCppDir = p.stdlibCpp,
            log = { }, report = { }, out = { },
        )
        assertEquals(0, result.exitCode)
        val added = p.files() - before.toSet()
        assertTrue(added.isNotEmpty())
        assertTrue(added.all { it.startsWith("gen/") }, "every generated file lives under gen/: $added")
        assertTrue("gen/demo/pilot/proto.kira.hxx" in added, added.toString())
        assertTrue("gen/kira/rt.hxx" in added, added.toString())
        assertTrue("gen/kira.gen.manifest" in added, added.toString())
        assertFalse(Files.exists(p.root.resolve("kira.gen.manifest")))
        assertFalse(Files.exists(p.root.resolve("lib/kira")))
        // and --check honours the same place
        val check = KiraCppBackend.run(
            p.unit, p.manifest, true, p.root, outDirOverride = "gen",
            emitterFactory = { _, o -> FakeCppModuleEmitter(o) }, stdlibCppDir = p.stdlibCpp,
            log = { }, report = { }, out = { p.outLines += it },
        )
        assertEquals(0, check.exitCode, p.outLines.toString())
    }

    @Test
    fun outDirOverrideMovesTheTreeLayout() {
        val p = project("backend-out", CppOptions(layout = net.exoad.kira.compiler.backend.codegen.cpp.CppLayout.TREE))
        val result = KiraCppBackend.run(
            p.unit, p.manifest, false, p.root,
            outDirOverride = "generated",
            emitterFactory = { _, o -> FakeCppModuleEmitter(o) },
            stdlibCppDir = p.stdlibCpp,
            log = { }, report = { }, out = { },
        )
        assertEquals(0, result.exitCode)
        val files = p.files()
        assertTrue("generated/demo/pilot/proto.kira.hxx" in files, files.toString())
        assertTrue("generated/demo/lib/text.kira.hxx" in files, files.toString())
        assertTrue("generated/demo/lib/text.kira.cxx" in files, files.toString())
        assertTrue("generated/kira/rt.hxx" in files, "runtimeDir defaults to outDir: $files")
    }

    @Test
    fun stdlibModulesLandHeaderOnlyUnderKiraStd() {
        val p = project("backend-std")
        // A URI the repository's own stdlib (bootstrapped into every CompilationUnit) does not declare.
        val geometry = PlumbingTestSupport.write(p.root, "stdlib/geometry.kira", PlumbingTestSupport.module("kira:geometry", """
            pub fx clamp: (v: Int32, lo: Int32, hi: Int32) Int32 {
                return v
            }
        """))
        val unit = PlumbingTestSupport.compilationUnit(mapOf(geometry to Files.readString(geometry)))
        val result = KiraCppBackend.run(
            unit, p.manifest, false, p.root,
            emitterFactory = { _, o -> FakeCppModuleEmitter(o) },
            stdlibCppDir = p.stdlibCpp,
            log = { }, report = { p.reportLines += it }, out = { },
        )
        assertEquals(0, result.exitCode, p.reportLines.joinToString("\n"))
        val files = p.files()
        assertTrue("lib/kira/std/geometry.kira.hxx" in files, files.toString())
        assertFalse(files.any { it.startsWith("lib/kira/std/geometry.kira.c") })
    }

    // --- the generated tree shares a directory with other files -------------------

    private fun runWithOut(p: Project, out: String, check: Boolean): CppBackendResult {
        p.outLines.clear()
        p.reportLines.clear()
        return KiraCppBackend.run(
            p.unit, p.manifest, check, p.root, outDirOverride = out,
            emitterFactory = { _, o -> FakeCppModuleEmitter(o) }, stdlibCppDir = p.stdlibCpp,
            log = { }, report = { p.reportLines += it }, out = { p.outLines += it },
        )
    }

    @Test
    fun outDotGeneratesIntoTheProjectRootAndLeavesTheSourcesAlone() {
        val p = project("backend-out-dot")
        val sources = p.files()
        val write = runWithOut(p, ".", check = false)
        assertEquals(0, write.exitCode, p.reportLines.joinToString("\n"))
        val files = p.files()
        assertTrue(sources.all { it in files }, "every source survives: $files")
        listOf("demo/pilot/proto.kira.hxx", "demo/lib/text.kira.hxx", "kira/rt.hxx", "kira/VERSION", "kira.gen.manifest")
            .forEach { assertTrue(it in files, "expected $it in $files") }
        assertEquals(0, runWithOut(p, ".", check = true).exitCode, p.outLines.joinToString("\n"))
        val again = runWithOut(p, ".", check = false)
        assertEquals(0, again.exitCode)
        assertEquals(emptyList(), again.changed)
        assertEquals(emptyList(), again.removed)
    }

    @Test
    fun aTreeLayoutRootedAtTheProjectLeavesTheSourcesAlone() {
        val p = project("backend-tree-dot", CppOptions(layout = CppLayout.TREE, outDir = "."))
        val sources = p.files()
        assertEquals(0, p.run(check = false).exitCode, p.reportLines.joinToString("\n"))
        assertTrue(sources.all { it in p.files() })
        assertTrue("demo/pilot/proto.kira.cxx" in p.files())
        assertEquals(0, p.run(check = true).exitCode, p.outLines.joinToString("\n"))
    }

    @Test
    fun aSharedRuntimeDirKeepsTheUsersOwnFilesAndStillFlagsRuntimeShapedOnes() {
        val p = project("backend-rt-shared", CppOptions(runtimeDir = "."))
        PlumbingTestSupport.write(p.root, "kira/NOTES.md", "mine\n")
        assertEquals(0, p.run(check = false).exitCode, p.reportLines.joinToString("\n"))
        assertEquals("mine\n", Files.readString(p.root.resolve("kira/NOTES.md")))
        assertEquals(0, p.run(check = true).exitCode, p.outLines.joinToString("\n"))

        PlumbingTestSupport.write(p.root, "kira/old.hxx", "// an older runtime's file\n")
        val blocked = p.run(check = false)
        assertEquals(1, blocked.exitCode)
        assertTrue(p.reportLines.any { it.contains("kira/old.hxx") && it.contains("not recorded") }, p.reportLines.toString())
        assertTrue(Files.exists(p.root.resolve("kira/NOTES.md")))
    }

    @Test
    fun aManifestEntryOutsideTheTreeIsIgnoredNeverDeleted() {
        val p = project("backend-escape")
        assertEquals(0, p.run(check = false).exitCode)
        val outside = p.root.parent.resolve("backend-escape-outside.txt")
        Files.writeString(outside, "not yours\n")
        val inside = PlumbingTestSupport.write(p.root, "src/keep.txt", "not yours either\n")
        try {
            val sha = CppGenManifest.sha256("not yours\n".toByteArray())
            val manifest = p.root.resolve("kira.gen.manifest")
            Files.writeString(
                manifest,
                Files.readString(manifest) +
                    "$sha  ../backend-escape-outside.txt\n" +
                    "$sha  ${outside.toString().replace('\\', '/')}\n" +
                    "$sha  /backend-escape-outside.txt\n" +
                    "${CppGenManifest.sha256("not yours either\n".toByteArray())}  src/../src/keep.txt\n",
            )
            val write = p.run(check = false)
            assertEquals(0, write.exitCode, p.reportLines.joinToString("\n"))
            assertEquals(4, write.diagnostics.count { it.code == KiraCppBackend.MANIFEST_ENTRY_CODE && !it.isError })
            assertEquals(emptyList(), write.removed)
            assertTrue(Files.exists(outside), "a manifest entry never reaches outside the tree")
            assertTrue(Files.exists(inside), "a manifest entry never climbs through ..")
            assertFalse(Files.readString(manifest).contains(".."), "the rewritten manifest is clean")
        } finally {
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun aCopiedProjectGeneratesIntoItsOwnTreeAndNeverTouchesTheOriginals() {
        val a = project("backend-copy/a/proj")
        val write = runWithOut(a, "../gen", check = false)
        assertEquals(0, write.exitCode, a.reportLines.joinToString("\n"))
        val aGen = a.root.parent.resolve("gen")
        val aFiles = PlumbingTestSupport.listFiles(aGen)
        assertTrue("kira.gen.manifest" in aFiles && "demo/pilot/proto.kira.hxx" in aFiles, aFiles.toString())
        val entries = CppGenManifest.parse(Files.readString(aGen.resolve("kira.gen.manifest")))!!.entries.map { it.path }
        assertTrue(entries.all { CppGenManifest.isRelativeInside(it) }, "relative to the manifest, never absolute: $entries")
        assertTrue("demo/pilot/proto.kira.hxx" in entries, entries.toString())
        assertEquals(0, runWithOut(a, "../gen", check = true).exitCode, a.outLines.joinToString("\n"))

        // Copy the whole parent (proj and gen) and regenerate the copy: the original tree is not its business.
        val bParent = a.root.parent.parent.resolve("b")
        bParent.toFile().deleteRecursively()
        Files.walk(a.root.parent).use { stream ->
            stream.forEach { source ->
                val target = bParent.resolve(a.root.parent.relativize(source))
                if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
            }
        }
        val bRoot = bParent.resolve("proj")
        val bUnit = PlumbingTestSupport.compilationUnit(
            listOf("src/pilot/proto.kira", "lib/text.kira").associate { bRoot.resolve(it) to Files.readString(bRoot.resolve(it)) }
        )
        val b = Project(bRoot, bUnit, a.manifest, bRoot.resolve("stdlib/cpp"))
        val bCheck = runWithOut(b, "../gen", check = true)
        assertEquals(0, bCheck.exitCode, b.outLines.joinToString("\n"))
        val bWrite = runWithOut(b, "../gen", check = false)
        assertEquals(0, bWrite.exitCode)
        assertEquals(emptyList(), bWrite.removed)
        assertEquals(aFiles, PlumbingTestSupport.listFiles(aGen), "the original's gen/ is intact")
        assertEquals(aFiles, PlumbingTestSupport.listFiles(bParent.resolve("gen")))
    }

    @Test
    fun aRuntimeDirThatLeavesTheProjectIsAnErrorAndNothingIsWritten() {
        val p = project("backend-rt-outside/proj", CppOptions(runtimeDir = "../lib"))
        val before = p.files()
        val result = p.run(check = false)
        assertEquals(1, result.exitCode)
        val outside = result.diagnostics.filter { it.code == KiraCppBackend.OUTSIDE_TREE_CODE }
        assertEquals(1, outside.size, result.diagnostics.toString())
        assertTrue(outside.single().message.contains("runtimeDir '../lib'"), outside.single().message)
        assertEquals(before, p.files())
        assertFalse(Files.exists(p.root.parent.resolve("lib")))
    }
}
