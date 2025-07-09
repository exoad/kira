package net.exoad.kira.compiler.front.exprs

import net.exoad.kira.compiler.Diagnostics
import net.exoad.kira.compiler.SourceContext
import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.Token
import net.exoad.kira.compiler.front.elements.BinaryOp

class CompoundAssignmentExpr(val left: Expr, val operator: BinaryOp, val right: Expr) : Expr()
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitCompoundAssignmentExpr(this)
    }

    override fun toString(): String
    {
        return "CompoundAssignmentExpr{ $left $operator $right}"
    }

    companion object
    {
        // we dont use varargs in these kind fo situations because it can make problematic code be problematic at runtime but not at compile time
        fun findBinaryOp(tokenType: Array<Token.Type>, context: SourceContext): BinaryOp
        {
            return when
            {
                tokenType.size == 3 &&
                        tokenType[0] == Token.Type.S_CLOSE_ANGLE &&
                        tokenType[1] == Token.Type.S_CLOSE_ANGLE &&
                        tokenType[2] == Token.Type.OP_ASSIGN -> BinaryOp.BIT_SHR
                tokenType.size == 4 &&
                        tokenType[0] == Token.Type.S_CLOSE_ANGLE &&
                        tokenType[1] == Token.Type.S_CLOSE_ANGLE &&
                        tokenType[2] == Token.Type.S_CLOSE_ANGLE &&
                        tokenType[3] == Token.Type.OP_ASSIGN -> BinaryOp.BIT_USHR
                tokenType.size == 1                          -> when(tokenType[0])
                {
                    Token.Type.OP_ASSIGN_MUL     -> BinaryOp.MUL
                    Token.Type.OP_ASSIGN_DIV     -> BinaryOp.DIV
                    Token.Type.OP_ASSIGN_ADD     -> BinaryOp.ADD
                    Token.Type.OP_ASSIGN_SUB     -> BinaryOp.SUB
                    Token.Type.OP_ASSIGN_MOD     -> BinaryOp.MOD
                    Token.Type.OP_ASSIGN_BIT_XOR -> BinaryOp.BIT_XOR
                    Token.Type.OP_ASSIGN_BIT_SHL -> BinaryOp.BIT_SHL
                    Token.Type.OP_ASSIGN_BIT_OR  -> BinaryOp.CONJUNCTIVE_OR
                    Token.Type.OP_ASSIGN_BIT_AND -> BinaryOp.CONJUNCTIVE_AND
                    else                         -> Diagnostics.panic(
                        "[[ BUG! ]]: $tokenType is not a valid compound assignment operator"
                    )
                }
                else                                         -> Diagnostics.panic(
                    "Kira",
                    "Could not find a valid compound assignment operator of the pattern $tokenType",
                    context = context
                )
            }
        }
    }
}