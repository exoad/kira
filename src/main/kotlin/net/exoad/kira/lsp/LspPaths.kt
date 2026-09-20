package net.exoad.kira.lsp

import java.io.File
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

object LspPaths {
    fun uriToPath(uri: String): Path {
        return try {
            val parsed = URI(uri)
            if (parsed.scheme == "file") {
                Paths.get(parsed)
            } else {
                Paths.get(uri)
            }
        } catch (_: Exception) {
            Paths.get(uri.removePrefix("file://"))
        }.toAbsolutePath().normalize()
    }

    fun pathToUri(path: String): String {
        return File(path).toURI().toString()
    }

    fun pathToUri(path: Path): String = path.toUri().toString()
}
