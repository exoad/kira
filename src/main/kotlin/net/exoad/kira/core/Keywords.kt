package net.exoad.kira.core

import net.exoad.kira.compiler.frontend.lexer.Token

/**
 * Keywords shared constants, holds all the special keywords used in kira.
 */
object Keywords {
    /**
     * The clean dialect's keywords. `mut` and `return` stay until their stages; the kind words
     * (class, trait, struct, fx, module, use, alias, require, enum, variant, do, finally) are gone.
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
        "is" to Token.Type.K_IS,
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

    /** The old dialect's keywords (py-backend 45d5255), for the converter's legacy lexer and parser. */
    val legacy = mapOf(
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
        "enum" to Token.Type.K_ENUM,
        "as" to Token.Type.K_AS,
        "is" to Token.Type.K_IS,
        "break" to Token.Type.K_BREAK,
        "continue" to Token.Type.K_CONTINUE,
        "with" to Token.Type.K_WITH,
        "fx" to Token.Type.K_FX,
        "trait" to Token.Type.K_TRAIT,
        "try" to Token.Type.K_TRY,
        "throw" to Token.Type.K_THROW,
        "on" to Token.Type.K_ON,
        "variant" to Token.Type.K_VARIANT,
        "alias" to Token.Type.K_ALIAS,
        "this" to Token.Type.K_THIS,
        // design 2.4: no existing .kira file or test used these as names (grepped)
        "struct" to Token.Type.K_STRUCT,
        "in" to Token.Type.K_IN,
        "initially" to Token.Type.K_INITIALLY,
        "finally" to Token.Type.K_FINALLY,
        "override" to Token.Type.K_MODIFIER_OVERRIDE,
        // charter: "final forbids inheriting" (spec, "use final modifier"); a class modifier
        // only (W2.9, 1.8). Grepped 2026-09-26: no .kira file, Kira snippet in src/test/kotlin,
        // or kira/ source used "final" as an identifier (only comments, and the C++ `final`
        // specifier in kira/cpp/kira/*.hxx and CppNamesTest's escape("final"), not Kira source).
        "final" to Token.Type.K_MODIFIER_FINAL,
    )
}