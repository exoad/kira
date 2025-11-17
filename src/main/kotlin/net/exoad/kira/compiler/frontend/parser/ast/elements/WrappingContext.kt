package net.exoad.kira.compiler.frontend.parser.ast.elements

enum class WrappingContext {
    CLASS,
    MODULE,
    FUNCTION,
    CLASS_MEMBER,
    VARIABLE,
    FUNCTION_PARAMETER,
    ENUM,
    ENUM_MEMBER,
    TRAIT,
    TRAIT_MEMBER,
    VARIANT,
    VARIANT_VARIANCE,
    VARIANT_MEMBER,
    TYPE_ALIAS
}