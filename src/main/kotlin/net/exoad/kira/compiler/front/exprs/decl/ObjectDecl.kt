package net.exoad.kira.compiler.front.exprs.decl

import net.exoad.kira.compiler.front.ASTVisitor
import net.exoad.kira.compiler.front.AbsoluteFileLocation
import net.exoad.kira.compiler.front.elements.Identifier
import net.exoad.kira.compiler.front.elements.Modifiers

// singleton namespace like thing. not as similar to kotlin's perception of object which has support for inheritance
// a static container you can think of. one thing is that you can supply both classes within it as well! (can be other objects)
//
// additionally they can serve as compound types
open class ObjectDecl(
    override val name: Identifier,
    val modifiers: List<Modifiers> = emptyList(),
    val members: List<Decl> = emptyList(),
) : Decl(name)
{
    override fun accept(visitor: ASTVisitor)
    {
        visitor.visitObjectDecl(this)
    }

    override fun toString(): String
    {
        return "ObjectDecl[[ $modifiers ]]{ $name -> $members }"
    }
}