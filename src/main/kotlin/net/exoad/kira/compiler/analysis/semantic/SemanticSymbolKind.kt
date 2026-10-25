package net.exoad.kira.compiler.analysis.semantic


enum class SemanticSymbolKind {
    VARIABLE,
    FUNCTION,
    CLASS,
    PARAMETER,
    CLASS_MEMBER,
    TRAIT,
    TRAIT_MEMBER,
    MODULE,
    TYPE_SPECIFIER,
    TYPE_ALIAS,
}