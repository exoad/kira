package net.exoad.kira

import net.exoad.kira.compiler.front.Token

enum class Symbols(val rep: Char)
{
    NULL('\u0000'),
    DOUBLE_QUOTE('\u0022'),
    BACK_SLASH('\u005c'),
    UNDERSCORE('\u005f'),
    OPEN_BRACKET('\u005b'),
    CLOSE_BRACKET('\u005d'),
    CARET('\u005e'),
    AMPERSAND('\u0026'),
    PLUS('\u002b'),
    HYPHEN('\u002d'),
    PIPE('\u007c'),
    ASTERISK('\u002a'),
    SLASH('\u002f'),
    PERCENT('\u0025'),
    SEMICOLON('\u003b'),
    EQUALS('\u003d'),
    OPEN_PARENTHESIS('\u0028'),
    CLOSE_PARENTHESIS('\u0029'),
    AT('\u0040'),
    TILDE('\u007e'),
    OPEN_BRACE('\u007b'),
    CLOSE_BRACE('\u007d'),
    OPEN_ANGLE('\u003c'),
    CLOSE_ANGLE('\u003e'),
    EXCLAMATION('\u0021'),
    COMMA('\u002c'),
    PERIOD('\u002e'),
    COLON('\u003a');

    override fun toString(): String
    {
        return rep.toString()
    }
}

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
    )

    val literals = mapOf(
        "true" to Token.Type.L_TRUE_BOOL,
        "false" to Token.Type.L_FALSE_BOOL,
        "null" to Token.Type.L_NULL,
    )

    val all = reserved + literals
}

/**
 * Builtin types that are although available at source level are builtin "direct" types
 * that the compiler is always aware of.
 *
 * Type precedence is an implied concept that means the likelihood of this type being
 * converted to one with higher precedence.
 *
 * For example. a float and an integer together in an arithmetic expr will always
 * equate to a float without specific casting. This is because floats in general will
 * have a higher precedence than integers. On the other hand, there is also precedence
 * within type groups themselves.
 *
 * For example, an expr adding an Int32 and an Int64 will always equate to an Int64
 * without specific casting that involves truncating.
 */
object Builtin
{
    enum class Intrinsics(val rep: String)
    {
        TRACE("__trace__")
    }

    /**
     * Unit types, only things like Void
     */
    val unitTypes = mapOf<String, Array<Token.Type>>(
        "Void" to emptyArray()
    )

    /**
     * Integer
     */
    val integerTypes = mapOf(
        "Int32" to arrayOf(Token.Type.L_INTEGER),
        "Int64" to arrayOf(Token.Type.L_INTEGER),
    )

    /**
     * Floating point numbers
     */
    val floatTypes = mapOf(
        "Float32" to arrayOf(Token.Type.L_FLOAT),
        "Float64" to arrayOf(Token.Type.L_FLOAT)
    )

    /**
     * Strings, Lists, anything sequential
     *
     * Has always the highest implicit precedence value
     */
    val sequenceTypes = mapOf(
        "String" to arrayOf(Token.Type.L_STRING)
    )

    /**
     * Generally just the Bool type
     *
     * Has the same implicit precedence as integer types
     */
    val logicalTypes = mapOf(
        "Bool" to arrayOf(Token.Type.L_TRUE_BOOL, Token.Type.L_FALSE_BOOL)
    )

    fun allBuiltinTypes(): Map<String, Array<Token.Type>>
    {
        return integerTypes + sequenceTypes + logicalTypes + unitTypes
    }
}


