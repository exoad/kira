package net.exoad.kira.compiler.frontend.parser.ast.declarations

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement

/**
 * A type that holds state or is constructed: its [members], its [parents] (a class first, then
 * traits) and its `initially` block ([initially], null when absent).
 */
open class ClassDecl(
    override val name: Type,
    val modifiers: List<Modifier> = emptyList(),
    val members: List<FirstClassDecl> = emptyList(),
    val parents: List<Type> = emptyList(),
    val initially: List<Statement>? = null,
) : Decl(name) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitClassDecl(this)
    }

    override fun toString(): String {
        return "Class(name=$name, mods=${modifiers.ifEmpty { "[]" }}, members=$members, parents=${parents.ifEmpty { "[]" }}" +
            "${if (initially != null) ", initially=${initially.size} stmts" else ""})"
    }
}
