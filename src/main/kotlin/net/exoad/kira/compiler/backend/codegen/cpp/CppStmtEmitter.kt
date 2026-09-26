package net.exoad.kira.compiler.backend.codegen.cpp

import net.exoad.kira.compiler.analysis.types.ArgBinding
import net.exoad.kira.compiler.analysis.types.AstTree
import net.exoad.kira.compiler.analysis.types.CallKind
import net.exoad.kira.compiler.analysis.types.ClassSymbol
import net.exoad.kira.compiler.analysis.types.EnumSymbol
import net.exoad.kira.compiler.analysis.types.FnSymbol
import net.exoad.kira.compiler.analysis.types.KType
import net.exoad.kira.compiler.analysis.types.LocalSymbol
import net.exoad.kira.compiler.analysis.types.LoopKind
import net.exoad.kira.compiler.analysis.types.ParamSymbol
import net.exoad.kira.compiler.analysis.types.Symbol
import net.exoad.kira.compiler.analysis.types.TypeSymbol
import net.exoad.kira.compiler.frontend.parser.ast.ASTNode
import net.exoad.kira.compiler.frontend.parser.ast.declarations.Decl
import net.exoad.kira.compiler.frontend.parser.ast.declarations.VariableDecl
import net.exoad.kira.compiler.frontend.parser.ast.elements.Identifier
import net.exoad.kira.compiler.frontend.parser.ast.expressions.AssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.CompoundAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.Expr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionCallExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IfExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.IntrinsicExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.MemberAccessExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.NoExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.PlaceAssignmentExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.RangeExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.ThrowExpr
import net.exoad.kira.compiler.frontend.parser.ast.expressions.TryExpr
import net.exoad.kira.compiler.frontend.parser.ast.literals.ArrayLiteral
import net.exoad.kira.compiler.frontend.parser.ast.literals.InterpolatedStringLiteral
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
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Statements and function bodies (design 5.4).
 *
 * - A scalar or enum local is brace-initialized (`const std::int32_t x{e};`), so a narrowing
 *   mistake is a hard error on every compiler [A-M5]; anything else is copy-initialized
 *   (`const Reply r = e;`), which avoids the `initializer_list` trap. A local without `mut` is
 *   `const`.
 * - `for i: T in a..b` evaluates its bound once (`i_end`), unless the bound is a literal or a
 *   constant; `for x: T in xs` is a range-for (by value for a scalar, by `const&` otherwise);
 *   the legacy `for mut i: a..b` keeps its inclusive bound (D17, the typer warns).
 * - A place assignment writes the lvalue (`kira::at(p, 30) = ...`); a discarded non-Void
 *   result is `static_cast<void>(...)` (R8: every non-Void function is `[[nodiscard]]`).
 * - `throw` is `throw kira::Error{...}` hosted and `kira::panic(...)` freestanding (D41);
 *   `try ... on e: Str` catches `kira::Error`, and is refused freestanding.
 * - Each statement is preceded by its `#line` when the options ask for them.
 *
 * Bodies are written as text with relative indentation, then handed to the writer line by
 * line, so a statement that holds a multi-line lambda indents it like any block.
 */
class CppStmtEmitter : CppStmtPart {
    override fun emit(ctx: CppEmitContextImpl, s: Statement, w: CppWriter) {
        w.lines(render(ctx, s).joinToString("\n"))
    }

    override fun body(ctx: CppEmitContextImpl, fn: FnSymbol?, statements: List<Statement>, w: CppWriter) {
        val lower = CppLowering.of(ctx)
        val state = lower.state
        val owner: TypeSymbol? = fn?.owner ?: ctx.scope
        val frame = CppBodyState.Frame(fn, owner, null)
        state.placed(headerPlaced(ctx, fn, owner)) {
            state.inFrame(frame) {
                reads.getOrPut(ctx) { IdentityHashMap() }.putAll(readsIn(ctx, statements))
                fn?.params?.forEach { state.bind(it, ctx.paramName(it)) }
                statements.forEach { emit(ctx, it, w) }
            }
        }
    }

    /** Whether the definition of [fn] (a member of [owner], or a class block when [fn] is null) is written in the header. */
    private fun headerPlaced(ctx: CppEmitContextImpl, fn: FnSymbol?, owner: TypeSymbol?): Boolean {
        if (ctx.isHeaderOnly) {
            return true
        }
        val placement = ctx.placement
        val cls = owner as? ClassSymbol
        return when {
            fn == null -> owner == null || runCatching { placement.type(owner as Symbol).def != Home.SOURCE }.getOrDefault(true)
            owner == null -> placement.function(fn).def != Home.SOURCE
            cls != null && cls.isStruct -> placement.method(cls, fn).def != Home.SOURCE
            else -> fn.isConst || placement.isTemplate(fn, cls) ||
                runCatching { placement.type(owner as Symbol).def != Home.SOURCE }.getOrDefault(true)
        }
    }

