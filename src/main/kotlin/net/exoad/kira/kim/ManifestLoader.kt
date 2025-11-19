package net.exoad.kira.kim

import com.akuleshov7.ktoml.Toml
import kotlinx.serialization.decodeFromString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

interface ManifestParser {
    fun parse(text: String): ProjectManifest
}

class KTomlManifestParser(private val toml: Toml = Toml()) : ManifestParser {
    override fun parse(text: String): ProjectManifest {
        return toml.decodeFromString<ProjectManifest>(text)
    }
}

object ManifestLoader {
    fun loadFromPath(manifestPath: Path, parser: ManifestParser = KTomlManifestParser()): ProjectManifest {
        require(manifestPath.exists()) { "Manifest file not found: $manifestPath" }
        return parser.parse(Files.readString(manifestPath))
    }
}