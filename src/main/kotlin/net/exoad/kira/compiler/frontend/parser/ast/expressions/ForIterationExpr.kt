package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.core.CompilerIntrinsic

/**
 * The head of a `for` loop: `for i: T in a..b { }` or `for x: T in xs { }`, [declaredType] = T,
 * and a range target is exclusive (design D37).
 */
open class ForIterationExpr(
    val initializer: Identifier,
    val target: Expr,
    override val attachedIntrinsics: List<CompilerIntrinsic>,
    val declaredType: Type? = null,
) : Expr(attachedIntrinsics) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitForIterationExpr(this)
    }

    override fun toString(): String {
        return "ForIter{ $initializer${if (declaredType != null) ": $declaredType" else ""} in $target }"
    }
}
