package net.exoad.kira.compiler.frontend.parser.ast.declarations

import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr

abstract class Decl(open val name: Expr) : Expr