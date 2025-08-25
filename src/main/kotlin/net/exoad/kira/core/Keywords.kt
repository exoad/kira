package net.exoad.kira.core

import net.exoad.kira.compiler.frontend.lexer.Token

/**
 * Keywords shared constants, holds all the special keywords used in kira.
 */
object Keywords
{
    /**
     * Reserved keywords that are essential to the programming language
     */
    val reserved = mapOf(
        "if" to Token.Type.K_IF,
        "else" to Token.Type.K_ELSE,
        "while" to Token.Type.K_WHILE,
        "do" to Token.Type.K_DO,
        "return" to Token.Type.K_RETURN,
        "mut" to Token.Type.K_MODIFIER_MUTABLE,
        "pub" to Token.Type.K_MODIFIER_PUBLIC,
        "require" to Token.Type.K_MODIFIER_REQUIRE,
        "class" to Token.Type.K_CLASS,
        "for" to Token.Type.K_FOR,
        "module" to Token.Type.K_MODULE,
        "use" to Token.Type.K_USE,
        "namespace" to Token.Type.K_NAMESPACE,
        "enum" to Token.Type.K_ENUM,
        "as" to Token.Type.K_AS,
        "is" to Token.Type.K_IS,
        "break" to Token.Type.K_BREAK,
        "continue" to Token.Type.K_CONTINUE,
        "with" to Token.Type.K_WITH,
        "fx" to Token.Type.K_FX
    )
    val literals = mapOf(
        "true" to Token.Type.L_TRUE_BOOL,
        "false" to Token.Type.L_FALSE_BOOL,
        "null" to Token.Type.L_NULL,
    )
    val all = reserved + literals
}