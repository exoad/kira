package net.exoad.kira.compiler.front

import net.exoad.kira.Public
import net.exoad.kira.compiler.DiagnosticsSymbols
import net.exoad.kira.compiler.isNotRepresentableDiagnosticsSymbol

object SrcProvider
{
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
     * 1-based indexing
     */
    fun findCanonicalLine(lineNumber: Int): String
    {
        return when
        {
            lineNumber > srcContentLines.size || lineNumber < 0 -> DiagnosticsSymbols.NOT_REPRESENTABLE
            else -> srcContentLines[lineNumber - 1].trimIndent()
        }
    }

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
                val gap = " ".repeat(fileLocation.column - 1)
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