package net.exoad.kira.compiler.analysis.types

import java.util.IdentityHashMap

/**
 * Stage 4's rules on types, after phase B knows every parent, field and override:
 *
 * - `types.mutable.restate`: a subtype of a Mutable type is Mutable and says so (`C: Mutable<P>`).
 * - `types.mutable.field`: a Mutable field (`x: Mutable<T>`) makes its type's values change.
 * - `types.mutable.contagion`: so does a field holding a Mutable value (a List, a Mutable type),
 *   so a type that is not Mutable is deeply immutable.
 *
 */
internal class MutableTypes(private val program: TypedProgram, private val classes: List<ClassSymbol>, private val traits: List<TraitSymbol>) {
    private val facts = TypeFacts(program.builtins)

    fun run() {
        classes.filter { user(it) }.forEach { check(it) }
        traits.filter { !it.module.isStdlib }.forEach { t ->
            t.parents.firstOrNull { it.sym.isMutable() }?.takeIf { !t.isMutableType }?.let { p -> restate(spelled(t.name), t.decl, p, "Mutable") }
        }
    }

    private fun user(c: ClassSymbol): Boolean = !c.module.isStdlib && (c.kind == ClassKind.CLASS || c.kind == ClassKind.STRUCT)

    private fun Any.isMutable(): Boolean = (this as? TypeSymbol)?.isMutable == true

    private fun check(c: ClassSymbol) {
        if (c.isMutableType) return
        val parent = c.superclass?.takeIf { it.sym.isMutable() } ?: c.traits.firstOrNull { it.sym.isMutable() }
        // A nested type is named as the program does (Outer.Inner) and declared by its own name.
        val name = spelled(c.name)
        if (parent != null) {
            restate(name, c.decl, parent, if (c.kind == ClassKind.STRUCT) "Struct" else "Mutable")
            return
        }
        val fix = if (c.kind == ClassKind.STRUCT) {
            "a value type (Struct) never changes: make it '${declared(name)}: Mutable<Any>', held by reference"
        } else {
            "declare '${declared(name)}: Mutable<${c.superclass?.display() ?: "Any"}>'"
        }
        for (f in c.fields) {
            when {
                f.isMut -> program.report(
                    "types.mutable.field",
                    "'$name' has the Mutable field '${f.name}', so its values change: $fix.",
                    f.decl ?: c.decl,
                )
                facts.carriesMutable(f.type) -> program.report(
                    "types.mutable.contagion",
                    "'$name' holds '${f.name}', ${article(f.type.display())} whose value changes, so its own values change with it: $fix.",
                    f.decl ?: c.decl,
                )
                else -> continue
            }
            return
        }
    }

    private fun restate(name: String, decl: net.exoad.kira.compiler.frontend.parser.ast.ASTNode?, parent: KType.Nominal, kind: String) {
        val fix = if (kind == "Struct") "'${declared(name)}: Mutable<Any>', held by reference" else "'${declared(name)}: Mutable<${parent.display()}>'"
        program.report(
            "types.mutable.restate",
            "'$name' is a subtype of the Mutable ${parent.display()}, so its values change too, and it says so: $fix.",
            decl,
        )
    }

    /** The name a type's declaration writes: a nested one's own (`Inner` of `Outer.Inner`). */
    private fun declared(name: String): String = name.substringAfterLast('.')

    private fun article(name: String): String = (if (name.first().lowercaseChar() in "aeiou") "an " else "a ") + name
}

/**
 * Stage 4, after phase C: which functions of a Mutable type change their receiver, read from their bodies now that no
 * `mut fx` says it (the rule passes and C++'s `const` ask): one that writes its own state, through `this` and no
 * reference on the way, or calls one that does. Each override family agrees (C++ `const` is part of the signature).
 */
internal class ChangingFunctions(private val program: TypedProgram) {
    private val model = program.model

