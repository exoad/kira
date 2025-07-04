package net.exoad.kira.compiler.front.exprs

import net.exoad.kira.Symbols
import net.exoad.kira.compiler.Diagnostics
import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.exprs.Expr
import net.exoad.kira.compiler.front.Token

class BinaryExpr(
    val leftExpr: Expr,
    val rightExpr: Expr,
    val operator: BinaryOp,
) : Expr()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitBinaryExpr(this)
    }

    override fun toString(): String
    {
        return "BinaryExpr{ $leftExpr ${operator.symbol} $rightExpr }"
    }
}

// precedence guide: https://images.hive.blog/DQmZkdJ5YJGuQzyZvBW1gLRaKwJrKLiSNWkETb8AyBw4Z5A/image.png
enum class BinaryOp(val symbol: Array<Symbols>, val tokenType: Token.Type, val precedence: Int)
{
    // arithmetic operators
    ADD(arrayOf(Symbols.PLUS), Token.Type.OP_ADD, 11),
    SUB(arrayOf(Symbols.HYPHEN), Token.Type.OP_SUB, 11),
    MUL(arrayOf(Symbols.ASTERISK), Token.Type.OP_MUL, 12),
    DIV(arrayOf(Symbols.SLASH), Token.Type.OP_DIV, 12),
    MOD(arrayOf(Symbols.PERCENT), Token.Type.OP_MOD, 12),

    // logical operators
    EQL(arrayOf(Symbols.EQUALS, Symbols.EQUALS), Token.Type.OP_CMP_EQL, 8),
    NEQ(arrayOf(Symbols.EXCLAMATION, Symbols.EQUALS), Token.Type.OP_CMP_NEQ, 8),
    GTE(arrayOf(Symbols.CLOSE_ANGLE, Symbols.EQUALS), Token.Type.OP_CMP_GEQ, 9),
    LTE(arrayOf(Symbols.OPEN_ANGLE, Symbols.EQUALS), Token.Type.OP_CMP_LEQ, 9),
    GRT(arrayOf(Symbols.CLOSE_ANGLE), Token.Type.S_CLOSE_ANGLE, 9),
    LST(arrayOf(Symbols.OPEN_ANGLE), Token.Type.S_OPEN_ANGLE, 9),
    AND(arrayOf(Symbols.AMPERSAND, Symbols.AMPERSAND), Token.Type.OP_CMP_AND, 4),
    OR(arrayOf(Symbols.PIPE, Symbols.PIPE), Token.Type.OP_CMP_OR, 3),

    // bitwise operators
    BIT_SHR(arrayOf(Symbols.OPEN_ANGLE, Symbols.OPEN_ANGLE), Token.Type.OP_BIT_SHR, 10),
    BIT_SHL(arrayOf(Symbols.CLOSE_ANGLE, Symbols.CLOSE_ANGLE), Token.Type.OP_BIT_SHL, 10),
    BIT_USHR(arrayOf(Symbols.OPEN_ANGLE, Symbols.OPEN_ANGLE, Symbols.OPEN_ANGLE), Token.Type.OP_BIT_USHR, 10),
    BIT_XOR(arrayOf(Symbols.CARET), Token.Type.OP_BIT_XOR, 6),

    // raw
    CONJUNCTIVE_OR(arrayOf(Symbols.PIPE), Token.Type.S_PIPE, 5),
    CONJUNCTIVE_AND(arrayOf(Symbols.AMPERSAND), Token.Type.S_AND, 7),
    CONJUNCTIVE_DOT(arrayOf(Symbols.PERIOD), Token.Type.S_DOT, 14)
    ;

    companion object
    {
        fun byTokenType(tokenType: Token.Type): BinaryOp
        {
            return when(tokenType)
            {
                Token.Type.OP_ADD        -> ADD
                Token.Type.OP_SUB        -> SUB
                Token.Type.OP_MUL        -> MUL
                Token.Type.OP_DIV        -> DIV
                Token.Type.OP_MOD        -> MOD
                Token.Type.OP_CMP_EQL    -> EQL
                Token.Type.OP_CMP_NEQ    -> NEQ
                Token.Type.OP_CMP_LEQ    -> LTE
                Token.Type.OP_CMP_GEQ    -> GTE
                Token.Type.S_OPEN_ANGLE  -> LST
                Token.Type.S_CLOSE_ANGLE -> GRT
                Token.Type.OP_BIT_USHR   -> BIT_USHR
                Token.Type.OP_BIT_SHR    -> BIT_SHR
                Token.Type.OP_BIT_SHL    -> BIT_SHL
                Token.Type.OP_BIT_XOR    -> BIT_XOR
                Token.Type.OP_CMP_OR     -> OR
                Token.Type.OP_CMP_AND    -> AND
                Token.Type.S_PIPE        -> CONJUNCTIVE_OR
                Token.Type.S_AND         -> CONJUNCTIVE_AND
                Token.Type.S_DOT         -> CONJUNCTIVE_DOT
                else                     -> Diagnostics.panic(
                    "BinaryOperator::byTokenType",
                    "$tokenType is not a binary operator!"
                )
            }
        }

        fun byTokenTypeMaybe(tokenType: Token.Type): BinaryOp?
        {
            return try
            {
                byTokenType(tokenType)
            }
            catch(_: Exception)
            {
                null
            }
        }
    }
}

