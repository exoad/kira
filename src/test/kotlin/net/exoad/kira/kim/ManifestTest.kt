package net.exoad.kira.kim

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManifestTest {
    @Test
    fun loadValidManifest() {
        val tempDir = Files.createTempDirectory("kimtest_valid")
        val mf = tempDir.resolve("kira.toml")
        val content = """
version = "1"

[package]
name = "demo"
version = "0.1.0"
authors = ["me"]
description = "desc"

[workspace]
src = ["main.kira"]
entry = "main.kira"

[build]
outDir = "build"
target = "c"
""".trimIndent()
        Files.writeString(mf, content)

        val mainKira = tempDir.resolve("main.kira")
        Files.writeString(mainKira, "module \"demo:main\"\n")

        val manifest = ManifestLoader.loadFromPath(mf)
        assertEquals("1", manifest.version)
        assertEquals("demo", manifest.pkg?.name)

        val issues = ManifestValidator.validate(manifest, tempDir)
        assertTrue(issues.isEmpty(), "Expected no validation issues for a valid manifest")
    }

    @Test
    fun validateMissingPackage() {
        val tempDir = Files.createTempDirectory("kimtest_nopkg")
        val mf = tempDir.resolve("kira.toml")
        val content = "version = \"1\"\n"
        Files.writeString(mf, content)

        val manifest = ManifestLoader.loadFromPath(mf)
        val issues = ManifestValidator.validate(manifest, tempDir)
        assertTrue(issues.any { it.field == "package" }, "Expected a 'package' validation issue")
    }

    @Test
    fun validateEntryNotFound() {
        val tempDir = Files.createTempDirectory("kimtest_entry")
        val mf = tempDir.resolve("kira.toml")
        val content = """
version = "1"

[package]
name = "demo"

[workspace]
entry = "missing.kira"
""".trimIndent()
        Files.writeString(mf, content)

        val manifest = ManifestLoader.loadFromPath(mf)
        val issues = ManifestValidator.validate(manifest, tempDir)
        assertTrue(issues.any { it.field == "workspace.entry" }, "Expected a 'workspace.entry' validation issue")
    }
}
