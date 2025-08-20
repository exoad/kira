package net.exoad.kira.compiler.elements

import net.exoad.kira.Symbols
import net.exoad.kira.compiler.Token

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
    EQUALS(arrayOf(Symbols.EQUALS, Symbols.EQUALS), 8),
    NOT_EQUAL(arrayOf(Symbols.EXCLAMATION, Symbols.EQUALS), 8),
    GREATER_THAN_OR_EQUAL(arrayOf(Symbols.CLOSE_ANGLE, Symbols.EQUALS), 9),
    LESS_THAN_OR_EQUAL(arrayOf(Symbols.OPEN_ANGLE, Symbols.EQUALS), 9),
    GREATER_THAN(arrayOf(Symbols.CLOSE_ANGLE), 9),
    LESS_THAN(arrayOf(Symbols.OPEN_ANGLE), 9),
    AND(arrayOf(Symbols.AMPERSAND, Symbols.AMPERSAND), 4),
    OR(arrayOf(Symbols.PIPE, Symbols.PIPE), 3),

    // bitwise operators
    SHR(arrayOf(Symbols.OPEN_ANGLE, Symbols.OPEN_ANGLE), 10),
    SHL(arrayOf(Symbols.CLOSE_ANGLE, Symbols.CLOSE_ANGLE), 10),
    USHR(arrayOf(Symbols.OPEN_ANGLE, Symbols.OPEN_ANGLE, Symbols.OPEN_ANGLE), 10),
    XOR(arrayOf(Symbols.CARET), 6),

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

        fun byTokenTypeMaybe(tokenType: Array<Token.Type>, onBad: (() -> Unit)? = null): BinaryOp?
        {
            val op = when
            {

                tokenType.size == 3 &&
                        tokenType[0] == Token.Type.S_CLOSE_ANGLE &&
                        tokenType[1] == Token.Type.S_CLOSE_ANGLE &&
                        tokenType[2] == Token.Type.S_CLOSE_ANGLE -> USHR // >>>
                tokenType.size == 2 &&
                        tokenType[0] == Token.Type.S_CLOSE_ANGLE &&
                        tokenType[1] == Token.Type.S_CLOSE_ANGLE -> SHR // >>
                tokenType.size == 2 &&
                        tokenType[0] == Token.Type.S_CLOSE_ANGLE &&
                        tokenType[1] == Token.Type.S_EQUAL       -> GREATER_THAN_OR_EQUAL // >=
                tokenType.size == 1                              -> when(tokenType[0])
                {
                    Token.Type.OP_ADD            -> ADD
                    Token.Type.OP_SUB            -> SUB
                    Token.Type.OP_MUL            -> MUL
                    Token.Type.OP_DIV            -> DIV
                    Token.Type.OP_MOD            -> MOD
                    Token.Type.OP_CMP_EQL        -> EQUALS
                    Token.Type.OP_CMP_NEQ        -> NOT_EQUAL
                    Token.Type.OP_CMP_LEQ        -> LESS_THAN_OR_EQUAL
                    Token.Type.S_OPEN_ANGLE      -> LESS_THAN
                    Token.Type.S_CLOSE_ANGLE     -> GREATER_THAN
                    Token.Type.OP_BIT_SHL        -> SHL
                    Token.Type.OP_BIT_XOR        -> XOR
                    Token.Type.OP_CMP_OR         -> OR
                    Token.Type.OP_CMP_AND        -> AND
                    Token.Type.S_PIPE            -> CONJUNCTIVE_OR
                    Token.Type.S_AND             -> CONJUNCTIVE_AND
                    Token.Type.S_DOT             -> CONJUNCTIVE_DOT
                    Token.Type.OP_RANGE          -> RANGE
                    Token.Type.K_IS              -> TYPE_CHECK
                    Token.Type.K_AS              -> TYPE_CAST
                    Token.Type.OP_ASSIGN_MUL     -> MUL
                    Token.Type.OP_ASSIGN_DIV     -> DIV
                    Token.Type.OP_ASSIGN_ADD     -> ADD
                    Token.Type.OP_ASSIGN_SUB     -> SUB
                    Token.Type.OP_ASSIGN_MOD     -> MOD
                    Token.Type.OP_ASSIGN_BIT_XOR -> XOR
                    Token.Type.OP_ASSIGN_BIT_SHL -> SHL
                    Token.Type.OP_ASSIGN_BIT_OR  -> CONJUNCTIVE_OR
                    Token.Type.OP_ASSIGN_BIT_AND -> CONJUNCTIVE_AND
                    else                         -> null
                }
                tokenType.size == 3 &&
                        tokenType[0] == Token.Type.S_CLOSE_ANGLE &&
                        tokenType[1] == Token.Type.S_CLOSE_ANGLE &&
                        tokenType[2] == Token.Type.S_EQUAL       -> SHR
                tokenType.size == 4 &&
                        tokenType[0] == Token.Type.S_CLOSE_ANGLE &&
                        tokenType[1] == Token.Type.S_CLOSE_ANGLE &&
                        tokenType[2] == Token.Type.S_CLOSE_ANGLE &&
                        tokenType[3] == Token.Type.S_EQUAL       -> USHR
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
