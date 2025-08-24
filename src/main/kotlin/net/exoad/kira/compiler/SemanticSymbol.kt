package net.exoad.kira.compiler

data class SemanticSymbol(
    val name: String,
    val kind: SemanticSymbolKind,
    val type: Token.Type,
    val declaredAt: AbsoluteFileLocation,
)

enum class SemanticSymbolKind
{
    VARIABLE,
    FUNCTION,
    CLASS,
    OBJECT,
    ENUM,
    PARAMETER,
    TYPE_SPECIFIER
}

enum class SemanticScope
{
    MODULE,
    CLASS,
    OBJECT,
    ENUM,
    FUNCTION,
}
