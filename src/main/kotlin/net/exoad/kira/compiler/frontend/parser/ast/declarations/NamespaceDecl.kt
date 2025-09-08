package net.exoad.kira.compiler.frontend.parser.ast.declarations

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.utils.ObsoleteLanguageFeat

// singleton namespace like thing. not as similar to kotlin's perception of object which has support for inheritance
// a static container you can think of. one thing is that you can supply both classes within it as well! (can be other objects)
//
// additionally they can serve as compound types
@ObsoleteLanguageFeat
open class NamespaceDecl(
    override val name: Identifier,
    val modifiers: List<Modifier> = emptyList(),
    val members: List<Decl> = emptyList(),
) : Decl(name) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitNamespaceDecl(this)
    }

    override fun toString(): String {
        return "__NSP__${modifiers.ifEmpty { "" }}{ $name -> $members }"
    }
}