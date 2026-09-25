package net.exoad.kira.kim

import java.nio.file.Files
import java.nio.file.Path

/**
 * Glob matching for the two shapes the manifest uses: file paths (separator
 * `/`, as in `srcExclude`) and module URIs (separator `.`, as in
 * `build.cpp.headerOnly`).
 *
 * `*` matches within one segment, `**` across segments, `?` one character
 * and `{a,b}` either alternative. Everything else is literal.
 */
object SourceGlob {
    fun toRegex(pattern: String, separator: Char): Regex {
        val sb = StringBuilder()
        var i = 0
        var braceDepth = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '*' && i + 1 < pattern.length && pattern[i + 1] == '*' -> {
                    sb.append(".*")
                    i += 2
                    continue
                }
                c == '*' -> sb.append("[^").append(Regex.escape(separator.toString())).append("]*")
                c == '?' -> sb.append("[^").append(Regex.escape(separator.toString())).append("]")
                c == '{' -> {
                    braceDepth += 1
                    sb.append("(?:")
                }
                c == '}' && braceDepth > 0 -> {
                    braceDepth -= 1
                    sb.append(")")
                }
                c == ',' && braceDepth > 0 -> sb.append("|")
                else -> sb.append(Regex.escape(c.toString()))
            }
            i += 1
        }
        return Regex(sb.toString())
    }

    /** A module URI glob against a whole URI. */
    fun matchesUri(pattern: String, uri: String): Boolean {
        if (pattern == uri) {
            return true
        }
        return toRegex(pattern, '.').matches(uri)
    }

    /**
     * A path glob against a `/`-separated path relative to the project root.
     * A pattern that names a directory matches everything beneath it, so
     * `build` excludes `build/x/y.kira` and `** /build` (no space) excludes
     * every `build` directory.
     */
    fun matchesPath(pattern: String, relativePath: String): Boolean {
        val normalizedPattern = pattern.replace('\\', '/').trimEnd('/')
        if (normalizedPattern.isEmpty()) {
            return false
        }
        val regex = toRegex(normalizedPattern, '/')
        val segments = relativePath.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        for (end in 1..segments.size) {
            if (regex.matches(segments.subList(0, end).joinToString("/"))) {
                return true
            }
        }
        return false
    }
}

object DependencyResolver {
    fun resolveProjectSources(manifest: ProjectManifest?, projectRoot: Path): List<String> {
        val root = when {
            manifest != null -> projectRoot.resolve(manifest.srcDir).normalize()
            Files.exists(projectRoot.resolve("src")) -> projectRoot.resolve("src").normalize()
            else -> projectRoot
        }
        val excludes = manifest?.srcExclude.orEmpty()
        val absoluteRoot = projectRoot.toAbsolutePath().normalize()

        val primary = scanKiraFiles(root).filterNot { isExcluded(it, absoluteRoot, excludes) }
        if (primary.isNotEmpty()) {
            return primary
        }

        if (manifest == null && root != projectRoot) {
            return scanKiraFiles(projectRoot)
        }
        return emptyList()
    }

    /** True when [file] sits under a path that one of [excludes] names, relative to [projectRoot]. */
    fun isExcluded(file: String, projectRoot: Path, excludes: List<String>): Boolean {
        if (excludes.isEmpty()) {
            return false
        }
        val absolute = Path.of(file).toAbsolutePath().normalize()
        val root = projectRoot.toAbsolutePath().normalize()
        if (!absolute.startsWith(root)) {
            return false
        }
        val relative = root.relativize(absolute).toString().replace('\\', '/')
        return excludes.any { SourceGlob.matchesPath(it, relative) }
    }

    fun resolveDependencySources(manifest: ProjectManifest?, projectRoot: Path): List<String> {
        if (manifest == null || manifest.dependencies.isEmpty()) {
            return scanKiraFiles(projectRoot.resolve("kira").normalize())
        }

        val sources = mutableListOf<String>()
        manifest.dependencies.values.forEach { spec ->
            val path = spec.path ?: return@forEach
            val resolved = resolvePath(projectRoot, path) ?: return@forEach
            sources.addAll(scanKiraFiles(resolved))
        }

        return sources.distinct().sorted()
    }

    fun resolvePath(projectRoot: Path, rawPath: String): Path? {
        val direct = projectRoot.resolve(rawPath).normalize()
        if (Files.exists(direct)) {
            return direct
        }

        val parent = projectRoot.parent ?: return null
        val parentResolved = parent.resolve(rawPath).normalize()
        return if (Files.exists(parentResolved)) parentResolved else null
    }

    private fun scanKiraFiles(path: Path): List<String> {
        if (!Files.exists(path)) {
            return emptyList()
        }

        if (Files.isRegularFile(path)) {
            return if (path.toString().endsWith(".kira")) {
                listOf(path.toAbsolutePath().toString())
            } else {
                emptyList()
            }
        }

        if (!Files.isDirectory(path)) {
            return emptyList()
        }

        val entries = mutableListOf<String>()
        Files.walk(path).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kira") }
                .forEach { entries.add(it.toAbsolutePath().toString()) }
        }
        return entries.distinct().sorted()
    }
}
