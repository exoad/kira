package net.exoad.kira.compiler.frontend.parser.ast.elements

import net.exoad.kira.compiler.frontend.lexer.Token

/**
 * Technically not an AST Node by inheritance, but it is crucial in the evaluation of AST nodes
 *
 * [context] describe on where these members are allowed to be placed
 */
enum class Modifiers(val tokenType: Token.Type, val context: Array<Context> = emptyArray())
{
    MUTABLE(
        Token.Type.K_MODIFIER_MUTABLE, arrayOf(
            Context.CLASS,
            Context.CLASS_MEMBER,
            Context.VARIABLE,
            Context.FUNCTION,
            Context.NAMESPACE_MEMBER,
            Context.MODULE
        )
    ),
    PUBLIC(
        Token.Type.K_MODIFIER_PUBLIC,
        arrayOf(
            Context.CLASS,
            Context.CLASS_MEMBER,
            Context.VARIABLE,
            Context.FUNCTION,
            Context.NAMESPACE,
            Context.NAMESPACE_MEMBER,
            Context.ENUM,
        )
    ),
    REQUIRE(Token.Type.K_MODIFIER_REQUIRE, arrayOf(Context.CLASS_MEMBER, Context.VARIABLE, Context.FUNCTION)),
    WEAK(
        Token.Type.K_MODIFIER_WEAK,
        arrayOf(Context.CLASS_MEMBER, Context.VARIABLE, Context.FUNCTION, Context.FUNCTION_PARAMETER)
    )
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

    enum class Context
    {
        CLASS, MODULE, FUNCTION, CLASS_MEMBER, VARIABLE, FUNCTION_PARAMETER, NAMESPACE, NAMESPACE_MEMBER, ENUM
    }
}