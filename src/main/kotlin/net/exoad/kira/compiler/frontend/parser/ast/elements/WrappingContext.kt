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
    /** derivations of the variant - variance */
    VARIANT_VARIANCE,
    /** fields of the variant itself */
    VARIANT_MEMBER,
    TYPE_ALIAS
}