package net.exoad.kira.compiler

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The file is the module: its name is its project's name plus its path under the project's
 * source directory (`tools/dbw/dbwframe.kira` in `net.exoad.bibo.dbw` is `net.exoad.bibo.dbw.dbwframe`).
 * Inside the compiler a module's URI keeps the project apart from the path, `project:path.file`,
 * so `kira:math` is the stdlib's math; the name a program writes is [dotted].
 */
object ModuleNames {
    const val STDLIB = "kira"

    /** The URI of [file] under [srcRoot] in project [project]. */
    fun project(project: String, srcRoot: Path, file: Path): String {
        val root = srcRoot.toAbsolutePath().normalize()
        val abs = file.toAbsolutePath().normalize()
        val rel = if (abs.startsWith(root)) root.relativize(abs) else abs.fileName
        val segments = rel.map { it.toString() }.toMutableList()
        segments[segments.lastIndex] = stem(segments.last())
        return "$project:${segments.joinToString(".")}"
    }

    /** A stdlib file: `kira:` and its name. */
    fun stdlib(file: String): String = "$STDLIB:${stem(Paths.get(file).fileName.toString())}"

    /**
     * A dependency's file: under a dependency with a kira.yaml, that project's name and srcDir;
     * under one without (the stdlib), `kira` and the path under [depRoot].
     */
    fun dependency(depRoot: Path, file: Path): String {
        val manifest = depRoot.resolve("kira.yaml")
        if (Files.isRegularFile(manifest)) {
            runCatching { net.exoad.kira.kim.ManifestLoader.loadFromPath(manifest) }.getOrNull()?.let { m ->
                return project(m.project.name, depRoot.resolve(m.srcDir), file)
            }
        }
        return project(STDLIB, depRoot, file)
    }

    /**
     * A file read outside any project: a relative path's first directory is its package and the
     * rest its path (`test/semantic/basic.kira` is `test:semantic.basic`); an absolute path's
     * directory and file name (`.../lang/closures.kira` is `lang:closures`).
     */
    fun loose(file: String): String {
        val p = Paths.get(file.replace('\\', '/'))
        val parts = p.map { it.toString() }
        if (!p.isAbsolute && parts.size >= 2 && !parts[0].contains(':')) {
            return "${parts[0]}:${(parts.drop(1).dropLast(1) + stem(parts.last())).joinToString(".")}"
        }
        if (p.isAbsolute) {
            enclosing(p)?.let { return it }
        }
        val dir = p.parent?.fileName?.toString()?.takeIf { it.isNotBlank() && it.all { c -> c.isLetterOrDigit() || c == '_' } } ?: "main"
        return "$dir:${stem(p.fileName.toString())}"
    }

    /**
     * Each source's URI, by canonical path: [stdlib] under the dependency that holds it, and
     * [workspace] under the project's srcDir (its `src`, or its root, without a kira.yaml).
     */
    fun assign(
        manifest: net.exoad.kira.kim.ProjectManifest?,
        projectRoot: Path,
        stdlib: Collection<String>,
        workspace: Collection<String>,
    ): Map<String, String> {
        val root = projectRoot.toAbsolutePath().normalize()
        val depRoots = buildList {
            manifest?.dependencies?.values?.forEach { spec ->
                spec.path?.let { net.exoad.kira.kim.DependencyResolver.resolvePath(root, it) }?.let { add(it.toAbsolutePath().normalize()) }
            }
            add(root.resolve("kira"))
            add(Paths.get("kira").toAbsolutePath().normalize())
        }
        val srcRoot = when {
            manifest != null -> root.resolve(manifest.srcDir).normalize()
            Files.isDirectory(root.resolve("src")) -> root.resolve("src")
            else -> root
        }
        val projectName = manifest?.project?.name ?: (root.fileName?.toString() ?: "main")
        val out = LinkedHashMap<String, String>()
        for (f in stdlib) {
            val p = Paths.get(f).toAbsolutePath().normalize()
            val dep = depRoots.firstOrNull { p.startsWith(it) }
            out[canonical(f)] = if (dep != null) dependency(dep, p) else stdlib(f)
        }
        for (f in workspace) {
            out.putIfAbsent(canonical(f), project(projectName, srcRoot, Paths.get(f)))
        }
        return out
    }

    private fun canonical(f: String): String = runCatching { java.io.File(f).canonicalPath }.getOrDefault(f)

    /** Each kira.yaml read, by its path and when it was last written: a rewritten one is read again. */
    private val manifests = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<net.exoad.kira.kim.ProjectManifest>>()

    /** [file]'s URI by what holds it: the stdlib's folder, or the nearest kira.yaml whose srcDir has it. */
    private fun enclosing(file: Path): String? {
        val abs = file.toAbsolutePath().normalize()
        val dir = abs.parent ?: return null
        if (Files.isRegularFile(dir.resolve("core.kira")) && !Files.isRegularFile(dir.resolve("kira.yaml"))) {
            return stdlib(abs.toString())
        }
        var d: Path? = dir
        while (d != null) {
            val yaml = d.resolve("kira.yaml")
            if (Files.isRegularFile(yaml)) {
                val key = "$yaml|${Files.getLastModifiedTime(yaml).toMillis()}|${Files.size(yaml)}"
                val m = manifests.computeIfAbsent(key) {
                    java.util.Optional.ofNullable(runCatching { net.exoad.kira.kim.ManifestLoader.loadFromPath(yaml) }.getOrNull())
                }.orElse(null) ?: return null
                val src = d.resolve(m.srcDir).normalize()
                return if (abs.startsWith(src)) project(m.project.name, src, abs) else null
            }
            d = d.parent
        }
        return null
    }

    /** The module name a program writes: the URI with its project joined by a dot. */
    fun dotted(uri: String): String = uri.replace(':', '.')

    private fun stem(name: String): String = name.removeSuffix(".kira")
}
