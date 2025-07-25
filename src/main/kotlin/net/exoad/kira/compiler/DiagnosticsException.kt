package net.exoad.kira.compiler

import net.exoad.kira.compiler.front.FileLocation
import java.io.PrintWriter
import java.io.StringWriter

data class DiagnosticsException(
    val tag: String,
    override val message: String,
    override val cause: Throwable? = null,
    val location: FileLocation? = null,
    val context: SourceContext,
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
            when
            {
                location != null -> context.formCanonicalLocatorString(location, message, selectorLength)
                else             -> message
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