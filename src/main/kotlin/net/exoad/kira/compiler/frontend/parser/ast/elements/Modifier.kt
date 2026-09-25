package net.exoad.kira.compiler.frontend.parser.ast.elements

import net.exoad.kira.compiler.frontend.lexer.Token

/**
 * Technically not an AST Node by inheritance, but it is crucial in the evaluation of AST nodes
 *
 * [wrappingContext] describe on where these members are allowed to be placed
 */
enum class Modifier(val tokenType: Token.Type, val wrappingContext: Array<WrappingContext> = emptyArray()) {
    MUTABLE(
        Token.Type.K_MODIFIER_MUTABLE, arrayOf(
            WrappingContext.CLASS,
            WrappingContext.CLASS_MEMBER,
            WrappingContext.VARIABLE,
            WrappingContext.FUNCTION,
            WrappingContext.MODULE,
            // `pub mut fx onLoad: () Void { }` in a trait (spec Mutable
            // Methods; design 5.9 chain): a trait method may mutate `this`.
            WrappingContext.TRAIT_MEMBER,
        )
    ),

    PUBLIC(
        Token.Type.K_MODIFIER_PUBLIC,
        arrayOf(
            WrappingContext.CLASS,
            WrappingContext.CLASS_MEMBER,
            WrappingContext.VARIABLE,
            WrappingContext.FUNCTION,
            WrappingContext.ENUM,
            WrappingContext.ENUM_MEMBER,
            WrappingContext.TRAIT_MEMBER,
            WrappingContext.TRAIT,
            WrappingContext.TYPE_ALIAS
        )
    ),
    REQUIRE(
        Token.Type.K_MODIFIER_REQUIRE,
        arrayOf(
            WrappingContext.CLASS_MEMBER,
            WrappingContext.VARIABLE,
            WrappingContext.FUNCTION,
        )
    ),

    /**
     * `override fx ...`: a method that replaces a parent's or a trait's (spec
     * Inheritance). Allowed on class and trait members; FUNCTION is listed
     * because a method is re-checked as a function when its body is parsed,
     * and the parser refuses `override` on a function outside a type body.
     */
    OVERRIDE(
        Token.Type.K_MODIFIER_OVERRIDE,
        arrayOf(
            WrappingContext.CLASS_MEMBER,
            WrappingContext.TRAIT_MEMBER,
            WrappingContext.FUNCTION,
        )
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