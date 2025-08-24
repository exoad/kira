package net.exoad.kira.compiler.analysis.semantic

import net.exoad.kira.compiler.frontend.lexer.Token
import net.exoad.kira.source.AbsoluteFileLocation

data class SemanticSymbol(
    val name: String,
    val kind: SemanticSymbolKind,
    val type: Token.Type,
    val declaredAt: AbsoluteFileLocation,
)



