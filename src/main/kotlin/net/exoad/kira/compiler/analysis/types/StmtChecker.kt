package net.exoad.kira.compiler.analysis.types

import net.exoad.kira.compiler.frontend.parser.ast.declarations.Decl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.FunctionDecl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.BinaryOp
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.elements.Modifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ArrayIndexExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.BinaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IfExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.NoExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ObjectInitExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.RangeExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThisExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TryExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TypeCastExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.UnaryExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.ArrayLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.StringLiteral
import net.exoad.kira.compiler.frontend.parser.ast.statements.BreakStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ContinueStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.DoWhileIterationStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ElseBranchStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ElseIfBranchStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ForIterationStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.IfSelectionStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.ReturnStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.Statement
import net.exoad.kira.compiler.frontend.parser.ast.statements.UseStatement
import net.exoad.kira.compiler.frontend.parser.ast.statements.WhileIterationStatement

/**
 * Phase C of the typer (design 3.2): types every body the program holds, filling every
 * TypedModel table but `effects`, `fnEffects`, `fxEscapes` and `viewEscapes` (the rule passes'
 * own). Registered as [KiraTyper.bodyTyper].
 */
internal object KiraBodyTyper : BodyTyper {
    override fun type(program: TypedProgram) {
        PhaseC(program).run()
    }
}

/**
 * Declarations and statements: every function, method and trait default body, every class's
 * `initially` and `finally`, every field and parameter default, every module-level
 * initializer, and the module-level `@_static_assert`s.
 *
 * - **Module initializers.** A constant (a global without `mut`) is a compile-time value; a Str
 *   constant must fold to a literal (design 3.3), and a `mut` global starts from a constant
 *   expression (D49). A constant that phase B could not fold but phase C can (an integer
 *   literal in a float constant) gets its `constValue` here.
 * - **Defaults (D48)** are literals or constants; a `pub` function's default names only `pub`
 *   constants, since C++ callers see it in the header.
 * - **Loops.** `for i: T in a..b` is exclusive and `T` types both bounds; `for x: T in xs`
 *   iterates an Arr, List, Set, View or Map (as `Tuple2<K, V>`), and `T` is the element type
 *   exactly. The legacy `for mut i: a..b` keeps its inclusive meaning with a deprecation
 *   warning (D17). Each gets a [LoopPlan].
 * - **Assignment** targets are places; writing a lambda's capture is an error.
 */
internal class StmtChecker(private val c: PhaseC) {
    private val model get() = c.model
    private val facts get() = c.facts

    /** Loop nesting of the body being typed; a lambda starts its own count. */
    private val loopDepth = ArrayDeque<Int>()

    private val resultFns = HashMap<String, FnSymbol>()

    fun all() {
        for (m in c.program.modules) {
            for (s in m.declarations) {
                KiraTyper.guard(c.program, "typing ${s.qualifiedName}", s.decl) { declaration(s) }
            }
            for (st in m.statements) {
                KiraTyper.guard(c.program, "typing a module statement of ${m.uri}", st) { moduleStatement(st, m) }
            }
        }
    }

    private fun declaration(s: Symbol) {
        when (s) {
            is GlobalSymbol -> global(s)
            is FnSymbol -> function(s)
            is ClassSymbol -> {
                s.fields.forEach { f -> f.default?.let { fieldDefault(f, it) } }
                val initCtx = BodyContext(s.module, null, s, KType.Void, null, true, "${s.name}'s initializer block")
                s.initially?.let { body(it, initCtx, Scope.root()) }
                s.finally?.let { body(it, BodyContext(s.module, null, s, KType.Void, null, true, "${s.name}'s finally block"), Scope.root()) }
                s.methods.forEach { m -> KiraTyper.guard(c.program, "typing ${m.qualifiedName}", m.decl) { function(m) } }
            }
            is TraitSymbol -> s.methods.forEach { m -> KiraTyper.guard(c.program, "typing ${m.qualifiedName}", m.decl) { function(m) } }
            is EnumSymbol -> s.decl?.members?.forEach { member ->
                // Phase B folded each value against the base type; the value expression has that type.
                val v = member.value ?: return@forEach
                if (model.consts[v]?.type == s.base) {
                    model.types[v] = s.base
                }
            }
            else -> {}
        }
    }

