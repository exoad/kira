package net.exoad.kira.core

import net.exoad.kira.compiler.frontend.lexer.Token

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
object BuiltinTypes {
    /**
     * Unit types, only things like Void
     */
    val unitTypes = mapOf(
        "Void" to arrayOf(Token.Type.X_ANY)
    )

    /**
     * Anything related to something being treated intrinsically as a modifier.
     */
    val referenceTypes = mapOf(
        "Maybe" to arrayOf(Token.Type.X_ANY),
        "Weak" to arrayOf(Token.Type.X_ANY),
        "Unsafe" to arrayOf(Token.Type.X_ANY)
    )

    /**
     * Integer
     */
    val integerTypes = mapOf(
        "Int8" to arrayOf(Token.Type.L_INTEGER),
        "Int16" to arrayOf(Token.Type.L_INTEGER),
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
        "String" to arrayOf(Token.Type.L_STRING),
        "Array" to arrayOf(Token.Type.X_ANY),
        "Map" to arrayOf(Token.Type.X_ANY),
        "List" to arrayOf(Token.Type.X_ANY),
        "Set" to arrayOf(Token.Type.X_ANY)
    )

    /**
     * Generally just the Bool type
     *
     * Has the same implicit precedence as integer types
     */

    fun allBuiltinTypes(): Map<String, Array<Token.Type>> {
        return integerTypes + sequenceTypes + unitTypes + referenceTypes
    }
}
