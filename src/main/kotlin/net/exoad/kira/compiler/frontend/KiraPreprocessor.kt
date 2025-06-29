package net.exoad.kira.compiler.frontend

import java.io.File

/**
 * The first process in the frontend compilation process by which certain
 * elements are either removed like comments and preprocessor directives.
 *
 * The results are passed onto [KiraLexer].
 */
object KiraPreprocessor
{
    const val COMMENT_PATTERN: String = "//";

    fun stripComments(file: File): String
    {
        return file
            .readLines()
            .map { line ->
                val index = line.indexOf(COMMENT_PATTERN);
                if(index != -1) line.substring(0, index).trimEnd() else line
            }
            .filter { it.trim().isNotEmpty() }
            .joinToString("\n")
    }
}