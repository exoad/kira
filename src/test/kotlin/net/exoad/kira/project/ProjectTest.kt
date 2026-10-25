package net.exoad.kira.project

import net.exoad.kira.Public
import net.exoad.kira.compiler.ProjectFile
import net.exoad.kira.compiler.ProjectSources
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A project at its root: loaded, what Project.issues says of it, and its dependencies' sources. */
class ProjectTest {
    private fun write(dir: Path, body: String) {
        Files.writeString(dir.resolve(ProjectFile.FILE), "PROJECT: Project = Project { } with {\n${body.trimIndent().prependIndent("    ")}\n}\n")
    }

    @Test
    fun loadValidProject() {
        val dir = Files.createTempDirectory("project_valid")
        write(dir, "name = \"demo\"\nsrcDir = \"src\"\ntarget = \"c\"")
        Files.createDirectories(dir.resolve("src"))
        Files.writeString(dir.resolve("src/main.kira"), "")
        val project = ProjectFile.load(dir.resolve(ProjectFile.FILE))
        assertEquals("demo", project.name)
        assertEquals("src", project.srcDir)
        assertTrue(project.issues(dir).isEmpty(), "a valid project has no issues")
    }

    @Test
    fun aBlankNameIsAnIssue() {
        val dir = Files.createTempDirectory("project_noname")
        write(dir, "name = \"\"\nsrcDir = \"src\"")
        Files.createDirectories(dir.resolve("src"))
        assertTrue(ProjectFile.load(dir.resolve(ProjectFile.FILE)).issues(dir).any { it.field == "name" }, "expected a 'name' issue")
    }

    @Test
    fun aMissingSrcDirIsAnIssue() {
        val dir = Files.createTempDirectory("project_srcdir")
        write(dir, "name = \"demo\"\nsrcDir = \"missing\"")
        assertTrue(ProjectFile.load(dir.resolve(ProjectFile.FILE)).issues(dir).any { it.field == "srcDir" }, "expected a 'srcDir' issue")
    }

    @Test
    fun theStdlibIsADependencyByPath() {
        val dir = Files.createTempDirectory("project_stdlib")
        Files.createDirectories(dir.resolve("kira"))
        Files.writeString(dir.resolve("kira/types.kira"), "// sample")
        write(dir, "name = \"demo\"\nsrcDir = \"src\"\ndependencies = @mapOf(\"kira_std\", \"kira\")")
        Files.createDirectories(dir.resolve("src"))
        val entries = ProjectSources.dependencies(ProjectFile.load(dir.resolve(ProjectFile.FILE)), dir)
        Public.Builtin.intrinsicalStandardLibrarySources = entries.distinct().sorted().toTypedArray()
        assertTrue(Public.Builtin.intrinsicalStandardLibrarySources.any { it.endsWith("types.kira") })
    }
}
