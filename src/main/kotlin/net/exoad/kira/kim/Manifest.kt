package net.exoad.kira.kim

import net.exoad.kira.compiler.backend.codegen.cpp.CppOptions

data class ProjectSpec(
    val name: String
)

data class BuildOptions(
    val target: String = "c",
    /** Extra .c/.o files linked with out.kira.c (paths relative to project root). */
    val cSources: List<String> = emptyList(),
    /** Extra flags passed to cc after sources (e.g. -framework Cocoa). */
    val linkFlags: List<String> = emptyList(),
    /** When true (default), generated C/JS user code is minified + obfuscated. */
    val minify: Boolean = true,
    /** The `build.cpp` block: how `--target cpp` lays out and names its files. */
    val cpp: CppOptions = CppOptions(),
)

/**
 * How much the typed frontend does for the C and JS backends. The C++ backend
 * always runs the typer STRICT regardless of this setting; `off` is the
 * default so C and JS stay byte-identical while the typer is young.
 */
enum class TypeCheckMode {
    OFF,
    LENIENT,
    STRICT;

    companion object {
        fun parse(raw: String): TypeCheckMode {
            return when (raw.trim().lowercase()) {
                "off" -> OFF
                "lenient" -> LENIENT
                "strict" -> STRICT
                else -> throw IllegalArgumentException(
                    "Field 'compiler.types' must be one of off, lenient, strict (got '$raw')"
                )
            }
        }
    }
}

data class CompilerOptions(
    val emitIr: String? = null,
    val types: TypeCheckMode = TypeCheckMode.OFF,
)

data class DependencySpec(
    val path: String? = null
)

data class ProjectManifest(
    val project: ProjectSpec,
    val srcDir: String = "src",
    /**
     * Path globs relative to the project root; a source file under a matching
     * path is left out of the workspace. `build` excludes the root's build
     * directory, `** /build` (without the space) every build directory.
     * Dependency sources are never excluded by this list.
     */
    val srcExclude: List<String> = emptyList(),
    val build: BuildOptions = BuildOptions(),
    val compiler: CompilerOptions = CompilerOptions(),
    val dependencies: Map<String, DependencySpec> = emptyMap()
)