    private fun moduleContext(m: ModuleSymbol, what: String): BodyContext =
        BodyContext(m, null, null, KType.Void, null, false, what)

    /** Errors (and, in LENIENT mode, their warnings) so far: a check that follows one says nothing more. */
    private fun errorCount(): Int = c.program.diagnostics.count { it.isError || (c.program.mode == TyperMode.LENIENT && it.severity == Severity.WARNING) }

    // ---- module-level ------------------------------------------------------------------------

    private fun global(g: GlobalSymbol) {
        val init = g.init ?: return
        if (g.foreign is Foreign.Magic && g.module.isStdlib) {
            return
        }
        val what = "the initializer of '${g.name}'"
        val ctx = moduleContext(g.module, what)
        val errorsBefore = errorCount()
        c.exprs.check(init, g.type, ctx, Scope.root(), what)
        if (g.type.containsError() || model.types[init]?.containsError() == true || errorCount() > errorsBefore) {
            return
        }
        val value = model.consts[init]
        if (g.isMut) {
            if (!isConstantExpr(init)) {
                c.report(
                    "types.global.mut-init",
                    "A module-level mut variable starts from a constant expression (D49), so no static-initialization order " +
                        "can matter; '${g.name}' starts from ${KiraUnparser.text(init)}.",
                    init,
                )
            }
            return
        }
        if (g.type == KType.Str) {
            if (value !is ConstValue.StrConst) {
                c.report(
                    "types.const.str-literal",
                    "A Str constant must be a compile-time literal (a literal, or + of literal constants); " +
                        "'${g.name}' is ${KiraUnparser.text(init)}.",
                    init,
                )
            }
        } else if (!isConstantExpr(init)) {
            c.report(
                "types.const.not-constant",
                "'${g.name}' is a module constant, so its value is fixed at compile time; ${KiraUnparser.text(init)} is not. " +
                    "Declare it `mut` for state, or compute it where it is used.",
                init,
            )
        }
        if (g.constValue == null && value != null && (value.type == g.type || (facts.isMaybe(g.type) && value is ConstValue.NullConst))) {
            g.constValue = value
        }
    }

    /**
     * An expression C++ can evaluate at compile time: it folded, or it is built from constants
     * with operators, constructions of structs, fixed arrays and tuples, `@_const` calls and
     * magic calls on constants (whose `constexpr` binding the C++ compiler checks).
     */
    fun isConstantExpr(e: Expr): Boolean {
        if (model.consts[e] != null) {
            return true
        }
        return when (e) {
            is BinaryExpr -> isConstantExpr(e.leftExpr) && isConstantExpr(e.rightExpr)
            is UnaryExpr -> isConstantExpr(e.operand)
            is TypeCastExpr -> isConstantExpr(e.value)
            is Identifier -> (model.refs[e] as? GlobalSymbol)?.let { it.isConstant && !it.isMut } ?: false
            is MemberAccessExpr -> when (val m = model.members[e]) {
                is MemberRef.EnumEntry -> true
                is MemberRef.Field -> isConstantExpr(e.origin)
                is MemberRef.ModuleMember -> (m.symbol as? GlobalSymbol)?.isConstant == true
                null -> (e.member as? FunctionCallExpr)?.let { isConstantExpr(it) } ?: false
                else -> false
            }
            is FunctionCallExpr -> {
                val rc = model.calls[e] ?: return false
                val fn = rc.fn ?: return false
                val ok = fn.isConst || rc.kind == CallKind.MAGIC
                ok && rc.args.all { it !is ArgBinding.Given || isConstantExpr(it.expr) } && (rc.receiver?.let { isConstantExpr(it) } ?: true)
            }
            is ObjectInitExpr -> {
                val ri = model.inits[e] ?: return false
                val cls = ri.cls ?: return false
                val literal = cls.kind == ClassKind.STRUCT ||
                    (cls.kind == ClassKind.MAGIC && (facts.isFixedArr(ri.type) || Builtins.tupleArity(cls.name) != null || cls.name == "StrBuf"))
                literal && ri.fields.all { it !is FieldInit.Given || isConstantExpr(it.expr) }
            }
            is ArrayLiteral -> e.value.all { isConstantExpr(it) }
            is ArrayIndexExpr -> isConstantExpr(e.originExpr) && isConstantExpr(e.indexExpr)
            is IfExpr -> isConstantExpr(e.condition) && listOf(e.thenBranch, e.elseBranch).all { b ->
                b.size == 1 && b[0].javaClass == Statement::class.java && isConstantExpr(b[0].expr)
            }
            else -> false
        }
    }

