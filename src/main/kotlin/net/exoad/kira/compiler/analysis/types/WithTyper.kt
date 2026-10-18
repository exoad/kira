package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.Decl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ArrayIndexExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallNamedParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IfExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThisExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThrowExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TypeCastExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.UnaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.WithExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.CharLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.FloatLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.IntegerLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.NullLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.statements.ElseBranchStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ElseIfBranchStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ForIterationStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.IfSelectionStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ReturnStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.compiler.frontend.parser.ast.statements.WhileIterationStatement
import java.util.IdentityHashMap

/** One `with` block being typed: its receiver's [members] are reachable bare inside it. */
internal class WithFrame(
    val expr: WithExpr,
    val type: KType.Nominal,
    val temp: LocalSymbol,
    val self: Identifier,
    val members: Set<String>,
    /** The lambda the `with` is written in, where its temporary lives. */
    val declaredIn: LambdaFrame?,
)

/**
 * `receiver with { ... }`, the builder operator (the owner's rules of 2026-10-15).
 *
 * - The receiver is a user class (or a plain stdlib one); its members are used bare in the
 *   block, after the block's and the function's locals and parameters, before the outer
 *   scope. A local or parameter named like a member is an error where it is declared or read
 *   ([clash], [declares]), and so are nested receivers that share a member name.
 * - A class with a `mut` field is edited in place. A value type, or a class with no `mut`
 *   field, is copied into a temporary whose every field the block may assign; the copy re-runs
 *   `initially` at the block's end. The value is the receiver (the copy), never the block's.
 * - It lowers inline before the statement it is in, so `break` and `continue` act on the
 *   enclosing loop. [placement] refuses a position where running it first would reorder
 *   evaluation (D33); a module constant's `with` folds into the construction it amounts to ([fold]).
 */
internal class WithTyper(private val c: PhaseC) {
    private val model get() = c.model
    private var temps = 0

    /** Every `with` the placement walk found a place for (or folded). */
    private val placed: MutableSet<WithExpr> = java.util.Collections.newSetFromMap(IdentityHashMap())

    fun with(e: WithExpr, hint: KType?, ctx: BodyContext, scope: Scope): KType {
        val t = c.exprs.type(e.receiver, hint, ctx, scope)
        if (t.containsError()) {
            return KType.Error
        }
        val n = t as? KType.Nominal
        val cls = n?.sym as? ClassSymbol
        val why = when {
            n == null || cls == null -> "${t.display()} is not a class"
            cls.kind == ClassKind.MAGIC -> "${t.display()} is a stdlib magic type, whose state is its methods'"
            cls.kind == ClassKind.OPAQUE || cls.foreign != null -> "${t.display()} is made by the host, which owns its fields"
            else -> null
        }
        if (n == null || cls == null || why != null) {
            c.report("types.with.receiver", "`with` builds the value of a class: $why.", e.receiver)
            return KType.Error
        }
        val members = c.members.memberNames(n)
        for (outer in scope.withFrames()) {
            val shared = members.intersect(outer.members)
            if (shared.isNotEmpty()) {
                c.report(
                    "types.with.nested",
                    "This `with` is inside another whose receiver also has ${shared.joinToString(", ") { "'$it'" }}, so a bare name would be ambiguous; " +
                        "build the inner value in a local first.",
                    e,
                )
            }
        }
        val chain = generateSequence(cls) { it.superclass?.sym as? ClassSymbol }.take(64).toList()
        val copy = cls.kind == ClassKind.STRUCT || chain.none { k -> k.fields.any { it.isMut } }
        val temp = LocalSymbol("_k_w${temps++}", ctx.module, e, t, isMut = copy, fn = ctx.fn, builds = copy)
        ctx.lambda?.let { c.declaredIn[temp] = it }
        val self = Identifier(temp.name)
        model.refs[self] = temp
        model.places[self] = Place.Local(temp)
        model.types[self] = t
        val frame = WithFrame(e, n, temp, self, members, ctx.lambda)
        c.stmts.block(e.body, ctx, scope.withBody(frame))
        model.withs[e] = WithInfo(temp, self, cls, copy, reinit = copy && chain.any { it.initially != null })
        if (copy) {
            model.withCopied.add(cls)
        }
        return t
    }

    /** [id] reads the local or parameter [sym]: an error when a `with` around it has a member of that name (true: the member is read). */
    fun clash(id: Identifier, sym: Symbol, scope: Scope): Boolean {
        val frame = scope.withMember(id.value) ?: return false
        c.report(
            "types.with.clash",
            "'${id.value}' is both a ${if (sym is ParamSymbol) "parameter" else "local"} and a member of this `with`'s receiver " +
                "${frame.type.display()}; rename the ${if (sym is ParamSymbol) "parameter" else "local"}.",
            id,
        )
        return true
    }

