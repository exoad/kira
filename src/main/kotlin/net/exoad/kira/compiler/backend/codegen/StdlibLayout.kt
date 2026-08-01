package net.exoad.kira.compiler.backend.codegen

import net.exoad.kira.Public
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves the stdlib directory layout the backends load from.
 *
 * The stdlib is one self-contained directory: the `kira` folder holds the
 * `.kira` declarations, the `*.bind.yaml` magic binding manifests, and the
 * per-backend runtime implementations under `c/` (C prelude) and `js/` (JS
 * prelude). Nothing stdlib-shaped is embedded in the compiler jar; a backend
 * asks [StdlibLayout] where its runtime file lives, next to the modules it
 * was asked to compile.
 *
 * Resolution order:
 *  1. The directory holding the loaded stdlib `.kira` sources (from
 *     [Public.Builtin.intrinsicalStandardLibrarySources], populated by the
 *     frontend from the resolved `kira:` dependencies).
 *  2. A `kira/` directory in the process working directory (test / dev
 *     fallback when the registry was never populated).
 */
object StdlibLayout {
    fun stdlibRoot(): Path? {
        val fromSources = Public.Builtin.intrinsicalStandardLibrarySources
            .firstOrNull { it.endsWith(".kira") }
            ?.let { Path.of(it).parent }
        if (fromSources != null && Files.isDirectory(fromSources)) {
            return fromSources
        }
        val cwd = Path.of("kira").toAbsolutePath().normalize()
        return if (Files.isDirectory(cwd)) cwd else null
    }

    /** A runtime implementation file under `kira/<backend>/`, or null. */
    fun runtimeFile(backend: String, fileName: String): Path? {
        val root = stdlibRoot() ?: return null
        val file = root.resolve(backend).resolve(fileName).normalize()
        return if (Files.isRegularFile(file)) file else null
    }

    fun cFile(fileName: String): Path? = runtimeFile("c", fileName)
    fun jsFile(fileName: String): Path? = runtimeFile("js", fileName)
}
