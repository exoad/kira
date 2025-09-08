package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type

open class FunctionDeclParameterExpr(
    val name: Identifier,
    val typeSpecifier: Type,
    val modifiers: List<Modifier> = emptyList(),
) : Expr {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitFunctionParameterExpr(this)
    }

    override fun toString(): String {
        return "FxParam${if (modifiers.isNotEmpty()) "[[ $modifiers ]]" else ""}{ $name -> $typeSpecifier }"
    }
}