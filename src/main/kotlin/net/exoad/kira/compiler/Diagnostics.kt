package net.exoad.kira.compiler

import net.exoad.kira.Public
import net.exoad.kira.compiler.frontend.FileLocation
import java.util.logging.ConsoleHandler
import java.util.logging.Level
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import kotlin.system.exitProcess

object Diagnostics
{
    private val logger: Logger = Logger.getLogger("net.exoad.kira")

    init
    {
        System.setProperty(
            "java.util.logging.SimpleFormatter.format",
            "%5\$s%n"
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
    }

    fun silenceDiagnostics()
    {
        logger.level = Level.OFF
    }

    fun panic(tag: String, message: Any, cause: Throwable? = null, location: FileLocation? = null): Nothing
    {
        throw DiagnosticsException(tag, message.toString(), cause, location)
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
                if(buffer.length + word.length + (if(buffer.isNotEmpty()) 1 else 0) > 64)
                {
                    println(buffer.toString())
                    buffer = StringBuilder(word)
                }
                else
                {
                    if(buffer.isNotEmpty())
                    {
                        buffer.append(" ")
                    }
                    buffer.append(word)
                }
            }
            if(buffer.isNotEmpty())
            {
                println(buffer.toString())
            }
            else
            {
                println("")
            }
        }
        println("===========================================================")
        exitProcess(1)
    }

    object Logging
    {
        fun ohNo(tag: String, message: Any)
        {
            logger.severe("OhNo/$tag: $message")
        }

        fun info(tag: String, message: Any)
        {
            logger.info("Info/$tag: $message")
        }

        fun wtf(tag: String, message: Any)
        {
            logger.info("WTF?/$tag: $message") // :)
        }

        fun yap(tag: String, message: Any)
        {
            if(Public.Flags.beVerbose) // i dont want to toggle flags using Level and Logger :(
            {
                logger.finer("Yap/$tag: $message")
            }
        }

        fun warn(tag: String, message: Any)
        {
            logger.warning("Warn/$tag: $message")
        }
    }
}