    /** A local or parameter [name] declared inside a `with` block: an error when the receiver has a member of that name. */
    fun declares(name: String, at: ASTNode, scope: Scope) {
        val frame = scope.withMember(name) ?: return
        c.report(
            "types.with.clash",
            "'$name' names a member of this `with`'s receiver ${frame.type.display()}, so it cannot name a local here; rename it.",
            at,
        )
    }

    // ---- where a `with` can run ---------------------------------------------------------------

    /**
     * Each `with` in [statements] (lambda bodies aside, which are walked as their own) is
     * lowered before its statement: refused where that would run it at another time than Kira
     * does: in a condition, a loop header, a branch, a short-circuit operand, or after
     * something the statement evaluates first that the `with` could change.
     */
    fun placement(statements: List<Statement>) {
        statements.forEach { statement(it) }
    }

    private fun statement(s: Statement) {
        when (s) {
            is ReturnStatement -> value(s.expr)
            is IfSelectionStatement -> {
                header(s.expr, "an if condition")
                placement(s.thenStatements)
                s.elseBranches.forEach { b ->
                    when (b) {
                        is ElseIfBranchStatement -> {
                            header(b.condition, "an else-if condition")
                            placement(b.statements)
                        }
                        is ElseBranchStatement -> placement(b.statements)
                    }
                }
            }
            is WhileIterationStatement -> {
                header(s.condition, "a while condition")
                placement(s.statements)
            }
            is ForIterationStatement -> {
                header(s.forIterationExpr, "a loop header")
                placement(s.body)
            }
            else -> if (s.javaClass == Statement::class.java) expression(s.expr)
        }
    }

    private fun expression(e: Expr) {
        when (e) {
            is VariableDecl -> e.value?.let { value(it) }
            is AssignmentExpr -> value(e.value, target = target(e.target))
            // A compound assignment reads its target first, too (OQ-1).
            is CompoundAssignmentExpr -> value(e.right, mutableListOf(e.left), target = target(e.left))
            is PlaceAssignmentExpr -> value(e.value, if (e.operator != null) mutableListOf(e.target) else mutableListOf(), target = target(e.target))
            is TryExpr -> {
                placement(e.tryBlock)
                placement(e.handlerBlock)
            }
            is Decl -> {}
            else -> value(e)
        }
    }

    /** An assignment's target, which Kira locates before the value runs (D33): what the value's `with`s must not move. */
    private fun target(t: Expr): Expr? {
        firstWith(t)?.let { refuse(it, "an assignment's target") }
        return t.takeIf { it !is Identifier }
    }

    private fun header(e: Expr, what: String) {
        firstWith(e)?.let { refuse(it, what) }
    }

    /**
     * [e], a value its statement evaluates; [before] holds what the statement evaluated before
     * it, and [target] the place it is assigned to, which Kira located first.
     */
    private fun value(e: Expr, before: MutableList<Expr> = mutableListOf(), conditional: String? = null, target: Expr? = null) {
        if (firstWith(e) == null) {
            before.add(e)
            return
        }
        when (e) {
            is WithExpr -> {
                if (conditional != null) {
                    refuse(e, conditional)
                    return
                }
                val written = writes(e)
                if (target != null && !located(target, written)) {
                    refuse(e, "the value of ${KiraUnparser.text(target)}, a place Kira finds first and the `with` could move")
                    return
                }
                before.firstOrNull { !unchanged(it, written) }?.let {
                    refuse(e, "a statement that evaluates ${KiraUnparser.text(it)} first, which the `with` could change")
                    return
                }
                value(e.receiver, before, null, target)
                placed.add(e)
                placement(e.body)
                before.add(e)
            }
            is BinaryExpr -> {
                value(e.leftExpr, before, conditional, target)
                val shortCircuit = e.operator == BinaryOp.AND || e.operator == BinaryOp.OR
                value(e.rightExpr, before, conditional ?: if (shortCircuit) "the right side of ${if (e.operator == BinaryOp.AND) "&&" else "||"}" else null, target)
            }
            is IfExpr -> {
                value(e.condition, before, conditional, target)
                firstWith(e.thenBranch + e.elseBranch)?.let { refuse(it, "a branch of an if-expression") }
            }
            is FunctionCallExpr -> {
                (e.name as? MemberAccessExpr)?.let { value(it.origin, before, conditional, target) } ?: before.add(e.name)
                e.positionalParameters.forEach { value(it.value, before, conditional, target) }
                e.namedParameters.forEach { value(it.value, before, conditional, target) }
            }
            is MemberAccessExpr -> {
                value(e.origin, before, conditional, target)
                (e.member as? FunctionCallExpr)?.let { call ->
                    call.positionalParameters.forEach { value(it.value, before, conditional, target) }
                    call.namedParameters.forEach { value(it.value, before, conditional, target) }
                }
            }
            is ObjectInitExpr -> {
                e.positionalArgs.forEach { value(it, before, conditional, target) }
                e.namedArgs.forEach { value(it.value, before, conditional, target) }
            }
            is ArrayIndexExpr -> {
                value(e.originExpr, before, conditional, target)
                value(e.indexExpr, before, conditional, target)
            }
            is ThrowExpr -> value(e.value, before, conditional, target)
            else -> AstTree.children(e).forEach { k -> if (k is Expr && k !is Type) value(k, before, conditional, target) }
        }
    }

