package net.exoad.kira.dialect

import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.CopyFlows
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.KiraUnparser
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.typeArgs
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.WithExpr
import net.exoad.kira.source.SourceContext

/**
 * Stage 4's hand checks in the old program: each copy-then-change ([CopyFlows]) of what was copied and is now shared, a
 * `with` that copied and now edits in place, and `==` or `contains` that compared fields and now compares identity.
 */
class Stage4Copies(p: TypedProgram, private val facts: Stage4Facts, reportedIn: Set<SourceContext>) : CopyFlows(p, reportedIn) {
    /** A value whose copies were copies and are now shared: a stdlib collection, a value type that becomes Mutable, or one holding either. */
    override fun turns(t: KType?): Boolean = turns(t, HashSet())

    private fun turns(t: KType?, seen: MutableSet<KType>): Boolean {
        val n = t as? KType.Nominal ?: return false
        if (!seen.add(n)) return false
        val k = facts.key(n)
        if (k != null && (k in COLLECTIONS || facts.valueTypeTurned(k))) return true
        val name = (n.sym as? ClassSymbol)?.takeIf { it.kind == ClassKind.MAGIC }?.name ?: return false
        return (name == "Maybe" || name == "Result" || name.startsWith("Tuple")) && n.typeArgs().any { turns(it, seen) }
    }

    override fun says(kind: Kind, what: String): String = when (kind) {
        Kind.COPY -> "$what: it is shared now"
        Kind.RETURN -> "$what: the caller held a copy and now shares it"
        Kind.CAPTURE -> "$what: it held a copy, and now shares it with what changes it"
        Kind.ELEMENT -> "$what changed a copy of the element, and now changes the element itself"
    }

    override fun checks(n: ASTNode) {
        when (n) {
            is WithExpr -> {
                val k = facts.key(type(n.receiver))
                if (copiedOnce(type(n.receiver)) && facts.mutable(k)) {
                    list(n, "a `with` on ${k!!.name}: it copied the value and now edits it in place, since ${k.name} is Mutable")
                }
            }
            is BinaryExpr -> if (n.operator == BinaryOp.EQUALS || n.operator == BinaryOp.NOT_EQUAL) {
                turnedHeld(type(n.leftExpr))?.let { k -> list(n, "'${KiraUnparser.text(n)}' compared ${k.name}'s fields and now compares identity, since ${k.name} is Mutable") }
            }
            // `xs.contains(v)` of a value type that becomes Mutable compared fields, and now identity.
            is FunctionCallExpr -> {
                val rc = p.model.calls[n] ?: return
                if ((rc.fn?.owner as? ClassSymbol)?.kind != ClassKind.MAGIC || rc.fn?.name !in COMPARES) return
                rc.receiver?.let { r -> turnedHeld(type(r)) }?.let { k ->
                    list(n, "'${KiraUnparser.text(n)}' compared ${k.name}'s fields and now compares identity, since ${k.name} is Mutable")
                }
            }
            else -> {}
        }
    }

    /** Whether a `with` on [t] copied it before stage 4: a value type, or a class with no `mut` field in its chain. */
    private fun copiedOnce(t: KType?): Boolean {
        val cls = (t as? KType.Nominal)?.sym as? ClassSymbol ?: return false
        if (cls.kind == ClassKind.STRUCT) return true
        if (cls.kind != ClassKind.CLASS) return false
        return generateSequence(cls) { it.superclass?.sym as? ClassSymbol }.take(64).none { k -> k.fields.any { it.isMut } }
    }

    /** The value type that becomes Mutable which a value of [t] is or holds (an element, a Maybe's, a tuple's), compared by its fields before. */
    private fun turnedHeld(t: KType?, seen: MutableSet<KType> = HashSet()): TypeKey? {
        val n = t as? KType.Nominal ?: return null
        if (!seen.add(n)) return null
        val k = facts.key(n)
        if (k != null && facts.valueTypeTurned(k)) return k
        if ((n.sym as? ClassSymbol)?.kind != ClassKind.MAGIC) return null
        return n.typeArgs().firstNotNullOfOrNull { turnedHeld(it, seen) }
    }

    companion object {
        /** A collection's functions that compare its elements with a value. */
        private val COMPARES = setOf("contains", "containsValue")

        /** The stdlib's collections, copied as values until stage 4. */
        val COLLECTIONS = setOf(
            TypeKey("kira:collections", "List"), TypeKey("kira:collections", "Map"), TypeKey("kira:collections", "Set"),
            TypeKey("kira:collections", "Stack"), TypeKey("kira:collections", "Queue"),
            TypeKey("kira:collections", "Deque"), TypeKey("kira:core", "StrBuf"),
        )
    }
}
