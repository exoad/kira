package net.exoad.kira.compiler.analysis.semantic

import net.exoad.kira.compiler.frontend.lexer.Token
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.source.SourceLocation

data class SemanticSymbol(
    val name: String,
    val kind: SemanticSymbolKind,
    val type: Token.Type,
    val declaredAt: SourceLocation,
    val relativelyVisible: Boolean = false,
    val aliasedType: Type? = null, // For TYPE_ALIAS, stores the target type
) {
    override fun toString(): String {
        val aliasInfo = if (kind == SemanticSymbolKind.TYPE_ALIAS && aliasedType != null) {
            " (alias of $aliasedType)"
        } else {
            ""
        }
        return "'$name' -> $kind (${if (relativelyVisible) "RELATIVE " else ""}${
            when (type) {
                Token.Type.IDENTIFIER -> "Identifier"
                Token.Type.INTRINSIC_IDENTIFIER -> "IntrinsicIdentifier"
                else -> type.name
            }
        })$aliasInfo @ $declaredAt"
    }
}
