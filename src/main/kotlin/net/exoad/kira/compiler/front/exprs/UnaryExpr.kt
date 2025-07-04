package net.exoad.kira.compiler.front.exprs

import net.exoad.kira.Symbols
import net.exoad.kira.compiler.Diagnostics
import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.exprs.Expr
import net.exoad.kira.compiler.front.Token

class UnaryExpr(val operator: UnaryOp, val operand: Expr) : Expr()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitUnaryExpr(this)
    }

    override fun toString(): String
    {
        return "UnaryExpr{ $operator -> $operand }"
    }
}

// TODO: migrate this to also use an array of symbols similar to BinaryOperator so we can have multiple symbols :)
enum class UnaryOp(val symbol: Symbols, val tokenType: Token.Type, val precedence: Int)
{
    // numerical operators
    NEG(Symbols.HYPHEN, Token.Type.OP_SUB, 13),
    POS(Symbols.PLUS, Token.Type.OP_ADD, 13),

    // logical operators
    NOT(Symbols.EXCLAMATION, Token.Type.S_BANG, 13),

    // bitwise operators
    BIT_NOT(Symbols.TILDE, Token.Type.S_TILDE, 13)
    ;

    companion object
    {
        fun byTokenType(tokenType: Token.Type): UnaryOp
        {
            return when(tokenType)
            {
                Token.Type.OP_SUB  -> NEG
                Token.Type.OP_ADD  -> POS
                Token.Type.S_BANG  -> NOT
                Token.Type.S_TILDE -> BIT_NOT
                else               -> Diagnostics.panic(
                    "UnaryOperator::byTokenType",
                    "$tokenType is not an unary operator!"
                )
            }
        }

        fun byTokenTypeMaybe(tokenType: Token.Type): UnaryOp?
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