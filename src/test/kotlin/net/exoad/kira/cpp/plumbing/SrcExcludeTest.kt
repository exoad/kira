package net.exoad.kira.cpp.plumbing

import net.exoad.kira.kim.DependencyResolver
import net.exoad.kira.kim.ProjectManifest
import net.exoad.kira.kim.ProjectSpec
import net.exoad.kira.kim.SourceGlob
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SrcExcludeTest {
    @Test
    fun aBuildDirectoryHoldingKiraFilesIsIgnored() {
        val root = PlumbingTestSupport.tempProject("srcexclude")
        PlumbingTestSupport.write(root, "src/app/main.kira", "module \"app:main\"\n")
        PlumbingTestSupport.write(root, "build/kira-toolchain/kira/core.kira", "module \"kira:core\"\n")
        PlumbingTestSupport.write(root, "firmware/build/gen.kira", "module \"firmware:gen\"\n")
        PlumbingTestSupport.write(root, "builder/keep.kira", "module \"app:keep\"\n")
        PlumbingTestSupport.write(root, "viewer/assets/car.kira", "module \"viewer:car\"\n")
        PlumbingTestSupport.write(root, "viewer/src/link.kira", "module \"viewer:link\"\n")

        val manifest = ProjectManifest(
            ProjectSpec("bibo"),
            srcDir = ".",
            srcExclude = listOf("build", "third_party", "**/build", "viewer/assets"),
        )
        val sources = DependencyResolver.resolveProjectSources(manifest, root)
            .map { root.relativize(java.nio.file.Path.of(it)).toString().replace('\\', '/') }
            .sorted()
        assertEquals(listOf("builder/keep.kira", "src/app/main.kira", "viewer/src/link.kira"), sources)
    }

    @Test
    fun withoutExcludesEverythingUnderSrcDirIsASource() {
        val root = PlumbingTestSupport.tempProject("srcexclude-none")
        PlumbingTestSupport.write(root, "src/app/main.kira", "module \"app:main\"\n")
        PlumbingTestSupport.write(root, "src/build/gen.kira", "module \"app:gen\"\n")
        val manifest = ProjectManifest(ProjectSpec("demo"), srcDir = "src")
        assertEquals(2, DependencyResolver.resolveProjectSources(manifest, root).size)
    }

    @Test
    fun excludesAreRelativeToTheProjectRootNotSrcDir() {
        val root = PlumbingTestSupport.tempProject("srcexclude-root")
        PlumbingTestSupport.write(root, "src/app/main.kira", "module \"app:main\"\n")
        PlumbingTestSupport.write(root, "src/gen/x.kira", "module \"app:x\"\n")
        val manifest = ProjectManifest(ProjectSpec("demo"), srcDir = "src", srcExclude = listOf("src/gen"))
        val sources = DependencyResolver.resolveProjectSources(manifest, root)
        assertEquals(1, sources.size)
        assertTrue(sources.single().replace('\\', '/').endsWith("src/app/main.kira"))
        // `gen` alone names <root>/gen, which is not <root>/src/gen
        val wrong = ProjectManifest(ProjectSpec("demo"), srcDir = "src", srcExclude = listOf("gen"))
        assertEquals(2, DependencyResolver.resolveProjectSources(wrong, root).size)
    }

    @Test
    fun pathGlobSemantics() {
        assertTrue(SourceGlob.matchesPath("build", "build/x/y.kira"))
        assertTrue(SourceGlob.matchesPath("build/", "build/x/y.kira"))
        assertFalse(SourceGlob.matchesPath("build", "builder/x.kira"))
        assertFalse(SourceGlob.matchesPath("build", "src/build/x.kira"))
        assertTrue(SourceGlob.matchesPath("**/build", "src/build/x.kira"))
        assertTrue(SourceGlob.matchesPath("**/build", "a/b/c/build/x.kira"))
        assertTrue(SourceGlob.matchesPath("viewer/assets", "viewer/assets/car.kira"))
        assertFalse(SourceGlob.matchesPath("viewer/assets", "viewer/src/car.kira"))
        assertTrue(SourceGlob.matchesPath("*.kira", "top.kira"))
        assertFalse(SourceGlob.matchesPath("*.kira", "src/top.kira"))
        assertTrue(SourceGlob.matchesPath("**/*.kira", "src/top.kira"))
        assertTrue(SourceGlob.matchesPath("{build,out}", "out/x.kira"))
    }

    @Test
    fun uriGlobSemantics() {
        assertTrue(SourceGlob.matchesUri("firmware:lib.**", "firmware:lib.text"))
        assertTrue(SourceGlob.matchesUri("firmware:lib.**", "firmware:lib.chassis.cal"))
        assertFalse(SourceGlob.matchesUri("firmware:lib.**", "firmware:pilot.src.scan"))
        assertTrue(SourceGlob.matchesUri("firmware:pilot.src.bibowire.*", "firmware:pilot.src.bibowire.frame"))
        assertFalse(SourceGlob.matchesUri("firmware:pilot.src.bibowire.*", "firmware:pilot.src.bibowire.a.b"))
        assertTrue(SourceGlob.matchesUri("firmware:pilot.src.{scan,speed,unilidar}", "firmware:pilot.src.speed"))
        assertFalse(SourceGlob.matchesUri("firmware:pilot.src.{scan,speed,unilidar}", "firmware:pilot.src.imu"))
        assertTrue(SourceGlob.matchesUri("firmware:pilot.src.proto", "firmware:pilot.src.proto"))
    }
}
