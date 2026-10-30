package net.exoad.kira.compiler.backend.targets

/**
 * Holds the information on the information necessary to output the final generated output format.
 *
 * Things like the compilation format with [OutputTarget]
 */
object GeneratedProvider {
    enum class OutputTarget {
        /** Not implemented: the CLI accepts it, and a run with it emits nothing. */
        NEKO,
        C,
        JS,

        /** C++20: one header (and at most one source) per module, see [net.exoad.kira.compiler.backend.codegen.cpp]. */
        CPP,

        /** Python 3.10+: one `x_kira.py` per module, see [net.exoad.kira.compiler.backend.codegen.py]. */
        PY,
        NONE
    }

    var outputMode: OutputTarget = OutputTarget.NONE

    /**
     * When true (default), the user layer of generated C/JS is minified and
     * obfuscated. Set false via `--readable` or project.kira's `minify = false`.
     */
    var minifyOutput: Boolean = true
}
