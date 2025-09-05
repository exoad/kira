package net.exoad.kira.compiler.frontend.parser.ast.elements

class VariadicTypeParameter(override val identifier: Identifier, override val constraint: Type?) :
    Type(identifier, constraint, emptyList())