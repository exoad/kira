package net.exoad.kira.compiler.fix

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.Effect
import net.exoad.kira.compiler.analysis.types.FieldSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.Foreign
import net.exoad.kira.compiler.analysis.types.GlobalSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.LocalSymbol
import net.exoad.kira.compiler.analysis.types.MemberRef
import net.exoad.kira.compiler.analysis.types.ModuleSymbol
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.ResolvedCall
import net.exoad.kira.compiler.analysis.types.Symbol
import net.exoad.kira.compiler.analysis.types.TypedModel
import net.exoad.kira.compiler.analysis.types.TypedProgram
import net.exoad.kira.compiler.analysis.types.TyperMode
import net.exoad.kira.compiler.analysis.types.TyperOptions
import net.exoad.kira.compiler.analysis.types.typeArgs
import net.exoad.kira.compiler.analysis.types.rules.Bodies
import net.exoad.kira.compiler.analysis.types.rules.BodyKind
import net.exoad.kira.compiler.analysis.types.rules.Effects
import net.exoad.kira.compiler.analysis.types.rules.EffectsPass
import net.exoad.kira.compiler.analysis.types.rules.Rules
import net.exoad.kira.compiler.frontend.lexer.Token
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Type
import net.exoad.kira.compiler.frontend.parser.ast.elements.UnaryOp
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ArrayIndexExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BuilderExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallNamedParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallPositionalParameterExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.InIterationExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.LambdaExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThisExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThrowExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TypeCastExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.UnaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.WithExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.IntegerLiteral
import net.exoad.kira.compiler.frontend.parser.ast.statements.BreakStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ContinueStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.InIterationStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.compiler.frontend.parser.ast.statements.WhileIterationStatement
import java.io.File
import java.util.IdentityHashMap

/**
 * `kira --fix-counters FILE...`: a hand-written counter becomes the loop over a range. Only this shape is rewritten:
 *
 *     i: Mutable<T> { a }            while i: T in a..<n {
 *     while i < n {                      ...
 *         ...                        }
 *         i += 1
 *     }
 *
 * (`<=` gives `a..n`; the step may be `i = i + 1`), and only where the two mean the same: the body writes `i` in its last
 * statement alone, nothing uses `i` after the loop, the body has no `continue` of this loop, and the end `n` is the same at
 * every turn. A counter set again for a later loop (`i = b` directly before `while i < m`) is a chain: each loop becomes the
 * loop over its own range where every loop of the counter can and nothing else uses it. An end is made of numbers, names of
 * numbers, `xs.size()` and `s.length()`, and arithmetic; the body assigns none of its names and changes the size of none of
 * its collections, by that name or by another that may hold the same value. A Mutable value is shared, so only a local made
 * fresh (a construction, a literal, or one of the stdlib results of [FRESH_RESULTS]) and never handed on is known to have
 * one name; a function of the program's own is followed by what it may do to each of its parameters ([Summaries]). Every
 * other counter loop is left and listed with why. The project is typed strictly to ask this, and typed again with the
 * rewritten text, which must have no error. Lines keep their comments, tabs and line ends; a second run finds nothing.
 */
object CounterFix {
    /** Why a counter loop is left as it is. */
    enum class Reason(val code: String) {
        CONDITION("condition"),
        NO_STEP("no-step"),
        STEP_NOT_ONE("step-not-1"),
        NOT_DECLARED_BEFORE("not-declared-before"),
        COUNTER_WRITTEN("counter-written"),
        COUNTER_CAPTURED("counter-captured"),
        COUNTER_AFTER("counter-after"),
        CHAIN("chain"),
        BREAK("break"),
        CONTINUE("continue"),
        BOUND_SHAPE("bound-shape"),
        BOUND_WRITTEN("bound-written"),
        BOUND_SHARED("bound-shared"),
        LAYOUT("layout"),
    }

    /** A counter loop left alone: the line of its `while`, the first reason (and [message]), and every reason that holds. */
    class Left(val line: Int, val reason: Reason, val message: String, val all: List<Reason>) {
        override fun toString(): String {
            val also = all.filter { it != reason }
            return "line $line [${reason.code}]: $message" + if (also.isEmpty()) "" else " (also: ${also.joinToString(", ") { it.code }})"
        }
    }

    /** One loop's edit: [declLine] and [stepLine] go, and on [whileLine] the columns [from] until [until] become [head]. */
    internal class Rewrite(val declLine: Int, val whileLine: Int, val from: Int, val until: Int, val head: String, val stepLine: Int)

    /** What one file's rewrite came to: its new text, the loops rewritten and those left, or why the file was refused. */
    class Outcome(val text: String?, val fixed: Int, val left: List<Left>, val refused: String?)

    /** The stdlib collections whose `size()` an end may read: an element or a result of one is a part or a copy, never the collection. */
    private val SIZED = setOf("List", "Arr", "View", "MutView", "Map", "Set", "Json")

    /** Those whose size no write of an element changes. */
    private val KEEPS_SIZE = setOf("List", "Arr", "View", "MutView")

    private val ARITHMETIC = setOf(BinaryOp.ADD, BinaryOp.SUB, BinaryOp.MUL, BinaryOp.DIV, BinaryOp.MOD)

    /**
     * The stdlib bindings, by key, whose result is a collection made by the call: no argument and no other name holds it.
     * Each is read off its manifest's Python: a split, a findall or a sorted listing builds a list; `_k_copy`, `list(..)` and
     * `bytearray(..)` copy. `Json.obj()` and `Json.arr()` (`{}` and `[]`) are new values too; the typer makes those calls, so
     * they are known by how they are written. A result that is its receiver or a part of it is not here: `view`,
     * `from` and `slice` (the receiver itself on py), `List.get`, `Map.get`, `Json.get` and `Json.at` (an element, shared).
     */
    private val FRESH_RESULTS = setOf(
        "Str.split", "Str.splitWhitespace", "Str.bytes", "splitSpace", "splitLines", "wrapWords",
        "Regex.findIter", "Regex.findAll", "Regex.split",
        "View.toList", "MutView.toList", "Arr.clone", "List.toArr", "Set.toArr",
        "Map.keys", "Map.valuesArr", "Map.entries", "Json.keys",
        "listDir", "glob", "walkFiles", "args",
    )

    /** A number, Bool, Char or Str: a value nothing changes in place, so a read of one is a copy. */
    private fun scalar(t: KType?): Boolean = t is KType.Scalar || t == KType.Str

