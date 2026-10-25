package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.core.CompilerIntrinsic

/**
 * The head of a `for` loop.
 *
 * - legacy `for mut i: a..b { }`: [isLegacy] = true, [declaredType] = null,
 *   and a range target is inclusive (design 2.2);
 * - spec `for i: T in a..b { }` / `for x: T in xs { }`: [isLegacy] = false,
 *   [declaredType] = T, and a range target is exclusive (design D37).
 */
open class ForIterationExpr(
    val initializer: Identifier,
    val target: Expr,
    override val attachedIntrinsics: List<CompilerIntrinsic>,
    val declaredType: Type? = null,
    val isLegacy: Boolean = true,
) : Expr(attachedIntrinsics) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitForIterationExpr(this)
    }

    override fun toString(): String {
        return "ForIter{ $initializer${if (declaredType != null) ": $declaredType" else ""} ${if (isLegacy) "->" else "in"} $target }"
    }
}
