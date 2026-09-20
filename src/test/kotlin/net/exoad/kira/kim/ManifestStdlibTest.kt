package net.exoad.kira.kim

import net.exoad.kira.Public
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertTrue

class ManifestStdlibTest {
    @Test
    fun discoverStdlibFromDependencyPath() {
        val tempDir = Files.createTempDirectory("kim_stdlib_test")
        val kiraDir = tempDir.resolve("kira")
        Files.createDirectories(kiraDir)
        val sample = kiraDir.resolve("types.kira")
        Files.writeString(sample, "// sample")

        val mf = tempDir.resolve("kira.yaml")
        val content = """
project:
  name: demo

srcDir: src

dependencies:
  kira_std:
    path: kira
""".trimIndent()
        Files.writeString(mf, content)
        Files.createDirectories(tempDir.resolve("src"))

        val manifest = ManifestLoader.loadFromPath(mf)
        val stdlibEntries = DependencyResolver.resolveDependencySources(manifest, tempDir)
        Public.Builtin.intrinsicalStandardLibrarySources = stdlibEntries.distinct().sorted().toTypedArray()
        assertTrue(Public.Builtin.intrinsicalStandardLibrarySources.any { it.endsWith("types.kira") })
    }
}