    /** The stdlib collection [t] is, by name, when an end may read its size. */
    private fun sizedKind(t: KType?): String? = ((t as? KType.Nominal)?.sym as? ClassSymbol)?.takeIf { it.module.isStdlib && it.name in SIZED }?.name

    /** Whether [rc] calls the stdlib: a binding does what its manifest says, and none of it runs code of the program's own unless given some. */
    private fun binding(rc: ResolvedCall): Boolean = rc.fn?.module?.isStdlib == true

    /**
     * Whether making a [cls] runs code of the program's own: an `initially` block, or a field's default that calls, makes a
     * closure, or builds a value whose making does.
     */
    private fun runsCode(model: TypedModel, cls: ClassSymbol?, seen: MutableSet<ClassSymbol> = HashSet()): Boolean {
        var at = cls ?: return true
        while (true) {
            if (!at.module.isStdlib) {
                if (at.initially != null || !seen.add(at)) return true
                for (f in at.fields) {
                    var runs = false
                    f.default?.let { d ->
                        AstTree.walk(d) { n ->
                            if (n is FunctionCallExpr || n is LambdaExpr || (n is ObjectInitExpr && runsCode(model, model.inits[n]?.cls, seen))) runs = true
                        }
                    }
                    if (runs) return true
                }
            }
            at = at.superclass?.sym as? ClassSymbol ?: return false
        }
    }

    /** Whether a value of [t] may be or hold a collection of type [root]: a stdlib value holds what its type arguments may (a Json, a Json), anything else what the typer says. */
    private fun mayHold(rules: Rules, t: KType?, root: KType?): Boolean {
        if (t == root) return true
        if (scalar(t)) return false
        val cls = (t as? KType.Nominal)?.sym as? ClassSymbol
        if (cls == null || !cls.module.isStdlib) return rules.mayHold(t, root)
        return t.typeArgs().any { mayHold(rules, it, root) }
    }

    /** What a function of the program's own may do to what it is given: [open] when it reaches past its parameters, else the parameters whose value it may resize or write into. */
    internal class Summary(var open: Boolean = false, val resizes: MutableSet<Int> = HashSet(), val writes: MutableSet<Int> = HashSet())

    /**
     * Each function's [Summary], to a fixpoint over the calls between them. A function is open when it reads a field, a
     * global collection or `this`, writes a field or a global by name, makes a closure, or calls what is not followed (a
     * function value, a dispatched method, a construction that runs code). Else every value in it comes from its parameters
     * or is made in it: a local holds what any value given it held, a call's result what its receiver and arguments held,
     * and a local given to a changing call with other values may hold them after. A parameter is resized when a stdlib call with
     * an effect, an element written on a Map, a Set or a Json, or a resizing call of the program's own is given a value
     * that came from it; written when a field or an element of such a value is.
     */
    internal class Summaries(program: TypedProgram, private val effects: Effects) {
        private val model = program.model
        private val of = IdentityHashMap<FnSymbol, Summary>()
        private val bodies = Bodies.of(program).filter { it.kind == BodyKind.FUNCTION && it.fn != null }

        init {
            bodies.forEach { of[it.fn!!] = Summary() }
            var grew = true
            while (grew) {
                grew = false
                for (b in bodies) if (pass(b.fn!!, b.roots)) grew = true
            }
        }

        /** The summary of [fn] when it is a function of the program's own with a body. */
        fun get(fn: FnSymbol?): Summary? = fn?.let { of[it] }

        private fun pass(fn: FnSymbol, roots: List<ASTNode>): Boolean {
            val s = of.getValue(fn)
            if (s.open) return false
            val was = s.resizes.size + s.writes.size
            val index = IdentityHashMap<Symbol, Int>()
            fn.params.forEachIndexed { k, p -> index[p] = k }
            val local = IdentityHashMap<Symbol, MutableSet<Int>>()
            var moved = true

            // The parameters the value of [e] may have come from.
            fun from(e: ASTNode?): Set<Int> {
                if (e == null || e is Type || (e is Expr && scalar(model.types[e]))) return emptySet()
                if (e is Identifier && e !is IntrinsicExpr) {
                    val sym = model.refs[e] ?: return emptySet()
                    return (index[sym]?.let { setOf(it) } ?: emptySet()) + (local[sym] ?: emptySet())
                }
                val out = HashSet<Int>()
                AstTree.children(e).forEach { out += from(it) }
                return out
            }
            // Every local named under [holder] may hold what [held] names after.
            fun holds(holder: ASTNode, held: Set<Int>) {
                if (held.isEmpty()) return
                AstTree.walk(holder) { n ->
                    val sym = (n as? Identifier)?.let { model.refs[it] }
                    if (sym != null && (sym is LocalSymbol || sym is ParamSymbol) && local.getOrPut(sym) { HashSet() }.addAll(held)) moved = true
                }
            }
            // A name given [value] may hold what it held.
            fun flows(sym: Symbol?, value: ASTNode?) {
                if (sym != null && (sym is LocalSymbol || sym is ParamSymbol) && local.getOrPut(sym) { HashSet() }.addAll(from(value))) moved = true
            }
            fun changed(given: List<Expr>, resize: Boolean) {
                val all = HashSet<Int>()
                given.forEach { all += from(it) }
                if (resize) s.resizes += all
                s.writes += all
                given.forEach { holds(it, all) }
            }
            fun visit(n: ASTNode) {
                when {
                    n is LambdaExpr || n is WithExpr || n is ThisExpr -> s.open = true
                    n is Identifier && n !is IntrinsicExpr -> when (val sym = model.refs[n]) {
                        is FieldSymbol, is GlobalSymbol -> if (!scalar(model.types[n])) s.open = true
                        else -> {}
                    }
                    n is IntrinsicExpr -> if (effects.node(n) == Effect.IMPURE) s.open = true
                    n is VariableDecl -> flows(model.refs[n.name], n.value)
                    n is InIterationStatement -> flows(model.refs[n.inIterationExpr.initializer], n.inIterationExpr.target)
                    n is AssignmentExpr -> when (val sym = model.refs[n.target]) {
                        is LocalSymbol, is ParamSymbol -> flows(sym, n.value)
                        else -> s.open = true
                    }
                    n is CompoundAssignmentExpr -> when {
                        (n.left as? Identifier)?.let { model.refs[it] }.let { it is FieldSymbol || it is GlobalSymbol } -> s.open = true
                        !scalar(model.types[n.left]) -> changed(listOf(n.left, n.right), true)
                    }
                    n is PlaceAssignmentExpr -> {
                        val target = n.target
                        when {
                            target is ArrayIndexExpr -> changed(listOf(target.originExpr, n.value), sizedKind(model.types[target.originExpr]) !in KEEPS_SIZE)
                            target is MemberAccessExpr -> changed(listOf(target.origin, n.value), false)
                            else -> s.open = true
                        }
                    }
                    n is ObjectInitExpr -> if (runsCode(model, model.inits[n]?.cls)) s.open = true
                    n is FunctionCallExpr -> {
                        val rc = model.calls[n]
                        val given = listOfNotNull(rc?.receiver) + n.positionalParameters.map { it.value } + n.namedParameters.map { it.value }
                        val callee = if (rc != null && (rc.kind == CallKind.FREE || rc.kind == CallKind.METHOD)) rc.fn?.let { of[it] } else null
                        when {
                            rc == null -> s.open = true
                            rc.kind == CallKind.PRINT -> {}
                            binding(rc) -> if (effects.node(n) == Effect.IMPURE) {
                                // A binding given a function value may run it.
                                if (given.any { model.types[it] is KType.Fn }) s.open = true else changed(given, true)
                            }
                            callee == null || callee.open -> s.open = true
                            else -> {
                                rc.args.forEachIndexed { k, a ->
                                    if (a is ArgBinding.Given) {
                                        if (k in callee.resizes) s.resizes += from(a.expr)
                                        if (k in callee.writes) s.writes += from(a.expr)
                                    }
                                }
                                // What it is given may hold each other after, where it changes any of it.
                                if (callee.resizes.isNotEmpty() || callee.writes.isNotEmpty()) {
                                    val all = HashSet<Int>()
                                    given.forEach { all += from(it) }
                                    given.forEach { holds(it, all) }
                                }
                            }
                        }
                    }
                }
                if (n is Expr && n !is Type && model.opCalls[n]?.let { !binding(it) } == true) s.open = true
                // The name after a dot is a member's, read through the value before it, not a name of this function's.
                if (n is MemberAccessExpr) {
                    // `module.NAME`: a global collection of another module.
                    if ((model.members[n] as? MemberRef.ModuleMember)?.symbol is GlobalSymbol && !scalar(model.types[n])) s.open = true
                    visit(n.origin)
                    if (n.member !is Identifier || n.member is IntrinsicExpr) visit(n.member)
                } else {
                    AstTree.children(n).forEach { visit(it) }
                }
            }
            while (moved && !s.open) {
                moved = false
                roots.forEach { visit(it) }
            }
            return s.open || s.resizes.size + s.writes.size != was
        }
    }

