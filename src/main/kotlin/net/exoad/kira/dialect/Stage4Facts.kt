package net.exoad.kira.dialect

import net.exoad.kira.compiler.analysis.types.ClassKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.PathStep
import net.exoad.kira.compiler.analysis.types.TraitSymbol
import net.exoad.kira.compiler.analysis.types.TypeSymbol
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.typeArgs
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr

/** A type by its module and name: the same declaration in every unit that reads its file, old text or new. */
data class TypeKey(val module: String, val name: String) {
    override fun toString(): String = "$name ($module)"
}

/** A field of a type, by name. */
data class FieldKey(val type: TypeKey, val field: String)

/**
 * What stage 4 decides about each type, from every group's old program typed with the old rules: a type is
 * Mutable when it says so already, has a `mut` field or a `mut fx`, is a value type (Struct) whose field some
 * code writes, holds a field whose value changes (contagion), or lists a Mutable parent (restated).
 */
class Stage4Facts {
    class Info(val key: TypeKey, val struct: Boolean, val magic: Boolean, val trait: Boolean) {
        var declared = false
        var mutField = false
        var mutMethod = false
        val parents = LinkedHashSet<TypeKey>()

        /** Each field, by name, and the types a value of it is or holds (through a Maybe, a Result, a tuple). */
        val fields = LinkedHashMap<String, Set<TypeKey>>()
    }

    val types = LinkedHashMap<TypeKey, Info>()

    /** A value type's fields some code assigns, or lends a MutView of: Mutable fields once the type is Mutable. */
    val writtenFields = LinkedHashSet<FieldKey>()

    private val decided = HashMap<TypeKey, String>()

    /** The stdlib's handles whose state changes with no `mut` of their own: a lock's value, a thread's flag. */
    private val handles = setOf(TypeKey("kira:sync", "Mutex"), TypeKey("kira:sync", "Thread"))

    /** The stdlib's fixed-size values: an Arr is C++'s std::array, which a value type may hold; its `set` needs a Mutable binding. */
    private val values = setOf(TypeKey("kira:collections", "Arr"))

    fun key(s: TypeSymbol): TypeKey = TypeKey(s.module.uri, s.name)

    fun key(t: KType?): TypeKey? = ((t as? KType.Nominal)?.sym as? TypeSymbol)?.let { key(it) }

    /** Reads [p]'s types and the fields its bodies write. */
    fun read(p: TypedProgram) {
        for (m in p.modules) {
            for (s in m.declarations) {
                when (s) {
                    is ClassSymbol -> addClass(s)
                    is TraitSymbol -> addTrait(s)
                    else -> {}
                }
            }
        }
        initiallyWrites.clear()
        for (m in p.modules) for (s in m.declarations) (s as? ClassSymbol)?.initially?.forEach { st ->
            net.exoad.kira.compiler.analysis.types.AstTree.walk(st) { n -> initiallyWrites += n }
        }
        for (b in bodies(p)) {
            net.exoad.kira.compiler.analysis.types.AstTree.walk(b) { n -> writes(p, n) }
        }
    }

