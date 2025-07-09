package net.exoad.kira.compiler.preprocessor

import net.exoad.kira.compiler.SourceContext

/**
 * The first process in the frontend compilation process by which certain
 * elements are either removed like comments and preprocessor directives.
 *
 * The results are passed onto [net.exoad.kira.compiler.front.KiraLexer].
 */
class KiraPreprocessor(private val rawContent: String)
{
    companion object
    {
        const val COMMENT_PATTERN: String = "//"
    }

    /**
     * Strips comments!
     */
    fun stripComments(): String
    {
        // todo: maybe we can capture the comments some time later and support exporting doc comments?
        // todo: or will this be part of some external tool that scans or hooks up to this process
        return rawContent.split("\n")
            .map { line ->
                val index = line.indexOf(COMMENT_PATTERN)
                when
                {
                    index != -1 -> line.substring(0, index)
                    else        -> line
                }
            }
            .filter { it.trim().isNotEmpty() }
            .joinToString("\n")
    }

    /**
     * Runs all the steps of the preprocessor in the preferred manner. Otherwise, you can call the steps you prefer.
     */
    fun process(): String
    {
        return stripComments()
    }
}