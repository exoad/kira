package net.exoad.kira.compiler.analysis.diagnostics

/**
 * These symbols will still be present even if [net.exoad.kira.Public.Flags.useDiagnosticsUnicode] is `false`
 */
object DiagnosticsSymbols
{
    /**
     * An emoji used to represent text that has been returned as a "null"
     *
     * It is inspired from video game graphics development where null textures
     * will be replaced by a signature noticeable texture. (See Valve's missing texture
     * and the black and magenta checker square)
     */
    const val NOT_REPRESENTABLE: String = "\uD83D\uDE15"
}

fun String.isNotRepresentableDiagnosticsSymbol(): Boolean
{
    return this == DiagnosticsSymbols.NOT_REPRESENTABLE
}