    /** The nodes inside `initially` blocks, which write the value they build. */
    private val initiallyWrites: MutableSet<ASTNode> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())

    private fun addClass(c: ClassSymbol) {
        val k = key(c)
        val info = types.getOrPut(k) { Info(k, c.kind == ClassKind.STRUCT, c.kind == ClassKind.MAGIC, false) }
        info.declared = info.declared || c.isMutableType
        info.mutField = info.mutField || c.fields.any { it.isMut }
        info.mutMethod = info.mutMethod || c.methods.any { m -> m.decl?.let { Modifier.MUTABLE in it.modifiers } == true }
        c.superclass?.let { key(it)?.let { pk -> info.parents += pk } }
        c.traits.forEach { t -> key(t)?.let { info.parents += it } }
        for (f in c.fields) info.fields[f.name] = carriers(f.type)
    }

    private fun addTrait(t: TraitSymbol) {
        val k = key(t)
        val info = types.getOrPut(k) { Info(k, false, false, true) }
        info.declared = info.declared || t.isMutableType
        info.mutMethod = info.mutMethod || t.methods.any { m -> m.decl?.let { Modifier.MUTABLE in it.modifiers } == true }
        t.parents.forEach { pt -> key(pt)?.let { info.parents += it } }
    }

    /** The types a value of [t] is, or holds through a Maybe, a Result or a tuple. */
    private fun carriers(t: KType?, seen: MutableSet<KType> = HashSet()): Set<TypeKey> {
        val n = t as? KType.Nominal ?: return emptySet()
        if (!seen.add(n)) return emptySet()
        val out = LinkedHashSet<TypeKey>()
        key(n)?.let { out += it }
        val name = (n.sym as? ClassSymbol)?.takeIf { it.kind == ClassKind.MAGIC }?.name
        if (name == "Maybe" || name == "Result" || (name != null && name.startsWith("Tuple"))) {
            n.typeArgs().forEach { out += carriers(it, seen) }
        }
        return out
    }

    /** The value-type fields [n] writes: an assignment's, or a MutView lent from one. */
    private fun writes(p: TypedProgram, n: ASTNode) {
        val target = when (n) {
            is AssignmentExpr -> n.target
            is CompoundAssignmentExpr -> n.left
            is PlaceAssignmentExpr -> n.target
            is FunctionCallExpr -> {
                val rc = p.model.calls[n]
                if (rc?.fn?.name in setOf("from", "slice", "view") && p.model.types[n]?.let { (it as? KType.Nominal)?.sym?.name } == "MutView") rc?.receiver else null
            }
            else -> null
        } ?: return
        val place = p.model.places[target] ?: return
        // A `with`'s copy is a new value; `initially` still builds its own (both write what nothing else holds yet).
        if ((place.root() as? net.exoad.kira.compiler.analysis.types.Place.Local)?.sym?.builds == true) return
        if (place.root() is net.exoad.kira.compiler.analysis.types.Place.This && n in initiallyWrites) return
        place.path().forEach { step ->
            val f = (step as? PathStep.FieldStep)?.sym ?: return@forEach
            if ((f.owner as? ClassSymbol)?.kind == ClassKind.STRUCT) writtenFields += FieldKey(key(f.owner), f.name)
        }
    }

    /** Whether [k] is Mutable once every type is decided. */
    fun mutable(k: TypeKey?): Boolean = k != null && decided[k] != null

    /** Why [k] is Mutable, for the report; null when it is not. */
    fun why(k: TypeKey): String? = decided[k]

    /** Decides every type: the reasons, then contagion and restating to a fixed point. */
    fun decide() {
        decided.clear()
        for ((k, i) in types) {
            if (k in values && !i.declared) continue
            val why = when {
                i.declared -> "declared"
                k in handles -> "a handle whose state changes"
                i.mutField -> "a mut field"
                i.mutMethod -> "a mut fx"
                // A stdlib value type stays one: code that writes its fields is a hand fix, never a change to the stdlib.
                i.struct && !k.module.startsWith("kira:") && i.fields.keys.any { FieldKey(k, it) in writtenFields } -> "a field written"
                else -> null
            }
            if (why != null) decided[k] = why
        }
        var grew = true
        while (grew) {
            grew = false
            for ((k, i) in types) {
                if (k in decided || k.module.startsWith("kira:")) continue
                val parent = i.parents.firstOrNull { it in decided }
                val field = i.fields.entries.firstOrNull { (_, ks) -> ks.any { it in decided } }
                val why = when {
                    parent != null -> "a Mutable parent ${parent.name}"
                    field != null -> "the field ${field.key}, which changes"
                    else -> null
                }
                if (why != null) {
                    decided[k] = why
                    grew = true
                }
            }
        }
    }

    /** A type becoming Mutable that was a value type: its copies were copies, and are now shared. */
    fun valueTypeTurned(k: TypeKey?): Boolean = k != null && types[k]?.struct == true && mutable(k)

    /** Whether a field of a value type gets `Mutable<>`: its type is Mutable and some code writes it. */
    fun mutableField(owner: TypeKey, field: String): Boolean = valueTypeTurned(owner) && FieldKey(owner, field) in writtenFields

    companion object {
        /** Every body of [p] a write can be in: functions, methods, initially blocks, field defaults, globals. */
        fun bodies(p: TypedProgram): List<ASTNode> {
            val out = mutableListOf<ASTNode>()
            for (m in p.modules) {
                for (s in m.declarations) {
                    when (s) {
                        is net.exoad.kira.compiler.analysis.types.FnSymbol -> s.decl?.let { out += it }
                        is ClassSymbol -> {
                            s.methods.forEach { f -> f.decl?.let { out += it } }
                            s.initially?.let { out.addAll(it) }
                            s.fields.forEach { f -> f.default?.let { out += it } }
                        }
                        is TraitSymbol -> s.methods.forEach { f -> f.decl?.let { out += it } }
                        is net.exoad.kira.compiler.analysis.types.GlobalSymbol -> s.init?.let { out += it }
                        else -> {}
                    }
                }
            }
            return out
        }
    }
}