    /** The counter loops of [module]: those that can be rewritten, and those left with why. */
    internal fun find(program: TypedProgram, module: ModuleSymbol, raw: String, known: Known = Known(program)): Pair<List<Rewrite>, List<Left>> {
        val rewrites = ArrayList<Rewrite>()
        val left = ArrayList<Left>()
        val lines = raw.split(Regex("(?<=\n)")).map { it.trimEnd('\n', '\r') }
        val tokens = module.source.tokens
        val index = HashMap<Long, Int>()
        tokens.forEachIndexed { i, t -> index[key(t.canonicalLocation.lineNumber, t.canonicalLocation.column)] = i }
        for (body in Bodies.of(program)) {
            if (body.module !== module) continue
            val scan = Scan(program, known, body.roots)
            val laid = ArrayList<Triple<Int, Found, Any>>()
            for ((loop, found) in scan.judged()) {
                val line = module.source.astOrigins[loop]?.lineNumber ?: -1
                if (found.reasons.isNotEmpty()) {
                    val (reason, message) = found.reasons.first()
                    left += Left(line, reason, message, found.reasons.map { it.first }.distinct())
                    continue
                }
                laid += Triple(line, found, layout(module, tokens, index, lines, loop, found))
            }
            // A chain is rewritten whole: one loop of a counter whose lines cannot be edited keeps the declaration for the others.
            val unlaid = laid.filter { it.third !is Rewrite }.map { it.second.counter }
            for ((line, found, edit) in laid) {
                when {
                    edit is Rewrite && unlaid.none { it === found.counter } -> rewrites += edit
                    edit is Rewrite -> left += Left(line, Reason.CHAIN, "`${found.counter.name}` counts another loop that is left, so its declaration stays", listOf(Reason.CHAIN))
                    else -> left += Left(line, Reason.LAYOUT, edit as String, listOf(Reason.LAYOUT))
                }
            }
        }
        return rewrites.sortedBy { it.whileLine } to left.sortedBy { it.line }
    }

    /** What a program's loops are all asked against: the typer's rules, its effects, and each function's [Summary]. */
    internal class Known(program: TypedProgram) {
        val rules = Rules(program)
        val effects: Effects = EffectsPass.of(rules)
        val summaries = Summaries(program, effects)
    }

    /**
     * What [Scan.judged] found of a counter loop: its counter, where it starts (its declaration [decl], or the assignment
     * [reset] that sets it again, the statement before the loop), its comparison, whether it has a `break`, and every
     * reason it is left.
     */
    internal class Found(
        val counter: LocalSymbol,
        val decl: VariableDecl?,
        val reset: AssignmentExpr?,
        val inclusive: Boolean,
        val breaks: Boolean,
        val reasons: MutableList<Pair<Reason, String>>,
    )

    /** A name the end reads: a number's ([sized] false), or a collection's whose size it reads, [type] its type. */
    private class Root(val sym: Symbol, val sized: Boolean, val type: KType?)

    /** One body's loops, with each node's parent, so a use of a name can be followed to what consumes it. */
    private class Scan(val program: TypedProgram, known: Known, val roots: List<ASTNode>) {
        val model = program.model
        val effects = known.effects
        val rules = known.rules
        val summaries = known.summaries
        val parents = IdentityHashMap<ASTNode, ASTNode>()
        val loops = ArrayList<WhileIterationStatement>()
        private val alone = IdentityHashMap<Symbol, Boolean>()

        init {
            fun go(n: ASTNode) {
                if (n is WhileIterationStatement) loops += n
                for (k in AstTree.children(n)) {
                    if (parents.putIfAbsent(k, n) == null) go(k)
                }
            }
            roots.forEach { go(it) }
        }

        private fun ref(e: Expr?): Symbol? = (e as? Identifier)?.let { model.refs[it] }

        private fun integer(t: KType?): Boolean = (t as? KType.Scalar)?.prim?.isInteger == true

        private fun isCounter(s: Symbol?): Boolean = s is LocalSymbol && s.isMut && integer(s.type)

        /** Whether [n] is [of] or under it. */
        private fun within(n: ASTNode, of: ASTNode): Boolean {
            var at: ASTNode? = n
            while (at != null) {
                if (at === of) return true
                at = parents[at]
            }
            return false
        }

