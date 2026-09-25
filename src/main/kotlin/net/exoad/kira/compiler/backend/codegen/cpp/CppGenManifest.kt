package net.exoad.kira.compiler.backend.codegen.cpp

import java.security.MessageDigest

/**
 * `kira.gen.manifest` at the project root: what the last `kira --target cpp`
 * produced, so a checkout can tell a stale generated tree from a fresh one
 * without the compiler.
 *
 * ```
 * kira.gen.manifest 1
 * compiler <version>
 * stdlib <sha256 over the runtime files>
 * <sha256>  <path relative to the manifest's own directory, forward slashes>
 * ...
 * ```
 *
 * Entries are sorted by path, so the file is the same for the same tree.
 * A path is always relative to the directory the manifest sits in (the
 * project root, or `<dir>` under `--out <dir>`) and never climbs out of it:
 * the backend removes what an earlier manifest recorded, so an entry that
 * named a file elsewhere would let one project's write reach another's.
 * [isRelativeInside] is the test, and the backend ignores what fails it.
 */
object CppGenManifest {
    const val FILE_NAME = "kira.gen.manifest"
    const val FORMAT = "kira.gen.manifest 1"

    data class Entry(val path: String, val sha256: String)

    /**
     * True for a path the manifest may hold: relative, forward slashes, and
     * no segment that is `..`, `.` or empty, so it names a file under the
     * manifest's directory and nowhere else. A drive letter, a leading
     * slash or backslash, and `../x` all fail.
     */
    fun isRelativeInside(path: String): Boolean {
        if (path.isEmpty() || path.contains('\\') || path.startsWith("/") || path.contains(':')) {
            return false
        }
        return path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    }

    data class Parsed(val compiler: String, val stdlib: String, val entries: List<Entry>)

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * One hash over the runtime files: each entry's path and content hash,
     * in path order, so a renamed or edited runtime file changes it. The
     * backend passes paths relative to `<runtimeDir>/kira` and leaves
     * `VERSION` out, so the hash names the stdlib alone: the same runtime
     * hashes the same under any `runtimeDir` and any compiler version.
     */
    fun stdlibHash(runtimeFiles: List<Pair<String, ByteArray>>): String {
        val text = runtimeFiles.sortedBy { it.first }
            .joinToString("\n") { (path, bytes) -> "${sha256(bytes)}  $path" }
        return sha256(text.toByteArray(Charsets.UTF_8))
    }

    fun render(version: String, stdlibHash: String, files: List<Pair<String, ByteArray>>): String {
        val sb = StringBuilder()
        sb.append(FORMAT).append('\n')
        sb.append("compiler ").append(version).append('\n')
        sb.append("stdlib ").append(stdlibHash).append('\n')
        files.map { (path, bytes) -> Entry(path, sha256(bytes)) }
            .sortedBy { it.path }
            .forEach { sb.append(it.sha256).append("  ").append(it.path).append('\n') }
        return sb.toString()
    }

    /** Reads a manifest back; null when the text is not one. */
    fun parse(text: String): Parsed? {
        val lines = text.replace("\r\n", "\n").split('\n').filter { it.isNotBlank() }
        if (lines.isEmpty() || lines[0].trim() != FORMAT) {
            return null
        }
        var compiler = ""
        var stdlib = ""
        val entries = mutableListOf<Entry>()
        lines.drop(1).forEach { line ->
            when {
                line.startsWith("compiler ") -> compiler = line.removePrefix("compiler ").trim()
                line.startsWith("stdlib ") -> stdlib = line.removePrefix("stdlib ").trim()
                else -> {
                    val split = line.indexOf("  ")
                    if (split > 0) {
                        entries += Entry(line.substring(split + 2).trim(), line.substring(0, split).trim())
                    }
                }
            }
        }
        return Parsed(compiler, stdlib, entries)
    }
}
