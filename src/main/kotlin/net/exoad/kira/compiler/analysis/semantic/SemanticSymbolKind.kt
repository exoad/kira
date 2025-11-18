package net.exoad.kira.compiler.analysis.semantic

import net.exoad.kira.utils.ObsoleteLanguageFeat

enum class SemanticSymbolKind {
    VARIABLE,
    FUNCTION,
    CLASS,
    ENUM,
    PARAMETER,
    VARIANT,
    ENUM_MEMBER,
    CLASS_MEMBER,
    TRAIT,
    TRAIT_MEMBER,
    MODULE,
    TYPE_SPECIFIER,
    TYPE_ALIAS,
    VARIANT_MEMBER
}