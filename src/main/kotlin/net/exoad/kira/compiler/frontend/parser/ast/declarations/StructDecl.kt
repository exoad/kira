package net.exoad.kira.compiler.frontend.parser.ast.declarations

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement

/**
 * A value type (design D1): `struct Name: Trait, ... { members; initially { } }`.
 *
 * Deliberately not a [ClassDecl] subclass: a backend without a struct lowering
 * must reach the throwing `visitStructDecl` default, never lower it as a class.
 * A struct has no `finally` block; the parser refuses one.
 */
class StructDecl(
    override val name: Type,
    val modifiers: List<Modifier> = emptyList(),
    val members: List<FirstClassDecl> = emptyList(),
    val traits: List<Type> = emptyList(),
    val initially: List<Statement>? = null,
) : Decl(name) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitStructDecl(this)
    }

    override fun toString(): String {
        return "Struct(name=$name, mods=${modifiers.ifEmpty { "[]" }}, members=$members, traits=${traits.ifEmpty { "[]" }}" +
            "${if (initially != null) ", initially=${initially.size} stmts" else ""})"
    }
}
