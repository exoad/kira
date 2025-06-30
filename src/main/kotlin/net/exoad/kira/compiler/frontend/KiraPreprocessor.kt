package net.exoad.kira.compiler.frontend

/**
 * The first process in the frontend compilation process by which certain
 * elements are either removed like comments and preprocessor directives.
 *
 * The results are passed onto [KiraLexer].
 */
object KiraPreprocessor
{
    const val COMMENT_PATTERN: String = "//"

    fun stripComments()
    {
        SrcProvider.srcContent = SrcProvider.srcContentLines
            .map { line ->
                val index = line.indexOf(COMMENT_PATTERN)
                when
                {
                    index != -1 -> line.substring(0, index).trimEnd()
                    else        -> line
                }
            }
            .filter { it.trim().isNotEmpty() }
            .joinToString("\n")
    }

    fun process()
    {
        return stripComments()
    }
}