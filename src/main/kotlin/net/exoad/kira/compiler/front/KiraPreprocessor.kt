package net.exoad.kira.compiler.front

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
        val originalLines = rawContent.lines()
        val processedLines = mutableListOf<String>()
        val lineMappingPreprocessed = mutableMapOf<Int, Int>()
        val lineMappingOriginal = mutableMapOf<Int, Int>()
        val removedSections = mutableListOf<RemovedSection>()
        var originalLine = 1
        var currentLine = 1
        for(line in originalLines)
        {
            val processed = removeTrailingComment(line)
            processedLines.add(processed)
            lineMappingPreprocessed[currentLine] = originalLine
            lineMappingOriginal[originalLine] = currentLine
            if(processed.isEmpty() && line.trim().isNotEmpty())
            {
                removedSections.add(
                    RemovedSection(
                        originalLine, originalLine, PreprocessorRemovalType.LINE_COMMENT
                    )
                )
            }
            else if(processed != line)
            {
                removedSections.add(
                    RemovedSection(
                        originalLine, originalLine, PreprocessorRemovalType.TRAILING_COMMENT
                    )
                )
            }
            originalLine++
            currentLine++
        }
        return PreprocessorResult(
            processedContent = processedLines.joinToString("\n"),
            sourceMap = SourceMap(
                preprocessedToOriginal = lineMappingPreprocessed,
                originalToPreprocessed = lineMappingOriginal
            ),
            removedSections = removedSections
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
                escape                                                                   ->
                {
                    escape = false
                }
                line[i] == '\\' && inString                                              ->
                {
                    escape = true
                }
                line[i] == '"'                                                           ->
                {
                    inString = !inString
                }
                !inString && line[i] == '/' && i + 1 < line.length && line[i + 1] == '/' ->
                {
                    return line.substring(0, i).trimEnd()
                }
            }
        }
        return line
    }
}

data class PreprocessorResult(
    val processedContent: String,
    val sourceMap: SourceMap,
    val removedSections: List<RemovedSection>,
)

// could be used for later implementing proper doc comment generation or some other compile time gimmick
data class RemovedSection(
    val originalStartLine: Int,
    val originalEndLine: Int,
    val type: PreprocessorRemovalType,
)

enum class PreprocessorRemovalType
{
    LINE_COMMENT,
    TRAILING_COMMENT
}

// lookup tables for converting between the original source line number to the preprocessed line number
class SourceMap(
    private val preprocessedToOriginal: Map<Int, Int>,
    private val originalToPreprocessed: Map<Int, Int>,
)
{
    fun getOriginalLine(preprocessedLine: Int): Int
    {
        return preprocessedToOriginal[preprocessedLine] ?: preprocessedLine
    }

    fun getPreprocessedLine(originalLine: Int): Int
    {
        return originalToPreprocessed[originalLine] ?: originalLine
    }
}