    private fun moduleStatement(st: Statement, m: ModuleSymbol) {
        val e = st.expr
        if (e is IntrinsicExpr && e.intrinsicKey.name == "_static_assert") {
            staticAssert(e, m)
            return
        }
        statement(st, moduleContext(m, "module ${m.uri}"), Scope.root())
    }

    /** `@_static_assert(cond, "why")` (D7): cond is a Bool constant expression, and must hold when it folds. */
    private fun staticAssert(e: IntrinsicExpr, m: ModuleSymbol) {
        val ctx = moduleContext(m, "a static assert")
        val params = e.parameters ?: emptyList()
        model.types[e] = KType.Void
        val cond = params.firstOrNull() ?: return
        val errorsBefore = errorCount()
        c.exprs.check(cond, KType.BOOL, ctx, Scope.root(), "a static assert's condition")
        val condFailed = errorCount() > errorsBefore
        val message = params.getOrNull(1)
        if (message != null) {
            c.exprs.check(message, KType.Str, ctx, Scope.root(), "a static assert's message")
            if (message !is StringLiteral) {
                c.report("types.static-assert.message", "A static assert's message is a string literal.", message)
            }
        }
        if (model.types[cond]?.containsError() == true || condFailed) {
            return
        }
        val value = model.consts[cond]
        if (value is ConstValue.BoolConst && !value.value) {
            c.report(
                "types.static-assert.failed",
                "This static assertion is false" + ((message as? StringLiteral)?.let { ": ${it.value}" } ?: "."),
                e,
            )
        } else if (value == null && !isConstantExpr(cond)) {
            c.report(
                "types.static-assert.not-constant",
                "A static assert's condition must be known at compile time: constants, literals, operators and @_const calls.",
                cond,
            )
        }
    }

    // ---- functions ---------------------------------------------------------------------------

    fun function(fn: FnSymbol) {
        if (fn.owner == null && fn.name == "main" && !fn.module.isStdlib) {
            entryPoint(fn)
        }
        fn.params.forEach { p -> p.default?.let { paramDefault(fn, p, it) } }
        val body = fn.body ?: return
        val owner = fn.owner
        val struct = (owner as? ClassSymbol)?.kind == ClassKind.STRUCT
        val ctx = BodyContext(
            fn.module, fn, owner, fn.ret, null,
            thisMutable = !struct || fn.isMutMethod,
            what = if (owner != null) "${owner.name}.${fn.name}" else "'${fn.name}'",
        )
        val scope = Scope.root()
        fn.params.forEach { scope.declare(it.name, it) }
        body(body, ctx, scope)
    }

    /** D23: `fx main` is `() Void`, `() Int32` or `(args: List<Str>) Int32`. */
    private fun entryPoint(fn: FnSymbol) {
        if (fn.ret.containsError() || fn.params.any { it.type.containsError() }) {
            return
        }
        val ok = when (fn.params.size) {
            0 -> fn.ret == KType.Void || fn.ret == KType.INT32
            1 -> {
                val p = fn.params.single()
                !p.byRef && facts.isList(p.type) && facts.elementOf(p.type) == KType.Str && fn.ret == KType.INT32
            }
            else -> false
        }
        if (!ok) {
            c.report(
                "types.main.signature",
                "An entry point is `fx main: () Void`, `fx main: () Int32` or `fx main: (args: List<Str>) Int32` (D23); " +
                    "this one is (${fn.params.joinToString(", ") { it.type.display() }}) ${fn.ret.display()}.",
                fn.decl,
            )
        }
    }

