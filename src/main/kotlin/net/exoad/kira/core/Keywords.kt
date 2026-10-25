package net.exoad.kira.core

import net.exoad.kira.compiler.frontend.lexer.Token

/**
 * Keywords shared constants, holds all the special keywords used in kira.
 */
object Keywords {
    /**
     * Kira's keywords. `mut` and `return` stay, so the parser refuses each by name; the kind words
     * (class, trait, struct, fx, module, use, alias, require, enum, variant, do, finally) are gone,
     * and `is` (a type test is `x == T`).
     */
    val reserved = mapOf(
        "if" to Token.Type.K_IF,
        "else" to Token.Type.K_ELSE,
        "while" to Token.Type.K_WHILE,
        "return" to Token.Type.K_RETURN,
        "mut" to Token.Type.K_MODIFIER_MUTABLE,
        "pub" to Token.Type.K_MODIFIER_PUBLIC,
        "for" to Token.Type.K_FOR,
        "as" to Token.Type.K_AS,
        "break" to Token.Type.K_BREAK,
        "continue" to Token.Type.K_CONTINUE,
        "with" to Token.Type.K_WITH,
        "try" to Token.Type.K_TRY,
        "throw" to Token.Type.K_THROW,
        "on" to Token.Type.K_ON,
        "this" to Token.Type.K_THIS,
        "in" to Token.Type.K_IN,
        "initially" to Token.Type.K_INITIALLY,
        "override" to Token.Type.K_MODIFIER_OVERRIDE,
        "final" to Token.Type.K_MODIFIER_FINAL,
    )
}