        /** [n] as a statement of its block: itself, or the plain wrapper that holds it. */
        private fun statement(n: ASTNode): ASTNode {
            var at = n
            while (true) {
                val up = parents[at]
                if (up == null || up.javaClass != Statement::class.java) return at
                at = up
            }
        }

        /** The statement directly before [loop] in its block, or null. */
        private fun before(loop: ASTNode): ASTNode? {
            val at = statement(loop)
            val block = parents[at]?.let { AstTree.children(it) } ?: roots
            return block.getOrNull(block.indexOfFirst { it === at } - 1)
        }

        /** Every name under [under] that is [sym]. */
        private fun uses(under: List<ASTNode>, sym: Symbol): List<Identifier> {
            val out = ArrayList<Identifier>()
            under.forEach { root -> AstTree.walk(root) { n -> if (n is Identifier && model.refs[n] === sym) out += n } }
            return out
        }

        private fun inLambda(n: ASTNode, upTo: ASTNode?): Boolean {
            var at: ASTNode? = n
            while (at != null && at !== upTo) {
                if (at is LambdaExpr) return true
                at = parents[at]
            }
            return false
        }

        /** The amount [e] adds to [sym] (`i += 1`, `i = i + 1`), 0 when it writes [sym] another way, null when it does not write it. */
        private fun step(e: Expr, sym: Symbol): Long? = when {
            e is CompoundAssignmentExpr && ref(e.left) === sym -> (e.right as? IntegerLiteral)?.value?.takeIf { e.operator == BinaryOp.ADD } ?: 0L
            e is AssignmentExpr && ref(e.target) === sym -> {
                val sum = (e.value as? BinaryExpr)?.takeIf { it.operator == BinaryOp.ADD }
                when {
                    sum == null -> 0L
                    ref(sum.leftExpr) === sym -> (sum.rightExpr as? IntegerLiteral)?.value ?: 0L
                    ref(sum.rightExpr) === sym -> (sum.leftExpr as? IntegerLiteral)?.value ?: 0L
                    else -> 0L
                }
            }
            else -> null
        }

        /** The `&&` operands of [e], in order. */
        private fun conjuncts(e: Expr): List<Expr> =
            if (e is BinaryExpr && e.operator == BinaryOp.AND) conjuncts(e.leftExpr) + conjuncts(e.rightExpr) else listOf(e)

        private fun below(e: Expr): BinaryExpr? =
            (e as? BinaryExpr)?.takeIf { (it.operator == BinaryOp.LESS_THAN || it.operator == BinaryOp.LESS_THAN_OR_EQUAL) && isCounter(ref(it.leftExpr)) }

        /**
         * Every counter loop of the body with what was found of it. A loop is first judged by itself; then each counter's loops
         * are judged together, since its declaration goes only when every one of them is rewritten: a use of the counter
         * outside its loops (its declaration and the resets that start them aside) leaves them all, and so does one loop left.
         */
        fun judged(): List<Pair<WhileIterationStatement, Found>> {
            val all = loops.mapNotNull { l -> judge(l)?.let { l to it } }
            for ((counter, group) in all.groupBy { it.second.counter }) {
                val i = counter.name
                val found = group.map { it.second }
                val resets = found.mapNotNull { it.reset }
                val stray = uses(roots, counter).filter { id ->
                    group.none { within(id, it.first) } && counter.decl?.let { within(id, it) } != true && resets.none { it.target === id }
                }
                val clean = found.map { it.reasons.isEmpty() }
                found.forEachIndexed { k, f ->
                    if (f.decl == null && f.reset == null) return@forEachIndexed
                    when {
                        stray.isNotEmpty() -> {
                            f.reasons += Reason.COUNTER_AFTER to "`$i` is used after the loop"
                            if (f.breaks) f.reasons += Reason.BREAK to "the body has a `break`, and `$i` is used after it"
                        }
                        found.none { it.decl != null } -> f.reasons += Reason.CHAIN to "no loop stands directly after the declaration of `$i`, which this loop sets again"
                        clean.withIndex().any { (j, ok) -> j != k && !ok } -> f.reasons += Reason.CHAIN to "`$i` counts another loop that is left, so its declaration stays"
                    }
                }
            }
            return all
        }

        /** [loop] judged by itself: null when it is no counter loop (no `i < n` on a Mutable integer local in its condition). */
        private fun judge(loop: WhileIterationStatement): Found? {
            val cond = conjuncts(loop.condition).firstNotNullOfOrNull { below(it) } ?: return null
            val counter = ref(cond.leftExpr) as LocalSymbol
            val i = counter.name
            val reasons = ArrayList<Pair<Reason, String>>()
            if (cond !== loop.condition) {
                reasons += Reason.CONDITION to "the condition is more than `$i < n`"
                return Found(counter, null, null, false, false, reasons)
            }
            // The step: the body's last statement, and one.
            val last = loop.statements.lastOrNull()?.takeIf { it.javaClass == Statement::class.java }
            val amount = last?.let { step(it.expr, counter) }
            when {
                amount == null -> reasons += Reason.NO_STEP to "the body does not end with `$i += 1`"
                amount != 1L -> reasons += Reason.STEP_NOT_ONE to "the step is not `$i += 1`"
            }
            // The start: `i: Mutable<T> { a }`, or `i = b` whose value does not read `i`, the statement before the loop.
            val prior = before(loop)
            val decl = (counter.decl as? VariableDecl)?.takeIf { it.value != null && prior === statement(it) }
            val reset = ((prior as? Statement)?.takeIf { decl == null && it.javaClass == Statement::class.java }?.expr as? AssignmentExpr)
                ?.takeIf { ref(it.target) === counter && uses(listOf(it.value), counter).isEmpty() }
            if (decl == null && reset == null) {
                reasons += Reason.NOT_DECLARED_BEFORE to "`$i` is neither declared nor set directly before the loop"
            }
            // The counter in the body: read only, the step aside, and never by a closure.
            val inBody = uses(loop.statements, counter).filter { amount == null || !within(it, last) }
            inBody.firstNotNullOfOrNull { id -> assigned(id) }?.let { reasons += Reason.COUNTER_WRITTEN to "`$i` $it in the body" }
            if (inBody.any { id -> inLambda(id, loop) }) {
                reasons += Reason.COUNTER_CAPTURED to "a closure in the body captures `$i`"
            }
            // A `continue` would skip the step. A `break` leaves the counter where it stopped, which matters only where it is used after.
            var breaks = false
            var continues = false
            fun exits(n: ASTNode) {
                if (n is BreakStatement) breaks = true
                if (n is ContinueStatement) continues = true
                if (n is WhileIterationStatement || n is InIterationStatement || n is LambdaExpr) return
                AstTree.children(n).forEach { exits(it) }
            }
            loop.statements.forEach { exits(it) }
            if (continues) reasons += Reason.CONTINUE to "the body has a `continue`, which skips the step"
            bound(loop, cond.rightExpr, counter)?.let { reasons += it }
            return Found(counter, decl, reset, cond.operator == BinaryOp.LESS_THAN_OR_EQUAL, breaks, reasons)
        }

