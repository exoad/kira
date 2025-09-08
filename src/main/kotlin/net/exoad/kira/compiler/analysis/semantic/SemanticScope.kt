package net.exoad.kira.compiler.analysis.semantic

import net.exoad.kira.utils.ObsoleteLanguageFeat

enum class SemanticScope {
    MODULE,
    CLASS,

    @ObsoleteLanguageFeat
    NAMESPACE,
    ENUM,
    FUNCTION,
}
