package net.exoad.kira.compiler.backend.codegen.cpp

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * A file the backend intends to have on disk: its absolute path and exact
 * bytes. The backend writes it when the disk differs (so `--check` after a
 * write is clean and mtimes stay put), or reports the difference in check
 * mode.
 */
data class CppPlannedFile(val path: Path, val bytes: ByteArray) {
    val text: String
        get() = String(bytes, StandardCharsets.UTF_8)

    override fun equals(other: Any?): Boolean {
        return other is CppPlannedFile && other.path == path && other.bytes.contentEquals(bytes)
    }

    override fun hashCode(): Int = 31 * path.hashCode() + bytes.contentHashCode()

    companion object {
        fun ofText(path: Path, text: String): CppPlannedFile {
            return CppPlannedFile(path.toAbsolutePath().normalize(), text.toByteArray(StandardCharsets.UTF_8))
        }
    }
}

/** What the installer plans: the runtime files plus `VERSION`, and what went wrong. */
data class CppInstallPlan(val files: List<CppPlannedFile>, val diagnostics: List<CppDiagnostic>)

/**
 * Installs the C++ runtime: copies every file under the stdlib's `cpp/kira`
 * directory to `<runtimeDir>/kira/` and writes `<runtimeDir>/kira/VERSION` holding the
 * compiler's version (its git SHA, or `dev`). Nothing is written here;
 * [plan] lists the files and [KiraCppBackend] writes or checks them, so a
 * file is touched only when its content differs.
 */
class CppRuntimeInstaller(
    /** `<stdlib>/cpp`, from [net.exoad.kira.compiler.backend.codegen.StdlibLayout.cppDir]. */
    private val stdlibCppDir: Path?,
    /** `<projectRoot>/<runtimeDir>`; the runtime lands in its `kira/`. */
    private val runtimeDir: Path,
    private val version: String,
) {
    fun plan(): CppInstallPlan {
        val files = mutableListOf<CppPlannedFile>()
        val diagnostics = mutableListOf<CppDiagnostic>()
        val target = runtimeDir.toAbsolutePath().normalize().resolve(RUNTIME_SUBDIR)
        val source = stdlibCppDir?.toAbsolutePath()?.normalize()?.resolve(RUNTIME_SUBDIR)

        if (source == null || !source.isDirectory()) {
            diagnostics += CppDiagnostic(
                MISSING_CODE,
                "the C++ runtime was not found at ${source ?: "<no stdlib>/cpp/kira"}; " +
                    "the kira_stdlib dependency must hold cpp/kira/*.hxx",
            )
            return CppInstallPlan(files, diagnostics)
        }

        Files.walk(source).use { stream ->
            stream.filter { it.isRegularFile() }
                .sorted()
                .forEach { file ->
                    val relative = source.relativize(file)
                    files += CppPlannedFile(target.resolve(relative).normalize(), Files.readAllBytes(file))
                }
        }
        if (files.isEmpty()) {
            diagnostics += CppDiagnostic(MISSING_CODE, "the C++ runtime directory $source holds no files")
        }
        files += CppPlannedFile.ofText(target.resolve(VERSION_FILE), "$version\n")
        return CppInstallPlan(files.sortedBy { it.path.toString() }, diagnostics)
    }

    companion object {
        const val RUNTIME_SUBDIR = "kira"
        const val VERSION_FILE = "VERSION"
        const val MISSING_CODE = "cpp.runtime-missing"
    }
}
