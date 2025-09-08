package net.exoad.kira.compiler.frontend.parser.ast.elements

import net.exoad.kira.compiler.frontend.lexer.Token
import net.exoad.kira.utils.ObsoleteLanguageFeat

/**
 * Technically not an AST Node by inheritance, but it is crucial in the evaluation of AST nodes
 *
 * [wrappingContext] describe on where these members are allowed to be placed
 */
enum class Modifier(val tokenType: Token.Type, val wrappingContext: Array<WrappingContext> = emptyArray()) {
    @OptIn(ObsoleteLanguageFeat::class)
    MUTABLE(
        Token.Type.K_MODIFIER_MUTABLE, arrayOf(
            WrappingContext.CLASS,
            WrappingContext.CLASS_MEMBER,
            WrappingContext.VARIABLE,
            WrappingContext.FUNCTION,
            WrappingContext.NAMESPACE_MEMBER,
            WrappingContext.MODULE

        )
    ),

    @OptIn(ObsoleteLanguageFeat::class)
    PUBLIC(
        Token.Type.K_MODIFIER_PUBLIC,
        arrayOf(
            WrappingContext.CLASS,
            WrappingContext.CLASS_MEMBER,
            WrappingContext.VARIABLE,
            WrappingContext.FUNCTION,
            WrappingContext.NAMESPACE,
            WrappingContext.NAMESPACE_MEMBER,
            WrappingContext.ENUM,
            WrappingContext.ENUM_MEMBER,
            WrappingContext.TRAIT_MEMBER,
            WrappingContext.TRAIT
        )
    ),
    REQUIRE(
        Token.Type.K_MODIFIER_REQUIRE,
        arrayOf(WrappingContext.CLASS_MEMBER, WrappingContext.VARIABLE, WrappingContext.FUNCTION)
    ),
    ;

    companion object {
        fun byTokenTypeMaybe(tokenType: Token.Type, onBad: (() -> Unit)? = null): Modifier? {
            val modifier = entries.find { it.tokenType == tokenType }
            if (modifier == null) {
                onBad?.invoke()
            }
            return modifier
        }
    }

}