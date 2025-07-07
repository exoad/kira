package net.exoad.kira.compiler.front.elements

import net.exoad.kira.compiler.Diagnostics
import net.exoad.kira.compiler.front.Token

/**
 * Technically not an AST Node by inheritance, but it is crucial in the evaluation of AST nodes
 *
 * [scope] describe on where these members are allowed to be placed
 */
enum class Modifiers(
    val tokenType: Token.Type,
    val scope: Array<Scope> = emptyArray(),
)
{
    MUTABLE(Token.Type.K_MODIFIER_MUTABLE, Scope.entries.toTypedArray()),
    PUBLIC(
        Token.Type.K_MODIFIER_PUBLIC,
        arrayOf(Scope.CLASS, Scope.CLASS_MEMBER, Scope.NAMESPACE, Scope.VARIABLE, Scope.FUNCTION)
    ),
    REQUIRE(Token.Type.K_MODIFIER_REQUIRE, arrayOf(Scope.CLASS_MEMBER, Scope.VARIABLE, Scope.FUNCTION))
    ;

    companion object
    {
        fun byTokenTypeMaybe(tokenType: Token.Type, onBad: (() -> Unit)? = null): Modifiers?
        {
            val modifier = Modifiers.entries.find { it.tokenType == tokenType }
            if(modifier == null)
            {
                onBad?.invoke()
            }
            return modifier
        }
    }

    enum class Scope
    {
        CLASS, MODULE, FUNCTION, NAMESPACE, CLASS_MEMBER, VARIABLE, FUNCTION_PARAMETER
    }
}