package net.exoad.kira.compiler.analysis.semantic


enum class SemanticSymbolKind {
    VARIABLE,
    FUNCTION,
    CLASS,
    ENUM,
    ENUM_MEMBER,
    PARAMETER,
    VARIANT,
    CLASS_MEMBER,
    TRAIT,
    TRAIT_MEMBER,
    MODULE,
    TYPE_SPECIFIER,
    TYPE_ALIAS,
    VARIANT_MEMBER
}