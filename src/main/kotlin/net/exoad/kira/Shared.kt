package net.exoad.kira

import net.exoad.kira.compiler.front.Token

// this file holds some of the global stuffs of the language like keywords and valid ascii symbols (maybe even utf8 later on)
//
// todo: [low-priority] should i modularize this and move some of the stuffs away from this file like the symbols and keywords and stuffs. in most of my other projects, a shared source file usually means just internal configurations and constants, but who knows, ig this makes sense!

/**
 * Global symbols used by the language and are defined here. these are all one byte characters for now and should all be ascii based (maybe utf8 for easter egg or special intrinsics or special feature??)
 * lmao having to copy and paste constantly from an utf8 character code website would be hilarious
 *
 *
 * - primarily used by [net.exoad.kira.compiler.front.KiraLexer] to match them to [Token]s
 */
enum class Symbols(val rep: Char)
{
    NULL('\u0000'),
    NEWLINE('\n'),
    DOUBLE_QUOTE('\u0022'),
    BACK_SLASH('\u005c'), // unused ignore; this might not be necessary, but we might need it in the parser stage since the parse will now evaluate escaped strings? (check chores)
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
    STATEMENT_DELIMITER('\u003b'),
    EQUALS('\u003d'),
    OPEN_PARENTHESIS('\u0028'),
    CLOSE_PARENTHESIS('\u0029'),
    AT('\u0040'),
    TILDE('\u007e'),
    OPEN_BRACE('\u007b'),
    CLOSE_BRACE('\u007d'),
    OPEN_ANGLE('\u003c'),
    // lmao this is just hilarious, but yea i just dont want to ruin the structuring of the language, so these tables are necessary
    LOWERCASE_A('\u0061'),
    LOWERCASE_I('\u0069'),
    LOWERCASE_S('\u0073'),
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
        "weak" to Token.Type.K_MODIFIER_WEAK,
        "object" to Token.Type.K_OBJECT,
        "enum" to Token.Type.K_ENUM,
        "as" to Token.Type.K_AS,
        "is" to Token.Type.K_IS,
        "break" to Token.Type.K_BREAK,
        "continue" to Token.Type.K_CONTINUE,
        "with" to Token.Type.K_WITH
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
        TRACE("trace")
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


