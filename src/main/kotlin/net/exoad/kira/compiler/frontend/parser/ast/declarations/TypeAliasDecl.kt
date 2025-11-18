package net.exoad.kira.compiler.frontend.parser.ast.declarations

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type

class TypeAliasDecl(val modifiers: List<Modifier>, val alias: Type, val target: Type) :
    Decl(alias.identifier as Identifier) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitTypeAliasDecl(this)
    }

    override fun toString(): String {
        return "TypeAliasDecl(alias=$alias, target=$target, modifiers=${modifiers.ifEmpty { "[]" }})"
    }
}