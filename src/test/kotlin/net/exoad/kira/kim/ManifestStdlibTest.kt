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

        val mf = tempDir.resolve("kira.toml")
        val content = """
version = "1"

[package]
name = "demo"

[dependencies.kira_std]
path = "kira"
registry = "kira"
""".trimIndent()
        Files.writeString(mf, content)

        val manifest = ManifestLoader.loadFromPath(mf)
        // replicate stdlib resolution logic (as in Main.kt)
        val stdlibEntries = mutableListOf<String>()
        if (manifest.dependencies.isNotEmpty()) {
            manifest.dependencies.forEach { (name, spec) ->
                if (spec.registry == "kira") {
                    if (spec.path != null) {
                        val depPath = tempDir.resolve(spec.path).normalize()
                        if (Files.exists(depPath)) {
                            Files.walk(depPath).use { stream ->
                                stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kira") }
                                    .forEach { stdlibEntries.add(it.toAbsolutePath().toString()) }
                            }
                        }
                    }
                }
            }
        }
        Public.Builtin.intrinsicalStandardLibrarySources = stdlibEntries.distinct().sorted().toTypedArray()
        assertTrue(Public.Builtin.intrinsicalStandardLibrarySources.any { it.endsWith("types.kira") })
    }
}

