package net.exoad.kira.cpp.plumbing

import net.exoad.kira.compiler.backend.codegen.cpp.CppRuntimeInstaller
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CppRuntimeInstallerTest {
    @Test
    fun plansEveryRuntimeFileUnderKiraPlusVersion() {
        val root = PlumbingTestSupport.tempProject("installer-plan")
        val cppDir = PlumbingTestSupport.fakeStdlib(root)
        PlumbingTestSupport.write(cppDir, "kira/std/README", "nested files come too\n")
        val runtimeDir = root.resolve("firmware/lib")

        val plan = CppRuntimeInstaller(cppDir, runtimeDir, "abc123").plan()

        assertEquals(emptyList(), plan.diagnostics)
        val relative = plan.files.map { runtimeDir.relativize(it.path).toString().replace('\\', '/') }
        assertEquals(listOf("kira/VERSION", "kira/core.hxx", "kira/rt.hxx", "kira/std/README"), relative)
        val version = plan.files.first { it.path.fileName.toString() == "VERSION" }
        assertEquals("abc123\n", version.text)
        val rt = plan.files.first { it.path.fileName.toString() == "rt.hxx" }
        assertEquals(Files.readString(cppDir.resolve("kira/rt.hxx")), rt.text)
        // cpp/tests is not part of the runtime
        assertTrue(relative.none { it.contains("tests") })
    }

    @Test
    fun aCrlfCheckoutIsInstalledAsLf() {
        val root = PlumbingTestSupport.tempProject("installer-crlf")
        val cppDir = PlumbingTestSupport.fakeStdlib(root)
        Files.writeString(cppDir.resolve("kira/rt.hxx"), "#pragma once\r\n#include \"kira/core.hxx\"\r\n// fake rt\r\n")
        val plan = CppRuntimeInstaller(cppDir, root.resolve("lib"), "dev").plan()
        val rt = plan.files.first { it.path.fileName.toString() == "rt.hxx" }
        assertEquals("#pragma once\n#include \"kira/core.hxx\"\n// fake rt\n", rt.text)
        assertTrue(plan.files.first { it.path.fileName.toString() == "core.hxx" }.origin.endsWith("cpp/kira/core.hxx"))
    }

    @Test
    fun missingRuntimeIsAnError() {
        val root = PlumbingTestSupport.tempProject("installer-missing")
        val plan = CppRuntimeInstaller(root.resolve("nowhere/cpp"), root.resolve("lib"), "dev").plan()
        assertTrue(plan.files.isEmpty())
        assertEquals(listOf(CppRuntimeInstaller.MISSING_CODE), plan.diagnostics.map { it.code })
        assertTrue(plan.diagnostics.single().isError)
    }

    @Test
    fun noStdlibAtAllIsAnError() {
        val plan = CppRuntimeInstaller(null, PlumbingTestSupport.tempProject("installer-null").resolve("lib"), "dev").plan()
        assertEquals(listOf(CppRuntimeInstaller.MISSING_CODE), plan.diagnostics.map { it.code })
    }
}
