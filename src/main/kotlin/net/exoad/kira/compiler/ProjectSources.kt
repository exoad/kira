package net.exoad.kira.compiler

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Which files a compile reads: a project's own modules (srcDir's, less srcExclude's, never its
 * project.kira) and its dependencies' (the stdlib among them). Without a project file, `src`
 * under the root, else the root, and the stdlib under `kira`.
 */
object ProjectSources {
    /** [project]'s own modules under [projectRoot]: srcDir's .kira files, less srcExclude's and project.kira. */
    fun workspace(project: Project?, projectRoot: Path): List<String> {
        val root = when {
            project != null -> projectRoot.resolve(project.srcDir).normalize()
            Files.exists(projectRoot.resolve("src")) -> projectRoot.resolve("src").normalize()
            else -> projectRoot
        }
        val excludes = project?.srcExclude.orEmpty()
        val absoluteRoot = projectRoot.toAbsolutePath().normalize()

        // The root's project.kira configures the project; it is none of its modules.
        val primary = scanKiraFiles(root) { isExcluded(it.toString(), absoluteRoot, excludes) }
            .filterNot { isExcluded(it, absoluteRoot, excludes) || ProjectFile.isProjectFile(Path.of(it), absoluteRoot) }
        if (primary.isNotEmpty()) {
            return primary
        }

        if (project == null && root != projectRoot) {
            return scanKiraFiles(projectRoot).filterNot { ProjectFile.isProjectFile(Path.of(it), absoluteRoot) }
        }
        return emptyList()
    }

    /**
     * True when [file] sits under a path that one of [excludes] names,
     * relative to [projectRoot]. [below] is a directory the caller is
     * already scanning: an exclude that covers it (or one of its parents)
     * does not count, only one matched underneath it (see
     * [SourceGlob.matchesPath]).
     */
    fun isExcluded(file: String, projectRoot: Path, excludes: List<String>, below: Path? = null): Boolean {
        if (excludes.isEmpty()) {
            return false
        }
        val absolute = Path.of(file).toAbsolutePath().normalize()
        val root = projectRoot.toAbsolutePath().normalize()
        if (!absolute.startsWith(root)) {
            return false
        }
        val relative = root.relativize(absolute).toString().replace('\\', '/')
        val scanned = below?.toAbsolutePath()?.normalize()
        val belowSegments = when {
            scanned == null || scanned == root || !scanned.startsWith(root) -> 0
            else -> root.relativize(scanned).nameCount
        }
        return excludes.any { SourceGlob.matchesPath(it, relative, belowSegments) }
    }

    /** Every dependency's .kira files, each dependency read whole; with none named, the root's `kira/`. */
    fun dependencies(project: Project?, projectRoot: Path): List<String> {
        if (project == null || project.dependencies.isEmpty()) {
            return scanKiraFiles(projectRoot.resolve("kira").normalize())
        }

        val sources = mutableListOf<String>()
        project.dependencies.values.forEach { path ->
            val resolved = resolvePath(projectRoot, path) ?: return@forEach
            sources.addAll(scanKiraFiles(resolved))
        }

        return sources.distinct().sorted()
    }

    /** [rawPath] under [projectRoot], else under its parent; null when neither has it. */
    fun resolvePath(projectRoot: Path, rawPath: String): Path? {
        val direct = projectRoot.resolve(rawPath).normalize()
        if (Files.exists(direct)) {
            return direct
        }

        val parent = projectRoot.parent ?: return null
        val parentResolved = parent.resolve(rawPath).normalize()
        return if (Files.exists(parentResolved)) parentResolved else null
    }

    /** The .kira files under [path] (a linked file too); a directory [prune] names is never entered, one that vanishes mid-walk is skipped. */
    private fun scanKiraFiles(path: Path, prune: (Path) -> Boolean = { false }): List<String> {
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
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != path && prune(dir)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (file.toString().endsWith(".kira") && Files.isRegularFile(file)) {
                    entries.add(file.toAbsolutePath().toString())
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                if (exc is NoSuchFileException) FileVisitResult.CONTINUE else throw exc

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult =
                if (exc == null || exc is NoSuchFileException) FileVisitResult.CONTINUE else throw exc
        })
        return entries.distinct().sorted()
    }
}
