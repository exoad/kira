package net.exoad.kira.compiler

import net.exoad.kira.compiler.frontend.lexer.KiraLexer
import net.exoad.kira.compiler.frontend.parser.KiraParser
import net.exoad.kira.source.SourceContext

object KiraImmediateCompiler {
    fun formAST(value: String): SourceContext {
        val srcContext = SourceContext(value, "ImmediateMode", emptyList())
        KiraLexer(srcContext).tokenize()
        KiraParser(srcContext).parse()
        return srcContext
    }
}