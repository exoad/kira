package net.exoad.kira.compiler.analysis.diagnostics

import net.exoad.kira.source.SourceContext
import net.exoad.kira.source.SourcePosition
import java.io.PrintWriter
import java.io.StringWriter

data class DiagnosticsException(
    val tag: String,
    override val message: String,
    override val cause: Throwable? = null,
    val location: SourcePosition? = null,
    val context: SourceContext,
    val selectorLength: Int,
) : RuntimeException(message, cause) {
    override fun toString(): String {
        return "\n${formattedPanicMessage()}"
    }

    fun formattedPanicMessage(): String {
        var exceptionTrace: String? = null
        cause?.let {
            val writer = StringWriter()
            val printWriter = PrintWriter(writer)
            it.printStackTrace(printWriter)
            printWriter.flush()
            exceptionTrace = writer.toString()
        }
        // SourcePosition.UNKNOWN (line -1) has no source line to point at.
        val known = location?.takeIf { it.lineNumber >= 1 }
        return """
===================[ Kira Panicked! ]===================
Kira panicked at $tag: ${
            when {
                known != null -> context.formCanonicalLocatorString(known, message, selectorLength)
                else -> message
            }
        }""" + when {
            exceptionTrace != null -> """
    Internal stack trace (from cause): 
                        
    $exceptionTrace    
    """.trimIndent()

            else -> ""
        }
    }
}
