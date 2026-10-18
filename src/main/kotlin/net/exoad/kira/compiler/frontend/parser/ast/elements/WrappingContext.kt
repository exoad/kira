package net.exoad.kira.compiler.frontend.parser.ast.elements

enum class WrappingContext {
    CLASS,
    MODULE,
    FUNCTION,
    CLASS_MEMBER,
    VARIABLE,
    FUNCTION_PARAMETER,
    TRAIT,
    TRAIT_MEMBER,
    TYPE_ALIAS
}