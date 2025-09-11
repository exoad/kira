package net.exoad.kira.compiler.analysis.semantic

import net.exoad.kira.compiler.frontend.lexer.Token
import net.exoad.kira.source.SourceLocation

data class SemanticSymbol(
    val name: String,
    val kind: SemanticSymbolKind,
    val type: Token.Type,
    val declaredAt: SourceLocation,
    val relativelyVisible: Boolean = false,

    ) {
    override fun toString(): String {
        return "$$ $name ${if (relativelyVisible) "RELATIVE" else ""} $$ $kind -> $type @ [$declaredAt] "
    }
}