        /** Whether the use [id] of a number's name assigns it: every other use of a number reads a copy. */
        private fun assigned(id: Identifier): String? {
            val parent = parents[id]
            return when {
                parent is AssignmentExpr && parent.target === id -> "is assigned"
                parent is CompoundAssignmentExpr && parent.left === id -> "is assigned"
                else -> null
            }
        }

        /** The names [e] reads into [out] when it is an end this fixer follows: numbers, names of numbers, sizes, and arithmetic of them. */
        private fun reads(e: Expr, out: MutableList<Root>): Boolean = when {
            e is IntegerLiteral -> true
            e is IntrinsicExpr -> false
            e is Identifier -> {
                val s = model.refs[e]
                val named = (s is LocalSymbol || s is ParamSymbol || s is FieldSymbol || s is GlobalSymbol) && integer(model.types[e])
                if (s != null && named) out += Root(s, false, model.types[e])
                named
            }
            e is BinaryExpr -> e.operator in ARITHMETIC && model.opCalls[e] == null && reads(e.leftExpr, out) && reads(e.rightExpr, out)
            e is UnaryExpr -> (e.operator == UnaryOp.NEG || e.operator == UnaryOp.POS) && reads(e.operand, out)
            e is TypeCastExpr -> integer(model.types[e]) && reads(e.value, out)
            e is FunctionCallExpr -> size(e, out)
            else -> false
        }

        /** `xs.size()` or `s.length()` of a name: the stdlib's own, of one of its collections or of a Str. */
        private fun size(call: FunctionCallExpr, out: MutableList<Root>): Boolean {
            val member = call.name as? MemberAccessExpr ?: return false
            val of = member.origin as? Identifier ?: return false
            if (of is IntrinsicExpr || (member.member as? Identifier)?.value !in setOf("size", "length")) return false
            if (call.positionalParameters.isNotEmpty() || call.namedParameters.isNotEmpty()) return false
            val fn = model.calls[call]?.fn ?: return false
            val s = model.refs[of] ?: return false
            val type = model.types[of]
            if (!fn.module.isStdlib || effects.node(call) == Effect.IMPURE) return false
            if (!(s is LocalSymbol || s is ParamSymbol || s is FieldSymbol || s is GlobalSymbol)) return false
            if (type != KType.Str && sizedKind(type) == null) return false
            // A Str never changes in place: its name is read as a number's is.
            out += Root(s, type != KType.Str, type)
            return true
        }

        /** Why the end [n] may change while [loop] runs, or null when it cannot. */
        private fun bound(loop: WhileIterationStatement, n: Expr, counter: LocalSymbol): Pair<Reason, String>? {
            val names = ArrayList<Root>()
            if (!reads(n, names) || model.effect(n) == Effect.IMPURE) {
                return Reason.BOUND_SHAPE to "the end is more than numbers, names of numbers, `xs.size()`, `s.length()` and arithmetic"
            }
            // By its own name: the body assigns none of them and changes the size of none.
            for (r in names) {
                val s = r.sym
                if (s === counter) return Reason.BOUND_WRITTEN to "the end reads the counter"
                for (use in uses(loop.statements, s)) {
                    val how = if (r.sized) resized(use, sizedKind(r.type) in KEEPS_SIZE) else assigned(use)
                    if (how != null) return Reason.BOUND_WRITTEN to "the end reads `${s.name}`, which $how in the body"
                }
            }
            // By another name: a field, a global, and a collection another name may hold change wherever the body's effects reach.
            val open = names.filter { r -> if (r.sized) !heldAlone(r.sym) else r.sym is FieldSymbol || (r.sym is GlobalSymbol && !r.sym.isConstant) }
            if (open.isEmpty()) return null
            val what = open.first().let { r ->
                when {
                    !r.sized -> "`${r.sym.name}`, which is not this function's own"
                    r.sym is LocalSymbol -> "the size of `${r.sym.name}`, a value another name may hold"
                    else -> "the size of `${r.sym.name}`, a value other names hold"
                }
            }
            val reach = reaches(loop, open) ?: return null
            return Reason.BOUND_SHARED to "the end reads $what, and the body $reach"
        }

        /**
         * How the use [id] of a collection's name may change its size, or null when it cannot: the collection assigned, a call
         * on it that has an effect, an element written where that may add one ([keeps] says it may not), or the collection
         * itself given to where it may be changed or kept. An element or a result of a stdlib call on it is a part or a copy.
         */
        private fun resized(id: Identifier, keeps: Boolean): String? {
            assigned(id)?.let { return it }
            val parent = parents[id] ?: return "is used where it cannot be followed"
            return when {
                parent is ArrayIndexExpr && parent.originExpr === id -> {
                    val up = parents[parent]
                    val target = (up is PlaceAssignmentExpr && up.target === parent) || (up is CompoundAssignmentExpr && up.left === parent)
                    if (target && !keeps) "has an element written, which may add one," else null
                }
                parent is MemberAccessExpr && parent.origin === id -> {
                    val call = (parents[parent] as? FunctionCallExpr)?.takeIf { it.name === parent }
                    when {
                        call == null -> "has a member read that cannot be followed"
                        effects.node(call) == Effect.IMPURE -> "is the receiver of `${(parent.member as? Identifier)?.value ?: "a call"}`, which may change it,"
                        else -> null
                    }
                }
                else -> if (handedOn(id)) "is handed on to where it may be changed or kept" else null
            }
        }

