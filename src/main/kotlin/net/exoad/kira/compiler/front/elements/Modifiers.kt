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
    PUBLIC(Token.Type.K_MODIFIER_PUBLIC, arrayOf(Scope.CLASS, Scope.MODULE, Scope.NAMESPACE)),
    REQUIRE(Token.Type.K_MODIFIER_REQUIRE, arrayOf(Scope.CLASS))
    ;

    companion object
    {
        fun byTokenType(tokenType: Token.Type): Modifiers
        {
            val modifier = Modifiers.entries.find { it.tokenType == tokenType }
            return when(modifier == null)
            {
                true -> Diagnostics.panic("Kira", "$tokenType is not a modifier!")
                else -> modifier
            }
        }
    }

    enum class Scope
    {
        CLASS, MODULE, FUNCTION, NAMESPACE
    }
}