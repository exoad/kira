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
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TypeCheckExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.UnaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.WithExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.CharLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.FloatLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.IntegerLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.NullLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.statements.ElseBranchStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ElseIfBranchStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.InIterationStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.IfSelectionStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ReturnStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.compiler.frontend.parser.ast.statements.WhileIterationStatement
import java.util.IdentityHashMap

/** One `with` block (or test block, [expr] its TypeCheckExpr) being typed: its receiver's [members] are reachable bare inside it. */
internal class WithFrame(
    val expr: Expr,
    val type: KType.Nominal,
    val temp: LocalSymbol?,
    val self: Identifier,
    val members: Set<String>,
    /** The lambda the `with` is written in, where its temporary lives. */
    val declaredIn: LambdaFrame?,
    /** Where a member read bare is read from, and what a lambda reading one captures. */
    val place: Place = Place.Local(temp!!),
    val captured: Symbol = temp!!,
    /** The members that are fields: a bare name in value position reaches only these (a method is never a value). */
    val fields: Set<String> = members,
) {
    /** The members a test block's check has read bare so far (a check names one). */
    var reads = 0

    /** What the frame is, in messages. */
    val what: String get() = if (expr is TypeCheckExpr) "this test block's ${type.display()}" else "this `with`'s receiver ${type.display()}"

    /** Records [id], a member named bare, for the backends. */
    fun record(id: Identifier, model: TypedModel) {
        when (expr) {
            is WithExpr -> model.withMembers[id] = expr
            is TypeCheckExpr -> model.testMembers[id] = expr
        }
        reads++
    }
}

/**
 * `receiver with { ... }`, the builder operator (the owner's rules of 2026-10-15).
 *
 * - The receiver is a user class, or a Mutable stdlib type (List, Map, Set, Stack, Queue, Deque, Json, StrBuf), whose
 *   methods alone are reachable. A bare call `f(...)` in the block is the receiver's `f` when it has one; a bare name is
 *   the receiver's field when it has one (a method is never a value, so a local named like a method stays the local).
 *   A local or parameter named like the member a bare name could also mean is an error where it is read or declared
 *   ([clash], [declares]): the member is written through the receiver, or the local renamed. Nested receivers that
 *   share a member name are an error too.
 * - A Mutable value is edited in place (its Mutable fields assigned, its mutators called). Any other value is copied
 *   into a temporary whose every field the block may assign; the copy re-runs `initially` at the
 *   block's end. The value is the receiver (the copy), never the block's.
 * - A receiver that is a local or a parameter the block never rebinds is used by its own name ([WithInfo.direct]), and a
 *   declaration's own `x: T { v } with { ... }` builds in `x` itself ([WithInfo.declares]): no temporary either way.
 * - It lowers inline before the statement it is in, so `break` and `continue` act on the
 *   enclosing loop. [placement] refuses a position where running it first would reorder
 *   evaluation (D33); a module constant's `with` folds into the construction it amounts to ([fold]).
 */
internal class WithTyper(private val c: PhaseC) {
    private val model get() = c.model
    private var temps = 0

    /** Every `with` the placement walk found a place for (or folded). */
    private val placed: MutableSet<WithExpr> = java.util.Collections.newSetFromMap(IdentityHashMap())

    /** A local declaration whose whole value is the `with` [e]: the local's [name], [type] and whether it is a Mutable binding. */
    private class Declared(val e: WithExpr, val name: String, val type: KType, val isMut: Boolean)

    /** The declaration whose `with` is typed next ([declaring]). */
    private var declared: Declared? = null

    /** [body] with [e] known as the whole value of the local [name] of [type], so an in-place `with` builds in the local itself. */
    fun <T> declaring(e: WithExpr, name: String, type: KType, isMut: Boolean, body: () -> T): T {
        val saved = declared
        declared = Declared(e, name, type, isMut)
        try {
            return body()
        } finally {
            declared = saved
        }
    }