        /**
         * Whether the use [id] of a collection's name hands the collection itself on, so that another name may hold it after:
         * anything but an element of it, a call on it, a loop over it, or a call given it that cannot change or keep it and
         * answers a number, Bool, Char or Str: a stdlib call with no effect, or a function of the program's own whose
         * [Summary] neither is open nor resizes that parameter. The typer's effects are not asked of the program's own: where
         * a Mutable value is shared, a function it finds free of effects may still change one through a local of its own.
         */
        private fun handedOn(id: Identifier): Boolean {
            val parent = parents[id] ?: return true
            return when {
                parent is ArrayIndexExpr && parent.originExpr === id -> false
                parent is MemberAccessExpr && parent.origin === id -> (parents[parent] as? FunctionCallExpr)?.name !== parent
                parent is InIterationExpr && parent.target === id -> false
                parent is BinaryExpr -> !scalar(model.types[parent])
                parent is FunctionCallPositionalParameterExpr || parent is FunctionCallNamedParameterExpr -> {
                    val call = parents[parent] as? FunctionCallExpr ?: return true
                    val rc = model.calls[call] ?: return true
                    val quiet = if (binding(rc)) effects.node(call) != Effect.IMPURE else keeps(rc, id)
                    !(quiet && scalar(model.types[call]))
                }
                else -> true
            }
        }

        /** The summary of the function [rc] calls, when it is one of the program's own called directly. */
        private fun summary(rc: ResolvedCall): Summary? = if (rc.kind == CallKind.FREE || rc.kind == CallKind.METHOD) summaries.get(rc.fn) else null

        /** Whether the function [rc] calls leaves the size of its argument [arg] as it is: its summary is not open and does not resize that parameter. */
        private fun keeps(rc: ResolvedCall, arg: Expr): Boolean {
            val s = summary(rc) ?: return false
            val k = rc.args.indexOfFirst { it is ArgBinding.Given && it.expr === arg }
            return !s.open && k >= 0 && k !in s.resizes
        }

        /** Whether [e] makes a value no name holds yet: a construction (`{ }`), a literal collection (`@listOf(..)`), `Json.obj()`, `Json.arr()`, or a stdlib result of [FRESH_RESULTS]. */
        private fun fresh(e: Expr?): Boolean = e is ObjectInitExpr || e is BuilderExpr || (e is FunctionCallExpr && (emptyJson(e) ||
            model.calls[e]?.let { rc -> binding(rc) && (rc.fn?.foreign as? Foreign.Magic)?.key in FRESH_RESULTS } == true))

        /** `Json.obj()` or `Json.arr()`, called on the stdlib's type itself: an empty Json of its own. */
        private fun emptyJson(e: FunctionCallExpr): Boolean {
            val member = e.name as? MemberAccessExpr ?: return false
            // The type's name, as the parser reads a name that starts a type (or a plain name that no value has).
            val on = member.origin
            val named = when {
                on is Type -> (on.identifier as? Identifier)?.value
                on is Identifier && model.refs[on].let { it !is LocalSymbol && it !is ParamSymbol && it !is FieldSymbol && it !is GlobalSymbol } -> on.value
                else -> null
            }
            return named == "Json" && (member.member as? Identifier)?.value in setOf("obj", "arr") &&
                e.positionalParameters.isEmpty() && e.namedParameters.isEmpty() && sizedKind(model.types[e]) == "Json"
        }

        /**
         * Whether [s] is a local that alone holds its value: declared with a fresh value, assigned only fresh values, never
         * handed on, and in no closure. Only its own name then reaches what it holds.
         */
        private fun heldAlone(s: Symbol?): Boolean {
            if (s !is LocalSymbol) return false
            return alone.getOrPut(s) {
                val decl = s.decl as? VariableDecl
                decl != null && fresh(decl.value) && uses(roots, s).all { id ->
                    val parent = parents[id]
                    when {
                        id === decl.name -> true
                        inLambda(id, null) -> false
                        parent is AssignmentExpr && parent.target === id -> fresh(parent.value)
                        parent is CompoundAssignmentExpr && parent.left === id -> false
                        else -> !handedOn(id)
                    }
                }
            }
        }

        /** Whether the value of [e] may be one of the collections [types] names: it is no number or Str, no local held alone, and of one of those types (or of none this can tell). */
        private fun mayBe(e: Expr, types: List<KType?>): Boolean {
            val t = model.types[e]
            if (scalar(t)) return false
            if (e is Identifier && e !is IntrinsicExpr && heldAlone(model.refs[e])) return false
            if (fresh(e)) return false
            val n = (t as? KType.Nominal)?.takeIf { it.sym is ClassSymbol } ?: return true
            return types.any { it !is KType.Nominal || (it.sym === n.sym && (it == n || open(it) || open(n))) }
        }

        /** Whether [t] has a type parameter among its arguments: two such types of one collection may be one. */
        private fun open(t: KType.Nominal): Boolean = t.typeArgs().any { it !is KType.Scalar && it != KType.Str && (it !is KType.Nominal || open(it)) }

        /**
         * Whether the value of [e], given to a function that may resize what it is given, may be or hold one of the collections
         * [types] names: by its type it is one (and is not made in place), or may hold one.
         */
        private fun mayReach(e: Expr, types: List<KType?>): Boolean {
            val t = model.types[e]
            if (scalar(t)) return false
            return types.any { root ->
                if (t == root) mayBe(e, listOf(root)) else mayHold(rules, t, root)
            }
        }

        private fun nameOf(call: FunctionCallExpr): String = when (val n = call.name) {
            is MemberAccessExpr -> (n.member as? Identifier)?.value ?: "a function"
            is Identifier -> n.value
            else -> "a function"
        }

