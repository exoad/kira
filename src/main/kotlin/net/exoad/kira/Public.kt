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

    var flags by Delegates.notNull<Map<String, Boolean>>()

    object Builtin {
        val intrinsicalStandardLibrarySources: Array<String> by lazy {
            val rootPath = Paths.get("kira").toAbsolutePath().normalize()
            if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
                return@lazy emptyArray<String>()
            }
            val entries = mutableListOf<String>()
            Files.walk(rootPath).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { p: Path ->
                    entries.add("kira/${rootPath.relativize(p).toString().replace('\\', '/')}")
                }
            }
            entries.sorted().toTypedArray()
        }
    }
}