package net.exoad.kira.compiler

import net.exoad.kira.Public
import net.exoad.kira.compiler.front.FileLocation
import net.exoad.kira.compiler.front.SrcProvider
import java.io.PrintWriter
import java.io.StringWriter

class DiagnosticsException(
    val tag: String,
    override val message: String,
    cause: Throwable? = null,
    val location: FileLocation? = null,
    val selectorLength: Int,
) : RuntimeException(message, cause)
{
    override fun toString(): String
    {
        return "\n${formattedPanicMessage()}"
    }

    fun formattedPanicMessage(): String
    {
        var exceptionTrace: String? = null
        cause?.let {
            val writer = StringWriter()
            val printWriter = PrintWriter(writer)
            it.printStackTrace(printWriter)
            printWriter.flush()
            exceptionTrace = writer.toString()
        }
        return """
===================[ Kira Panicked! ]===================    
Kira panicked at $tag:
${
            when(location)
            {
                null -> ""
                else ->
                {
                    "${
                        when(Public.Flags.useDiagnosticsUnicode)
                        {
                            true -> "➥"
                            else -> "@"
                        }
                    } [${SrcProvider.srcFile}] : $location"
                }
            }
        }
${
            when
            {
                location != null -> SrcProvider.formCanonicalLocatorString(location, message, selectorLength)
                else             -> ""
            }
        }
            ${
            when
            {
                exceptionTrace != null -> """
    Internal stack trace (from cause): 
                        
    $exceptionTrace    
    """.trimIndent()
                else                   -> ""
            }
        }
            """
    }
}