    private fun body(statements: List<Statement>, ctx: BodyContext, scope: Scope) {
        loopDepth.addLast(0)
        try {
            block(statements, ctx, scope)
        } finally {
            loopDepth.removeLast()
        }
    }

    /** A lambda's body: its own loop nesting, its own return type. */
    fun lambdaBody(statements: List<Statement>, ctx: BodyContext, scope: Scope) = body(statements, ctx, scope)

    private fun paramDefault(fn: FnSymbol, p: ParamSymbol, d: Expr) {
        val what = "the default of '${p.name}'"
        val errorsBefore = errorCount()
        c.exprs.check(d, p.type, moduleContext(fn.module, what), Scope.root(), what)
        if (model.types[d]?.containsError() == true || p.type.containsError() || errorCount() > errorsBefore) {
            return
        }
        val private = privateConstant(d)
        when {
            !isDefaultShape(d) -> c.report(
                "types.default.not-constant",
                "A default is a literal or a constant (D48); ${KiraUnparser.text(d)} is neither.",
                d,
            )
            fn.isPub && private != null -> c.report(
                "types.default.private",
                "'${fn.name}' is pub, so its C++ callers see this default in the header; '${private.name}' is not pub. " +
                    "Make the constant pub or write the literal (D48).",
                d,
            )
        }
    }

    /** D48: a literal (signed or not), `true`/`false`/`null`, a constant, or an enum entry. */
    private fun isDefaultShape(d: Expr): Boolean = when (d) {
        is Identifier -> (model.refs[d] as? GlobalSymbol)?.isConstant == true
        is MemberAccessExpr -> model.members[d] is MemberRef.EnumEntry || (model.members[d] as? MemberRef.ModuleMember)?.symbol.let { it is GlobalSymbol && it.isConstant }
        is UnaryExpr -> c.literals.isBareLiteral(d)
        else -> c.literals.isBareLiteral(d) || d is StringLiteral || d is net.exoad.kira.compiler.frontend.parser.ast.literals.CharLiteral
    }

    /** The non-pub constant or enum a default names, if any. */
    private fun privateConstant(d: Expr): Symbol? = when (d) {
        is Identifier -> (model.refs[d] as? GlobalSymbol)?.takeIf { !it.isPub && !it.module.isStdlib }
        is MemberAccessExpr -> (model.members[d] as? MemberRef.EnumEntry)?.entry?.owner?.takeIf { !it.isPub && !it.module.isStdlib }
        else -> null
    }

    private fun fieldDefault(f: FieldSymbol, d: Expr) {
        val what = "the default of field '${f.name}'"
        c.exprs.check(d, f.type, moduleContext(f.module, what), Scope.root(), what)
    }

    // ---- statements --------------------------------------------------------------------------

    fun block(statements: List<Statement>, ctx: BodyContext, scope: Scope) {
        statements.forEach { statement(it, ctx, scope) }
    }

    fun statement(s: Statement, ctx: BodyContext, scope: Scope) {
        when (s) {
            is ReturnStatement -> returnStatement(s, ctx, scope)
            is IfSelectionStatement -> {
                c.exprs.check(s.expr, KType.BOOL, ctx, scope, "an if condition")
                block(s.thenStatements, ctx, scope.child())
                for (b in s.elseBranches) {
                    when (b) {
                        is ElseIfBranchStatement -> {
                            c.exprs.check(b.condition, KType.BOOL, ctx, scope, "an else-if condition")
                            block(b.statements, ctx, scope.child())
                        }
                        is ElseBranchStatement -> block(b.statements, ctx, scope.child())
                    }
                }
            }
            is WhileIterationStatement -> {
                c.exprs.check(s.condition, KType.BOOL, ctx, scope, "a while condition")
                loopBody(s.statements, ctx, scope.child())
            }
            is DoWhileIterationStatement -> {
                loopBody(s.statements, ctx, scope.child())
                c.exprs.check(s.condition, KType.BOOL, ctx, scope, "a do-while condition")
            }
            is ForIterationStatement -> forLoop(s, ctx, scope)
            is BreakStatement, is ContinueStatement -> {
                if ((loopDepth.lastOrNull() ?: 0) == 0) {
                    c.report(
                        "types.loop.outside",
                        "${if (s is BreakStatement) "break" else "continue"} is only meaningful inside a loop" +
                            if (ctx.lambda != null) " of the same lambda." else ".",
                        s,
                    )
                }
            }
            is UseStatement -> c.report("types.use.position", "A use statement goes at the top of a module.", s)
            else -> expressionStatement(s.expr, ctx, scope)
        }
    }

