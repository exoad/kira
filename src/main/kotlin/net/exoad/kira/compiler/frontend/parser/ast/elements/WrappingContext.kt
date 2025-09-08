package net.exoad.kira.compiler.frontend.parser.ast.elements

import net.exoad.kira.utils.ObsoleteLanguageFeat

enum class WrappingContext {
    CLASS,
    MODULE,
    FUNCTION,
    CLASS_MEMBER,
    VARIABLE,
    FUNCTION_PARAMETER,

    @ObsoleteLanguageFeat
    NAMESPACE,

    @ObsoleteLanguageFeat
    NAMESPACE_MEMBER,
    ENUM,
    ENUM_MEMBER,
    TRAIT,
    TRAIT_MEMBER
}