    // ---- rendering -------------------------------------------------------------------------------

    /** The lines of [s] (relative indentation), its `#line` first when enabled. */
    fun render(ctx: CppEmitContextImpl, s: Statement): List<String> {
        val out = mutableListOf<String>()
        ctx.lineDirective(s)?.let { out += it }
        out += when (s) {
            is ReturnStatement -> returnStatement(ctx, s)
            is IfSelectionStatement -> ifStatement(ctx, s)
            is WhileIterationStatement -> listOf(CppWriter.whileHead(cond(ctx, s.condition))) + block(ctx, s.statements)
            is DoWhileIterationStatement -> listOf("do") + block(ctx, s.statements) + "while(${cond(ctx, s.condition)});"
            is ForIterationStatement -> forStatement(ctx, s)
            is BreakStatement -> listOf("break;")
            is ContinueStatement -> listOf("continue;")
            is UseStatement -> {
                ctx.unsupported(s, "a use statement inside a body")
                emptyList()
            }
            else -> expressionStatement(ctx, s, s.expr)
        }
        return out
    }

    /** A braced block of [statements] in a nested scope: `{`, the body indented, `}`. */
    fun block(ctx: CppEmitContextImpl, statements: List<Statement>): List<String> {
        val state = CppLowering.of(ctx).state
        val inner = state.block { statements.flatMap { render(ctx, it) } }
        return listOf("{") + inner.flatMap { it.split('\n') }.map { if (it.isEmpty()) it else "    $it" } + "}"
    }

    /** The lines of a lambda's (or an IIFE's) body, not braced: the closure emitter braces them. */
    fun bodyLines(ctx: CppEmitContextImpl, statements: List<Statement>): List<String> = statements.flatMap { render(ctx, it) }

    private fun cond(ctx: CppEmitContextImpl, e: Expr): String = CppLowering.of(ctx).emit(e, CppPrec.NONE)

    private fun returnStatement(ctx: CppEmitContextImpl, s: ReturnStatement): List<String> {
        val e = s.expr
        if (e === NoExpr) {
            return listOf("return;")
        }
        return listOf("return ${CppLowering.of(ctx).emit(e, CppPrec.NONE)};")
    }

    private fun ifStatement(ctx: CppEmitContextImpl, s: IfSelectionStatement): List<String> {
        val out = mutableListOf<String>()
        out += CppWriter.ifHead(cond(ctx, s.expr))
        out += block(ctx, s.thenStatements)
        for (b in s.elseBranches) {
            when (b) {
                is ElseIfBranchStatement -> {
                    out += "else ${CppWriter.ifHead(cond(ctx, b.condition))}"
                    out += block(ctx, b.statements)
                }
                is ElseBranchStatement -> {
                    out += "else"
                    out += block(ctx, b.statements)
                }
            }
        }
        return out
    }

    private fun forStatement(ctx: CppEmitContextImpl, s: ForIterationStatement): List<String> {
        val lower = CppLowering.of(ctx)
        val state = lower.state
        val model = ctx.model
        val plan = model.loop(s) ?: return listOf(lower.internal(s.forIterationExpr, "no loop plan was recorded").text + ";")
        val fe = s.forIterationExpr
        val variable = plan.variable ?: return listOf(lower.internal(fe, "a loop without its variable").text + ";")
        val declared = fe.declaredType?.takeIf { model.typeOf(it) != null }
        val typeText = if (declared != null) ctx.spell(declared, Pos.VALUE) else ctx.spell(plan.element, Pos.VALUE, fe)
        return state.block {
            val name = state.declare(variable, ctx.names.escape(fe.initializer.value)) { taken(ctx, it) }
            val head = when (plan.kind) {
                LoopKind.RANGE, LoopKind.RANGE_INCLUSIVE -> {
                    val range = fe.target as? RangeExpr ?: return@block listOf(lower.internal(fe, "a range loop without a range").text + ";")
                    val start = lower.emit(range.begin, CppPrec.ASSIGN)
                    val cmp = if (plan.kind == LoopKind.RANGE) "<" else "<="
                    if (model.const(range.end) != null) {
                        // A literal or constant bound needs no temporary.
                        "$typeText $name = $start; $name $cmp ${lower.emit(range.end, CppPrec.REL + 1, CppLitRole.OPERAND)}; ++$name"
                    } else {
                        val end = state.fresh("${name.lowercase()}_end")
                        "$typeText $name = $start, $end = ${lower.emit(range.end, CppPrec.ASSIGN)}; $name $cmp $end; ++$name"
                    }
                }
                else -> {
                    val unused = if (isRead(ctx, variable)) "" else "[[maybe_unused]] "
                    val decl = when {
                        (variable as? LocalSymbol)?.isMut == true -> "$typeText $name"
                        ctx.speller.byValue(plan.element) -> "const $typeText $name"
                        else -> "const $typeText& $name"
                    }
                    "$unused$decl : ${lower.emit(fe.target, CppPrec.NONE)}"
                }
            }
            listOf(CppWriter.forHead(head)) + block(ctx, s.body)
        }
    }

