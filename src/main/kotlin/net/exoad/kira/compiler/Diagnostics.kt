package net.exoad.kira.compiler

import net.exoad.kira.Public
import net.exoad.kira.compiler.front.FileLocation
import java.util.logging.ConsoleHandler
import java.util.logging.Level
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import kotlin.properties.Delegates
import kotlin.system.exitProcess

/**
 * General debugging things or information to be shown to the user like panic information and others
 *
 * kira's internal `System.out.print`
 */
object Diagnostics
{
    // could use something like println provided by the language, but nah, i am a cool programmer and we dont use this sissy tools (i still use println to debug sometimes ;D)
    private val logger: Logger =
        Logger.getLogger("net.exoad.kira") // the logger used so we can getter better compatibility and controls from external systems

    init
    {
        System.setProperty(
            "java.util.logging.SimpleFormatter.format",
            "%5\$s%n" // get rid of all the garbage produced by the default java logger including things like method site, a long time stamp.
        )
        val consoleHandler = ConsoleHandler().apply {
            formatter = SimpleFormatter()
        }
        logger.addHandler(consoleHandler)
        logger.level = Level.OFF
        logger.useParentHandlers = false
    }

    fun useDiagnostics()
    {
        logger.level = Level.ALL
        // i learned it the hard way that just setting the logger's level doesnt work.
        // YOU HAVE TO DO IT FOR ALL THE HANDLERS???
        // bruh why is this a thing, cant they just built it in? i dont really understand why this isnt done under the hood
        logger.handlers.forEach { it.level = Level.ALL }
    }

    fun silenceDiagnostics()
    {
        logger.level = Level.OFF
        logger.handlers.forEach { it.level = Level.OFF }
    }

    fun recordDiagnostics(exception: DiagnosticsException): String
    {
        return """
${
            when
            {
                exception.location != null -> exception.context.formCanonicalLocatorString(
                    exception.location,
                    exception.message,
                    exception.selectorLength
                )
                else                       -> exception.message
            }
        }
        ${
            when
            {
                exception.cause != null -> """
    Internal stack trace (from cause): 
                        
    ${exception.cause}    
    """.trimIndent()
                else                    -> ""
            }
        }
        """
    }

    fun recordPanic(
        tag: String,
        message: Any,
        cause: Throwable? = null,
        location: FileLocation? = null,
        selectorLength: Int = 1,
        context: SourceContext,
    ): DiagnosticsException
    {
        return DiagnosticsException(tag, message.toString(), cause, location, context, selectorLength)
    }

    // something went wrong?? scary!
    fun panic(
        tag: String,
        message: Any,
        cause: Throwable? = null,
        location: FileLocation? = null,
        selectorLength: Int = 1,
        context: SourceContext,
    ): Nothing
    {
        Logging.finer("Kira", "Target Location = $location")
        throw recordPanic(tag, message, cause, location, selectorLength, context)
    }

    fun panic(message: String): Nothing
    {
        println("\n=====================[ Kira Panicked ]=====================")
        for(segment in message.split('\n'))
        {
            val words = segment.split(" ")
            var buffer = StringBuilder()
            for(word in words)
            {
                when(buffer.length + word.length + (if(buffer.isNotEmpty()) 1 else 0) > 64)
                {
                    true ->
                    {
                        println(buffer.toString())
                        buffer = StringBuilder(word)
                    }
                    else ->
                    {
                        if(buffer.isNotEmpty())
                        {
                            buffer.append(" ")
                        }
                        buffer.append(word)
                    }
                }
            }
            println(
                when
                {
                    buffer.isNotEmpty() -> buffer.toString()
                    else                -> ""
                }
            )
        }
        println("===========================================================")
        exitProcess(1)
    }

    object Logging
    {
        fun info(tag: String, message: Any)
        {
            logger.info("Info/$tag: $message")
        }

        fun finer(tag: String, message: Any)
        {
            if(Public.Flags.beVerbose) // i dont want to toggle flags using Level and Logger :(
            {
                logger.finer("Finer/$tag: $message")
            }
        }

        fun warn(tag: String, message: Any)
        {
            logger.warning("Warn/$tag: $message")
        }
    }
}
