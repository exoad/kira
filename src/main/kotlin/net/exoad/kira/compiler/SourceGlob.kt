package net.exoad.kira.compiler

/**
 * Glob matching for the two shapes a project uses: file paths (separator
 * `/`, as in `srcExclude`) and module URIs (separator `.`, as in
 * `cpp.headerOnly`).
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

    /**
     * The value of the key in [map] that names [uri]: an exact key first, then the
     * matching glob with the most literal text, then the longer glob. Key order
     * never matters. `cpp.namespaces` resolves through this, in the layout
     * and in the typer alike.
     */
    fun <V> lookupUri(map: Map<String, V>, uri: String): V? {
        map[uri]?.let { return it }
        map.entries.firstOrNull { (k, _) -> !isGlob(k) && dotted(k) == dotted(uri) }?.let { return it.value }
        return map.entries
            .filter { (pattern, _) -> isGlob(pattern) && matchesUri(pattern, uri) }
            .sortedWith(compareByDescending<Map.Entry<String, V>> { literalLength(it.key) }
                .thenByDescending { it.key.length })
            .firstOrNull()
            ?.value
    }

    fun isGlob(pattern: String): Boolean {
        return pattern.any { it == '*' || it == '?' || it == '{' }
    }

    private fun literalLength(pattern: String): Int {
        return pattern.count { it != '*' && it != '?' && it != '{' && it != '}' && it != ',' }
    }

    /** A module URI glob against a whole URI, both read as the dotted name a program writes. */
    fun matchesUri(pattern: String, uri: String): Boolean {
        if (pattern == uri || dotted(pattern) == dotted(uri)) {
            return true
        }
        return toRegex(dotted(pattern), '.').matches(dotted(uri))
    }

    /** A module's name as a program writes it: `project.path`, its URI's ':' a '.'. */
    private fun dotted(uri: String): String = uri.replace(':', '.')

    /**
     * A path glob against a `/`-separated path relative to the project root.
     * A pattern that names a directory matches everything beneath it, so
     * `build` excludes `build/x/y.kira` and `** /build` (no space) excludes
     * every `build` directory. A leading `./` or `/` means the project root,
     * which is where every pattern is anchored anyway, so `./build` and
     * `/build` are `build`.
     *
     * With [belowSegments], only a prefix longer than that many segments
     * counts: a scan that already stands inside `build/gen` (two segments)
     * asks whether an exclude names something *below* it, so `build` no
     * longer hides `build/gen/app`, while `** /build` still hides
     * `build/gen/app/build`.
     */
    fun matchesPath(pattern: String, relativePath: String, belowSegments: Int = 0): Boolean {
        val normalizedPattern = normalizePathPattern(pattern)
        if (normalizedPattern.isEmpty()) {
            return false
        }
        val regex = toRegex(normalizedPattern, '/')
        val segments = relativePath.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        for (end in (belowSegments + 1).coerceAtLeast(1)..segments.size) {
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
