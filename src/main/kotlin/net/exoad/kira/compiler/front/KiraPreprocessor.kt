package net.exoad.kira.compiler.front

import net.exoad.kira.compiler.Diagnostics

/**
 * The first process in the frontend compilation process by which certain
 * elements are either removed like comments and preprocessor directives.
 *
 * The results are passed onto [KiraLexer].
 */
class KiraPreprocessor(private val rawContent: String)
{
    fun process(): PreprocessorResult
    {
        val rawLines = rawContent.lines()
        val processedLines = mutableListOf<String>()
        val lineComments = mutableListOf<Int>()
        var originalLine = 1
        for(line in rawLines)
        {
            val processed = removeTrailingComment(line)
            processedLines.add(processed)
            if(processed.isEmpty() && line.trim().isNotEmpty())
            {
                lineComments.add(originalLine)
            } // ignore trailing comments (they are not captured)
            originalLine++
        }
        return PreprocessorResult(
            processedContent = processedLines.joinToString("\n"),
            lineComments = lineComments
        )
    }

    private fun removeTrailingComment(line: String): String
    {
        var inString = false
        var escape = false
        for(i in line.indices)
        {
            when
            {
                escape                                                                   -> escape = false
                line[i] == '\\' && inString                                              -> escape = true
                line[i] == '"'                                                           -> inString = !inString
                !inString && line[i] == '/' && i + 1 < line.length && line[i + 1] == '/' -> return line.substring(0, i)
                    .trimEnd()
            }
        }
        return line
    }
}

data class PreprocessorResult(
    val processedContent: String,
    val lineComments: List<Int>,
)

