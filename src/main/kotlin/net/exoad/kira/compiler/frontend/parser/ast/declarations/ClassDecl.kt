package net.exoad.kira.compiler.frontend.parser.ast.declarations

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement

/**
 * TODO: Only the root object type `Any` will have [parent] marked as null
 *
 * [initially] and [finally] are the class's initializer and finalizer blocks
 * (null when absent). They default to null so the C, JS and XML readers see
 * the node exactly as before.
 */
open class ClassDecl(
    override val name: Type,
    val modifiers: List<Modifier> = emptyList(),
    val members: List<FirstClassDecl> = emptyList(),
    val parents: List<Type> = emptyList(),
    val initially: List<Statement>? = null,
    val finally: List<Statement>? = null,
) : Decl(name) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitClassDecl(this)
    }

    override fun toString(): String {
        return "Class(name=$name, mods=${modifiers.ifEmpty { "[]" }}, members=$members, parents=${parents.ifEmpty { "[]" }}" +
            "${if (initially != null) ", initially=${initially.size} stmts" else ""}" +
            "${if (finally != null) ", finally=${finally.size} stmts" else ""})"
    }
}
