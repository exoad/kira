package net.exoad.kira.kim

import java.nio.file.Files
import java.nio.file.Path

object DependencyResolver {
    fun resolveProjectSources(manifest: ProjectManifest?, projectRoot: Path): List<String> {
        val root = when {
            manifest != null -> projectRoot.resolve(manifest.srcDir).normalize()
            Files.exists(projectRoot.resolve("src")) -> projectRoot.resolve("src").normalize()
            else -> projectRoot
        }

        val primary = scanKiraFiles(root)
        if (primary.isNotEmpty()) {
            return primary
        }

        if (manifest == null && root != projectRoot) {
            return scanKiraFiles(projectRoot)
        }
        return emptyList()
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
