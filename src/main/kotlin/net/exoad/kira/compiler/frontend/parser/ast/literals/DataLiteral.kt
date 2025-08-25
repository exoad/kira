package net.exoad.kira.compiler.frontend.parser.ast.literals

abstract class DataLiteral<T>(open val value: T) : Literal()