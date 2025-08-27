package net.exoad.kira.compiler.frontend.parser.ast.elements

import net.exoad.kira.compiler.frontend.lexer.Token

/**
 * Technically not an AST Node by inheritance, but it is crucial in the evaluation of AST nodes
 *
 * [wrappingContext] describe on where these members are allowed to be placed
 */
enum class Modifiers(val tokenType: Token.Type, val wrappingContext: Array<WrappingContext> = emptyArray())
{
    MUTABLE(
        Token.Type.K_MODIFIER_MUTABLE, arrayOf(
            WrappingContext.CLASS,
            WrappingContext.CLASS_MEMBER,
            WrappingContext.VARIABLE,
            WrappingContext.FUNCTION,
            WrappingContext.OBJECT_MEMBER,
            WrappingContext.MODULE
        )
    ),
    PUBLIC(
        Token.Type.K_MODIFIER_PUBLIC,
        arrayOf(
            WrappingContext.CLASS,
            WrappingContext.CLASS_MEMBER,
            WrappingContext.VARIABLE,
            WrappingContext.FUNCTION,
            WrappingContext.OBJECT,
            WrappingContext.OBJECT_MEMBER,
            WrappingContext.ENUM,
        )
    ),
    REQUIRE(
        Token.Type.K_MODIFIER_REQUIRE,
        arrayOf(WrappingContext.CLASS_MEMBER, WrappingContext.VARIABLE, WrappingContext.FUNCTION)
    ),
    ;

    companion object
    {
        fun byTokenTypeMaybe(tokenType: Token.Type, onBad: (() -> Unit)? = null): Modifiers?
        {
            val modifier = entries.find { it.tokenType == tokenType }
            if(modifier == null)
            {
                onBad?.invoke()
            }
            return modifier
        }
    }

    enum class WrappingContext
    {
        CLASS, MODULE, FUNCTION, CLASS_MEMBER, VARIABLE, FUNCTION_PARAMETER, OBJECT, OBJECT_MEMBER, ENUM
    }
}