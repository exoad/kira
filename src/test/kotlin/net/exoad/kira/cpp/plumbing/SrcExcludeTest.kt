package net.exoad.kira.cpp.plumbing

import net.exoad.kira.compiler.Project
import net.exoad.kira.compiler.ProjectSources
import net.exoad.kira.compiler.SourceGlob
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SrcExcludeTest {
    @Test
    fun aBuildDirectoryHoldingKiraFilesIsIgnored() {
        val root = PlumbingTestSupport.tempProject("srcexclude")
        PlumbingTestSupport.write(root, "src/app/main.kira", "")
        PlumbingTestSupport.write(root, "build/kira-toolchain/kira/core.kira", "")
        PlumbingTestSupport.write(root, "firmware/build/gen.kira", "")
        PlumbingTestSupport.write(root, "builder/keep.kira", "")
        PlumbingTestSupport.write(root, "viewer/assets/car.kira", "")
        PlumbingTestSupport.write(root, "viewer/src/link.kira", "")

        val project = Project("bibo",
            srcDir = ".",
            srcExclude = listOf("build", "third_party", "**/build", "viewer/assets"),
        )
        val sources = ProjectSources.workspace(project, root)
            .map { root.relativize(java.nio.file.Path.of(it)).toString().replace('\\', '/') }
            .sorted()
        assertEquals(listOf("builder/keep.kira", "src/app/main.kira", "viewer/src/link.kira"), sources)
    }

    @Test
    fun withoutExcludesEverythingUnderSrcDirIsASource() {
        val root = PlumbingTestSupport.tempProject("srcexclude-none")
        PlumbingTestSupport.write(root, "src/app/main.kira", "")
        PlumbingTestSupport.write(root, "src/build/gen.kira", "")
        val project = Project("demo", srcDir = "src")
        assertEquals(2, ProjectSources.workspace(project, root).size)
    }

    @Test
    fun excludesAreRelativeToTheProjectRootNotSrcDir() {
        val root = PlumbingTestSupport.tempProject("srcexclude-root")
        PlumbingTestSupport.write(root, "src/app/main.kira", "")
        PlumbingTestSupport.write(root, "src/gen/x.kira", "")
        val project = Project("demo", srcDir = "src", srcExclude = listOf("src/gen"))
        val sources = ProjectSources.workspace(project, root)
        assertEquals(1, sources.size)
        assertTrue(sources.single().replace('\\', '/').endsWith("src/app/main.kira"))
        // `gen` alone names <root>/gen, which is not <root>/src/gen
        val wrong = Project("demo", srcDir = "src", srcExclude = listOf("gen"))
        assertEquals(2, ProjectSources.workspace(wrong, root).size)
    }

    @Test
    fun pathGlobSemantics() {
        assertTrue(SourceGlob.matchesPath("build", "build/x/y.kira"))
        assertTrue(SourceGlob.matchesPath("build/", "build/x/y.kira"))
        assertFalse(SourceGlob.matchesPath("build", "builder/x.kira"))
        assertFalse(SourceGlob.matchesPath("build", "src/build/x.kira"))
        assertTrue(SourceGlob.matchesPath("**/build", "src/build/x.kira"))
        assertTrue(SourceGlob.matchesPath("**/build", "a/b/c/build/x.kira"))
        // `**` matches zero segments too: the root's build directory, and a/b for a/**/b
        assertTrue(SourceGlob.matchesPath("**/build", "build/x.kira"))
        assertTrue(SourceGlob.matchesPath("a/**/b", "a/b/x.kira"))
        assertTrue(SourceGlob.matchesPath("a/**/b", "a/p/q/b/x.kira"))
        assertFalse(SourceGlob.matchesPath("a/**/b", "a/bb/x.kira"))
        assertFalse(SourceGlob.matchesPath("**/build", "builder/x.kira"))
        assertTrue(SourceGlob.matchesPath("src/**", "src/x.kira"))
        assertTrue(SourceGlob.matchesPath("{build,out}/**", "out/x.kira"))
        assertTrue(SourceGlob.matchesPath("viewer/assets", "viewer/assets/car.kira"))
        assertFalse(SourceGlob.matchesPath("viewer/assets", "viewer/src/car.kira"))
        assertTrue(SourceGlob.matchesPath("*.kira", "top.kira"))
        assertFalse(SourceGlob.matchesPath("*.kira", "src/top.kira"))
        assertTrue(SourceGlob.matchesPath("**/*.kira", "src/top.kira"))
        assertTrue(SourceGlob.matchesPath("{build,out}", "out/x.kira"))
        // `./` and `/` anchor the root, where every pattern is anchored anyway
        assertTrue(SourceGlob.matchesPath("./build", "build/x.kira"))
        assertTrue(SourceGlob.matchesPath("/build", "build/x.kira"))
        assertTrue(SourceGlob.matchesPath("./build/", "build/x.kira"))
        assertTrue(SourceGlob.matchesPath(".\\viewer\\assets", "viewer/assets/car.kira"))
        assertFalse(SourceGlob.matchesPath("./build", "src/build/x.kira"))
        assertFalse(SourceGlob.matchesPath("./", "src/x.kira"))
        assertTrue(SourceGlob.matchesPath("./{a/b,c}/**", "a/b/x.kira"))
    }

    @Test
    fun anExcludeAScanAlreadyStandsInsideDoesNotPruneBelowIt() {
        // Scanning build/gen (two segments): `build` covers it, so it hides nothing inside...
        assertFalse(SourceGlob.matchesPath("build", "build/gen/app", belowSegments = 2))
        assertFalse(SourceGlob.matchesPath("**/build", "build/gen/app", belowSegments = 2))
        // ...but a build directory further down is still excluded.
        assertTrue(SourceGlob.matchesPath("**/build", "build/gen/app/build", belowSegments = 2))
        assertTrue(SourceGlob.matchesPath("build/gen/app", "build/gen/app/x", belowSegments = 2))
        // Scanning the root (zero segments) is the plain rule.
        assertTrue(SourceGlob.matchesPath("build", "build/gen/app", belowSegments = 0))

        val root = PlumbingTestSupport.tempProject("srcexclude-below")
        val excludes = listOf("build", "**/build")
        val inside = root.resolve("build/gen/app").toString()
        assertTrue(ProjectSources.isExcluded(inside, root, excludes))
        assertFalse(ProjectSources.isExcluded(inside, root, excludes, below = root.resolve("build/gen")))
        assertTrue(ProjectSources.isExcluded(root.resolve("build/gen/app/build").toString(), root, excludes, below = root.resolve("build/gen")))
        assertTrue(ProjectSources.isExcluded(inside, root, excludes, below = root))
    }

    @Test
    fun aDotSlashExcludeIsTheSameExclude() {
        val root = PlumbingTestSupport.tempProject("srcexclude-dotslash")
        PlumbingTestSupport.write(root, "src/app/main.kira", "")
        PlumbingTestSupport.write(root, "build/gen.kira", "")
        val project = Project("demo", srcDir = ".", srcExclude = listOf("./build"))
        val sources = ProjectSources.workspace(project, root)
            .map { root.relativize(java.nio.file.Path.of(it)).toString().replace('\\', '/') }
        assertEquals(listOf("src/app/main.kira"), sources)
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
        assertTrue(SourceGlob.matchesUri("firmware:lib.**", "firmware:lib"))
        assertFalse(SourceGlob.matchesUri("firmware:lib.**", "firmware:library"))
        assertTrue(SourceGlob.matchesUri("firmware:lib.**.cal", "firmware:lib.cal"))
        assertTrue(SourceGlob.matchesUri("firmware:lib.**.cal", "firmware:lib.chassis.cal"))
    }

    @Test
    fun aRootBuildDirectoryIsExcludedByTheDoubleStarPatternAlone() {
        val root = PlumbingTestSupport.tempProject("srcexclude-root-build")
        PlumbingTestSupport.write(root, "src/app/main.kira", "")
        PlumbingTestSupport.write(root, "build/kira-toolchain/kira/core.kira", "")
        val project = Project("bibo", srcDir = ".", srcExclude = listOf("**/build"))
        val sources = ProjectSources.workspace(project, root)
            .map { root.relativize(java.nio.file.Path.of(it)).toString().replace('\\', '/') }
        assertEquals(listOf("src/app/main.kira"), sources)
    }
}
