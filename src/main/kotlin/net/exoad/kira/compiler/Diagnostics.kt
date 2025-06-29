package net.exoad.kira.compiler

import net.exoad.kira.compiler.frontend.FileLocation
import net.exoad.kira.compiler.frontend.Token
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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
            "%1\$tH:%1\$tM:%1\$tS %5\$s%n"
        )
        val consoleHandler = ConsoleHandler().apply {
            formatter = SimpleFormatter()
        }
        logger.addHandler(consoleHandler)
        logger.level = Level.ALL
        logger.useParentHandlers = false
    }

    fun panic(tag: String, message: Any, cause: Throwable? = null, location: FileLocation? = null): Nothing
    {
        throw DiagnosticsException(tag, message.toString(), cause, location)
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

        fun uhOh(tag: String, message: Any)
        {
            logger.warning("Warn/$tag: $message")
        }
    }
}
