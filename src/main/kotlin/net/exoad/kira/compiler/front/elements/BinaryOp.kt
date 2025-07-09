package net.exoad.kira.compiler.front.elements

import net.exoad.kira.Symbols
import net.exoad.kira.compiler.front.Token

// precedence guide: https://images.hive.blog/DQmZkdJ5YJGuQzyZvBW1gLRaKwJrKLiSNWkETb8AyBw4Z5A/image.png
enum class BinaryOp(val symbol: Array<Symbols>, val precedence: Int)
{
    // arithmetic operators
    ADD(arrayOf(Symbols.PLUS), 11),
    SUB(arrayOf(Symbols.HYPHEN), 11),
    MUL(arrayOf(Symbols.ASTERISK), 12),
    DIV(arrayOf(Symbols.SLASH), 12),
    MOD(arrayOf(Symbols.PERCENT), 12),

    // logical operators
    EQL(arrayOf(Symbols.EQUALS, Symbols.EQUALS), 8),
    NEQ(arrayOf(Symbols.EXCLAMATION, Symbols.EQUALS), 8),
    GTE(arrayOf(Symbols.CLOSE_ANGLE, Symbols.EQUALS), 9),
    LTE(arrayOf(Symbols.OPEN_ANGLE, Symbols.EQUALS), 9),
    GRT(arrayOf(Symbols.CLOSE_ANGLE), 9),
    LST(arrayOf(Symbols.OPEN_ANGLE), 9),
    AND(arrayOf(Symbols.AMPERSAND, Symbols.AMPERSAND), 4),
    OR(arrayOf(Symbols.PIPE, Symbols.PIPE), 3),

    // bitwise operators
    BIT_SHR(arrayOf(Symbols.OPEN_ANGLE, Symbols.OPEN_ANGLE), 10),
    BIT_SHL(arrayOf(Symbols.CLOSE_ANGLE, Symbols.CLOSE_ANGLE), 10),
    BIT_USHR(arrayOf(Symbols.OPEN_ANGLE, Symbols.OPEN_ANGLE, Symbols.OPEN_ANGLE), 10),
    BIT_XOR(arrayOf(Symbols.CARET), 6),

    // raw
    CONJUNCTIVE_OR(arrayOf(Symbols.PIPE), 5),
    CONJUNCTIVE_AND(arrayOf(Symbols.AMPERSAND), 7),
    CONJUNCTIVE_DOT(arrayOf(Symbols.PERIOD), 14),
    RANGE(arrayOf(Symbols.PERIOD, Symbols.PERIOD), 14),

    TYPE_CHECK(arrayOf(Symbols.LOWERCASE_I, Symbols.LOWERCASE_S), 9),
    TYPE_CAST(arrayOf(Symbols.LOWERCASE_A, Symbols.LOWERCASE_S), 9)
    ;

    companion object
    {
//                else                     -> Diagnostics.panic(
//                    "BinaryOperator::byTokenType",
//                    "$tokenType is not a binary operator!"
//                )

        fun byTokenTypeMaybe(vararg tokenType: Token.Type, onBad: (() -> Unit)? = null): BinaryOp?
        {
            val op = when
            {

                tokenType.size == 3 &&
                        tokenType[0] == Token.Type.S_CLOSE_ANGLE &&
                        tokenType[1] == Token.Type.S_CLOSE_ANGLE &&
                        tokenType[2] == Token.Type.S_CLOSE_ANGLE -> BIT_USHR // >>>
                tokenType.size == 2 &&
                        tokenType[0] == Token.Type.S_CLOSE_ANGLE &&
                        tokenType[1] == Token.Type.S_CLOSE_ANGLE -> BIT_SHR // >>
                tokenType.size == 2 &&
                        tokenType[0] == Token.Type.S_CLOSE_ANGLE &&
                        tokenType[1] == Token.Type.OP_ASSIGN     -> GTE // >=
                tokenType.size == 1                              -> when(tokenType[0])
                {
                    Token.Type.OP_ADD        -> ADD
                    Token.Type.OP_SUB        -> SUB
                    Token.Type.OP_MUL        -> MUL
                    Token.Type.OP_DIV        -> DIV
                    Token.Type.OP_MOD        -> MOD
                    Token.Type.OP_CMP_EQL    -> EQL
                    Token.Type.OP_CMP_NEQ    -> NEQ
                    Token.Type.OP_CMP_LEQ    -> LTE
                    Token.Type.S_OPEN_ANGLE  -> LST
                    Token.Type.S_CLOSE_ANGLE -> GRT
                    Token.Type.OP_BIT_SHL    -> BIT_SHL
                    Token.Type.OP_BIT_XOR    -> BIT_XOR
                    Token.Type.OP_CMP_OR     -> OR
                    Token.Type.OP_CMP_AND    -> AND
                    Token.Type.S_PIPE        -> CONJUNCTIVE_OR
                    Token.Type.S_AND         -> CONJUNCTIVE_AND
                    Token.Type.S_DOT         -> CONJUNCTIVE_DOT
                    Token.Type.OP_RANGE      -> RANGE
                    Token.Type.K_IS          -> TYPE_CHECK
                    Token.Type.K_AS          -> TYPE_CAST
                    else                     -> null
                }
                else                                             -> null
            }
            if(op == null)
            {
                onBad?.invoke()
            }
            return op
        }
    }
}
