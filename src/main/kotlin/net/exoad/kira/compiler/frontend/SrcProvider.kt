package net.exoad.kira.compiler.frontend

import net.exoad.kira.Public
import net.exoad.kira.compiler.DiagnosticsSymbols
import net.exoad.kira.compiler.isNotRepresentableDiagnosticsSymbol

object SrcProvider
{
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
        return if(lineNumber > srcContentLines.size || lineNumber < 0)
        {
            DiagnosticsSymbols.NOT_REPRESENTABLE
        }
        else
        {
            srcContentLines[lineNumber].trimIndent()
        }
    }

    fun formCanonicalLocatorString(fileLocation: FileLocation, trailingText: String? = null): String
    {
        val line = findCanonicalLine(fileLocation.lineNumber)
        return if(fileLocation.column < 0 || line.isNotRepresentableDiagnosticsSymbol())
        {
            line
        }
        else
        {
            val builder = StringBuilder()
            builder.appendLine(line)
            val gap = " ".repeat(fileLocation.column - 1)
            builder.append(gap)
            if(Public.Flags.useDiagnosticsUnicode)
            {
                builder.appendLine("↑")
            }
            else
            {
                builder.appendLine("^")
            }
            if(trailingText != null)
            {
                builder.append(gap)
                builder.append(trailingText)
            }
            builder.toString()
        }
    }
}