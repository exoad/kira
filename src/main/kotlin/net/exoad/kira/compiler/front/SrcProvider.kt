package net.exoad.kira.compiler.front

import net.exoad.kira.Public
import net.exoad.kira.compiler.Diagnostics
import net.exoad.kira.compiler.DiagnosticsSymbols
import net.exoad.kira.compiler.isNotRepresentableDiagnosticsSymbol

/**
 * A global handler for every part of the compiler to be able to read the original source content.
 *
 * It also provides an easy way to identify locations within the source content (see: [findCanonicalLine] and [formCanonicalLocatorString], which is great for
 * generating debug messages or error messages of where something went wrong :)
 */
object SrcProvider
{
    // it would be very dangerous to change these after the initial initialization of these variables and then provide with lexical and semantical analysis.
    //
    // that would of course corrupt the error messages and even cause the generation of error messages to fail
    // ^should i cover this case? where the error message fails. i think i do already in [findCanonicalLine] where it returns the weird NOT_REP null char which is pretty cool
    var srcFile = ""
    var srcContent = ""
        set(value)
        {
            field = value
            srcContentLines = field.split("\n")
        }
    var srcContentLines = emptyList<String>()
        private set

    /**
     * 1-based indexing (is this lua? when anything refers to canonicity in my code, it often just means the way that ordinary folks (users of the language) would refer to things or like things
     */
    fun findCanonicalLine(lineNumber: Int): String
    {
        return when
        {
            lineNumber > srcContentLines.size || lineNumber < 0 -> DiagnosticsSymbols.NOT_REPRESENTABLE // should never happen or appear, if it appears, well... something fucked up BADDDDDDD
            else                                                -> srcContentLines[lineNumber - 1].trimIndent()
        }
    }

    /**
     * Creates a visual pointer to a portion of [srcContent] by the specified [fileLocation] and how long of the content to point at [locatorLength]
     *
     * - [locatorLength] starts at the first character of [fileLocation]'s [FileLocation.column] parameter. *PS, this function will throw an assertion error if [locatorLength] is not `>=` (greater than or equal to) `1`*
     *
     * - commonly used by the [Diagnostics.panic] function to generate useful & friendly error messages
     */
    fun formCanonicalLocatorString(
        fileLocation: FileLocation,
        trailingText: String? = null,
        locatorLength: Int = 1,
    ): String
    {
        assert(locatorLength >= 1) { "Locator length must be visible!" }
        val line = findCanonicalLine(fileLocation.lineNumber)
        return when
        {
            fileLocation.column < 0 || line.isNotRepresentableDiagnosticsSymbol() -> line
            else                                                                  ->
            {
                val builder = StringBuilder()
                val gutter = "${
                    when(Public.Flags.useDiagnosticsUnicode)
                    {
                        true -> "░"
                        else -> " "
                    }.repeat(fileLocation.lineNumber.toString().length)
                }| "
                builder.appendLine(gutter)
                builder.appendLine("${fileLocation.lineNumber}| $line")
                // makes sure the arrows are always aligned properly to the actual selected portion of the line
                val gap =
                    " ".repeat(fileLocation.column - 1 - srcContentLines[fileLocation.lineNumber - 1].indexOfFirst { !it.isWhitespace() }
                        .coerceAtLeast(0))
                builder.append(gutter)
                builder.append(gap)
                builder.appendLine(
                    when
                    {
                        Public.Flags.useDiagnosticsUnicode -> "↑"
                        else                               -> "^"
                    }.repeat(locatorLength)
                )
                if(trailingText != null)
                {
                    builder.append(gutter)
                    builder.append(gap)
                    builder.append(trailingText)
                }
                builder.toString()
            }
        }
    }
}