    private fun expressionStatement(ctx: CppEmitContextImpl, s: Statement, e: Expr): List<String> {
        val lower = CppLowering.of(ctx)
        return when (e) {
            is VariableDecl -> listOf(local(ctx, e))
            is Decl -> {
                ctx.unsupported(s, "a declaration inside a body")
                emptyList()
            }
            NoExpr -> emptyList()
            is AssignmentExpr, is CompoundAssignmentExpr, is PlaceAssignmentExpr, is ThrowExpr -> listOf("${lower.emit(e, CppPrec.NONE)};")
            is TryExpr -> tryStatement(ctx, e)
            else -> {
                strBufStatement(ctx, e)?.let { return it }
                val t = ctx.model.require(e)
                val text = lower.emit(e, CppPrec.NONE)
                if (t == KType.Void || t == KType.Never || e is IntrinsicExpr) {
                    listOf("$text;")
                } else {
                    // R8: every non-Void function is [[nodiscard]].
                    listOf("static_cast<void>($text);")
                }
            }
        }
    }

    /** `buf.set("...${x}")` as one statement per append (design 10), or null for any other statement. */
    private fun strBufStatement(ctx: CppEmitContextImpl, e: Expr): List<String>? {
        val call = when (e) {
            is FunctionCallExpr -> e
            is MemberAccessExpr -> e.member as? FunctionCallExpr
            else -> null
        } ?: return null
        val rc = ctx.model.call(call) ?: return null
        val fn = rc.fn ?: return null
        val receiver = rc.receiver ?: return null
        if (rc.kind != CallKind.MAGIC) {
            return null
        }
        val lower = CppLowering.of(ctx)
        if (!lower.isStrBufText(fn, ctx.model.typeOrNull(receiver))) {
            return null
        }
        val text = (rc.args.firstOrNull() as? ArgBinding.Given)?.expr as? InterpolatedStringLiteral ?: return null
        return lower.strBufPieces(receiver, fn, text).map { "$it;" }
    }

    /**
     * A local (5.4): brace-init for a scalar or enum, copy-init otherwise, `const` without
     * `mut`, `[[maybe_unused]]` when nothing reads it.
     */
    private fun local(ctx: CppEmitContextImpl, decl: VariableDecl): String {
        val lower = CppLowering.of(ctx)
        val state = lower.state
        val sym = ctx.model.declSymbol(decl) as? LocalSymbol ?: return lower.internal(decl, "no local was recorded for '${decl.name.value}'").text + ";"
        val t = sym.type
        val pos = if (sym.isMut) Pos.MUT_VALUE else Pos.VALUE
        val spelled = if (ctx.model.typeOf(decl.type) != null) ctx.spell(decl.type, pos) else ctx.spell(t, pos, decl)
        val name = state.declare(sym, ctx.names.escape(decl.name.value)) { taken(ctx, it) }
        val unused = if (isRead(ctx, sym)) "" else "[[maybe_unused]] "
        val declarator = when {
            sym.isMut -> "$spelled $name"
            spelled.endsWith("*") -> "$spelled const $name"
            else -> "const $spelled $name"
        }
        val init = decl.value ?: return "$unused$declarator{};"
        val scalar = t is KType.Scalar || (t is KType.Nominal && t.sym is EnumSymbol)
        return when {
            scalar -> "$unused$declarator{${lower.emit(init, CppPrec.ASSIGN)}};"
            init is ArrayLiteral && ctx.model.coercion(init) == null -> "$unused$declarator = ${lower.bracedElements(init)};"
            else -> "$unused$declarator = ${lower.emit(init, CppPrec.ASSIGN)};"
        }
    }