    private fun loopBody(statements: List<Statement>, ctx: BodyContext, scope: Scope) {
        val depth = loopDepth.removeLastOrNull() ?: 0
        loopDepth.addLast(depth + 1)
        try {
            block(statements, ctx, scope)
        } finally {
            loopDepth.removeLast()
            loopDepth.addLast(depth)
        }
    }

    private fun expressionStatement(e: Expr, ctx: BodyContext, scope: Scope) {
        when (e) {
            is VariableDecl -> local(e, ctx, scope)
            is FunctionDecl -> c.report(
                "types.decl.nested",
                "A function is declared at module level or in a type; inside a body, write a lambda: f: Fx<...> = fx(...) R { ... }.",
                e,
            )
            is Decl -> c.report("types.decl.nested", "A type is declared at module level, not inside a body.", e)
            NoExpr -> {}
            else -> c.exprs.synth(e, ctx, scope)
        }
    }

    private fun local(decl: VariableDecl, ctx: BodyContext, scope: Scope) {
        val type = c.typeOf(decl.type)
        val name = decl.name.value
        decl.value?.let { c.exprs.check(it, type, ctx, scope, "the initializer of '$name'") }
        val local = LocalSymbol(name, ctx.module, decl, type, isMut = Modifier.MUTABLE in decl.modifiers, fn = ctx.fn)
        model.declSyms[decl] = local
        model.refs[decl.name] = local
        ctx.lambda?.let { c.declaredIn[local] = it }
        if (scope.declare(name, local) != null) {
            c.report("types.local.duplicate", "'$name' is already declared in this block.", decl)
        }
    }

    private fun returnStatement(s: ReturnStatement, ctx: BodyContext, scope: Scope) {
        val e = s.expr
        val ret = ctx.returnType
        if (ret == KType.Void || ret.containsError()) {
            if (e !== NoExpr) {
                val t = c.exprs.synth(e, ctx, scope)
                if (ret == KType.Void && t != KType.Void && t != KType.Never && !t.containsError()) {
                    c.report("types.return.value", "${ctx.what} returns Void, so its return takes no value; this one is ${t.display()}.", e)
                }
            }
            return
        }
        if (e === NoExpr) {
            c.report("types.return.missing", "${ctx.what} returns ${ret.display()}: this return needs a value.", s)
            return
        }
        c.exprs.check(e, ret, ctx, scope, "the return value of ${ctx.what}")
    }

    // ---- loops -------------------------------------------------------------------------------

    private fun forLoop(s: ForIterationStatement, ctx: BodyContext, scope: Scope) {
        val fe = s.forIterationExpr
        val target = fe.target
        val name = fe.initializer.value
        val kind: LoopKind
        val element: KType
        if (fe.isLegacy) {
            c.report(
                "types.for.legacy",
                "`for mut $name: a..b` includes b and is deprecated (D17); write `for $name: T in a..(b + 1)`, whose range excludes its end.",
                fe,
                Severity.WARNING,
            )
            if (target is RangeExpr) {
                val (lt, rt) = c.exprs.operands(target.begin, target.end, null, ctx, scope)
                element = rangeElement(target, lt, rt)
                kind = LoopKind.RANGE_INCLUSIVE
            } else {
                val (k, el) = iteration(target, c.exprs.synth(target, ctx, scope))
                kind = k
                element = el
            }
        } else {
            val declared = fe.declaredType?.let { c.typeOf(it) } ?: KType.Error
            if (target is RangeExpr) {
                c.exprs.check(target.begin, declared, ctx, scope, "the start of the range")
                c.exprs.check(target.end, declared, ctx, scope, "the end of the range")
                if (!declared.containsError() && !facts.isInteger(declared)) {
                    c.report("types.for.range-type", "A range counts integers; ${declared.display()} is not an integer type.", fe)
                }
                kind = LoopKind.RANGE
                element = declared
            } else {
                val (k, el) = iteration(target, c.exprs.synth(target, ctx, scope))
                kind = k
                element = el
                if (!el.containsError() && !declared.containsError() && el != declared) {
                    c.report(
                        "types.for.element",
                        "The loop variable '$name' is ${declared.display()}, but the elements are ${el.display()}: they must be the same type.",
                        fe,
                    )
                }
            }
        }
        val varType = if (fe.isLegacy) element else fe.declaredType?.let { c.typeOf(it) } ?: element
        val variable = LocalSymbol(name, ctx.module, fe, varType, isMut = fe.isLegacy, fn = ctx.fn)
        model.declSyms[fe] = variable
        model.refs[fe.initializer] = variable
        ctx.lambda?.let { c.declaredIn[variable] = it }
        model.loops[s] = LoopPlan(kind, element, variable, fe.isLegacy)
        val bodyScope = scope.child()
        bodyScope.declare(name, variable)
        loopBody(s.body, ctx, bodyScope)
    }

