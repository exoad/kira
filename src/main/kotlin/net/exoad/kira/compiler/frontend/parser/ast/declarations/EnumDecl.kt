package net.exoad.kira.compiler.frontend.parser.ast.declarations

import net.exoad.kira.compiler.frontend.parser.ast.KiraASTVisitor
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.EnumMemberExpr

class EnumDecl(
    override val name: Identifier,
    val members: Array<EnumMemberExpr>,
    val modifiers: List<Modifier> = emptyList(),
    /**
     * `enum Name: Int32 { ... }`. Null when declared without one, which
     * means integer members (Int32) whose values may be left implicit.
     */
    val baseType: Type? = null,
) : Decl(name) {
    override fun accept(visitor: KiraASTVisitor) {
        visitor.visitEnumDecl(this)
    }

    /**
     * The base type the members are values of: the declared one, else the
     * kind of the first explicit value (Str, Float64 or Int32).
     */
    fun baseTypeName(): String {
        val declared = (baseType?.identifier as? Identifier)?.value
        return declared ?: inferBaseTypeName(members.toList())
    }

    companion object {
        /** Base type implied by the first explicit member value; Int32 when there is none. */
        fun inferBaseTypeName(members: List<EnumMemberExpr>): String {
            return when (members.firstOrNull { it.value != null }?.value?.value) {
                is String -> "Str"
                is Double -> "Float64"
                else -> "Int32"
            }
        }
    }

    /**
     * Every member's value, explicit or implied, in declaration order. An
     * integer member without a value is the previous value plus one (the
     * first is 0), exactly as C numbers its enumerators. Str and Float
     * bases require explicit values -- the parser enforces that -- so they
     * never take the implied path.
     */
    fun memberValues(): List<Any> {
        var next = 0L
        return members.map { member ->
            val value: Any = member.value?.value ?: next
            if (value is Long) {
                next = value + 1
            }
            value
        }
    }

    override fun toString(): String {
        val base = baseType?.let { ", baseType=$it" } ?: ""
        return "EnumDecl(name=$name$base, modifiers=${modifiers.ifEmpty { "[]" }}, members=${members.toList()})"
    }
}