    fun run() {
        val classes = program.modules.flatMap { it.declarations }.filterIsInstance<ClassSymbol>()
        val traits = program.modules.flatMap { it.declarations }.filterIsInstance<TraitSymbol>()
        val owned = (classes.filter { it.isMutableType && !it.module.isStdlib }.flatMap { it.methods } +
            traits.filter { !it.module.isStdlib }.flatMap { it.methods }).filter { it.body != null }
        var grew = true
        while (grew) {
            grew = false
            for (f in owned) {
                if (!f.isMutMethod && writesThis(f)) {
                    f.isMutMethod = true
                    grew = true
                }
            }
            if (unify(classes.flatMap { it.methods } + traits.flatMap { it.methods })) grew = true
        }
    }

    private fun writesThis(f: FnSymbol): Boolean {
        var hit = false
        f.body?.forEach { s ->
            AstTree.walk(s) { n ->
                if (hit) return@walk
                hit = when (n) {
                    is net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr -> own(model.places[n.target])
                    is net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr -> own(model.places[n.left])
                    is net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr -> own(model.places[n.target])
                    is net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr -> {
                        val rc = model.calls[n]
                        rc != null && (
                            (rc.fn?.isMutMethod == true && (rc.implicitThis || rc.receiver?.let { own(model.readPlace(it)) } == true)) ||
                                rc.args.any { a -> a is ArgBinding.Given && a.byRef && own(model.readPlace(a.expr)) } ||
                                (rc.fn?.name in LENDERS && (model.types[n] as? KType.Nominal)?.sym?.name == "MutView" && rc.receiver?.let { own(model.readPlace(it)) } == true)
                            )
                    }
                    else -> false
                }
            }
        }
        return hit
    }

    /** A place of the receiver's own state: from `this`, through no reference after its own field. */
    private fun own(p: Place?): Boolean {
        if (p == null || p.root() !is Place.This) return false
        return p.path().drop(1).none { s ->
            when (s) {
                is PathStep.FieldStep -> s.sym.owner is TraitSymbol || (s.sym.owner as? ClassSymbol)?.kind.let { it == ClassKind.CLASS || it == ClassKind.OPAQUE }
                is PathStep.IndexStep -> s.kind == IndexKind.VIEW || s.kind == IndexKind.MUT_VIEW
            }
        }
    }

    /** Each family of functions joined by [FnSymbol.overrides] changes the receiver when one of them does; true when one grew. */
    private fun unify(all: List<FnSymbol>): Boolean {
        val parent = IdentityHashMap<FnSymbol, FnSymbol>()
        fun root(f: FnSymbol): FnSymbol {
            var r = f
            while (true) {
                val p = parent[r] ?: return r
                r = p
            }
        }
        for (f in all) {
            val base = f.overrides ?: continue
            val a = root(f)
            val b = root(base)
            if (a !== b) parent[a] = b
        }
        val changes = IdentityHashMap<FnSymbol, Boolean>()
        for (f in all) if (f.isMutMethod) changes[root(f)] = true
        var grew = false
        for (f in all) if (changes[root(f)] == true && !f.module.isStdlib && !f.isMutMethod) {
            f.isMutMethod = true
            grew = true
        }
        return grew
    }

    private companion object {
        val LENDERS = setOf("from", "slice", "view")
    }
}

/**
 * Stage 4, after phase C: a parameter of a Mutable type a target holds by value (a List, a Map, a Set, a StrBuf, a Stack,
 * a Queue, a Deque: C++'s are values) that its function or lambda changes in place is passed by reference, so
 * its changes reach the caller as sharing has them; each call's argument is marked so (ArgBinding.Given.byRef).
 */
internal class WrittenParams(private val program: TypedProgram) {
    private val model = program.model
    private val facts = TypeFacts(program.builtins)

