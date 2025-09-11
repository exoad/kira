package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.core.Intrinsic
import net.exoad.kira.source.SourceLocation

open class IntrinsicExpr(
    val intrinsicKey: Intrinsic,
    val sourceLocation: SourceLocation,
    val parameters: List<Expr>? // if this is null, it is a marker, else it is a function (even if it is 0)
) : Identifier(intrinsicKey.rep), Expr {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitIntrinsicExpr(this)
    }

    override fun toString(): String {
        return "MAGIC[[ $intrinsicKey @ $sourceLocation ]]{ $parameters }"
    }
}