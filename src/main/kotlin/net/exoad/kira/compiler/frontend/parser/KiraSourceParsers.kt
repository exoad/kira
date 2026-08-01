package net.exoad.kira.compiler.frontend.parser

import net.exoad.kira.source.SourceContext

/**
 * Factory for the frontend parser. Kira's parser is implemented in Kotlin
 * against the native lexer ([KiraLexer]) -- there is no generated grammar
 * backend, so this always produces the one real parser.
 */
object KiraSourceParsers {
    fun from(context: SourceContext): KiraSourceParser {
        return LegacyKiraSourceParser(context)
    }
}
