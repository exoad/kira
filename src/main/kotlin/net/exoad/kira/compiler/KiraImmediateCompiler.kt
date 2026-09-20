package net.exoad.kira.compiler

import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraSourceParsers
import net.exoad.kira.source.SourceContext

object KiraImmediateCompiler {
    fun formAST(value: String): SourceContext {
        val sourceWithoutTokens = SourceContext(value, "ImmediateMode", emptyList())
        val tokens = KiraLexer(sourceWithoutTokens).tokenize()
        val sourceWithTokens = SourceContext(value, "ImmediateMode", tokens)
        KiraSourceParsers.from(sourceWithTokens).parse()
        return sourceWithTokens
    }
}