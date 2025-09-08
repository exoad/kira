package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.core.BuiltinIntrinsics
import net.exoad.kira.source.SourceLocation

open class IntrinsicExpr(
    val intrinsicKey: BuiltinIntrinsics,
    val sourceLocation: SourceLocation,
    val parameters: List<Expr>
) : Identifier(intrinsicKey.rep), Expr {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitIntrinsicCallExpr(this)
    }

    override fun toString(): String {
        return "MAGIC[[ $intrinsicKey @ $sourceLocation ]]{ $parameters }"
    }
}