        /**
         * How the body of [loop] may change what the [open] names read through another name, or null when it cannot. A field or
         * a global changes by a call with an effect or a write of a field. A collection other names may hold changes size by
         * anything that runs the program's own code (a call, an operator, a construction that runs a block or a default), or
         * by a stdlib call or an element written on a value that may be it.
         */
        private fun reaches(loop: WhileIterationStatement, open: List<Root>): String? {
            val members = open.any { !it.sized }
            val types = open.filter { it.sized }.map { it.type }
            var hit: String? = null
            // The program's own code: with an effect it may write anything, and without one it may still change a shared collection.
            fun own(impure: Boolean, what: String): String? = if (impure || types.isNotEmpty()) "$what, which may change what other names hold" else null
            fun place(target: Expr): String? = when {
                target is ArrayIndexExpr ->
                    if (sizedKind(model.types[target.originExpr]) in KEEPS_SIZE || !mayBe(target.originExpr, types)) null
                    else "writes an element of a value that may be the same"
                target is MemberAccessExpr -> if (members) "writes a field" else null
                else -> "writes where it cannot be followed"
            }
            fun call(k: FunctionCallExpr): String? {
                val rc = model.calls[k] ?: return "calls what cannot be followed"
                val impure = effects.node(k) == Effect.IMPURE
                if (!binding(rc) && rc.kind != CallKind.PRINT) {
                    // A function that stays within what it is given changes only what its summary says, of the arguments that may reach the end's.
                    val s = summary(rc)?.takeIf { !it.open } ?: return own(impure, "calls `${nameOf(k)}`")
                    rc.args.forEachIndexed { at, a ->
                        if (a is ArgBinding.Given) {
                            if (at in s.resizes && mayReach(a.expr, types)) return "calls `${nameOf(k)}`, which may resize what it is given"
                            if (members && (at in s.writes || at in s.resizes) && !scalar(model.types[a.expr])) return "calls `${nameOf(k)}`, which may write through what it is given"
                        }
                    }
                    return null
                }
                if (!impure || rc.kind == CallKind.PRINT) return null
                val given = listOfNotNull(rc.receiver) + k.positionalParameters.map { it.value } + k.namedParameters.map { it.value }
                return if (given.any { mayBe(it, types) }) "calls `${nameOf(k)}` on a value that may be the same" else null
            }
            loop.statements.forEach { st ->
                AstTree.walk(st) { k ->
                    if (hit != null || k !is Expr || k is Type) return@walk
                    val impure = effects.node(k) == Effect.IMPURE
                    hit = when {
                        model.opCalls[k]?.let { !binding(it) } == true -> own(impure, "runs an operator of the program's own")
                        k is PlaceAssignmentExpr -> place(k.target)
                        // A name assigned holds another value: the value it held is as it was.
                        k is AssignmentExpr -> null
                        k is CompoundAssignmentExpr -> if (scalar(model.types[k.left])) null else "changes a value in place"
                        k is FunctionCallExpr -> call(k)
                        k is ObjectInitExpr && runsCode(model, model.inits[k]?.cls) -> own(impure, "makes a `${model.inits[k]?.cls?.name}`, whose making runs code")
                        !impure || k is ThrowExpr -> null
                        else -> "has an effect that cannot be followed"
                    }
                }
            }
            return hit
        }
    }

    private fun key(line: Int, col: Int): Long = line.toLong() * 100_000L + col

    /** The edit of [loop], read off its tokens, or why its lines are not laid out as the rewrite needs. */
    private fun layout(module: ModuleSymbol, tokens: List<Token>, index: Map<Long, Int>, lines: List<String>, loop: WhileIterationStatement, found: Found): Any {
        val i = found.counter.name
        fun at(n: ASTNode): Int? = module.source.astOrigins[n]?.let { index[key(it.lineNumber, it.column)] }
        fun line(k: Int): Int = tokens[k].canonicalLocation.lineNumber
        fun col(k: Int): Int = tokens[k].canonicalLocation.column - 1
        fun text(k: Int): String = tokens[k].content
        fun close(open: Int): Int? {
            var depth = 0
            for (k in open until tokens.size) {
                when (tokens[k].type) {
                    Token.Type.S_OPEN_BRACE -> depth++
                    Token.Type.S_CLOSE_BRACE -> if (--depth == 0) return k
                    else -> {}
                }
            }
            return null
        }
        // Whether tokens a..b are all of their line: nothing before but indentation, nothing after, a comment neither.
        fun alone(a: Int, b: Int): Boolean {
            if (line(a) != line(b)) return false
            val raw = lines.getOrNull(line(a) - 1) ?: return false
            val end = col(b) + text(b).length
            return end <= raw.length && raw.substring(0, col(a)).isBlank() && raw.substring(end).isBlank()
        }
        // `i: Mutable<T> { a }`, alone on its line: the start of its own loop, and the type of every loop of the counter.
        val d = at(found.counter.decl!!) ?: return "the declaration of `$i` is not where the parser put it"
        if (text(d) != i || tokens.getOrNull(d + 1)?.type != Token.Type.S_COLON || tokens.getOrNull(d + 2)?.content != "Mutable" || tokens.getOrNull(d + 3)?.type != Token.Type.S_OPEN_ANGLE) {
            return "`$i` is not declared as `$i: Mutable<T> { a }`"
        }
        var open = d + 4
        while (open < tokens.size && tokens[open].type != Token.Type.S_OPEN_BRACE) open++
        val dEnd = close(open) ?: return "the declaration of `$i` does not close"
        if (tokens[open - 1].type != Token.Type.S_CLOSE_ANGLE || open - 1 <= d + 4 || dEnd <= open + 1) return "`$i` is not declared as `$i: Mutable<T> { a }`"
        if (!alone(d, dEnd)) return "the declaration of `$i` shares its line, spans lines or carries a comment"
        val declRaw = lines[line(d) - 1]
        val type = declRaw.substring(col(d + 4), col(open - 1)).trim()
        // `while i < n {`, on one line, directly after its start: the declaration, or `i = b` alone on its line.
        val w = at(loop) ?: return "the loop is not where the parser put it"
        var from = open + 1
        var until = dEnd
        var startLine = line(d)
        var start = declRaw.substring(col(open) + 1, col(dEnd)).trim()
        if (found.reset != null) {
            var r = w - 1
            while (r > 0 && line(r - 1) == line(w - 1)) r--
            if (w < 3 || text(r) != i || tokens[r + 1].type != Token.Type.S_EQUAL || r + 2 > w - 1) return "`$i` is not set as `$i = b` on one line before the loop"
            if (!alone(r, w - 1)) return "the line that sets `$i` shares its line or carries a comment"
            from = r + 2
            until = w
            startLine = line(r)
            start = lines[startLine - 1].substring(col(r + 2)).trim()
        } else if (w != dEnd + 1) {
            return "`$i` is not declared directly before the loop"
        }
        if (tokens[w].type != Token.Type.K_WHILE || tokens.getOrNull(w + 1)?.content != i) return "the condition is parenthesised"
        val op = tokens.getOrNull(w + 2)?.type
        if (op != Token.Type.S_OPEN_ANGLE && op != Token.Type.OP_CMP_LEQ) return "the condition is not written `$i < n`"
        if ((op == Token.Type.OP_CMP_LEQ) != found.inclusive) return "the condition is not written as it was read"
        var bodyOpen = w + 3
        var depth = 0
        while (bodyOpen < tokens.size) {
            val t = tokens[bodyOpen].type
            if (t == Token.Type.S_OPEN_PARENTHESIS || t == Token.Type.S_OPEN_BRACKET) depth++
            if (t == Token.Type.S_CLOSE_PARENTHESIS || t == Token.Type.S_CLOSE_BRACKET) depth--
            if (t == Token.Type.S_OPEN_BRACE) break
            bodyOpen++
        }
        if (bodyOpen >= tokens.size || depth != 0 || bodyOpen == w + 3) return "the end is not a plain expression"
        if (line(w) != line(bodyOpen)) return "the loop's head spans lines"
        val headRaw = lines[line(w) - 1]
        val bound = headRaw.substring(col(w + 3), col(bodyOpen)).trimEnd()
        val bodyClose = close(bodyOpen) ?: return "the loop's body does not close"
        // The step, alone on the body's last line, with no comment of its own above it.
        val forms = listOf(listOf(i, "+=", "1"), listOf(i, "=", i, "+", "1"), listOf(i, "=", "1", "+", i))
        val form = forms.firstOrNull { f -> bodyClose - f.size > bodyOpen && f.indices.all { text(bodyClose - f.size + it) == f[it] } }
            ?: return "the step is not written `$i += 1` or `$i = $i + 1`"
        val s = bodyClose - form.size
        if (!alone(s, bodyClose - 1) || line(bodyClose) == line(s) || line(s) == line(bodyOpen)) return "the step shares its line or carries a comment"
        if (lines.getOrNull(line(s) - 2)?.trimStart()?.startsWith("//") == true) return "a comment stands above the step"
        fun operand(text: String, from: Int, until: Int): String = if (ForFix.simpleOperand(tokens.subList(from, until))) text else "($text)"
        val head = "$i: $type in ${operand(start, from, until)}${if (found.inclusive) ".." else "..<"}${operand(bound, w + 3, bodyOpen)}"
        return Rewrite(startLine, line(w), col(w + 1), col(w + 3) + bound.length, head, line(s))
    }