    fun with(e: WithExpr, hint: KType?, ctx: BodyContext, scope: Scope): KType {
        val t = c.exprs.type(e.receiver, hint, ctx, scope)
        if (t.containsError()) {
            return KType.Error
        }
        val n = t as? KType.Nominal
        val cls = n?.sym as? ClassSymbol
        val magic = cls?.kind == ClassKind.MAGIC
        val value = "${t.display()} is a stdlib value type, which no method changes in place (a stdlib receiver is a Mutable one: " +
            "a List, a Map, a Set, a Stack, a Queue, a Deque, a Json or a StrBuf)"
        val why = when {
            t is KType.Scalar || t == KType.Str -> value
            n == null || cls == null -> "${t.display()} is not a class"
            magic && !cls.isMutableType -> value
            cls.kind == ClassKind.OPAQUE || (!magic && cls.foreign != null) -> "${t.display()} is made by the host, which owns its fields"
            else -> null
        }
        if (n == null || cls == null || why != null) {
            c.report("types.with.receiver", "`with` builds the value of a class or edits a Mutable stdlib value: $why.", e.receiver)
            return KType.Error
        }
        // A stdlib type's state is its methods': only they are reachable bare.
        val members = if (magic) c.members.methodNames(n) else c.members.memberNames(n)
        val fields = if (magic) emptySet() else members.filterTo(LinkedHashSet()) { c.members.field(n, it) != null }
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
        // A Mutable value is edited in place, any other value copied.
        val copy = !cls.isMutableType
        val direct = if (copy) null else directReceiver(e, ctx, scope)
        val declares = !copy && direct == null && declared?.let { it.e === e && it.type == t } == true
        // The declared local's own binding; a new stdlib value, held by nothing else, is the block's to change.
        val mutable = copy || (if (declares) declared!!.isMut else magic && model.places[e.receiver] == null)
        // Only a temporary that is used takes a number.
        val temp = LocalSymbol(if (declares) declared!!.name else if (direct != null) "_k_w" else "_k_w${temps++}", ctx.module, e, t, isMut = mutable, fn = ctx.fn, builds = copy)
        val self = Identifier(direct?.symbol?.name ?: temp.name)
        model.types[self] = t
        val frame = if (direct != null) {
            val place = model.places[e.receiver]!!
            model.refs[self] = direct.symbol
            model.places[self] = place
            WithFrame(e, n, null, self, members, direct.declaredIn, place, direct.symbol, fields)
        } else {
            ctx.lambda?.let { c.declaredIn[temp] = it }
            model.refs[self] = temp
            // A stdlib value's temporary stands for the receiver's own place, which its mutators write.
            val place = model.places[e.receiver]?.takeIf { magic } ?: Place.Local(temp)
            model.places[self] = place
            WithFrame(e, n, temp, self, members, ctx.lambda, place, temp, fields)
        }
        c.stmts.block(e.body, ctx, scope.withBody(frame))
        model.withs[e] = WithInfo(temp, self, cls, copy, reinit = copy && chain.any { it.initially != null }, direct = direct?.symbol, declares = declares)
        if (copy) {
            model.withCopied.add(cls)
        }
        return t
    }

    /**
     * The receiver of [e] when it is a name that means one value through the whole block: a local or a parameter the block
     * never assigns or declares again, or a field of `this` that is no Mutable binding (in a block without a lambda, which
     * would capture it). The block then uses it by its own name. Null for any other receiver, which a temporary holds.
     */
    private fun directReceiver(e: WithExpr, ctx: BodyContext, scope: Scope): Scope.Found? {
        val id = e.receiver as? Identifier ?: return null
        if (id.javaClass != Identifier::class.java || model.narrowings[id] != null || model.places[id] == null) return null
        val found = when (val sym = model.refs[id]) {
            is LocalSymbol, is ParamSymbol -> scope.find(id.value)?.takeIf { it.symbol === sym }
            is FieldSymbol -> {
                val ofThis = (model.places[id] as? Place.Field)?.receiver is Place.This
                var lambda = false
                e.body.forEach { s -> AstTree.walk(s) { if (it is LambdaExpr) lambda = true } }
                if (sym.isMut || !ofThis || lambda || model.withMembers[id] != null || model.testMembers[id] != null) null else Scope.Found(sym, ctx.lambda)
            }
            else -> null
        } ?: return null
        var rebound = false
        fun names(x: Expr?) {
            if (x is Identifier && x.value == id.value) rebound = true
        }
        e.body.forEach { s ->
            AstTree.walk(s) { n ->
                when (n) {
                    is AssignmentExpr -> names(n.target)
                    is CompoundAssignmentExpr -> names(n.left)
                    is PlaceAssignmentExpr -> names(n.target)
                    is VariableDecl -> names(n.name)
                    is InIterationStatement -> names(n.inIterationExpr.initializer)
                    is TryExpr -> names(n.exceptionName)
                    else -> {}
                }
            }
        }
        return found.takeIf { !rebound }
    }

