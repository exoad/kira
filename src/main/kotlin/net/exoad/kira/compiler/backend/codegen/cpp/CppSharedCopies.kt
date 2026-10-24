package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.CopyFlows
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.typeArgs

/**
 * Stage 4 shares a List, a Map, a Set, a StrBuf and the queues, which C++ holds by value: a user module's copy-then-change
 * of one ([CopyFlows]) is refused on the cpp target; an explicit copy (`xs.view().toList()`) is a new value and lowers.
 */
internal class CppSharedCopies(p: TypedProgram) : CopyFlows(p, p.modules.filter { !it.isStdlib }.map { it.source }.toSet()) {
    override fun turns(t: KType?): Boolean = held(t, HashSet())

    private fun held(t: KType?, seen: MutableSet<KType>): Boolean {
        val n = t as? KType.Nominal ?: return false
        val s = n.sym as? ClassSymbol ?: return false
        if (s.kind != ClassKind.MAGIC || !seen.add(n)) return false
        if (s.isMutableType && s.name in VALUE_HELD) return true
        return (s.name == "Maybe" || s.name == "Result" || s.name.startsWith("Tuple")) && n.typeArgs().any { held(it, seen) }
    }

    override fun says(kind: Kind, what: String): String = when (kind) {
        Kind.COPY -> "$what, one value that C++ holds as two (write the copy, `xs.view().toList()`, or keep one name),"
        Kind.RETURN -> "$what, the caller's to share and C++'s to copy,"
        Kind.CAPTURE -> "$what, which then changes: a C++ lambda holds a copy where stage 4 shares one value,"
        Kind.ELEMENT -> "$what changing an element read by `get`, a copy on C++,"
    }

    private companion object {
        val VALUE_HELD = setOf("List", "Map", "Set", "StrBuf", "Stack", "Queue", "Deque")
    }
}
