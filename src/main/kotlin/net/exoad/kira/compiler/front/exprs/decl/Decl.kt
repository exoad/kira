package net.exoad.kira.compiler.front.exprs.decl

import net.exoad.kira.compiler.front.AbsoluteFileLocation
import net.exoad.kira.compiler.front.exprs.Expr
import net.exoad.kira.compiler.front.elements.Identifier

abstract class Decl(open val name: Expr) : Expr()