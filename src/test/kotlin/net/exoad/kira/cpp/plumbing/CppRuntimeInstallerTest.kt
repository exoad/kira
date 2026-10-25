package net.exoad.kira.cpp.plumbing

import net.exoad.kira.compiler.backend.codegen.cpp.CppCompilerVersion
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

    // --- VERSION: the checkout the compiler sits in ------------------------------

    private val shaA = "0123456789abcdef0123456789abcdef01234567"
    private val shaB = "89abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun versionIsTheHeadOfTheCheckoutTheInstallSitsIn() {
        val root = PlumbingTestSupport.tempProject("version-checkout")
        // A plain clone on a branch whose ref is loose.
        val clone = root.resolve("clone")
        PlumbingTestSupport.write(clone, ".git/HEAD", "ref: refs/heads/main\n")
        PlumbingTestSupport.write(clone, ".git/refs/heads/main", "$shaA\n")
        assertEquals(shaA, CppCompilerVersion.fromCheckout(clone.resolve("build/install/kira/lib/kira.jar")))
        // Detached at a SHA, as kira_gen.py checks out tools/kira.lock.
        val detached = root.resolve("detached")
        PlumbingTestSupport.write(detached, ".git/HEAD", "$shaB\n")
        assertEquals(shaB, CppCompilerVersion.fromCheckout(detached.resolve("build/install/kira/lib")))
        // A worktree: .git is a file, the ref lives packed in the common dir.
        PlumbingTestSupport.write(clone, ".git/packed-refs", "# pack-refs with: peeled fully-peeled sorted\n$shaB refs/heads/topic\n")
        PlumbingTestSupport.write(clone, ".git/worktrees/wt/HEAD", "ref: refs/heads/topic\n")
        PlumbingTestSupport.write(clone, ".git/worktrees/wt/commondir", "../..\n")
        val worktree = root.resolve("wt")
        PlumbingTestSupport.write(worktree, ".git", "gitdir: ${clone.resolve(".git/worktrees/wt").toString().replace('\\', '/')}\n")
        assertEquals(shaB, CppCompilerVersion.fromCheckout(worktree.resolve("build/classes/kotlin/main")))
        // A ref nothing resolves is not a version.
        PlumbingTestSupport.write(detached, ".git/HEAD", "ref: refs/heads/nowhere\n")
        assertEquals(null, CppCompilerVersion.fromCheckout(detached.resolve("build/install/kira/lib")))
    }

    @Test
    fun anInstallCopiedIntoAnotherRepositoryNeverTakesThatRepositorysHead() {
        val root = PlumbingTestSupport.tempProject("version-host")
        // A project that keeps a copy of the installed compiler under tools/kira: its .git is the nearest.
        val host = root.resolve("host")
        PlumbingTestSupport.write(host, ".git/HEAD", "$shaA\n")
        val jar = host.resolve("tools/kira/lib/kira.jar")
        PlumbingTestSupport.write(host, "tools/kira/lib/kira.jar", "")
        assertEquals(null, CppCompilerVersion.fromCheckout(jar), "the host's HEAD is not the compiler's version")
        assertTrue(!CppCompilerVersion.isKiraCheckout(host, jar))
        // Not even at a path that merely resembles the install layout.
        PlumbingTestSupport.write(host, "build/install/kira/lib2/kira.jar", "")
        assertEquals(null, CppCompilerVersion.fromCheckout(host.resolve("build/install/kira/lib2/kira.jar")))
        // The Kira checkout itself is known by its settings.gradle.kts, wherever the code sits in it.
        PlumbingTestSupport.write(host, "settings.gradle.kts", "plugins { }\nrootProject.name = \"kira\"\n")
        assertEquals(shaA, CppCompilerVersion.fromCheckout(jar))
        // A settings file naming another project does not make it Kira's.
        PlumbingTestSupport.write(host, "settings.gradle.kts", "rootProject.name = \"bibo\"\n")
        assertEquals(null, CppCompilerVersion.fromCheckout(jar))
        // The design's flow: build/kira-toolchain is a Kira clone inside the project, and its install is accepted.
        val toolchain = host.resolve("build/kira-toolchain")
        PlumbingTestSupport.write(toolchain, ".git/HEAD", "$shaB\n")
        assertEquals(shaB, CppCompilerVersion.fromCheckout(toolchain.resolve("build/install/kira/lib/kira.jar")))
        assertEquals(shaB, CppCompilerVersion.fromCheckout(toolchain.resolve("build/classes/kotlin/main")))
    }

    @Test
    fun theTestsOwnCheckoutGivesItsShaAndASourceTreeWithoutGitGivesDev() {
        if (System.getenv("KIRA_VERSION") != null || System.getProperty("kira.version") != null) {
            return
        }
        // The Kira checkout this test runs in, found the way a person would: the nearest .git above the working directory.
        var dir: java.nio.file.Path? = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        while (dir != null && !Files.exists(dir.resolve(".git"))) {
            dir = dir.parent
        }
        val version = CppCompilerVersion.current()
        if (dir == null) {
            // A git archive, a "Download ZIP" or a release tarball: nothing says which commit this is.
            assertEquals(CppCompilerVersion.DEV, version)
        } else {
            assertTrue(version.length == 40 && version.all { it in '0'..'9' || it in 'a'..'f' }, "expected the checkout's SHA, got '$version'")
        }
    }
}
