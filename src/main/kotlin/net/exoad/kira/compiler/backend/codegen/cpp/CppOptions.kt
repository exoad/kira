package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.kim.SourceGlob

/** Where a module's generated files land. */
enum class CppLayout {
    /** `proto.kira.hxx` (+ `.cxx`) next to `proto.kira`. */
    BESIDE,

    /** `<outDir>/<package>/<path>/<name>.kira.hxx`, mirroring the module URI. */
    TREE;

    companion object {
        fun parse(raw: String): CppLayout {
            return when (raw.trim().lowercase()) {
                "beside" -> BESIDE
                "tree" -> TREE
                else -> throw IllegalArgumentException(
                    "Field 'build.cpp.layout' must be one of beside, tree (got '$raw')"
                )
            }
        }
    }
}

/**
 * The `build.cpp` block of `kira.yaml`, as the C++ backend consumes it. Every
 * field has the manifest's default so a project with no `cpp:` block still
 * builds.
 *
 * Module globs (`headerOnly`, `freestanding`, and the keys of `namespaces`)
 * match module URIs: `*` matches one URI segment, `**` any number, and
 * `{a,b}` alternatives, so `firmware:lib.**` covers every module under
 * `firmware:lib` and `firmware:pilot.src.{scan,imu}` names two.
 */
data class CppOptions(
    val layout: CppLayout = CppLayout.BESIDE,
    /** The root of the `tree` layout, relative to the project root. */
    val outDir: String = "gen/kira",
    /**
     * Where the runtime (`kira/rt.hxx` and friends) and the Kira-written
     * stdlib headers are installed, relative to the project root. `null`
     * means [outDir].
     */
    val runtimeDir: String? = null,
    /** Emit `#line` directives in function bodies. Goldens turn this off. */
    val lineDirectives: Boolean = true,
    /** Module URI (or glob) to C++ namespace; `a::b` nests. */
    val namespaces: Map<String, String> = emptyMap(),
    /** Module globs that emit a header only (every function inline). */
    val headerOnly: List<String> = emptyList(),
    /** Module globs compiled for the freestanding profile (`kira/core.hxx` only). */
    val freestanding: List<String> = emptyList(),
    val headerExt: String = DEFAULT_HEADER_EXT,
    val sourceExt: String = DEFAULT_SOURCE_EXT,
) {
    val effectiveRuntimeDir: String
        get() = runtimeDir ?: outDir

    /** Stdlib modules (`kira:x`) are always header-only. */
    fun isHeaderOnly(uri: String): Boolean {
        return uri.startsWith(STDLIB_URI_PREFIX) || headerOnly.any { SourceGlob.matchesUri(it, uri) }
    }

    fun isFreestanding(uri: String): Boolean {
        return freestanding.any { SourceGlob.matchesUri(it, uri) }
    }

    companion object {
        const val DEFAULT_OUT_DIR = "gen/kira"
        const val DEFAULT_HEADER_EXT = ".kira.hxx"
        const val DEFAULT_SOURCE_EXT = ".kira.cxx"
        const val STDLIB_URI_PREFIX = "kira:"
    }
}