    /**
     * [id] names the local or parameter [sym], as a call when [call]: an error when a `with` around it has a member the name
     * could also mean, a method or an Fx field for a call, a field for a value (true: the member is meant). A method is
     * never a value, so a local named like one stays the local.
     */
    fun clash(id: Identifier, sym: Symbol, scope: Scope, call: Boolean = false): Boolean {
        val frame = scope.withMember(id.value) ?: return false
        if (!call && id.value !in frame.fields) return false
        val what = if (sym is ParamSymbol) "parameter" else "local"
        val member = if (id.value in frame.fields) "field" else "method"
        c.report(
            if (frame.expr is TypeCheckExpr) "types.test.clash" else "types.with.clash",
            "'${id.value}' is both a $what and a $member of ${frame.what}, so the bare name is ambiguous here; " +
                "${through(frame, id.value, member, call)}rename the $what.",
            id,
        )
        return true
    }

    /** A local or parameter [name] declared inside a `with` block: an error when the receiver has a field of that name. */
    fun declares(name: String, at: ASTNode, scope: Scope) {
        val frame = scope.withMember(name)?.takeIf { name in it.fields } ?: return
        c.report(
            if (frame.expr is TypeCheckExpr) "types.test.clash" else "types.with.clash",
            "'$name' names a field of ${frame.what}, so it cannot name a local here; rename it.",
            at,
        )
    }

    /** The fix that names the [member] [name] through [frame]'s receiver, when the receiver is a name or a path of names. */
    private fun through(frame: WithFrame, name: String, member: String, call: Boolean): String {
        val receiver = (frame.expr as? WithExpr)?.receiver ?: return ""
        fun path(x: Expr): Boolean = x is ThisExpr || (x is Identifier && x.javaClass == Identifier::class.java) ||
            (x is MemberAccessExpr && x.member is Identifier && path(x.origin))
        if (!path(receiver)) return ""
        return "write the $member through the receiver, `${KiraUnparser.text(receiver)}.$name${if (call) "(...)" else ""}`, or "
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
            is InIterationStatement -> {
                header(s.inIterationExpr, "a loop header")
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
                if (oneValueEach(e)) {
                    // `a if c else b` on py: a `with` of an arm itself would run before the condition.
                    listOf(e.thenBranch, e.elseBranch).forEach { value(it.single().expr, mutableListOf(), "a branch of an if-expression") }
                } else {
                    // Arms with statements run in their own function, in turn: each statement places its own.
                    valueArm(e.thenBranch)
                    valueArm(e.elseBranch)
                }
                before.add(e)
            }
            is TryExpr -> {
                valueArm(e.tryBlock)
                valueArm(e.handlerBlock)
                before.add(e)
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

    /** Whether each arm of [e] is one plain expression, the shape py writes as `a if c else b`. */
    private fun oneValueEach(e: IfExpr): Boolean = listOf(e.thenBranch, e.elseBranch).all { b ->
        val s = b.singleOrNull()
        s != null && s.javaClass == Statement::class.java &&
            s.expr.let { it !is Decl && it !is AssignmentExpr && it !is CompoundAssignmentExpr && it !is PlaceAssignmentExpr }
    }

    /** An arm of an if or try that runs as its own function: its statements, then its value as a statement's. */
    private fun valueArm(block: List<Statement>) {
        placement(block.dropLast(1))
        val last = block.lastOrNull() ?: return
        if (last.javaClass == Statement::class.java && last.expr !is Decl) value(last.expr) else statement(last)
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
