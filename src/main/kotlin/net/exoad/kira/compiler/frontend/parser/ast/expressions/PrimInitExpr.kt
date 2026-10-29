package net.exoad.kira.compiler.frontend.parser.ast.expressions

import net.exoad.kira.compiler.frontend.parser.ast.elements.Type

/**
 * `Int32 { 0 }`, `Str { "hi" }`: a primitive's block holds exactly one value, which is that value typed as the
 * primitive (what `x: Int32 = 0` types). Only the parser makes one, for a primitive's name with one positional value;
 * if the name turns out to be a user's type, the typer builds it as any [ObjectInitExpr].
 */
class PrimInitExpr(typeName: Type, val value: Expr) : ObjectInitExpr(typeName, listOf(value)) {
    override fun toString(): String = "PrimInit{ $typeName -> $value }"
}
