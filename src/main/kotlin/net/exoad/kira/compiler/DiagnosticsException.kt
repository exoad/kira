package net.exoad.kira.compiler

import net.exoad.kira.compiler.frontend.FileLocation
import net.exoad.kira.compiler.frontend.SrcProvider
import java.io.PrintWriter
import java.io.StringWriter

class DiagnosticsException(
    val tag: String,
    override val message: String,
    cause: Throwable? = null,
    val location: FileLocation? = null
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
The compiler panicked at $tag with:
$message
${
            if(location != null)
            {
                "\n${SrcProvider.formCanonicalLocatorString(location, "Here")}"
            }
            else
            {
                ""
            }
        }
${
            if(exceptionTrace != null)
                """
Internal stack trace (from cause): 
                    
$exceptionTrace    
""".trimIndent()
            else
                ""
        }
        """
    }
}