    private fun rangeElement(r: RangeExpr, lt: KType, rt: KType): KType {
        if (lt.containsError() || rt.containsError()) {
            return KType.Error
        }
        if (lt != rt || !facts.isInteger(lt)) {
            c.report("types.for.range-type", "A range's ends are integers of one type; this one is ${lt.display()}..${rt.display()}.", r)
            return KType.Error
        }
        return lt
    }

    /** What iterating a value of type [t] yields. */
    private fun iteration(target: Expr, t: KType): Pair<LoopKind, KType> {
        if (t.containsError()) {
            return LoopKind.ARR to KType.Error
        }
        val element = facts.elementOf(t)
        return when {
            facts.isArr(t) && element != null -> LoopKind.ARR to element
            facts.isList(t) && element != null -> LoopKind.LIST to element
            facts.isMagic(t, "Set") && element != null -> LoopKind.SET to element
            (facts.isView(t) || facts.isMutView(t)) && element != null -> LoopKind.VIEW to element
            facts.isMap(t) -> {
                val args = (t as KType.Nominal).typeArgs()
                LoopKind.MAP to facts.tuple2Of(args.getOrElse(0) { KType.Error }, args.getOrElse(1) { KType.Error })
            }
            else -> {
                c.report(
                    "types.for.not-iterable",
                    "${t.display()} cannot be iterated; a for loop takes a range, an Arr, a List, a Set, a View or a Map" +
                        if (t == KType.Str) " (for a Str, iterate s.view())." else ".",
                    target,
                )
                LoopKind.ARR to KType.Error
            }
        }
    }

    // ---- assignment --------------------------------------------------------------------------

    fun assignment(e: AssignmentExpr, ctx: BodyContext, scope: Scope): KType {
        val tt = c.exprs.synth(e.target, ctx, scope)
        writable(e.target, tt, ctx)
        c.exprs.check(e.value, tt, ctx, scope, "the value assigned to '${e.target.value}'")
        return KType.Void
    }

    fun compoundAssignment(e: CompoundAssignmentExpr, ctx: BodyContext, scope: Scope): KType {
        val lt = c.exprs.synth(e.left, ctx, scope)
        writable(e.left, lt, ctx)
        compound(e, e.operator, lt, e.right, ctx, scope)
        return KType.Void
    }

    fun placeAssignment(e: PlaceAssignmentExpr, ctx: BodyContext, scope: Scope): KType {
        val target = e.target
        val tt = if (target is ArrayIndexExpr) {
            c.exprs.index(target, ctx, scope, forWrite = e.operator == null).also {
                model.types[target] = it
                c.folder.fold(target)
            }
        } else {
            c.exprs.synth(target, ctx, scope)
        }
        if (target is ThisExpr) {
            c.report("types.assign.this", "`this` is the receiver; it cannot be reassigned. Assign its fields instead.", target)
        } else {
            writable(target, tt, ctx)
        }
        val op = e.operator
        if (op == null) {
            c.exprs.check(e.value, tt, ctx, scope, "the value assigned to ${KiraUnparser.text(target)}")
        } else {
            compound(e, op, tt, e.value, ctx, scope)
        }
        return KType.Void
    }

