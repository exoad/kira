package net.exoad.kira.compiler.frontend.parser.ast.elements

import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr

class VariadicTypeParameter(override val identifier: Expr, override val constraint: Type?) :
    Type(identifier, constraint, emptyList())