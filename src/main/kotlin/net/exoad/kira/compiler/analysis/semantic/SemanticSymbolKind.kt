package net.exoad.kira.compiler.analysis.semantic

import net.exoad.kira.utils.ObsoleteLanguageFeat

enum class SemanticSymbolKind {
    VARIABLE,
    FUNCTION,
    CLASS,

    @ObsoleteLanguageFeat
    NAMESPACE,
    ENUM,
    PARAMETER,
    TYPE_SPECIFIER
}