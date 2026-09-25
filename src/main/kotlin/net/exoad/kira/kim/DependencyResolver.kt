package net.exoad.kira.kim

import java.nio.file.Files
import java.nio.file.Path

/**
 * Glob matching for the two shapes the manifest uses: file paths (separator
 * `/`, as in `srcExclude`) and module URIs (separator `.`, as in
 * `build.cpp.headerOnly`).
 *
 * `*` matches within one segment, `?` one character and `{a,b}` either
 * alternative. A segment that is exactly `**` matches zero or more whole
 * segments, so `** /build` (no space) matches `build` as well as `a/b/build`,
 * a double star between `a/` and `/b` matches `a/b`, and `firmware:lib.**`
 * matches `firmware:lib` and everything under it. Everything else is literal.
 */
object SourceGlob {
    fun toRegex(pattern: String, separator: Char): Regex {
        val sep = Regex.escape(separator.toString())
        val segments = splitSegments(pattern, separator)
        val sb = StringBuilder()
        var needSeparator = false
        segments.forEachIndexed { index, segment ->
            if (segment == "**") {
                if (index == segments.lastIndex) {
                    sb.append(if (needSeparator) "(?:$sep.*)?" else ".*")
                } else {
                    if (needSeparator) {
                        sb.append(sep)
                    }
                    sb.append("(?:[^").append(sep).append("]*").append(sep).append(")*")
                }
                needSeparator = false
            } else {
                if (needSeparator) {
                    sb.append(sep)
                }
                sb.append(segmentRegex(segment, sep))
                needSeparator = true
            }
        }
        return Regex(sb.toString())
    }

    /** Splits on [separator] outside `{...}`, so `{a/b,c}` stays one segment. */
    private fun splitSegments(pattern: String, separator: Char): List<String> {
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        var braceDepth = 0
        pattern.forEach { c ->
            when {
                c == '{' -> braceDepth += 1
                c == '}' && braceDepth > 0 -> braceDepth -= 1
            }
            if (c == separator && braceDepth == 0) {
                segments += current.toString()
                current.setLength(0)
            } else {
                current.append(c)
            }
        }
        segments += current.toString()
        return segments
    }

    /** One segment's glob; a `**` inside a segment (`firmware:**`, where the package colon is no separator) spans separators. */
    private fun segmentRegex(segment: String, sep: String): String {
        val sb = StringBuilder()
        var braceDepth = 0
        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            if (c == '*' && i + 1 < segment.length && segment[i + 1] == '*') {
                sb.append(".*")
                i += 2
                continue
            }
            when {
                c == '*' -> sb.append("[^").append(sep).append("]*")
                c == '?' -> sb.append("[^").append(sep).append("]")
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
        return sb.toString()
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
     * every `build` directory. A leading `./` or `/` means the project root,
     * which is where every pattern is anchored anyway, so `./build` and
     * `/build` are `build`.
     */
    fun matchesPath(pattern: String, relativePath: String): Boolean {
        val normalizedPattern = normalizePathPattern(pattern)
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

    /** Forward slashes, no trailing slash, and no `./` or `/` anchoring the root: `.//./build/` is `build`. */
    fun normalizePathPattern(pattern: String): String {
        var p = pattern.replace('\\', '/').trimEnd('/')
        while (true) {
            p = when {
                p.startsWith("./") -> p.substring(2)
                p.startsWith("/") -> p.substring(1)
                p == "." -> ""
                else -> return p
            }
        }
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