    /** [source] with each of [rewrites] made: the declaration's line and the step's line go (with the blank lines left above the step), and the head is rewritten. */
    internal fun apply(source: String, rewrites: List<Rewrite>): String {
        val lines: MutableList<String?> = source.split(Regex("(?<=\n)")).toMutableList()
        for (r in rewrites) {
            val head = lines[r.whileLine - 1]!!
            lines[r.whileLine - 1] = head.substring(0, r.from) + r.head + head.substring(r.until)
            lines[r.declLine - 1] = null
            lines[r.stepLine - 1] = null
            var above = r.stepLine - 2
            while (above >= r.whileLine && lines[above]?.isBlank() == true) lines[above--] = null
        }
        return lines.filterNotNull().joinToString("")
    }

    /** The errors the typer has in [file] of [program], rendered. */
    private fun errorsIn(program: TypedProgram, file: String): List<String> =
        program.diagnostics.filter { it.isError && (it.source == null || it.source.file == file) }.map { it.render() }

    /** [srcs] typed, the counter loops of each of [keys] (a file to its text) rewritten, and the rewritten text typed again: one outcome per key. */
    private fun fix(srcs: List<TypedFix.Src>, keys: Map<String, String>, bootstrap: Boolean, options: TyperOptions = TyperOptions()): Map<String, Outcome> {
        val (_, program) = TypedFix.typeSources(srcs, bootstrap = bootstrap, options = options, mode = TyperMode.STRICT)
        val out = LinkedHashMap<String, Outcome>()
        val known = Known(program)
        for ((key, raw) in keys) {
            val module = program.workspaceModules.firstOrNull { it.source.file == key }
            val errors = errorsIn(program, key)
            if (module == null) {
                out[key] = Outcome(null, 0, emptyList(), "the project above it does not read it as a module (it does not parse, or the project leaves it out)")
            } else if (errors.isNotEmpty()) {
                out[key] = Outcome(null, 0, emptyList(), "it does not type, so nothing is proved of its loops: ${errors.first()}")
            } else {
                val (rewrites, left) = find(program, module, raw, known)
                out[key] = Outcome(apply(raw, rewrites), rewrites.size, left, null)
            }
        }
        return settle(srcs, out, bootstrap, options)
    }

    /**
     * [proposed] with every rewritten file checked: the program is typed again with the rewritten texts, and a file that has an
     * error then (a loop variable is never written, and its range has one type) is left whole.
     */
    internal fun settle(srcs: List<TypedFix.Src>, proposed: Map<String, Outcome>, bootstrap: Boolean, options: TyperOptions = TyperOptions()): Map<String, Outcome> {
        val texts = proposed.filterValues { it.fixed != 0 && it.text != null }.mapValues { it.value.text!! }
        if (texts.isEmpty()) return proposed
        val out = LinkedHashMap(proposed)
        val (_, again) = TypedFix.typeSources(srcs, texts = texts, bootstrap = bootstrap, options = options, mode = TyperMode.STRICT)
        for (key in texts.keys) {
            val errors = errorsIn(again, key)
            val gone = again.workspaceModules.none { it.source.file == key }
            if (gone || errors.isNotEmpty()) {
                out[key] = Outcome(null, 0, out.getValue(key).left, "the rewritten file does not type, so it is left whole: ${errors.firstOrNull() ?: "it does not parse"}")
            }
        }
        return out
    }

    /** [files] rewritten (not written), each by the project above it. */
    fun fixFiles(files: List<File>): Map<File, Outcome> {
        val out = LinkedHashMap<File, Outcome>()
        for ((dir, group) in files.groupBy { TypedFix.projectDirOf(it) }) {
            if (dir == null) {
                group.forEach { out[it] = Outcome(null, 0, emptyList(), "no project.kira above it, so no project to type it in") }
                continue
            }
            try {
                out += TypedFix.withProject(dir) { project, srcs ->
                    // Typed as the project's own build types it: the modules its C++ target holds to the freestanding profile.
                    val cpp = project.cpp
                    val options = TyperOptions(cpp.freestanding, cpp.headerOnly, cpp.namespaces)
                    val done = fix(srcs, group.associate { it.canonicalPath to it.readText() }, bootstrap = false, options = options)
                    group.associateWith { done.getValue(it.canonicalPath) }
                }
            } catch (e: Throwable) {
                group.forEach { out[it] = Outcome(null, 0, emptyList(), "the project at $dir did not load: ${e.message?.lineSequence()?.firstOrNull()}") }
            }
        }
        return files.associateWith { out.getValue(it) }
    }

    /** One module's [text] rewritten, against the stdlib under `./kira`: for tests. */
    fun fixSnippet(text: String, uri: String = "test:main"): Outcome {
        val src = snippetSrc(text, uri)
        return fix(listOf(src), mapOf(src.file to text), bootstrap = true).getValue(src.file)
    }

    /** [text] as the one module [uri] of a program, as [fixSnippet] reads it. */
    internal fun snippetSrc(text: String, uri: String = "test:main"): TypedFix.Src {
        val (pkg, dotted) = uri.split(":", limit = 2)
        return TypedFix.Src("$pkg/${dotted.replace('.', '/')}.kira", text, uri, true)
    }
}