    /** What a `with` may write: locals and parameters by name, and whether it writes through `this`. */
    private class Written(val symbols: Set<Symbol>, val self: Boolean)

    /** Whether [e], evaluated before a `with` that writes [written], reads the same value after it. */
    private fun unchanged(e: Expr, written: Written): Boolean = when (e) {
        is IntegerLiteral, is FloatLiteral, is StringLiteral, is CharLiteral, is NullLiteral, is LambdaExpr, is WithExpr -> true
        is ThisExpr -> !written.self
        is Identifier -> when (val s = model.refs[e]) {
            is LocalSymbol, is ParamSymbol -> s !in written.symbols && !(s is ParamSymbol && s.byRef)
            is GlobalSymbol -> !s.isMut
            is FnSymbol, is ClassSymbol, is TraitSymbol, is ModuleSymbol -> true
            null -> model.consts[e] != null
            else -> false
        }
        is MemberAccessExpr -> model.members[e] is MemberRef.ModuleMember && ((model.members[e] as MemberRef.ModuleMember).symbol as? GlobalSymbol)?.isMut != true
        is BinaryExpr -> model.opCalls[e] == null && unchanged(e.leftExpr, written) && unchanged(e.rightExpr, written)
        is UnaryExpr -> model.opCalls[e] == null && unchanged(e.operand, written)
        is TypeCastExpr -> unchanged(e.value, written)
        else -> model.consts[e] != null
    }

    /** Whether the place [t] (a field or an element) is the same place after a `with` that writes [written]. */
    private fun located(t: Expr, written: Written): Boolean = when (t) {
        is Identifier -> when (val s = model.refs[t]) {
            is LocalSymbol, is ParamSymbol -> s !in written.symbols
            is FieldSymbol -> !written.self
            is GlobalSymbol -> true
            else -> false
        }
        is ThisExpr -> !written.self
        is MemberAccessExpr -> model.members[t] is MemberRef.ModuleMember || located(t.origin, written)
        is ArrayIndexExpr -> located(t.originExpr, written) && unchanged(t.indexExpr, written)
        else -> false
    }

    /** What [w] may write (lambda bodies aside): by assignment, a `mut fx` on it, or a `mut` argument. */
    private fun writes(w: WithExpr): Written {
        val out: MutableSet<Symbol> = java.util.Collections.newSetFromMap(IdentityHashMap())
        var self = false
        fun root(e: Expr?) {
            val p = e?.let { model.places[it] } ?: return
            when (val r = p.root()) {
                is Place.Local -> out.add(r.sym)
                is Place.Param -> out.add(r.sym)
                is Place.This -> self = true
                else -> {}
            }
        }
        fun walk(n: ASTNode) {
            if (n is LambdaExpr) return
            when (n) {
                is AssignmentExpr -> root(n.target)
                is CompoundAssignmentExpr -> root(n.left)
                is PlaceAssignmentExpr -> root(n.target)
                is FunctionCallExpr -> model.calls[n]?.let { rc ->
                    if (rc.implicitThis) self = true
                    if (rc.fn?.isMutMethod == true) root(rc.receiver)
                    rc.args.forEach { a -> if (a is ArgBinding.Given && a.byRef) root(a.expr) }
                }
                else -> {}
            }
            AstTree.children(n).forEach { walk(it) }
        }
        walk(w)
        return Written(out, self)
    }

    /** The first `with` in [nodes], lambda bodies aside. */
    private fun firstWith(nodes: List<ASTNode>): WithExpr? = nodes.firstNotNullOfOrNull { firstWith(it) }

    private fun firstWith(n: ASTNode): WithExpr? {
        if (n is WithExpr) return n
        if (n is LambdaExpr) return null
        return AstTree.children(n).firstNotNullOfOrNull { firstWith(it) }
    }