    fun run() {
        val bodies = IdentityHashMap<ParamSymbol, List<net.exoad.kira.compiler.frontend.parser.ast.statements.Statement>>()
        for (m in program.modules) {
            if (m.isStdlib) continue
            for (s in m.declarations) {
                val fns = when (s) {
                    is FnSymbol -> listOf(s)
                    is ClassSymbol -> s.methods
                    is TraitSymbol -> s.methods
                    else -> emptyList()
                }
                for (f in fns) {
                    val body = f.body ?: continue
                    f.params.filter { valueHeld(it.type) }.forEach { bodies[it] = body }
                    body.forEach { st -> AstTree.walk(st) { n -> if (n is net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr) lambda(n, bodies) } }
                }
            }
        }
        val written: MutableSet<ParamSymbol> = java.util.Collections.newSetFromMap(IdentityHashMap())
        var grew = true
        while (grew) {
            grew = false
            for ((p, body) in bodies) {
                if (p.byRef || p in written) continue
                if (writes(p, body, written)) {
                    written += p
                    grew = true
                }
            }
        }
        if (written.isEmpty()) return
        written.forEach { it.byRef = true }
        for ((e, rc) in model.calls.entries.toList()) {
            val fn = rc.fn ?: continue
            if (fn.params.none { it in written }) continue
            val args = rc.args.mapIndexed { i, a -> if (a is ArgBinding.Given && !a.byRef && fn.params.getOrNull(i) in written) a.copy(byRef = true) else a }
            model.calls[e] = rc.copy(args = args)
        }
    }

    private fun lambda(l: net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr, bodies: IdentityHashMap<ParamSymbol, List<net.exoad.kira.compiler.frontend.parser.ast.statements.Statement>>) {
        val body = l.def.body ?: return
        for (pd in l.def.parameters) {
            val p = model.declSyms[pd] as? ParamSymbol ?: continue
            if (valueHeld(p.type)) bodies[p] = body
        }
    }

    /** A Mutable type a target holds by value: a stdlib collection or StrBuf. */
    private fun valueHeld(t: KType): Boolean {
        val s = (t as? KType.Nominal)?.sym as? ClassSymbol ?: return false
        return s.kind == ClassKind.MAGIC && s.isMutableType && s.name in VALUE_HELD
    }

    /** Whether [body] changes [p] in place: written, a mutator called on it or its element, lent as a MutView, or passed on to a parameter that is. */
    private fun writes(p: ParamSymbol, body: List<net.exoad.kira.compiler.frontend.parser.ast.statements.Statement>, written: Set<ParamSymbol>): Boolean {
        fun mine(e: net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr?): Boolean {
            val pl = e?.let { model.readPlace(it) } ?: return false
            val root = pl.root() as? Place.Param ?: return false
            // Through a reference on the way (a class's field, a view), the write is that object's, not the List's.
            return root.sym === p && pl.path().none { s ->
                when (s) {
                    is PathStep.FieldStep -> s.sym.owner is TraitSymbol || (s.sym.owner as? ClassSymbol)?.kind.let { it == ClassKind.CLASS || it == ClassKind.OPAQUE }
                    is PathStep.IndexStep -> s.kind == IndexKind.VIEW || s.kind == IndexKind.MUT_VIEW
                }
            }
        }
        var hit = false
        body.forEach { st ->
            AstTree.walk(st) { n ->
                if (hit) return@walk
                hit = when (n) {
                    is net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr -> mine(n.target) && model.places[n.target] !is Place.Param
                    is net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr -> mine(n.left) && model.places[n.left] !is Place.Param
                    is net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr -> mine(n.target)
                    is net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr -> {
                        val rc = model.calls[n]
                        rc != null && (
                            (rc.fn?.isMutMethod == true && mine(rc.receiver)) ||
                                rc.args.withIndex().any { (i, a) ->
                                    a is ArgBinding.Given && mine(a.expr) && (a.byRef || rc.fn?.params?.getOrNull(i)?.let { it.byRef || it in written } == true)
                                } ||
                                (rc.fn?.name in LENDERS && (model.types[n] as? KType.Nominal)?.sym?.name == "MutView" && mine(rc.receiver))
                            )
                    }
                    else -> false
                }
            }
        }
        return hit
    }

    private companion object {
        val LENDERS = setOf("from", "slice", "view")
        val VALUE_HELD = setOf("List", "Map", "Set", "StrBuf", "Stack", "Queue", "Deque")
    }
}
