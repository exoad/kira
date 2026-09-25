package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type

/**
 * One declared parameter, `[mut] name: Type [= default]`. [defaultValue] is
 * the spec's default parameter (null when absent).
 */
open class FunctionDeclParameterExpr(
    val name: Identifier,
    val typeSpecifier: Type,
    val modifiers: List<Modifier> = emptyList(),
    val defaultValue: Expr? = null,
) : Expr() {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitFunctionParameterExpr(this)
    }

    override fun toString(): String {
        return "FxParam${if (modifiers.isNotEmpty()) "[[ $modifiers ]]" else ""}{ $name -> $typeSpecifier" +
            "${if (defaultValue != null) " = $defaultValue" else ""} }"
    }
}
