package net.exoad.kira.kim

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManifestTest {
    @Test
    fun loadValidManifest() {
        val tempDir = Files.createTempDirectory("kimtest_valid")
        val mf = tempDir.resolve("kira.yaml")
        val content = """
project:
    name: demo

srcDir: src

build:
    target: c
""".trimIndent()
        Files.writeString(mf, content)

        val srcDir = tempDir.resolve("src")
        Files.createDirectories(srcDir)
        val mainKira = srcDir.resolve("main.kira")
        Files.writeString(mainKira, "module \"demo:main\"\n")

        val manifest = ManifestLoader.loadFromPath(mf)
        assertEquals("demo", manifest.project.name)
        assertEquals("src", manifest.srcDir)

        val issues = ManifestValidator.validate(manifest, tempDir)
        assertTrue(issues.isEmpty(), "Expected no validation issues for a valid manifest")
    }

    @Test
    fun validateMissingProjectName() {
        val tempDir = Files.createTempDirectory("kimtest_noname")
        val mf = tempDir.resolve("kira.yaml")
        val content = """
project:
    name: ""

srcDir: src
""".trimIndent()
        Files.writeString(mf, content)
        Files.createDirectories(tempDir.resolve("src"))

        val manifest = ManifestLoader.loadFromPath(mf)
        val issues = ManifestValidator.validate(manifest, tempDir)
        assertTrue(issues.any { it.field == "project.name" }, "Expected a 'project.name' validation issue")
    }

    @Test
    fun validateSrcDirNotFound() {
        val tempDir = Files.createTempDirectory("kimtest_srcdir")
        val mf = tempDir.resolve("kira.yaml")
        val content = """
project:
    name: demo

srcDir: missing
""".trimIndent()
        Files.writeString(mf, content)

        val manifest = ManifestLoader.loadFromPath(mf)
        val issues = ManifestValidator.validate(manifest, tempDir)
        assertTrue(issues.any { it.field == "srcDir" }, "Expected a 'srcDir' validation issue")
    }
}
