package net.exoad.kira

import net.exoad.kira.compiler.frontend.Token

enum class Symbols(val rep: Char)
{
    NULL('\u0000'),
    DOUBLE_QUOTE('\u0022'),
    BACK_SLASH('\u005c'),
    UNDERSCORE('\u005f'),
    PLUS('\u002b'),
    HYPHEN('\u002d'),
    ASTERISK('\u002a'),
    SLASH('\u002f'),
    PERCENT('\u0025'),
    SEMICOLON('\u003b'),
    EQUALS('\u003d'),
    OPEN_PARENTHESIS('\u0028'),
    CLOSE_PARENTHESIS('\u0029'),
    OPEN_BRACE('\u007b'),
    CLOSE_BRACE('\u007d'),
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
     * Common literal like keywords that are essential to the programming language
     */
    val common = mapOf(
        "true" to Token.Type.BOOL_TRUE_LITERAL,
        "false" to Token.Type.BOOL_FALSE_LITERAL,
        "if" to Token.Type.K_IF,
        "else" to Token.Type.K_ELSE,
        "while" to Token.Type.K_WHILE
    )
}

/**
 * Builtin types that are although available at source level are builtin "direct" types
 * that the compiler is always aware of.
 *
 * Type precedence is an implied concept that means the likelihood of this type being
 * converted to one with higher precedence.
 *
 * For example. a float and an integer together in an arithmetic expression will always
 * equate to a float without specific casting. This is because floats in general will
 * have a higher precedence than integers. On the other hand, there is also precedence
 * within type groups themselves.
 *
 * For example, an expression adding an Int32 and an Int64 will always equate to an Int64
 * without specific casting that involves truncating.
 */
object Builtin
{
    val unitTypes = mapOf<String, Array<Token.Type>>(
        "Void" to emptyArray()
    )

    /**
     * Integer
     */
    val integerTypes = mapOf(
        "Int32" to arrayOf(Token.Type.INTEGER_LITERAL),
        "Int64" to arrayOf(Token.Type.INTEGER_LITERAL),
    )

    /**
     * Floating point numbers
     */
    val floatTypes = mapOf(
        "Float32" to arrayOf(Token.Type.FLOAT_LITERAL),
        "Float64" to arrayOf(Token.Type.FLOAT_LITERAL)
    )

    /**
     * Strings, Lists, anything sequential
     *
     * Has always the highest implicit precedence value
     */
    val sequenceTypes = mapOf(
        "String" to arrayOf(Token.Type.STRING_LITERAL)
    )

    /**
     * Generally just the Bool type
     *
     * Has the same implicit precedence as integer types
     */
    val logicalTypes = mapOf(
        "Bool" to arrayOf(Token.Type.BOOL_TRUE_LITERAL, Token.Type.BOOL_FALSE_LITERAL)
    )

    fun allBuiltinTypes(): Map<String, Array<Token.Type>>
    {
        return integerTypes + sequenceTypes + logicalTypes + unitTypes
    }
}


