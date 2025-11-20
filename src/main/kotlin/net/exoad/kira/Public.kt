package net.exoad.kira

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.properties.Delegates

object Public {

    val flagsDefault = mapOf(
        "useDiagnosticsUnicode" to true,
        "beVerbose" to false,
        "enableVisualView" to false,
    )

    // Restored mutable flags map used by tests and runtime toggles
    var flags: Map<String, Boolean> = flagsDefault.toMutableMap()

    object Builtin {
        var intrinsicalStandardLibrarySources: Array<String> = emptyArray()

        // fallback discovery in case no manifest is provided or manifest has no kira deps
        fun discoverLegacyKiraFolder(): Array<String> {
            val rootPath = Paths.get("kira").toAbsolutePath().normalize()
            if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
                return emptyArray()
            }
            val entries = mutableListOf<String>()
            Files.walk(rootPath).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { p: Path ->
                    entries.add(rootPath.resolve(p).toAbsolutePath().toString())
                }
            }
            return entries.sorted().toTypedArray()
        }
    }
}