    /** The target of an assignment is a place, and not a lambda's capture. */
    private fun writable(target: Expr, t: KType, ctx: BodyContext) {
        if (t.containsError()) {
            return
        }
        val place = model.places[target]
        if (place == null) {
            c.report(
                "types.assign.not-place",
                "${KiraUnparser.text(target)} cannot be assigned: an assignment writes a variable, a field or an element.",
                target,
            )
            return
        }
        if (c.writesCapture(place, ctx)) {
            c.report(
                "types.lambda.assign-capture",
                "${KiraUnparser.text(target)} is captured by this lambda, and a capture is an immutable copy (spec); " +
                    "share mutable state through a Ref<T> instead.",
                target,
            )
        }
    }

    /** `x op= y`: the operator's rule with `x`'s type on both sides (a shift count may be any integer). */
    private fun compound(node: Expr, op: BinaryOp, lt: KType, right: Expr, ctx: BodyContext, scope: Scope) {
        val sym = op.symbol.joinToString("") { it.rep.toString() }
        if (lt.containsError()) {
            c.exprs.synth(right, ctx, scope)
            return
        }
        if (op in LiteralTyper.SHIFTS) {
            val rt = c.exprs.synth(right, ctx, scope)
            if (!rt.containsError() && (!facts.isInteger(lt) || !facts.isInteger(rt))) {
                c.report("types.op.mismatch", "${lt.display()} $sym= ${rt.display()}: a shift takes an integer and an integer count.", node)
            }
            return
        }
        val rt = c.exprs.type(right, lt, ctx, scope)
        if (rt.containsError()) {
            return
        }
        val why: String? = when {
            rt != lt -> "both sides of $sym= have one type: no widening, no int with float"
            lt == KType.Str -> if (op == BinaryOp.ADD) null else "a Str takes += only"
            lt == KType.CHAR -> "a Char has comparisons only (D3)"
            op in LiteralTyper.BITS -> if (facts.isInteger(lt)) null else "bitwise operators take integers"
            !facts.isNumeric(lt) -> "${lt.display()} has no $sym"
            op == BinaryOp.MOD && facts.isFloat(lt) -> "% takes integers"
            else -> null
        }
        if (why != null) {
            c.report("types.op.mismatch", "${lt.display()} $sym= ${rt.display()} is not an operation Kira has: $why.", node)
        }
    }

    // ---- try ---------------------------------------------------------------------------------

    fun tryExpr(e: TryExpr, ctx: BodyContext, scope: Scope) {
        block(e.tryBlock, ctx, scope.child())
        val name = e.exceptionName ?: run {
            block(e.handlerBlock, ctx, scope.child())
            return
        }
        val declared = e.exceptionType?.let { c.typeOf(it) } ?: KType.Str
        if (!declared.containsError() && declared != KType.Str) {
            c.report("types.try.type", "A thrown value is a Str: write `on ${name.value}: Str`.", e.exceptionType ?: e)
        }
        val handler = scope.child()
        val local = LocalSymbol(name.value, ctx.module, name, KType.Str, isMut = false, fn = ctx.fn)
        model.declSyms[name] = local
        model.refs[name] = local
        ctx.lambda?.let { c.declaredIn[local] = it }
        handler.declare(name.value, local)
        block(e.handlerBlock, ctx, handler)
    }

    // ---- D39 ---------------------------------------------------------------------------------

    /**
     * `Result.success` / `Result.error` (D39) have no Kira declaration; phase C gives each one
     * magic FnSymbol on the Result class, keyed `Result.success` / `Result.error` for the
     * binding table.
     */
    fun resultFn(cls: ClassSymbol, which: String): FnSymbol = resultFns.getOrPut(which) {
        val tp = if (which == "success") cls.typeParams.getOrNull(0) else cls.typeParams.getOrNull(1)
        val param = ParamSymbol("value", cls.module, null, type = tp?.let { KType.Param(it) } ?: KType.Error, index = 0)
        val fn = FnSymbol(
            which, cls.module, null, emptyList(), listOf(param), cls.selfType,
            owner = cls, foreign = Foreign.Magic("Result.$which"),
        )
        fn.isPub = true
        param.fn = fn
        fn
    }
}