    private fun refuse(e: WithExpr, why: String) {
        placed.add(e)
        c.report("types.with.position", "This `with` cannot run here, in $why: bind it to a local first.", e)
    }

    /** Every `with` typed that no body placed or folded: in a default, a module-level value or a static assert. */
    fun unplaced() {
        model.withs.keys.filter { it !in placed }.forEach { e ->
            c.report("types.with.position", "This `with` has no statement to run before: build the value in a function, or in a module constant's construction.", e)
        }
    }

    // ---- module constants ----------------------------------------------------------------------

    /**
     * A module constant's `T { ... } with { f = v }` is the construction `T { ..., f = v }`
     * (a D49 constant), each assigned field given by name in place of what the receiver gave
     * it. Null, with an error, when the `with` is no such shape.
     */
    fun fold(e: WithExpr, g: GlobalSymbol): ObjectInitExpr? {
        val receiver = when (val r = e.receiver) {
            is ObjectInitExpr -> r
            is WithExpr -> fold(r, g) ?: return null
            else -> return refuseFold(e, "its receiver is a construction `T { ... }`")
        }
        val t = c.typeOf(receiver.typeName)
        val cls = (t as? KType.Nominal)?.sym as? ClassSymbol ?: return refuseFold(e, "its receiver constructs a class")
        val chain = generateSequence(cls) { it.superclass?.sym as? ClassSymbol }.take(64).toList().reversed()
        val fields = chain.flatMap { it.fields }
        val members = c.members.memberNames(t as KType.Nominal)
        val sets = LinkedHashMap<String, Expr>()
        for (s in e.body) {
            val a = (s.takeIf { it.javaClass == Statement::class.java }?.expr as? AssignmentExpr)
                ?: return refuseFold(e, "its block only assigns the receiver's fields, one `field = value` a line")
            val name = a.target.value
            if (fields.none { it.name == name }) {
                return refuseFold(e, "'$name' is no field of ${t.display()}")
            }
            readsMember(a.value, members)?.let { return refuseFold(e, "'$it' reads the receiver, which a constant's value cannot") }
            sets.remove(name)
            sets[name] = a.value
        }
        val overridesPositional = receiver.positionalArgs.indices.any { it < fields.size && fields[it].name in sets }
        val positional = if (overridesPositional) emptyList() else receiver.positionalArgs
        val named = mutableListOf<FunctionCallNamedParameterExpr>()
        if (overridesPositional) {
            receiver.positionalArgs.forEachIndexed { i, v ->
                if (i < fields.size) named += namedArg(fields[i].name, sets.remove(fields[i].name) ?: v, v)
            }
        }
        receiver.namedArgs.forEach { n -> named += sets.remove(n.name.value)?.let { namedArg(n.name.value, it, n) } ?: n }
        sets.forEach { (name, v) -> named += namedArg(name, v, v) }
        val merged = ObjectInitExpr(receiver.typeName, positional, named)
        g.module.source.astOrigins[receiver]?.let { pos -> g.module.source.astOrigins[merged] = pos }
        placed.add(e)
        return merged
    }

    private fun namedArg(name: String, v: Expr, at: ASTNode): FunctionCallNamedParameterExpr {
        val id = Identifier(name)
        return FunctionCallNamedParameterExpr(id, v)
    }

    /** A bare name in [e] that is one of [members] (the receiver's), outside member and named-argument positions. */
    private fun readsMember(e: Expr, members: Set<String>): String? {
        var found: String? = null
        fun walk(n: ASTNode) {
            if (found != null) return
            when (n) {
                is MemberAccessExpr -> {
                    walk(n.origin)
                    (n.member as? FunctionCallExpr)?.let { m -> m.positionalParameters.forEach { walk(it.value) }; m.namedParameters.forEach { walk(it.value) } }
                    return
                }
                is FunctionCallNamedParameterExpr -> {
                    walk(n.value)
                    return
                }
                is FunctionCallExpr -> {
                    val callee = n.name
                    if (callee is Identifier && callee.value in members) found = callee.value
                    n.positionalParameters.forEach { walk(it.value) }
                    n.namedParameters.forEach { walk(it.value) }
                    return
                }
                is Type -> return
                is Identifier -> if (n.value in members) found = n.value
                else -> {}
            }
            AstTree.children(n).forEach { walk(it) }
        }
        walk(e)
        return found
    }

    private fun refuseFold(e: WithExpr, why: String): ObjectInitExpr? {
        placed.add(e)
        c.report(
            "types.with.constant",
            "A module constant's `with` folds into a construction, so $why.",
            e,
        )
        return null
    }
}
