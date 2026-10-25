package net.exoad.kira.kim

import java.nio.file.Files
import java.nio.file.Path

/**
 * A project's file: project.kira, or kira.yaml until stage 6 retires it. With both, project.kira
 * is read and [note] says so.
 */
object ProjectFiles {
    const val YAML = "kira.yaml"

    /** [dir]'s project file, project.kira first; null for neither. */
    fun find(dir: Path): Path? =
        dir.resolve(ProjectKira.FILE).takeIf { Files.isRegularFile(it) } ?: dir.resolve(YAML).takeIf { Files.isRegularFile(it) }

    fun isProject(dir: Path): Boolean = find(dir) != null

    /** The configuration in [file], read by its name. */
    fun loadFromPath(file: Path): ProjectManifest =
        if (file.fileName.toString() == ProjectKira.FILE) ProjectKira.loadFromPath(file) else ManifestLoader.loadFromPath(file)

    /** [dir]'s configuration; null when it has no project file. */
    fun load(dir: Path): ProjectManifest? = find(dir)?.let(::loadFromPath)

    /** What to say when [dir] has both files. */
    fun note(dir: Path): String? =
        if (Files.isRegularFile(dir.resolve(ProjectKira.FILE)) && Files.isRegularFile(dir.resolve(YAML))) {
            "both ${ProjectKira.FILE} and $YAML are in $dir: ${ProjectKira.FILE} is read, $YAML is not"
        } else {
            null
        }

    /** Whether [file] is [root]'s project.kira, which configures the project and is none of its modules. */
    fun isProjectKira(file: Path, root: Path): Boolean =
        file.toAbsolutePath().normalize() == root.toAbsolutePath().normalize().resolve(ProjectKira.FILE)
}
