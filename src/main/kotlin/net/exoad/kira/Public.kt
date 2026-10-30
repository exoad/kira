package net.exoad.kira

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object Public {

    object Builtin {
        var intrinsicalStandardLibrarySources: Array<String> = emptyArray()

        // fallback discovery when there is no project.kira, or it names no kira dependency
        fun discoverLegacyKiraFolder(): Array<String> {
            val rootPath = Paths.get("kira").toAbsolutePath().normalize()
            if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
                return emptyArray()
            }
            val entries = mutableListOf<String>()
            Files.walk(rootPath).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kira") }
                    .forEach { p: Path ->
                        entries.add(p.toAbsolutePath().normalize().toString())
                    }
            }
            return entries.sorted().toTypedArray()
        }
    }
}