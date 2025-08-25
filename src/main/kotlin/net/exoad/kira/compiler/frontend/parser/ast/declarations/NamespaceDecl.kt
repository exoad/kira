package net.exoad.kira.compiler.frontend.parser.ast.declarations

import net.exoad.kira.compiler.frontend.parser.ast.ASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifiers

// singleton namespace like thing. not as similar to kotlin's perception of object which has support for inheritance
// a static container you can think of. one thing is that you can supply both classes within it as well! (can be other objects)
//
// additionally they can serve as compound types
open class NamespaceDecl(
    override val name: Identifier,
    val modifiers: List<Modifiers> = emptyList(),
    val members: List<Decl> = emptyList(),
) : Decl(name)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitNamespaceDecl(this)
    }

    override fun toString(): String
    {
        return "ObjectDecl[[ $modifiers ]]{ $name -> $members }"
    }
}