    private fun tryStatement(ctx: CppEmitContextImpl, e: TryExpr): List<String> {
        val lower = CppLowering.of(ctx)
        val state = lower.state
        if (lower.freestanding) {
            ctx.unsupported(e, "try in a freestanding module (D41: throw is kira::panic there, so nothing is caught)")
            return emptyList()
        }
        val out = mutableListOf("try")
        out += block(ctx, e.tryBlock)
        val name = e.exceptionName
        if (name == null) {
            out += "catch(const kira::Error&)"
            out += block(ctx, e.handlerBlock)
            return out
        }
        state.block {
            val ex = state.fresh("ex_")
            out += "catch(const kira::Error& $ex)"
            val local = ctx.model.declSymbol(name) as? LocalSymbol
            val handler = state.block {
                val bound = if (local != null) {
                    val n = state.declare(local, ctx.names.escape(name.value)) { taken(ctx, it) }
                    val unused = if (isRead(ctx, local)) "" else "[[maybe_unused]] "
                    listOf("${unused}const kira::Str& $n = $ex.message;")
                } else {
                    emptyList()
                }
                bound + e.handlerBlock.flatMap { render(ctx, it) }
            }
            out += "{"
            out += handler.flatMap { it.split('\n') }.map { if (it.isEmpty()) it else "    $it" }
            out += "}"
        }
        return out
    }

    // ---- if-expressions that are no ternary (R18) -----------------------------------------------

    /**
     * `[&]() -> T { if(c) { ...; return a; } else { ...; return b; } }()`: an if-expression
     * whose branches hold statements, or end in a `throw`.
     */
    fun ifExprLambda(ctx: CppEmitContextImpl, e: IfExpr, t: KType): String {
        val lower = CppLowering.of(ctx)
        val state = lower.state
        escapingJump(e)?.let { jump ->
            // Inside the IIFE a return would leave the lambda, not the function, and a break or
            // continue would have no loop: refused rather than lowered to something else.
            ctx.unsupported(jump, "a ${jumpName(jump)} inside a branch of an if-expression that is not a ternary (its branches become a C++ lambda)")
        }
        val capture = if (state.inBody) "[&]" else "[]"
        val ret = ctx.spell(t, Pos.RETURN, e)
        val lines = state.block { ifChain(ctx, e) }
        return "$capture() -> $ret\n{\n${CppHoister.indent(lines)}\n}()"
    }

    /**
     * A `return` anywhere in [e]'s branches, or a `break` or `continue` outside a loop the
     * branches hold themselves: a jump out of the if-expression (not into a nested lambda, whose
     * own return is its own).
     */
    private fun escapingJump(e: IfExpr): Statement? {
        fun scan(statements: List<Statement>, inLoop: Boolean): Statement? {
            for (s in statements) {
                val found: Statement? = when (s) {
                    is ReturnStatement -> s
                    is BreakStatement, is ContinueStatement -> if (inLoop) null else s
                    is WhileIterationStatement -> scan(s.statements, true)
                    is DoWhileIterationStatement -> scan(s.statements, true)
                    is ForIterationStatement -> scan(s.body, true)
                    is IfSelectionStatement -> scan(s.thenStatements, inLoop) ?: s.elseBranches.firstNotNullOfOrNull { b ->
                        when (b) {
                            is ElseIfBranchStatement -> scan(b.statements, inLoop)
                            is ElseBranchStatement -> scan(b.statements, inLoop)
                        }
                    }
                    else -> (s.expr as? TryExpr)?.let { scan(it.tryBlock, inLoop) ?: scan(it.handlerBlock, inLoop) }
                        ?: (s.expr as? IfExpr)?.let { scan(it.thenBranch, inLoop) ?: scan(it.elseBranch, inLoop) }
                }
                if (found != null) {
                    return found
                }
            }
            return null
        }
        return scan(e.thenBranch, false) ?: scan(e.elseBranch, false)
    }

    private fun jumpName(s: Statement): String = when (s) {
        is ReturnStatement -> "return"
        is BreakStatement -> "break"
        else -> "continue"
    }

    private fun ifChain(ctx: CppEmitContextImpl, e: IfExpr): List<String> {
        val out = mutableListOf<String>()
        out += CppWriter.ifHead(cond(ctx, e.condition))
        out += branch(ctx, e.thenBranch)
        val nested = e.elseBranch.singleOrNull()?.takeIf { it.javaClass == Statement::class.java }?.expr as? IfExpr
        if (nested != null && ctx.model.ifShape(nested) != true) {
            val chain = ifChain(ctx, nested)
            out += "else ${chain.first()}"
            out += chain.drop(1)
        } else {
            out += "else"
            out += branch(ctx, e.elseBranch)
        }
        return out
    }

