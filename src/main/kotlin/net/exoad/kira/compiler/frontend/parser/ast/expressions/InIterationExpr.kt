package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.core.CompilerIntrinsic

/**
 * The head of an iteration loop: `while i: T in a..b { }` or `while x: T in xs { }`, [declaredType] = T;
 * a range target holds both its ends.
 */
open class InIterationExpr(
    val initializer: Identifier,
    val target: Expr,
    override val attachedIntrinsics: List<CompilerIntrinsic>,
    val declaredType: Type? = null,
) : Expr(attachedIntrinsics) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitInIterationExpr(this)
    }

    override fun toString(): String {
        return "InIter{ $initializer${if (declaredType != null) ": $declaredType" else ""} in $target }"
    }
}
