package net.exoad.kira.cpp.plumbing

import net.exoad.kira.compiler.CompilationUnit
import net.exoad.kira.compiler.backend.codegen.cpp.CppGenManifest
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
}