    /** A branch of an if-expression: its statements, then `return` of the value it ends in. */
    private fun branch(ctx: CppEmitContextImpl, statements: List<Statement>): List<String> {
        val lower = CppLowering.of(ctx)
        val state = lower.state
        val inner = state.block {
            val body = statements.dropLast(1).flatMap { render(ctx, it) }
            val last = statements.lastOrNull()
            val value = last?.let { lower.branchValue(listOf(it)) }
            val tail = when {
                last == null -> emptyList()
                value == null || value is ThrowExpr || ctx.model.typeOrNull(value) == KType.Never -> render(ctx, last)
                else -> listOf("return ${lower.emit(value, CppPrec.NONE)};")
            }
            body + tail
        }
        return listOf("{") + inner.flatMap { it.split('\n') }.map { if (it.isEmpty()) it else "    $it" } + "}"
    }

    // ---- names -----------------------------------------------------------------------------------

    /**
     * Whether [name], declared as a local, would hide a variable `-Wshadow` (gcc, clang) and
     * C4459 (MSVC) warn about: a module constant or state, or a field of the enclosing type.
     * A function of the same name is not one: none of the three warns when a variable hides a
     * function (measured by the numerics golden's local `scaled` beside its function `scaled`).
     */
    fun taken(ctx: CppEmitContextImpl, name: String): Boolean {
        if (ctx.symbol.members[name] is net.exoad.kira.compiler.analysis.types.GlobalSymbol) {
            return true
        }
        val owner = CppLowering.of(ctx).state.frame?.owner ?: ctx.scope ?: return false
        return fieldNames(owner).contains(name)
    }

    private fun fieldNames(owner: TypeSymbol): Set<String> {
        val out = HashSet<String>()
        var c: ClassSymbol? = owner as? ClassSymbol
        val seen = Collections.newSetFromMap(IdentityHashMap<ClassSymbol, Boolean>())
        while (c != null && seen.add(c)) {
            c.fields.forEach { out.add(it.name) }
            c = c.superclass?.sym as? ClassSymbol
        }
        return out
    }

    /**
     * Per module emission: every local and parameter declared in a body written so far, and
     * whether anything reads it (true) or only writes it (false).
     */
    private val reads = java.util.WeakHashMap<CppEmitContextImpl, IdentityHashMap<Symbol, Boolean>>()

    /** Whether [sym] is read in its body; true for a symbol of a body that was not scanned. */
    fun isRead(ctx: CppEmitContextImpl, sym: Symbol): Boolean = reads[ctx]?.get(sym) ?: true

    /**
     * The locals and parameters [statements] declare or use: true for one an identifier reads
     * (anything but the name that declares it or the whole target of `=` or `op=`), false for
     * one that is only declared or written (gcc's -Wunused-but-set-variable counts `x += 1` as a
     * write only). Writing a `mut` parameter writes the caller's variable (`T&`), which is a use.
     */
    fun readsIn(ctx: CppEmitContextImpl, statements: List<Statement>): Map<Symbol, Boolean> {
        val model = ctx.model
        val out = IdentityHashMap<Symbol, Boolean>()
        val declaring = Collections.newSetFromMap(IdentityHashMap<Identifier, Boolean>())
        val written = Collections.newSetFromMap(IdentityHashMap<Identifier, Boolean>())
        statements.forEach { st ->
            AstTree.walk(st) { node ->
                when (node) {
                    is AssignmentExpr -> written.add(node.target)
                    is CompoundAssignmentExpr -> (node.left as? Identifier)?.let { written.add(it) }
                    is VariableDecl -> declaring.add(node.name)
                    is net.exoad.kira.compiler.frontend.parser.ast.expressions.ForIterationExpr -> declaring.add(node.initializer)
                    is net.exoad.kira.compiler.frontend.parser.ast.expressions.FunctionDeclParameterExpr -> declaring.add(node.name)
                    is TryExpr -> node.exceptionName?.let { declaring.add(it) }
                    else -> {}
                }
            }
        }
        statements.forEach { st ->
            AstTree.walk(st) { node: ASTNode ->
                if (node is Identifier && node !is IntrinsicExpr) {
                    val sym = model.symbolOf(node)
                    if (sym is LocalSymbol || sym is ParamSymbol) {
                        val use = node !in declaring && (node !in written || (sym is ParamSymbol && sym.byRef))
                        out[sym] = (out[sym] ?: false) || use
                    }
                }
            }
        }
        return out
    }
}
