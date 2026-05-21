package net.exoad.kira.compiler.frontend.parser

import net.exoad.kira.source.SourceContext

enum class ParserBackend {
    LEGACY,
    ANTLR
}

object KiraSourceParsers {
    private fun backendFromConfig(): ParserBackend {
        val raw = (System.getProperty("kira.parser") ?: System.getenv("KIRA_PARSER") ?: "legacy").lowercase()
        return when (raw) {
            "antlr" -> ParserBackend.ANTLR
            else -> ParserBackend.LEGACY
        }
    }

    fun activeBackend(): ParserBackend {
        return backendFromConfig()
    }

    fun from(context: SourceContext): KiraSourceParser {
        return when (backendFromConfig()) {
            ParserBackend.ANTLR -> AntlrKiraSourceParser(context)
            ParserBackend.LEGACY -> LegacyKiraSourceParser(context)
        }
    }
}
